#!/bin/bash
# doris lane: parallel inject + first-seen latency tracking via mysql SELECT
# usage: doris-burst.sh <N> <intervalSec> <startId>
N=${1:-25}
IV=${2:-1.0}
START=${3:-601}
LOG=${LANE_LOG:-/tmp/lane}
mkdir -p "$LOG"
rm -f "$LOG/burst.csv" "$LOG/inject-log.txt"
M="mysql -h127.0.0.1 -P9030 -uroot --ssl-mode=DISABLED -N --skip-column-names"
DIR="$(cd "$(dirname "$0")" && pwd)"

export LANE_LOG=$LOG
bash "$DIR/lane-inject.sh" "$N" "$IV" "$START" \
  "postgresql://bdp_lane:bdp_lane@127.0.0.1:5433/bdp_lane" >/dev/null 2>&1 &
INJ=$!

declare -A SEEN
END=$((START + N - 1))
EXTRA=0
while true; do
  now=$($M -e "USE dw; SELECT id FROM mv_sales_lane WHERE id >= $START AND id <= $END;" 2>/dev/null)
  NOWMS=$(date +%s%3N)
  while read -r id; do
    [ -n "$id" ] && [ -z "${SEEN[$id]:-}" ] && SEEN[$id]=$NOWMS
  done <<< "$now"
  CNT=${#SEEN[@]}
  if ! kill -0 "$INJ" 2>/dev/null; then
    if [ "$CNT" -ge "$N" ]; then break; fi
    EXTRA=$((EXTRA + 1))
    [ "$EXTRA" -ge 12 ] && break
  fi
  sleep 1
done
wait "$INJ" 2>/dev/null || true
touch "$LOG/burst.csv"
for id in $(seq "$START" "$END"); do
  rd=${SEEN[$id]:-}
  [ -n "$rd" ] && echo "$id,$rd" >> "$LOG/burst.csv"
done
echo "first-seen rows: $(wc -l < "$LOG/burst.csv") / $N"