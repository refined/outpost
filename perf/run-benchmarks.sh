#!/usr/bin/env bash
# Benchmarks the balance modes under the same budget: 2 CPUs and 3 GB for app + Postgres. k6 runs outside the limit.
# Results: perf/results/<timestamp>/summary.md. The six-variant comparison in docs/performance.md ran at commit 0ffcf3f.
#
#   perf/run-benchmarks.sh                 # all variants
#   perf/run-benchmarks.sh pg-batched-100  # one of them
#
# Needs only Docker and bash (Git Bash on Windows). Each variant starts from empty volumes.
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1 # Git Bash: don't rewrite /results paths

STAMP=$(date +%Y%m%d-%H%M%S)
OUTDIR="perf/results/$STAMP"
mkdir -p "$OUTDIR"
COMPOSE=(docker compose -f docker-compose.yml -f perf/compose.limits.yml --profile tools)
RATES=(200 250 300 350 400 500 600 800 1000 1300 1600)
STEP_SECONDS=20

LIMITS="APP_CPUS=1.0 APP_MEM=1280M PG_CPUS=1.0 PG_MEM=1792M PG_SHARED_BUFFERS=448MB"
variant_env() {
  case "$1" in
    pg-stored)      echo "BALANCE_MODE=STORED $LIMITS" ;;
    pg-batched-10)  echo "BALANCE_MODE=BATCHED BATCH_MAX_SIZE=10 BATCH_MAX_WAIT_MS=0 $LIMITS" ;;
    pg-batched-100) echo "BALANCE_MODE=BATCHED BATCH_MAX_SIZE=100 BATCH_MAX_WAIT_MS=0 $LIMITS" ;;
    *) echo "unknown variant $1" >&2; exit 1 ;;
  esac
}

k6() { "${COMPOSE[@]}" run --rm --no-deps -e VARIANT="$VARIANT" "$@" k6 run --quiet "${SCRIPT}" 2>&1 | grep -E "^(local|pg-|seed:)" || true; }

# Samples pg_stat_activity 5 times a second for one step: "active,waiting on a lock,waiting on IO" per line.
# psql ends by itself after the step (\watch c=N), so nothing is left running in the container.
sample_waits() {
  "${COMPOSE[@]}" exec -T postgres psql -U mor -d mor_ledger -At -F, > "$1" 2>/dev/null <<SQL || true
SELECT count(*) FILTER (WHERE state = 'active'),
       count(*) FILTER (WHERE state = 'active' AND wait_event_type = 'Lock'),
       count(*) FILTER (WHERE state = 'active' AND wait_event_type = 'IO')
FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid()
\watch i=0.2 c=$((STEP_SECONDS * 5))
SQL
}

# Share of active backend samples that were waiting: column 2 = row locks, column 3 = IO (mostly WAL flush on commit).
wait_share() { awk -F, -v col="$2" '/^[0-9]+,[0-9]+,[0-9]+$/ { a += $1; w += $col } END { printf "%d%%", (a ? 100 * w / a : 0) }' "$1" 2>/dev/null || echo "?"; }

reset_stack() {
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
  docker volume rm -f outpost_pgdata >/dev/null 2>&1 || true
  "${COMPOSE[@]}" up -d --build --wait postgres app >/dev/null
  echo "  stack: $(curl -s http://127.0.0.1:8090/health)"
}

run_variant() {
  VARIANT=$1
  # shellcheck disable=SC2046
  export $(variant_env "$VARIANT")
  echo "== $VARIANT ($(variant_env "$VARIANT"))"
  reset_stack

  SCRIPT=bench.js k6 -e SCENARIO=capture -e RATE=200 -e DURATION=15s -e OUT="$STAMP/$VARIANT-warmup" -e RUN="warm" # JIT warm-up, not reported

  # Read latency of the balances report at two data sizes (closed-loop seed = bulk ingest throughput).
  SCRIPT=seed.js k6 -e PAYMENTS=10000 -e PREFIX=pre -e OUT="$STAMP/$VARIANT-seed-10k"
  SCRIPT=bench.js k6 -e SCENARIO=report -e RATE=20 -e DURATION=20s -e OUT="$STAMP/$VARIANT-report-10k"
  SCRIPT=seed.js k6 -e PAYMENTS=90000 -e PREFIX=pre2 -e OUT="$STAMP/$VARIANT-seed-100k"
  SCRIPT=bench.js k6 -e SCENARIO=report -e RATE=20 -e DURATION=20s -e OUT="$STAMP/$VARIANT-report-100k"

  # Throughput ladders: raise the arrival rate until a step no longer holds (keeps up, p95 < 250 ms, < 1% errors).
  for scenario in capture hot mixed; do
    for rate in "${RATES[@]}"; do
      sample_waits "$OUTDIR/$VARIANT-$scenario-$rate.waits" &
      SCRIPT=bench.js k6 -e SCENARIO=$scenario -e RATE="$rate" -e DURATION=${STEP_SECONDS}s -e OUT="$STAMP/$VARIANT-$scenario-$rate" -e RUN="$scenario$rate"
      wait
      grep -q '"holds":true' "$OUTDIR/$VARIANT-$scenario-$rate.json" 2>/dev/null || break
    done
  done

  # The books must still balance after all of it.
  {
    echo "{\"variant\":\"$VARIANT\",\"verify\":$(curl -s http://127.0.0.1:8090/v1/ledger/verify),"
    echo "\"integrity\":$(curl -s http://127.0.0.1:8090/v1/reports/balances | grep -o '"integrity":{[^}]*}' | sed 's/"integrity"://'),"
    echo "\"merchantsReconciled\":\"$(curl -s 'http://127.0.0.1:8090/v1/analytics/merchants?limit=500' | grep -o '"reconciled":true' | wc -l) of $(curl -s 'http://127.0.0.1:8090/v1/analytics/merchants?limit=1' | grep -o '"total":[0-9]*' | cut -d: -f2)\","
    echo "\"payments\":$(curl -s http://127.0.0.1:8090/v1/payments?limit=1 | grep -o '"total":[0-9]*' | cut -d: -f2)}"
  } | tr -d '\n' > "$OUTDIR/$VARIANT-final.json"
  echo "  final: $(cat "$OUTDIR/$VARIANT-final.json")"
}

field() { grep -o "\"$2\":[^,}]*" "$1" | head -1 | cut -d: -f2- | tr -d '"'; }

summarise() {
  local md="$OUTDIR/summary.md"
  {
    echo "# Benchmark $STAMP"
    echo
    echo "Budget: 2 CPUs and 3 GB for the system under test; k6 outside the limit. Docker $(docker version --format '{{.Server.Version}}'), $(docker info --format '{{.NCPU}} CPUs / {{.MemTotal}} bytes') host VM."
    echo
    echo "## Highest holding rate per scenario (keeps up, p95 < 250 ms, errors < 1%)"
    echo
    echo "Lock / IO: share of active Postgres backends waiting on a row lock / on IO at that step."
    echo
    echo "| Variant | capture (100 merchants) | hot (1 merchant) | mixed (85/10/5) |"
    echo "|---|---|---|---|"
    for v in "$@"; do
      row="| $v |"
      for s in capture hot mixed; do
        best=""; for r in "${RATES[@]}"; do f="$OUTDIR/$v-$s-$r.json"; [ -f "$f" ] || continue
          if grep -q '"holds":true' "$f"; then
            best="$(field "$f" achievedRps)/s (p95 $(field "$f" p95) ms; lock $(wait_share "${f%.json}.waits" 2), IO $(wait_share "${f%.json}.waits" 3))"
          fi; done
        row="$row ${best:-none} |"
      done
      echo "$row"
    done
    echo
    echo "## Bulk ingest and report latency"
    echo
    echo "| Variant | seed 100k: payments/s | report p50 / p95 @ 10k payments | report p50 / p95 @ 100k payments |"
    echo "|---|---|---|---|"
    for v in "$@"; do
      echo "| $v | $(field "$OUTDIR/$v-seed-100k.json" paymentsPerSecond) | $(field "$OUTDIR/$v-report-10k.json" p50) / $(field "$OUTDIR/$v-report-10k.json" p95) ms | $(field "$OUTDIR/$v-report-100k.json" p50) / $(field "$OUTDIR/$v-report-100k.json" p95) ms |"
    done
    echo
    echo "## All steps"
    echo
    echo "| Variant | Scenario | Target/s | Achieved/s | p50 | p95 | p99 | Errors | Dropped | Lock wait | IO wait | Holds |"
    echo "|---|---|---|---|---|---|---|---|---|---|---|---|"
    for v in "$@"; do for s in capture hot mixed; do for r in "${RATES[@]}"; do f="$OUTDIR/$v-$s-$r.json"; [ -f "$f" ] || continue
      echo "| $v | $s | $r | $(field "$f" achievedRps) | $(field "$f" p50) | $(field "$f" p95) | $(field "$f" p99) | $(field "$f" errorRate) | $(field "$f" dropped) | $(wait_share "${f%.json}.waits" 2) | $(wait_share "${f%.json}.waits" 3) | $(field "$f" holds) |"
    done; done; done
    echo
    echo "## Consistency after each run"
    echo
    for v in "$@"; do echo "- $v: \`$(cat "$OUTDIR/$v-final.json")\`"; done
  } > "$md"
  echo "Summary: $md"
}

VARIANTS=("$@")
[ ${#VARIANTS[@]} -eq 0 ] && VARIANTS=(pg-stored pg-batched-10 pg-batched-100)
for v in "${VARIANTS[@]}"; do run_variant "$v"; done
summarise "${VARIANTS[@]}"
"${COMPOSE[@]}" down >/dev/null 2>&1 || true
