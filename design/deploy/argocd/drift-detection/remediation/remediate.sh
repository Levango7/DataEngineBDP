#!/usr/bin/env bash
# ============================================================================
# 漂移修复脚本 · remediate.sh
# ============================================================================
# 作用：对检测到的漂移执行自动修复（argocd app sync）
# 用法：
#   bash remediate.sh --auto                    # 自动模式（CronJob 调用）
#   bash remediate.sh --app root-prod           # 修复指定 App
#   bash remediate.sh --app root-prod --confirm # 人工确认修复（prod）
#   bash remediate.sh --dry-run                 # 仅模拟，不执行修复
# 修复流程：
#   1. 调用 detect-drift.sh 获取漂移列表
#   2. 加载修复策略（drift-policy ConfigMap）
#   3. 对每个漂移事件校验前置条件
#   4. 执行 argocd app sync <app>
#   5. 等待 sync 完成，验证修复结果
#   6. 更新漂移事件 ConfigMap 状态
#   7. 记录修复事件到审计日志
# ============================================================================
set -euo pipefail

# ----------------------------------------------------------------------------
# 配置
# ----------------------------------------------------------------------------
ARGOCD_NS="${ARGOCD_NS:-argocd}"
ARGOCD_SERVER="${ARGOCD_SERVER:-argocd-server.argocd.svc.cluster.local}"
REMEDIATION_TIMEOUT="${REMEDIATION_TIMEOUT:-180}"
MAX_CONCURRENT="${MAX_CONCURRENT_REMEDIATIONS:-5}"
RATE_LIMIT="${REMEDIATION_RATE_LIMIT:-3}"
POLICY_DIR="${POLICY_DIR:-/etc/drift-policy}"

# 参数
MODE="manual"
TARGET_APP=""
DRY_RUN=false
CONFIRMED=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --auto) MODE="auto"; shift ;;
    --app) TARGET_APP="$2"; shift 2 ;;
    --confirm) CONFIRMED=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

# ----------------------------------------------------------------------------
# 日志
# ----------------------------------------------------------------------------
log() {
  echo "[$(date -u +%FT%TZ)] [$1] $2"
}

# ----------------------------------------------------------------------------
# 初始化 argocd CLI
# ----------------------------------------------------------------------------
init_argocd() {
  if [[ -n "${ARGOCD_AUTH_TOKEN:-}" ]]; then
    argocd login "$ARGOCD_SERVER" --token "$ARGOCD_AUTH_TOKEN" --insecure
  fi
}

# ----------------------------------------------------------------------------
# 加载修复策略
# ----------------------------------------------------------------------------
load_policy() {
  local policy_file="$POLICY_DIR/drift-policy.yaml"
  if [[ ! -f "$policy_file" ]]; then
    log warn "策略文件不存在: $policy_file，使用默认策略"
    return
  fi
  log info "加载修复策略: $policy_file"
}

# ----------------------------------------------------------------------------
# 检查同步窗口（prod）
# ----------------------------------------------------------------------------
check_sync_window() {
  local env=$1
  if [[ "$env" != "prod" ]]; then
    return 0  # 非 prod 总是允许
  fi

  local dow hour
  dow=$(date -u +%u)    # 1=Mon ... 7=Sun
  hour=$(date -u +%H)

  # 工作日 09:00-18:00 UTC（假设 prod 同步窗口）
  if [[ "$dow" -ge 1 && "$dow" -le 5 && "$hour" -ge 1 && "$hour" -lt 10 ]]; then
    return 0
  fi

  log warn "prod 当前不在同步窗口（工作日 09-18 UTC），跳过自动修复"
  return 1
}

# ----------------------------------------------------------------------------
# 检查修复频率限制
# ----------------------------------------------------------------------------
check_rate_limit() {
  local app=$1
  local count
  count=$(kubectl get configmap -n "$ARGOCD_NS" \
    -l drift-remediation=true,app="$app" \
    --field-selector=metadata.creationTimestamp>=$(date -u -d '5 minutes ago' +%FT%TZ) \
    -o jsonpath='{.items}' 2>/dev/null | jq 'length' || echo 0)

  if [[ "$count" -ge "$RATE_LIMIT" ]]; then
    log warn "App $app 5 分钟内已修复 $count 次，触发频率限制"
    return 1
  fi
  return 0
}

# ----------------------------------------------------------------------------
# 检查熔断
# ----------------------------------------------------------------------------
check_circuit_breaker() {
  local app=$1
  local fail_count
  fail_count=$(kubectl get configmap "drift-circuit-breaker-$app" -n "$ARGOCD_NS" \
    -o jsonpath='{.data.fail-count}' 2>/dev/null || echo 0)

  if [[ "$fail_count" -ge 3 ]]; then
    log error "App $app 熔断中（连续失败 $fail_count 次），暂停自动修复"
    return 1
  fi
  return 0
}

# ----------------------------------------------------------------------------
# 执行修复
# ----------------------------------------------------------------------------
remediate_app() {
  local app=$1 env=$2 severity=$3

  log info "===== 开始修复 App: $app (env=$env, severity=$severity) ====="

  # 前置校验
  if [[ "$MODE" == "auto" ]]; then
    check_sync_window "$env" || return 1
    check_rate_limit "$app" || return 1
    check_circuit_breaker "$app" || return 1

    # prod High/Critical 需人工确认
    if [[ "$env" == "prod" && "$CONFIRMED" == "false" ]]; then
      if [[ "$severity" == "critical" || "$severity" == "high" ]]; then
        log warn "prod $severity 漂移需人工确认，跳过自动修复（App=$app）"
        return 1
      fi
    fi
  fi

  # 记时
  local start_ts
  start_ts=$(date +%s)

  # 执行修复
  if [[ "$DRY_RUN" == "true" ]]; then
    log info "[dry-run] 将执行: argocd app sync $app"
    return 0
  fi

  log info "执行: argocd app sync $app"
  if ! timeout "$REMEDIATION_TIMEOUT" argocd app sync "$app" --timeout "$REMEDIATION_TIMEOUT"; then
    local elapsed=$(( $(date +%s) - start_ts ))
    record_remediation "$app" "$env" "failed" "$elapsed" "sync-timeout-or-error"
    increment_fail_count "$app"
    return 1
  fi

  # 等待 sync 完成
  log info "等待 sync 完成..."
  local wait=0
  while [[ $wait -lt $REMEDIATION_TIMEOUT ]]; do
    local sync_status
    sync_status=$(kubectl get application "$app" -n "$ARGOCD_NS" \
      -o jsonpath='{.status.sync.status}' 2>/dev/null || echo "Unknown")
    if [[ "$sync_status" == "Synced" ]]; then
      break
    fi
    sleep 5
    wait=$((wait + 5))
  done

  local elapsed=$(( $(date +%s) - start_ts ))

  # 验证修复结果
  local sync_status health_status
  sync_status=$(kubectl get application "$app" -n "$ARGOCD_NS" -o jsonpath='{.status.sync.status}')
  health_status=$(kubectl get application "$app" -n "$ARGOCD_NS" -o jsonpath='{.status.health.status}')

  if [[ "$sync_status" == "Synced" && "$health_status" == "Healthy" ]]; then
    log info "修复成功: $app (耗时 ${elapsed}s)"
    record_remediation "$app" "$env" "success" "$elapsed" "synced-and-healthy"
    reset_fail_count "$app"
    return 0
  else
    log error "修复失败: $app sync=$sync_status health=$health_status (耗时 ${elapsed}s)"
    record_remediation "$app" "$env" "failed" "$elapsed" "sync=$sync_status,health=$health_status"
    increment_fail_count "$app"
    return 1
  fi
}

# ----------------------------------------------------------------------------
# 记数管理
# ----------------------------------------------------------------------------
increment_fail_count() {
  local app=$1
  local cm="drift-circuit-breaker-$app"
  local count
  count=$(kubectl get configmap "$cm" -n "$ARGOCD_NS" -o jsonpath='{.data.fail-count}' 2>/dev/null || echo 0)
  count=$((count + 1))

  if [[ "$DRY_RUN" != "true" ]]; then
    kubectl create configmap "$cm" -n "$ARGOCD_NS" \
      --from-literal=fail-count="$count" \
      --from-literal=last-fail-at="$(date -u +%FT%TZ)" \
      -o yaml --dry-run=client | kubectl apply -f -

    if [[ $count -ge 3 ]]; then
      log error "App $app 触发熔断（连续失败 $count 次），暂停自动修复 30 分钟"
    fi
  fi
}

reset_fail_count() {
  local app=$1
  local cm="drift-circuit-breaker-$app"
  if [[ "$DRY_RUN" != "true" ]]; then
    kubectl delete configmap "$cm" -n "$ARGOCD_NS" --ignore-not-found
  fi
}

# ----------------------------------------------------------------------------
# 记录修复事件
# ----------------------------------------------------------------------------
record_remediation() {
  local app=$1 env=$2 result=$3 duration=$4 detail=$5
  local ts=$(date -u +%Y%m%d-%H%M%S)
  local cm="remediation-event-${app}-${ts}"

  if [[ "$DRY_RUN" == "true" ]]; then
    log info "[dry-run] 修复事件: $cm result=$result duration=${duration}s"
    return
  fi

  kubectl create configmap "$cm" -n "$ARGOCD_NS" \
    --from-literal=app="$app" \
    --from-literal=environment="$env" \
    --from-literal=result="$result" \
    --from-literal=duration="${duration}s" \
    --from-literal=detail="$detail" \
    --from-literal=trigger="$MODE" \
    --from-literal=operator="drift-remediation-$(whoami)" \
    --from-literal=completed-at="$(date -u +%FT%TZ)" \
    -o yaml --dry-run=client | kubectl apply -f -

  kubectl label configmap "$cm" -n "$ARGOCD_NS" \
    drift-remediation=true app="$app" environment="$env" result="$result" --overwrite
}

# ----------------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------------
main() {
  log info "===== 漂移修复开始 (mode=$MODE) ====="

  init_argocd
  load_policy

  # 获取漂移事件列表
  local drift_events=()
  if [[ -n "$TARGET_APP" ]]; then
    # 修复指定 App
    local env
    env=$(kubectl get application "$TARGET_APP" -n "$ARGOCD_NS" \
      -o jsonpath='{.metadata.labels.environment}' 2>/dev/null || echo "unknown")
    drift_events+=("${TARGET_APP}|${env}|high")
  else
    # 从 ConfigMap 获取所有 pending 漂移事件
    mapfile -t drift_events < <(
      kubectl get configmap -n "$ARGOCD_NS" \
        -l drift-event=true,remediation-status=pending \
        -o json | jq -r '.items[] | "\(.data.app)|\(.data.environment)|\(.data.severity)"'
    )
  fi

  if [[ ${#drift_events[@]} -eq 0 ]]; then
    log info "无待修复漂移事件"
    exit 0
  fi

  log info "待修复漂移事件数: ${#drift_events[@]}"

  # 限制并发
  if [[ ${#drift_events[@]} -gt $MAX_CONCURRENT ]]; then
    log warn "待修复数 ($((${#drift_events[@]}))) 超过并发限制 ($MAX_CONCURRENT)，仅修复前 $MAX_CONCURRENT 个"
    drift_events=("${drift_events[@]:0:$MAX_CONCURRENT}")
  fi

  # 逐个修复
  local success=0 failed=0 skipped=0
  for event in "${drift_events[@]}"; do
    IFS='|' read -r app env severity <<< "$event"

    if remediate_app "$app" "$env" "$severity"; then
      success=$((success + 1))
    else
      failed=$((failed + 1))
    fi
  done

  log info "===== 漂移修复完成 ====="
  log info "成功: $success / 失败: $failed / 跳过: $skipped"

  exit 0
}

main "$@"