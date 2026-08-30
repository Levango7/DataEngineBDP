#!/usr/bin/env bash
# ============================================================
# v2.1.0-RC 四环境 Profile 快速校验（无需 kind，仅 Helm template）
# 用于 CI / 本地快速验证 values 无占位符、YAML 合法、Schema 通过
# ============================================================
set -euo pipefail

cd "$(dirname "$0")/.." || exit 1

UMBRELLA=design/deploy/charts/dataenginebdp-umbrella
LOCAL_VALUES=deploy/local/values-local-core.yaml

log() { echo "[$(date '+%H:%M:%S')] $*"; }
fail() { echo "FAIL: $*"; exit 1; }
pass() { echo "PASS: $*"; }

# 依赖检查
for cmd in helm kubeconform; do
  command -v "$cmd" >/dev/null || { echo "缺少 $cmd，请先安装"; exit 1; }
done

# Helm 依赖更新
log "更新 Helm 依赖..."
helm dependency update "$UMBRELLA" >/dev/null

# 1. 核心子集渲染 + Schema 校验（四环境）
log "核心子集渲染 + Schema 校验..."
for env in xinchuang onprem public-cloud private-cloud; do
  log "  Profile: $env"
  # stdout=渲染产物 / stderr=helm 警告（如 dependency v1 警告）。
  # 此前 2>&1 把警告行混入 YAML，kubeconform 解析必炸——这也是 CI 一直
  # 报 "Schema 校验失败" 的直接原因。分离后警告只在失败时透出。
  if ! helm template test-core "$UMBRELLA" -n smoke -f "$LOCAL_VALUES" --set "global.env=$env" > /tmp/render.yaml 2> /tmp/render-err.txt; then
    fail "渲染失败 ($env): $(cat /tmp/render-err.txt)"
  fi
  kubeconform -strict -ignore-missing-schemas -kubernetes-version 1.29.0 -summary /tmp/render.yaml >/dev/null 2>&1 || fail "Schema 校验失败 ($env)"
  # 占位符检查（只查渲染产物）
  if grep -q "REPLACE_WITH_\|CHANGE_ME_\|your-\|<.*>" /tmp/render.yaml; then
    fail "发现占位符 ($env): $(grep -E "REPLACE_WITH_|CHANGE_ME_|your-|<.*>" /tmp/render.yaml | head -1)"
  fi
  pass "Profile $env 通过"
done

# 2. 全量 Chart 矩阵渲染（仅 catalog/umbrella 注入 JWT）
log "全量 Chart 矩阵渲染..."
SMOKE_JWT="ci-smoke-only-value-0123456789abcdef"
fail_count=0
for cf in $(find design/deploy/charts platform/industry-templates/charts -name Chart.yaml 2>/dev/null | sort); do
  dir=$(dirname "$cf"); name=$(basename "$dir")
  extra_args=()
  case "$name" in
    catalog)                 extra_args=(--set "auth.jwtSigningKey=${SMOKE_JWT}") ;;
    dataenginebdp-umbrella)  extra_args=(--set "catalog.auth.jwtSigningKey=${SMOKE_JWT}") ;;
    # nacos chart 含安全 fail-fast：auth.enabled=true 且 tokenSecretKey 为空时拒绝渲染
    # （生产语义正确）。矩阵冒烟需注入 ≥32 字符测试值，与 catalog JWT 同模式。
    nacos)                   extra_args=(--set "auth.tokenSecretKey=${SMOKE_JWT}") ;;
  esac
  if ! helm template "$name" "$dir" --namespace smoke "${extra_args[@]+"${extra_args[@]}"}" > /tmp/render.yaml 2>/dev/null; then
    log "FAIL: $name 渲染失败"
    fail_count=$((fail_count+1))
    continue
  fi
  kubeconform -strict -ignore-missing-schemas -kubernetes-version 1.29.0 -summary /tmp/render.yaml >/dev/null 2>&1 || { log "FAIL: $name Schema 校验失败"; fail_count=$((fail_count+1)); continue; }
done
(( fail_count == 0 )) || fail "共 $fail_count 个 Chart 失败"
pass "全量 Chart 矩阵通过"

echo "=== v2.1.0-RC Profile 快速校验全部通过 ==="