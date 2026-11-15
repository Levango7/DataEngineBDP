#!/bin/bash
# lane injector: INSERT N rows with staggered timing, append to inject log
# usage: lane-inject.sh <N> <intervalSec> <startId> <psqlUrl>
# inject log (row N injected at epochMs) -> $LANE_LOG/inject-log.txt
N=${1:?N required}
IV=${2:?intervalSec required}
START=${3:?startId required}
PGURL=${4:?psqlUrl required (postgresql://user:pass@host:port/db)}
LOG=${LANE_LOG:-/tmp/lane}
mkdir -p "$LOG"
echo "inject-start $(date +%s%3N)" >> "$LOG/inject-log.txt"
END=$((START + N - 1))
for i in $(seq "$START" "$END"); do
  psql "$PGURL" -qc \
    "INSERT INTO public.mv_sales_lane(id,name,amount,src_ts) VALUES ($i,'row-$i',$i.5,clock_timestamp());" \
    && echo "row $i injected $(date +%s%3N)" >> "$LOG/inject-log.txt"
  sleep "$IV"
done
echo "inject-end $(date +%s%3N)" >> "$LOG/inject-log.txt"
echo DONE
