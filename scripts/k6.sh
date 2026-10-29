#!/usr/bin/env sh
# Runs a k6 scenario from loadtest/ in Docker, against the running docker compose stack.
#
#   ./scripts/k6.sh steady
#   RATE=100 DURATION=5m ./scripts/k6.sh steady
#   ./scripts/k6.sh burst
#   ./scripts/k6.sh timeout-storm
#
# Passes RATE, DURATION, PEAK and STORM_SHARE through. Writes a JSON summary to loadtest/results/.
set -eu

cd "$(dirname "$0")/.."
SCENARIO=${1:?usage: ./scripts/k6.sh steady|burst|timeout-storm}
mkdir -p loadtest/results

exec docker run --rm -i \
  --network payment-switch_default \
  -v "$PWD/loadtest:/scripts" \
  -e BASE_URL=http://app:8080 \
  -e RATE="${RATE:-}" -e DURATION="${DURATION:-}" -e PEAK="${PEAK:-}" -e STORM_SHARE="${STORM_SHARE:-}" \
  grafana/k6:1.8.1 run --summary-export "/scripts/results/$SCENARIO.json" "/scripts/$SCENARIO.js"
