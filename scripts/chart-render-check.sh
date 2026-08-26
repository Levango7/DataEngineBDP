#!/usr/bin/env bash
# 全量 Chart 渲染 + Schema 校验（helm template -> 拆文档 -> 批量 kubeconform）
# 依赖：helm、kubeconform、python3；退出码非0表示存在失败 chart（helm template → 拆文档 → 单进程批量 kubeconform）
set -uo pipefail
# 相对脚本自身定位仓库根（兼容任意调用 cwd，勿硬编码绝对路径）
cd "$(dirname "$0")/.." || exit 1

check_chart() { # $1=rendered file -> echo err or empty
python3 - "$1" <<'PYEOF'
import os, subprocess, sys, glob
outdir = '/tmp/_split'
for f in glob.glob(outdir + '/*'):
    os.remove(f)
docs = open(sys.argv[1], encoding='utf-8').read().split('\n---\n')
n = 0
for d in docs:
    if not d.strip():
        continue
    n += 1
    open(f'{outdir}/doc{n:04d}.yaml', 'w', encoding='utf-8').write(d)
if n == 0:
    sys.exit(0)
r = subprocess.run(['/usr/local/bin/kubeconform', '-strict', '-ignore-missing-schemas',
                    '-kubernetes-version', '1.29.0', '-summary', outdir],
                   capture_output=True, text=True)
if r.returncode != 0:
    lines = [l for l in (r.stdout + r.stderr).splitlines() if l.strip() and not l.startswith('Summary')]
    print('; '.join(lines[:3])[:400])
    sys.exit(1)
PYEOF
}

mkdir -p /tmp/_split
pass=0; fail=0; : > /tmp/chart_failures.txt
# CI 冒烟专用 JWT 覆盖：catalog chart 默认 jwtSigningKey 已改为空串（fail-fast），
# 裸渲染必须显式注入占位值才能通过 required 校验；
# umbrella 下传给子 chart 的键需带 "catalog." 前缀。
SMOKE_JWT=ci-smoke-only-value-0123456789abcdef
for cf in $(find design/deploy/charts platform/industry-templates/charts -name Chart.yaml 2>/dev/null | sort); do
  dir=$(dirname "$cf"); name=$(basename "$dir")
  extra_args=()
  case "$name" in
    catalog)                extra_args=(--set "auth.jwtSigningKey=${SMOKE_JWT}") ;;
    dataenginebdp-umbrella) extra_args=(--set "catalog.auth.jwtSigningKey=${SMOKE_JWT}") ;;
  esac
  errfile=$(mktemp)
  out=$(helm template "$name" "$dir" --namespace smoke ${extra_args[@]+"${extra_args[@]}"} 2>"$errfile"); rc=$?
  if [ $rc -ne 0 ] || [ -z "$out" ]; then
    fail=$((fail+1)); echo "RENDER-FAIL $name :: $(cat "$errfile" | head -3 | tr '\n' ' ' | head -c 240)" >> /tmp/chart_failures.txt; continue
  fi
  echo "$out" > /tmp/render.yaml
  if err=$(check_chart /tmp/render.yaml); then
    pass=$((pass+1))
  else
    fail=$((fail+1)); echo "SCHEMA-FAIL $name :: $err" >> /tmp/chart_failures.txt
  fi
done
echo "PASS=$pass FAIL=$fail"
cat /tmp/chart_failures.txt
