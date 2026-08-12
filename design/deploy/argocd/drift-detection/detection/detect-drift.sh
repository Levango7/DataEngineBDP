#!/usr/bin/env bash
# ============================================================================
# 漂移检测脚本 · detect-drift.sh
# ============================================================================
# 作用：枚举所有 ArgoCD Application，执行 diff，输出结构化漂移事件
# 用法：
#   bash detect-drift.sh                    # 检测所有环境
#   bash detect-drift.sh --env prod         # 仅检测 prod
#   bash detect-drift.sh --app root-dev     # 仅检测指定 App
#   bash detect-drift.sh --dry-run          # 仅输出，不写 ConfigMap / 不触发告警
#   bash detect-drift.sh --json             # JSON 格式输出
# 依赖：argocd CLI、kubectl、jq
# ============================================================================
set -euo pipefail

# ----------------------------------------------------------------------------
# 配置
# ----------------------------------------------------------------------------
ARGOCD_NS="${ARGOCD_NS:-argocd}"
DRIFT_EVENT_NS="${DRIFT_EVENT_NS:-argocd}"
OUTPUT_DIR="${OUTPUT_DIR:-/tmp/drift-events}"
LOG_LEVEL="${LOG_LEVEL:-info}"

# 颜色
RED='\033[0;31m'
YELLOW='\033[0;33m'
GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

# 参数解析
TARGET_ENV=""
TARGET_APP=""
DRY_RUN=false
JSON_OUTPUT=false

while [[ $# -gt 0 ]]; do
  case $1 in
    --env) TARGET_ENV="$2"; shift 2 ;;
    --app) TARGET_APP="$2"; shift 2 ;;
    --dry-run) DRY_RUN=true; shift ;;
    --json) JSON_OUTPUT=true; shift ;;
    *) echo "未知参数: $1"; exit 1 ;;
  esac
done

mkdir -p "$OUTPUT_DIR"

# ----------------------------------------------------------------------------
# 日志
# ----------------------------------------------------------------------------
log() {
  local level=$1; shift
  local msg=$1; shift
  if [[ "$JSON_OUTPUT" == "true" ]]; then
    echo "{\"level\":\"$level\",\"msg\":\"$msg\",\"ts\":\"$(date -u +%FT%TZ)\"}"
  else
    local color=$NC
    case $level in
      error) color=$RED ;;
      warn)  color=$YELLOW ;;
      info)  color=$GREEN ;;
      debug) color=$BLUE ;;
    esac
    echo -e "${color}[$(date -u +%FT%TZ)] [$level] $msg${NC}"
  fi
}

# ----------------------------------------------------------------------------
# 严重程度判定（基于 diff 内容）
# ----------------------------------------------------------------------------
# 输入：diff 文本
# 输出：critical / high / medium / low / none
classify_severity() {
  local diff_text="$1"
  if echo "$diff_text" | grep -qE '^\s*[+-]\s*image:'; then
    echo "critical"   # 镜像漂移
  elif echo "$diff_text" | grep -qE '^\s*[+-]\s*data:'; then
    echo "high"       # ConfigMap/Secret data 变更
  elif echo "$diff_text" | grep -qE '^\s*[+-]\s*replicas:'; then
    echo "medium"     # 副本数变更
  elif echo "$diff_text" | grep -qE '^\s*[+-]\s*labels:'; then
    echo "low"        # 标签变更
  elif echo "$diff_text" | grep -qE '^\s*[+-]\s*annotations:'; then
    echo "low"        # 注解变更
  elif [[ -z "$diff_text" ]]; then
    echo "none"
  else
    echo "medium"     # 默认 medium
  fi
}

# ----------------------------------------------------------------------------
# 漂移类型判定
# ----------------------------------------------------------------------------
classify_type() {
  local diff_text="$1"
  if echo "$diff_text" | grep -qE 'image:'; then
    echo "image-drift"
  elif echo "$diff_text" | grep -qE 'data:'; then
    echo "config-drift"
  elif echo "$diff_text" | grep -qE 'replicas:'; then
    echo "replica-drift"
  elif echo "$diff_text" | grep -qE 'labels:'; then
    echo "label-drift"
  elif echo "$diff_text" | grep -qE 'annotations:'; then
    echo "annotation-drift"
  else
    echo "field-drift"
  fi
}

# ----------------------------------------------------------------------------
# 提取 App 所在环境
# ----------------------------------------------------------------------------
get_env() {
  local app=$1
  kubectl get application "$app" -n "$ARGOCD_NS" -o jsonpath='{.metadata.labels.environment}' 2>/dev/null || echo "unknown"
}

# ----------------------------------------------------------------------------
# 写入漂移事件 ConfigMap
# ----------------------------------------------------------------------------
write_drift_event() {
  local app=$1 env=$2 severity=$3 dtype=$4 diff_summary=$5

  if [[ "$DRY_RUN" == "true" ]]; then
    log info "[dry-run] 漂移事件: app=$app env=$env severity=$severity type=$dtype"
    return
  fi

  local ts=$(date -u +%Y%m%d-%H%M%S)
  local cm_name="drift-event-${app}-${ts}"

  kubectl create configmap "$cm_name" -n "$DRIFT_EVENT_NS" \
    --from-literal=app="$app" \
    --from-literal=environment="$env" \
    --from-literal=severity="$severity" \
    --from-literal=drift-type="$dtype" \
    --from-literal=detected-at="$(date -u +%FT%TZ)" \
    --from-literal=detected-by="detect-drift.sh" \
    --from-literal=diff-summary="$diff_summary" \
    --from-literal=remediation-status="pending" \
    -o yaml --dry-run=client | kubectl apply -f -

  kubectl label configmap "$cm_name" -n "$DRIFT_EVENT_NS" \
    drift-event=true app="$app" environment="$env" severity="$severity" --overwrite

  log info "漂移事件已写入: $cm_name"
}

# ----------------------------------------------------------------------------
# 检测单个 App
# ----------------------------------------------------------------------------
detect_app() {
  local app=$1
  local env
  env=$(get_env "$app")

  # 环境过滤
  if [[ -n "$TARGET_ENV" && "$env" != "$TARGET_ENV" ]]; then
    return
  fi

  log info "检测 App: $app (env=$env)"

  # 1. 检查 sync status
  local sync_status
  sync_status=$(kubectl get application "$app" -n "$ARGOCD_NS" \
    -o jsonpath='{.status.sync.status}' 2>/dev/null || echo "Unknown")

  local health_status
  health_status=$(kubectl get application "$app" -n "$ARGOCD_NS" \
    -o jsonpath='{.status.health.status}' 2>/dev/null || echo "Unknown")

  if [[ "$sync_status" == "Synced" && "$health_status" == "Healthy" ]]; then
    log debug "$app: Synced + Healthy，无漂移"
    return
  fi

  # 2. 执行 diff
  local diff_output=""
  diff_output=$(argocd app diff "$app" --server-side-generate 2>&1 || true)

  if [[ -z "$diff_output" || "$diff_output" == "" ]]; then
    log debug "$app: diff 为空（可能仅 status 字段差异）"
    return
  fi

  # 3. 分级
  local severity dtype
  severity=$(classify_severity "$diff_output")
  dtype=$(classify_type "$diff_output")

  if [[ "$severity" == "none" ]]; then
    log debug "$app: 无有效漂移"
    return
  fi

  log warn "$app: 检测到漂移 severity=$severity type=$dtype"

  # 4. 输出 diff（截断前 2000 字符）
  local diff_summary
  diff_summary=$(echo "$diff_output" | head -c 2000)

  if [[ "$JSON_OUTPUT" == "true" ]]; then
    jq -n \
      --arg app "$app" \
      --arg env "$env" \
      --arg sev "$severity" \
      --arg typ "$dtype" \
      --arg diff "$diff_summary" \
      --arg sync "$sync_status" \
      --arg health "$health_status" \
      '{app:$app, environment:$env, severity:$sev, driftType:$typ,
        syncStatus:$sync, healthStatus:$health, diff:$diff,
        detectedAt: (now | todate)}'
  else
    echo "────────────────────────────────────────"
    echo "App:        $app"
    echo "Env:        $env"
    echo "Severity:   $severity"
    echo "Type:       $dtype"
    echo "Sync:       $sync_status"
    echo "Health:     $health_status"
    echo "Diff:"
    echo "$diff_summary"
    echo "────────────────────────────────────────"
  fi

  # 5. 写入漂移事件
  write_drift_event "$app" "$env" "$severity" "$dtype" "$diff_summary"

  # 6. 暴露 Prometheus 指标（通过 textfile collector）
  if [[ -d "/var/lib/node_exporter/textfile_collector" ]]; then
    echo "argocd_drift_detected{app=\"$app\",env=\"$env\",severity=\"$severity\",type=\"$dtype\"} 1" \
      > "/var/lib/node_exporter/textfile_collector/drift_${app}.prom"
  fi
}

# ----------------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------------
main() {
  log info "===== 漂移检测开始 ====="
  log info "参数: env=$TARGET_ENV app=$TARGET_APP dry_run=$DRY_RUN json=$JSON_OUTPUT"

  # 检查依赖
  for cmd in argocd kubectl jq; do
    if ! command -v $cmd &>/dev/null; then
      log error "缺少依赖: $cmd"
      exit 1
    fi
  done

  # 枚举 Application
  local apps=()
  if [[ -n "$TARGET_APP" ]]; then
    apps=("$TARGET_APP")
  else
    mapfile -t apps < <(kubectl get application -n "$ARGOCD_NS" -o jsonpath='{.items[*].metadata.name}')
  fi

  if [[ ${#apps[@]} -eq 0 ]]; then
    log warn "未找到任何 Application"
    exit 0
  fi

  log info "待检测 App 数: ${#apps[@]}"

  # 逐个检测
  local drift_count=0
  for app in "${apps[@]}"; do
    if detect_app "$app"; then
      :
    else
      drift_count=$((drift_count + 1))
    fi
  done

  # 统计
  log info "===== 漂移检测完成 ====="
  log info "检测 App 数: ${#apps[@]}"
  log info "漂移 App 数: $drift_count"

  # 输出汇总到文件
  echo "{\"detected_at\":\"$(date -u +%FT%TZ)\",\"total_apps\":${#apps[@]},\"drifted_apps\":$drift_count}" \
    > "$OUTPUT_DIR/drift-summary-$(date -u +%Y%m%d-%H%M%S).json"

  exit 0
}

main "$@"