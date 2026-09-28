#!/usr/bin/env bash
# 临时脚本：82 chart 模板改动后的 helm lint 批量验证
set -u
cd "$(dirname "$0")/.."
fail=0
count=0
while IFS= read -r cf; do
  d=$(dirname "$cf")
  count=$((count+1))
  # helm lint 的 quiet 只有长选项 --quiet，没有 -q 短选项：用 -q 会报
  # "unknown shorthand flag: 'q' in -q" 并对每个 chart 都返回非 0，导致全部误判 LINT-FAIL。
  # （与 scripts/verify-all-templates.sh 的既有修法一致。）
  # 注：本脚本未被任何 workflow / 脚本引用（临时脚本），此处仅修缺陷、防未来踩坑。
  if ! helm lint "$d" --quiet >/dev/null 2>&1; then
    echo "LINT-FAIL: $d"
    fail=$((fail+1))
  fi
done < <(find design/deploy/charts -name Chart.yaml | grep -v tgz | sort)
echo "linted=$count failed=$fail"
[ "$fail" -gt 0 ] && exit 1 || exit 0
