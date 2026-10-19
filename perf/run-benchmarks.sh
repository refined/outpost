#!/usr/bin/env bash
# Benchmarks ledger variants under the same budget: 2 CPUs and 3 GB for app + Postgres. k6 runs outside the limit.
# Results: perf/results/<timestamp>/summary.md and results.json.
#
#   perf/run-benchmarks.sh                                   # the presets: pg-stored pg-batched-100 pg-batched-1000
#   perf/run-benchmarks.sh pg-batched-100                    # one preset
#   perf/run-benchmarks.sh "pg-computed:BALANCE_MODE=COMPUTED" # name:settings, for variants of older commits
#   QUICK=1 perf/run-benchmarks.sh ...                       # 10k preload only, no 100k report
#
# Each scenario's ladder starts at START_CAPTURE / START_HOT / START_MIXED, or START_RATE (400), and climbs by 200 up to
# MAX_RATE (6000) while steps hold; after the first failing step it tries 100 below. If the first step fails it steps
# down by 100. Every step also samples CPU per container (docker stats). A step that clearly cannot hold stops early
# (k6 thresholds with abortOnFail), followed by a short cool-down.
#
# Every variant also gets a profile at a fixed 300 rps: docker stats (CPU per container) and pg_stat_statements
# (SQL per capture) from a step without a profiler, then JFR (where app CPU goes, which calls wait on Postgres).
#
# EXTRA_COMPOSE (an extra compose file) and EXTRA_SERVICES (more services to start) are for engines with their own
# containers, e.g. TigerBeetle on commit 0ffcf3f, together with COMPOSE_PROFILES.
#
# Needs only Docker and bash (Git Bash on Windows). Each variant starts from empty volumes.
set -euo pipefail
cd "$(dirname "$0")/.."
export MSYS_NO_PATHCONV=1 # Git Bash: don't rewrite /results paths

STAMP=$(date +%Y%m%d-%H%M%S)
OUTDIR="perf/results/$STAMP"
mkdir -p "$OUTDIR"
PROJECT=${COMPOSE_PROJECT_NAME:-$(basename "$(pwd)" | tr '[:upper:]' '[:lower:]')}
BASE_URL="http://127.0.0.1:${APP_HOST_PORT:-8090}"
START_RATE=${START_RATE:-400}
MAX_RATE=${MAX_RATE:-6000}
RATES=(); for ((r = 100; r <= MAX_RATE; r += 100)); do RATES+=("$r"); done
STEP_SECONDS=20
COOL_DOWN_SECONDS=5
PROFILE_RPS=300
PROFILE_SECONDS=40

LIMITS="APP_CPUS=1.0 APP_MEM=1280M PG_CPUS=1.0 PG_MEM=1792M PG_SHARED_BUFFERS=448MB"
preset() {
  case "$1" in
    pg-stored)       echo "BALANCE_MODE=STORED" ;;
    pg-batched-10)   echo "BALANCE_MODE=BATCHED BATCH_MAX_SIZE=10 BATCH_MAX_WAIT_MS=0" ;;
    pg-batched-100)  echo "BALANCE_MODE=BATCHED BATCH_MAX_SIZE=100 BATCH_MAX_WAIT_MS=0" ;;
    pg-batched-1000) echo "BALANCE_MODE=BATCHED BATCH_MAX_SIZE=1000 BATCH_MAX_WAIT_MS=50" ;;
    *) echo "unknown variant $1 (use a preset or name:SETTING=value ...)" >&2; exit 1 ;;
  esac
}

k6() { "${COMPOSE[@]}" run --rm --no-deps -e VARIANT="$VARIANT" "$@" k6 run --quiet "${SCRIPT}" 2>&1 | grep -E "target=|^seed:" || true; }

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
wait_pct() { awk -F, -v col="$2" '/^[0-9]+,[0-9]+,[0-9]+$/ { a += $1; w += $col } END { printf "%d", (a ? 100 * w / a : 0) }' "$1" 2>/dev/null || echo 0; }

psql_q() { "${COMPOSE[@]}" exec -T postgres psql -U mor -d mor_ledger -At -F'|' -c "$1"; }

# docker stats once a second: "app CPU %,postgres CPU %" per line (100% = one core).
sample_cpu() {
  local end=$((SECONDS + ${2:-$PROFILE_SECONDS}))
  while [ $SECONDS -lt $end ]; do
    docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' "$PROJECT-app-1" "$PROJECT-postgres-1" |
      awk -v app="$PROJECT-app-1" -v pg="$PROJECT-postgres-1" '{ gsub("%", "", $2); v[$1] = $2 } END { print v[app] "," v[pg] }'
  done > "$1"
}

pct() { awk -v c="$1" 'BEGIN { if (c == "null" || c == "") printf "?"; else printf "%d%%", 100 * c + 0.5 }'; }

cpu_avg() { awk -F, -v col="$2" '$col != "" { s += $col; n++ } END { if (n) printf "%.2f", s / n / 100; else printf "null" }' "$1" 2>/dev/null || echo null; }

# Two steps at a fixed rate. The first, without a profiler, gives CPU per container (docker stats) and SQL per capture
# (pg_stat_statements). The second restarts the app with JFR, which slows it down, so it is used only for proportions:
# where CPU goes and which calls wait on Postgres. JFR keeps the last 60 s and writes them when the app stops.
profile_variant() {
  local base="$OUTDIR/$VARIANT-profile"
  psql_q "CREATE EXTENSION IF NOT EXISTS pg_stat_statements" >/dev/null
  psql_q "SELECT pg_stat_statements_reset()" >/dev/null
  sample_cpu "$base.cpu" &
  SCRIPT=bench.js k6 -e SCENARIO=capture -e RATE=$PROFILE_RPS -e DURATION=${PROFILE_SECONDS}s -e OUT="$STAMP/$VARIANT-profile" -e RUN=profile
  wait
  psql_q "SELECT calls, round(total_exec_time), left(regexp_replace(query, '\s+', ' ', 'g'), 80)
          FROM pg_stat_statements WHERE query !~ 'pg_stat|pg_extension' ORDER BY total_exec_time DESC" > "$base.sql"

  export JFR_OPTS="-XX:StartFlightRecording=settings=profile,jdk.ExecutionSample#period=2ms,disk=true,maxage=60s,dumponexit=true,filename=/tmp/app.jfr"
  "${COMPOSE[@]}" up -d --wait --no-deps app >/dev/null 2>&1
  SCRIPT=bench.js k6 -e SCENARIO=capture -e RATE=$PROFILE_RPS -e DURATION=15s -e OUT="$STAMP/$VARIANT-jfr-warmup" -e RUN=jwarm
  SCRIPT=bench.js k6 -e SCENARIO=capture -e RATE=$PROFILE_RPS -e DURATION=${PROFILE_SECONDS}s -e OUT="$STAMP/$VARIANT-jfr" -e RUN=jfr
  "${COMPOSE[@]}" stop app >/dev/null 2>&1
  docker cp "$PROJECT-app-1:/tmp/app.jfr" "$base.jfr" >/dev/null || true
  unset JFR_OPTS
  "${COMPOSE[@]}" up -d --wait --no-deps app >/dev/null 2>&1
  docker run --rm -v "$(cd "$OUTDIR" && (pwd -W 2>/dev/null || pwd))":/data -v "$(cd perf && (pwd -W 2>/dev/null || pwd))":/perf:ro \
    gradle:9.8.1-jdk21 sh /perf/analyse-jfr.sh "/data/$VARIANT-profile.jfr" > "$base.txt" 2>&1 || true
  echo "  profile: $(cpu_avg "$base.cpu" 1) app / $(cpu_avg "$base.cpu" 2) postgres cores at $PROFILE_RPS rps"
}

step() {
  local f="$OUTDIR/$VARIANT-$1-$2"
  sample_waits "$f.waits" &
  local sampler=$!
  sample_cpu "$f.cpu" "$STEP_SECONDS" &
  local cpu=$!
  SCRIPT=bench.js k6 -e SCENARIO="$1" -e RATE="$2" -e DURATION=${STEP_SECONDS}s -e ABORT=1 -e OUT="$STAMP/$VARIANT-$1-$2" -e RUN="$1$2"
  kill "$sampler" "$cpu" 2>/dev/null || true
  wait "$sampler" "$cpu" 2>/dev/null || true
}

holds() { grep -q '"holds":true' "$OUTDIR/$VARIANT-$1-$2.json" 2>/dev/null; }

# A failed step leaves a backlog behind; let the app drain it before the next one.
cool_down() { sleep "$COOL_DOWN_SECONDS"; }

ladder() {
  local s=$1 start="START_${1^^}"
  local r=${!start:-$START_RATE}
  step "$s" "$r"
  if holds "$s" "$r"; then
    while [ $((r + 200)) -le $MAX_RATE ]; do
      r=$((r + 200))
      step "$s" "$r"
      if ! holds "$s" "$r"; then cool_down; step "$s" $((r - 100)); return; fi
    done
  else
    for ((r = r - 100; r >= 100; r -= 100)); do cool_down; step "$s" "$r"; holds "$s" "$r" && return; done
  fi
  return 0
}

reset_stack() {
  "${COMPOSE[@]}" down --remove-orphans >/dev/null 2>&1 || true
  docker volume rm -f "${PROJECT}_pgdata" "${PROJECT}_tbdata" >/dev/null 2>&1 || true
  # shellcheck disable=SC2086
  "${COMPOSE[@]}" up -d --build --wait postgres ${EXTRA_SERVICES:-} app >/dev/null
  echo "  stack: $(curl -s "$BASE_URL/health")"
}

# Runs in a subshell, so one variant's settings never leak into the next.
run_variant() {
  VARIANT=${1%%:*}
  local settings
  if [[ $1 == *:* ]]; then settings=${1#*:}; else settings=$(preset "$1"); fi
  # shellcheck disable=SC2086
  export $LIMITS $settings
  COMPOSE=(docker compose -f docker-compose.yml -f perf/compose.limits.yml ${EXTRA_COMPOSE:+-f "$EXTRA_COMPOSE"} --profile tools)
  echo "== $VARIANT ($settings) at $(git rev-parse --short HEAD 2>/dev/null || echo '?')"
  echo "$settings" > "$OUTDIR/$VARIANT-settings.txt"
  reset_stack

  SCRIPT=bench.js k6 -e SCENARIO=capture -e RATE=200 -e DURATION=15s -e OUT="$STAMP/$VARIANT-warmup" -e RUN="warm" # JIT warm-up, not reported

  # Read latency of the balances report at two data sizes (closed-loop seed = bulk ingest throughput).
  SCRIPT=seed.js k6 -e PAYMENTS=10000 -e PREFIX=pre -e OUT="$STAMP/$VARIANT-seed-10k"
  SCRIPT=bench.js k6 -e SCENARIO=report -e RATE=20 -e DURATION=20s -e OUT="$STAMP/$VARIANT-report-10k"
  if [ "${QUICK:-0}" != 1 ]; then
    SCRIPT=seed.js k6 -e PAYMENTS=90000 -e PREFIX=pre2 -e OUT="$STAMP/$VARIANT-seed-100k"
    SCRIPT=bench.js k6 -e SCENARIO=report -e RATE=20 -e DURATION=20s -e OUT="$STAMP/$VARIANT-report-100k"
  fi

  for scenario in capture hot mixed; do ladder "$scenario"; done

  profile_variant

  # The books must still balance after all of it.
  {
    echo "{\"variant\":\"$VARIANT\",\"verify\":$(curl -s "$BASE_URL/v1/ledger/verify"),"
    echo "\"integrity\":$(curl -s "$BASE_URL/v1/reports/balances" | grep -o '"integrity":{[^}]*}' | sed 's/"integrity"://'),"
    echo "\"merchantsReconciled\":\"$(curl -s "$BASE_URL/v1/analytics/merchants?limit=500" | grep -o '"reconciled":true' | wc -l) of $(curl -s "$BASE_URL/v1/analytics/merchants?limit=1" | grep -o '"total":[0-9]*' | cut -d: -f2)\","
    echo "\"payments\":$(curl -s "$BASE_URL/v1/payments?limit=1" | grep -o '"total":[0-9]*' | cut -d: -f2)}"
  } | tr -d '\n' > "$OUTDIR/$VARIANT-final.json"
  echo "  final: $(cat "$OUTDIR/$VARIANT-final.json")"
}

field() { grep -o "\"$2\":[^,}]*" "$1" 2>/dev/null | head -1 | cut -d: -f2- | tr -d '"' || true; }
num() { local v; v=$(field "$1" "$2"); echo "${v:-null}"; }

# Highest rate that held in a scenario: "rate|p95|lock%|io%|app cores|postgres cores", or empty.
best_step() {
  local best="" f r
  for r in "${RATES[@]}"; do
    f="$OUTDIR/$1-$2-$r.json"
    if [ -f "$f" ] && grep -q '"holds":true' "$f"; then
      best="$r|$(field "$f" p95)|$(wait_pct "${f%.json}.waits" 2)|$(wait_pct "${f%.json}.waits" 3)|$(cpu_avg "${f%.json}.cpu" 1)|$(cpu_avg "${f%.json}.cpu" 2)"
    fi
  done
  echo "$best"
}

# "statements per capture|SQL ms per capture" from the profile step's pg_stat_statements.
sql_per_capture() {
  local b="$OUTDIR/$1-profile.sql" captures
  captures=$(awk -F'|' '$3 ~ /^INSERT INTO payments/ { print $1 }' "$b" 2>/dev/null | head -1 || true)
  awk -F'|' -v c="${captures:-0}" '{ n += $1; ms += $2 } END { if (c) printf "%.1f|%.2f", n / c, ms / c; else printf "null|null" }' "$b" 2>/dev/null || echo "null|null"
}

results_json() {
  local commit v s b first=1 seed best app pg rps sql r p95 lock io bapp bpg
  commit=$(git rev-parse --short HEAD 2>/dev/null || echo "")
  echo "{\"stamp\":\"$STAMP\",\"commit\":\"$commit\",\"variants\":["
  for v in "$@"; do
    [ $first = 1 ] || echo ","
    first=0
    b="$OUTDIR/$v-profile"
    seed="$OUTDIR/$v-seed-10k.json"
    app=$(cpu_avg "$b.cpu" 1); pg=$(cpu_avg "$b.cpu" 2); rps=$(num "$b.json" achievedRps); sql=$(sql_per_capture "$v")
    printf '{"variant":"%s","settings":"%s"' "$v" "$(cat "$OUTDIR/$v-settings.txt" 2>/dev/null)"
    for s in capture hot mixed; do
      best=$(best_step "$v" "$s")
      if [ -n "$best" ]; then
        IFS='|' read -r r p95 lock io bapp bpg <<< "$best"
        printf ',"%s":{"rps":%s,"p95":%s,"lockWaitPct":%s,"ioWaitPct":%s,"appCores":%s,"postgresCores":%s}' "$s" "$r" "${p95:-null}" "$lock" "$io" "$bapp" "$bpg"
      else
        printf ',"%s":null' "$s"
      fi
    done
    printf ',"profile":{"rps":%s,"p95":%s,"appCores":%s,"postgresCores":%s,"appCpuMsPerRequest":%s,"sqlStatementsPerCapture":%s,"sqlMsPerCapture":%s}' \
      "$rps" "$(num "$b.json" p95)" "${app:-null}" "${pg:-null}" \
      "$(awk -v a="${app:-0}" -v r="$rps" 'BEGIN { if (r + 0 > 0 && a != "null") printf "%.2f", 1000 * a / r; else printf "null" }')" "${sql%|*}" "${sql#*|}"
    printf ',"seedPerSecond":%s,"reportP95At10k":%s,"reportP95At100k":%s' \
      "$(num "$seed" paymentsPerSecond)" "$(num "$OUTDIR/$v-report-10k.json" p95)" "$(num "$OUTDIR/$v-report-100k.json" p95)"
    printf ',"final":%s}' "$(cat "$OUTDIR/$v-final.json" 2>/dev/null || echo null)"
  done
  echo "]}"
}

summarise() {
  local md="$OUTDIR/summary.md" v s r f b best rps app pg sql seed p95 lock io bapp bpg
  results_json "$@" > "$OUTDIR/results.json"
  {
    echo "# Benchmark $STAMP"
    echo
    echo "Commit $(git rev-parse --short HEAD 2>/dev/null || echo '?'). Budget: 2 CPUs and 3 GB for the system under test; k6 outside the limit. Docker $(docker version --format '{{.Server.Version}}'), $(docker info --format '{{.NCPU}} CPUs / {{.MemTotal}} bytes') host VM."
    echo
    echo "## Highest holding rate per scenario (keeps up, p95 < 200 ms, errors < 1%)"
    echo
    echo "CPU: average over that step, % of each container's 1-CPU quota (docker stats)."
    echo
    echo "| Variant | Scenario | rps | p95, ms | app CPU | Postgres CPU | lock wait | IO wait |"
    echo "|---|---|---|---|---|---|---|---|"
    for v in "$@"; do
      for s in capture hot mixed; do
        best=$(best_step "$v" "$s")
        if [ -n "$best" ]; then
          IFS='|' read -r r p95 lock io bapp bpg <<< "$best"
          echo "| $v | $s | $r | $p95 | $(pct "$bapp") | $(pct "$bpg") | $lock% | $io% |"
        else
          echo "| $v | $s | none | | | | | |"
        fi
      done
    done
    echo
    echo "## Bulk ingest and report latency"
    echo
    echo "| Variant | seed: payments/s | report p50 / p95 @ 10k payments | report p50 / p95 @ 100k payments |"
    echo "|---|---|---|---|"
    for v in "$@"; do
      seed="$OUTDIR/$v-seed-10k.json"
      echo "| $v | $(field "$seed" paymentsPerSecond) | $(field "$OUTDIR/$v-report-10k.json" p50) / $(field "$OUTDIR/$v-report-10k.json" p95) ms | $(field "$OUTDIR/$v-report-100k.json" p50) / $(field "$OUTDIR/$v-report-100k.json" p95) ms |"
    done
    echo
    echo "## Profile at $PROFILE_RPS captures/s"
    echo
    echo "| Variant | achieved | p95 | app CPU (cores) | app CPU ms / request | Postgres CPU (cores) | SQL statements / capture | SQL ms / capture |"
    echo "|---|---|---|---|---|---|---|---|"
    for v in "$@"; do
      b="$OUTDIR/$v-profile"; [ -f "$b.json" ] || continue
      rps=$(field "$b.json" achievedRps); app=$(cpu_avg "$b.cpu" 1); pg=$(cpu_avg "$b.cpu" 2); sql=$(sql_per_capture "$v")
      echo "| $v | $rps/s | $(field "$b.json" p95) ms | $app | $(awk -v a="$app" -v r="$rps" 'BEGIN { if (r + 0 > 0 && a != "null") printf "%.2f", 1000 * a / r; else printf "?" }') | $pg | ${sql%|*} | ${sql#*|} |"
    done
    for v in "$@"; do
      b="$OUTDIR/$v-profile"; [ -f "$b.txt" ] || continue
      echo
      echo "### $v"
      echo
      echo '```'
      cat "$b.txt"
      echo "Top SQL (calls | total ms | statement):"
      head -6 "$b.sql" | sed 's/^/  /'
      echo '```'
    done
    echo
    echo "## All steps"
    echo
    echo "| Variant | Scenario | Target/s | Achieved/s | p50 | p95 | p99 | Errors | Dropped | Seconds | App CPU | Postgres CPU | Lock wait | IO wait | Holds |"
    echo "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
    for v in "$@"; do for s in capture hot mixed; do for r in "${RATES[@]}"; do f="$OUTDIR/$v-$s-$r.json"; [ -f "$f" ] || continue
      echo "| $v | $s | $r | $(field "$f" achievedRps) | $(field "$f" p50) | $(field "$f" p95) | $(field "$f" p99) | $(field "$f" errorRate) | $(field "$f" dropped) | $(field "$f" seconds) | $(pct "$(cpu_avg "${f%.json}.cpu" 1)") | $(pct "$(cpu_avg "${f%.json}.cpu" 2)") | $(wait_pct "${f%.json}.waits" 2)% | $(wait_pct "${f%.json}.waits" 3)% | $(field "$f" holds) |"
    done; done; done
    echo
    echo "## Consistency after each run"
    echo
    for v in "$@"; do echo "- $v: \`$(cat "$OUTDIR/$v-final.json" 2>/dev/null)\`"; done
  } > "$md"
  echo "Summary: $md"
}

ARGS=("$@")
[ ${#ARGS[@]} -eq 0 ] && ARGS=(pg-stored pg-batched-100 pg-batched-1000)
NAMES=()
for a in "${ARGS[@]}"; do
  (run_variant "$a")
  NAMES+=("${a%%:*}")
done
COMPOSE=(docker compose -f docker-compose.yml -f perf/compose.limits.yml ${EXTRA_COMPOSE:+-f "$EXTRA_COMPOSE"} --profile tools)
summarise "${NAMES[@]}"
"${COMPOSE[@]}" down >/dev/null 2>&1 || true
