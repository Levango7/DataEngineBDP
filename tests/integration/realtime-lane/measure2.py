# first-seen latency measurer: burst.csv (id,firstSeenMs) + inject-log.txt (row N injected at ms)
# per-row latency = firstSeen - injected (seconds); P95 criterion default 60s (Phase D)
# usage: python3 measure2.py <burstCsv> [injectLog] [thresholdSec]
import sys

burst_csv = sys.argv[1]
inject_log = sys.argv[2] if len(sys.argv) > 2 else "/tmp/lane/inject-log.txt"
threshold = float(sys.argv[3]) if len(sys.argv) > 3 else 60.0

first_seen = {}
for line in open(burst_csv, encoding="utf-8"):
    line = line.strip()
    if not line:
        continue
    rid, ms = line.split(",")
    first_seen[int(rid)] = int(ms)

inj = {}
try:
    for line in open(inject_log, encoding="utf-8"):
        line = line.strip()
        if not line.startswith("row "):
            continue
        parts = line.split()
        rid = int(parts[1])
        ms = int(parts[3])
        inj[rid] = ms
except FileNotFoundError:
    print(f"WARN: inject log missing: {inject_log}")

lats = []
for rid, seen in sorted(first_seen.items()):
    if rid in inj:
        lats.append((seen - inj[rid]) / 1000.0)

lats.sort()
n = len(lats)
if n == 0:
    print("FAIL n=0 (no measurable rows)")
    sys.exit(1)
p50 = lats[int(0.50 * (n - 1))]
p95 = lats[int(0.95 * (n - 1))]
mx = lats[-1]
mn = lats[0]
print(f"n={n} min={mn:.1f}s p50={p50:.1f}s p95={p95:.1f}s max={mx:.1f}s (criterion p95<={threshold:g}s)")
if p95 > threshold:
    print(f"FAIL p95={p95:.1f}s > {threshold:g}s")
    sys.exit(1)
print("PASS")
