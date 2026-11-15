#!/bin/bash
# parallel inject + first-seen latency tracking (the true P95 criterion:
# per-row latency = first time the row is readable in iceberg - injected time)
# usage: lane-burst.sh <N> <intervalSec> <startId> <psqlUrl> <warehouseUri> <db> <table>
# output: $LANE_LOG/burst.csv (id,firstSeenMs) for measure2.py
N=${1:?N required}
IV=${2:?intervalSec required}
START=${3:?startId required}
PGURL=${4:?psqlUrl required}
WH=${5:?warehouseUri required}
DB=${6:?db required}
TBL=${7:?table required}
LOG=${LANE_LOG:-/tmp/lane}
: "${LANE_CP:?LANE_CP not set (dependency classpath incl iceberg-api)}"
mkdir -p "$LOG"
rm -f "$LOG/burst.csv" "$LOG/inject-log.txt"

DIR="$(cd "$(dirname "$0")" && pwd)"

bash "$DIR/lane-inject.sh" "$N" "$IV" "$START" "$PGURL" >/dev/null 2>&1 &
INJ=$!

declare -A SEEN
END=$((START + N - 1))
EXTRA=0
while true; do
  (cd "$DIR" && java -cp "$LANE_CP" LaneTools dump "$WH" "$DB" "$TBL" 2>/dev/null) \
    | awk -F, -v s="$START" -v e="$END" '$1+0>=s && $1+0<=e && $2+0>0 {print $1","$2","$3}' \
    > "$LOG/now.csv"
  while IFS=, read -r id src rd; do
    [ -z "${SEEN[$id]:-}" ] && SEEN[$id]=$rd
  done < "$LOG/now.csv"
  CNT=${#SEEN[@]}
  if ! kill -0 "$INJ" 2>/dev/null; then
    if [ "$CNT" -ge "$N" ]; then
      break
    fi
    EXTRA=$((EXTRA + 1))
    [ "$EXTRA" -ge 10 ] && break
  fi
  sleep 2
done
wait "$INJ" 2>/dev/null || true
for id in $(seq "$START" "$END"); do
  rd=${SEEN[$id]:-}
  [ -n "$rd" ] && echo "$id,$rd" >> "$LOG/burst.csv"
done
echo "first-seen rows: $(wc -l < "$LOG/burst.csv") / $N"
