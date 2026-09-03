#!/usr/bin/env bash
# 临时脚本：82 chart 模板改动后的 helm lint 批量验证
set -u
cd "$(dirname "$0")/.."
fail=0
count=0
while IFS= read -r cf; do
  d=$(dirname "$cf")
  count=$((count+1))
  if ! helm lint "$d" -q >/dev/null 2>&1; then
    echo "LINT-FAIL: $d"
    fail=$((fail+1))
  fi
done < <(find design/deploy/charts -name Chart.yaml | grep -v tgz | sort)
echo "linted=$count failed=$fail"
