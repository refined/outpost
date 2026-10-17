#!/bin/sh
# Summarises a JFR recording; runs inside a JDK image (the `jfr` tool), see perf/run-benchmarks.sh.
#   CPU:  Java execution samples, by the first frame (from the top) in a known library or in our code.
#   JDBC: samples of threads blocked inside the Postgres driver, by the call in our code that issued it
#         (skipping the shared helpers in db/, so the table method shows, not db.SqlKt.query).
set -eu
f=$1

stacks() {
  jfr print --events "$1" --stack-depth 64 "$f" | awk '
    /^jdk\./ { stack = ""; inside = 0; next }
    /stackTrace = \[/ { inside = 1; next }
    inside && /^[[:space:]]*\]/ { inside = 0; print stack; next }
    inside { sub(/^[[:space:]]+/, ""); sub(/[[:space:]]+line:.*/, ""); stack = stack "|" $0 }'
}

echo "CPU (Java execution samples):"
stacks jdk.ExecutionSample | awk -F'|' '
  {
    c = "JVM / JDK"
    for (i = 2; i <= NF; i++) {
      if ($i ~ /^kotlinx\.serialization\./) { c = "JSON (kotlinx.serialization)"; break }
      if ($i ~ /^(org\.postgresql|com\.zaxxer)\./) { c = "JDBC driver + Hikari"; break }
      if ($i ~ /^(io\.ktor|io\.netty)\./) { c = "HTTP (Ktor / Netty)"; break }
      if ($i ~ /^kotlinx?\.coroutines\./) { c = "coroutines"; break }
      if ($i ~ /^io\.github\.refined\./) { c = "app code"; break }
    }
    n[c]++; total++
  }
  END { for (c in n) printf "  %5.1f%%  %s\n", 100 * n[c] / total, c; printf "  (%d samples)\n", total }' | sort -rn

echo "Waiting on Postgres (thread samples blocked in the JDBC driver):"
stacks jdk.NativeMethodSample | awk -F'|' '
  /org\.postgresql\./ {
    c = "other"
    if ($0 ~ /PgConnection\.commit/) c = "commit"
    else for (i = 2; i <= NF; i++) if ($i ~ /^io\.github\.refined\./ && $i !~ /^io\.github\.refined\.morledger\.db\./) { c = $i; sub(/\(.*/, "", c); sub(/^io\.github\.refined\.morledger\./, "", c); break }
    n[c]++; total++
  }
  END { for (c in n) printf "  %5.1f%%  %s\n", 100 * n[c] / total, c; printf "  (%d samples)\n", total }' | sort -rn
