#!/bin/bash
# ============================================================================
# 一键恢复脚本 - restore-cluster.sh
# 功能：
#   1. 参数：集群名、备份名称、恢复策略
#   2. 执行 Velero restore
#   3. 等待恢复完成
#   4. 验证恢复结果（资源数量/健康状态）
#   5. 生成恢复报告
# 用法：
#   ./restore-cluster.sh --cluster <name> --backup <backup-name> [--policy <policy>]
#   ./restore-cluster.sh --help
# 依赖：velero, kubectl
# ============================================================================
set -euo pipefail

# ---- 默认参数 ----
CLUSTER_NAME=""              # 目标集群名
BACKUP_NAME=""               # 备份名称
RESTORE_POLICY="default"     # 恢复策略：default | business-critical | full
KARMADA_HOST_CONTEXT="karmada-host"
VELERO_NAMESPACE="velero"
RESTORE_TIMEOUT="1800"       # 恢复超时（秒）
REPORT_DIR="/tmp/dr-reports"
DRY_RUN=false                # 试运行模式

# ---- 颜色输出 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*" >&2; }
log_step()  { echo -e "${BLUE}[STEP]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*"; }

# ---- 帮助信息 ----
show_help() {
  cat <<EOF
一键集群恢复脚本 - 基于 Velero

用法:
  $0 --cluster <name> --backup <backup-name> [选项]

必选参数:
  --cluster <name>        目标集群名称
  --backup <backup-name>  Velero 备份名称

可选参数:
  --policy <policy>         恢复策略（default|business-critical|full，默认: default）
  --karmada-context <ctx>   Karmada 控制面 context（默认: karmada-host）
  --velero-namespace <ns>   Velero namespace（默认: velero）
  --timeout <seconds>       恢复超时（默认: 1800）
  --report-dir <dir>        报告输出目录（默认: /tmp/dr-reports）
  --dry-run                 试运行（不实际执行恢复）
  --help                    显示帮助信息

恢复策略说明:
  default           按 namespace 恢复，不覆盖已有资源
  business-critical 仅恢复核心业务（label priority=critical），覆盖已有资源
  full              全量恢复，灾难性故障后使用

示例:
  # 默认恢复
  $0 --cluster cluster-shanghai --backup full-cluster-beijing-20260101-020000

  # 核心业务恢复
  $0 --cluster cluster-shanghai --backup backup-xxx --policy business-critical

  # 试运行
  $0 --cluster cluster-shanghai --backup backup-xxx --dry-run
EOF
  exit 0
}

# ---- 参数解析 ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --cluster)            CLUSTER_NAME="$2"; shift 2 ;;
    --backup)             BACKUP_NAME="$2"; shift 2 ;;
    --policy)             RESTORE_POLICY="$2"; shift 2 ;;
    --karmada-context)    KARMADA_HOST_CONTEXT="$2"; shift 2 ;;
    --velero-namespace)   VELERO_NAMESPACE="$2"; shift 2 ;;
    --timeout)            RESTORE_TIMEOUT="$2"; shift 2 ;;
    --report-dir)         REPORT_DIR="$2"; shift 2 ;;
    --dry-run)            DRY_RUN=true; shift ;;
    --help|-h)            show_help ;;
    *)                    log_error "未知参数: $1"; show_help ;;
  esac
done

# ---- 参数校验 ----
if [[ -z "$CLUSTER_NAME" ]]; then
  log_error "必须指定 --cluster"
  show_help
fi
if [[ -z "$BACKUP_NAME" ]]; then
  log_error "必须指定 --backup"
  show_help
fi

# ---- 获取集群 context ----
get_cluster_context() {
  echo "karmada-${1}"
}

# ---- 恢复策略映射 ----
get_policy_args() {
  case "$RESTORE_POLICY" in
    default)
      # 按 namespace 恢复，不覆盖已有资源
      echo "--existing-resource-policy=none"
      ;;
    business-critical)
      # 仅恢复核心业务，覆盖已有资源
      echo "--selector=priority=critical --existing-resource-policy=backup"
      ;;
    full)
      # 全量恢复
      echo "--existing-resource-policy=none"
      ;;
    *)
      log_warn "未知策略: ${RESTORE_POLICY}，使用默认策略"
      echo "--existing-resource-policy=none"
      ;;
  esac
}

# ---- 等待恢复完成 ----
wait_for_restore() {
  local restore_name="$1"
  local cluster_context="$2"
  local timeout="$3"
  local elapsed=0

  log_step "等待恢复完成: ${restore_name}（超时: ${timeout}s）"
  while [[ $elapsed -lt $timeout ]]; do
    local phase
    phase=$(velero --kubecontext "$cluster_context" --namespace "$VELERO_NAMESPACE" \
      restore get "$restore_name" -o jsonpath='{.status.phase}' 2>/dev/null || echo "Unknown")

    case "$phase" in
      Completed)
        log_info "恢复完成: ${restore_name}"
        return 0
        ;;
      Failed)
        log_error "恢复失败: ${restore_name}"
        velero --kubecontext "$cluster_context" --namespace "$VELERO_NAMESPACE" \
          restore describe "$restore_name" 2>/dev/null || true
        return 1
        ;;
      PartiallyFailed)
        log_warn "恢复部分失败: ${restore_name}"
        return 2
        ;;
      InProgress|New|"")
        sleep 10
        elapsed=$((elapsed + 10))
        ;;
      *)
        log_warn "未知状态: ${phase}（${elapsed}/${timeout}s）"
        sleep 10
        elapsed=$((elapsed + 10))
        ;;
    esac
  done
  log_error "恢复超时: ${restore_name}"
  return 1
}

# ---- 验证恢复结果 ----
verify_restore() {
  local cluster_context="$1"
  local restore_name="$2"

  log_step "验证恢复结果..."

  # 1. 恢复的资源数量
  local restored_items
  restored_items=$(velero --kubecontext "$cluster_context" --namespace "$VELERO_NAMESPACE" \
    restore get "$restore_name" -o jsonpath='{.status.progress.totalItems}' 2>/dev/null || echo "0")
  log_info "恢复资源总数: ${restored_items}"

  # 2. 检查 Deployment 就绪状态
  local total_deploy
  local ready_deploy
  total_deploy=$(kubectl --context="$cluster_context" get deploy --all-namespaces -o json \
    | python3 -c "import sys,json; print(len(json.load(sys.stdin)['items']))" 2>/dev/null || echo "0")
  ready_deploy=$(kubectl --context="$cluster_context" get deploy --all-namespaces -o json \
    | python3 -c "
import sys,json
data=json.load(sys.stdin)['items']
ready=sum(1 for d in data if d.get('status',{}).get('availableReplicas',0) == d.get('spec',{}).get('replicas',0) and d.get('spec',{}).get('replicas',0)>0)
print(ready)
" 2>/dev/null || echo "0")
  log_info "Deployment 就绪: ${ready_deploy}/${total_deploy}"

  # 3. 检查 Pod 状态
  local total_pods
  local running_pods
  total_pods=$(kubectl --context="$cluster_context" get pods --all-namespaces --field-selector=status.phase!=Succeeded -o json \
    | python3 -c "import sys,json; print(len(json.load(sys.stdin)['items']))" 2>/dev/null || echo "0")
  running_pods=$(kubectl --context="$cluster_context" get pods --all-namespaces --field-selector=status.phase=Running -o json \
    | python3 -c "import sys,json; print(len(json.load(sys.stdin)['items']))" 2>/dev/null || echo "0")
  log_info "Pod 运行: ${running_pods}/${total_pods}"

  # 4. 检查失败 Pod
  local failed_pods
  failed_pods=$(kubectl --context="$cluster_context" get pods --all-namespaces --field-selector=status.phase=Failed --no-headers 2>/dev/null | wc -l || echo "0")
  if [[ "$failed_pods" -gt 0 ]]; then
    log_warn "存在 ${failed_pods} 个 Failed 状态 Pod"
  fi

  # 健康评分
  local health_score=100
  if [[ "$total_deploy" -gt 0 ]]; then
    health_score=$((ready_deploy * 100 / total_deploy))
  fi
  log_info "健康评分: ${health_score}%"

  # 返回验证结果
  echo "${restored_items}|${ready_deploy}|${total_deploy}|${running_pods}|${total_pods}|${failed_pods}|${health_score}"
}

# ---- 生成恢复报告 ----
generate_report() {
  local report_file="$REPORT_DIR/restore-report-$(date +%Y%m%d-%H%M%S).md"
  local restore_name="$1"
  local duration="$2"
  local verify_result="$3"

  IFS='|' read -r restored_items ready_deploy total_deploy running_pods total_pods failed_pods health_score <<< "$verify_result"

  cat > "$report_file" <<EOF
# 集群恢复报告

## 概要
- **目标集群**: ${CLUSTER_NAME}
- **备份名称**: ${BACKUP_NAME}
- **恢复名称**: ${restore_name}
- **恢复策略**: ${RESTORE_POLICY}
- **执行时间**: $(date '+%Y-%m-%d %H:%M:%S')
- **恢复耗时**: ${duration} 秒

## 恢复结果
- **恢复资源数**: ${restored_items}
- **Deployment 就绪**: ${ready_deploy}/${total_deploy}
- **Pod 运行**: ${running_pods}/${total_pods}
- **Failed Pod**: ${failed_pods}
- **健康评分**: ${health_score}%

## 验收标准
- [ ] 恢复资源数 > 0
- [ ] Deployment 全部就绪
- [ ] 无 Failed Pod
- [ ] 健康评分 >= 95%

## 后续操作
1. 检查业务功能是否正常
2. 验证数据完整性
3. 更新 DNS/流量路由指向恢复集群
4. 通知相关团队恢复完成
EOF

  log_info "恢复报告已生成: ${report_file}"
  echo "$report_file"
}

# ---- 主流程 ----
main() {
  log_info "=== 集群恢复开始 ==="
  log_info "集群: ${CLUSTER_NAME} | 备份: ${BACKUP_NAME} | 策略: ${RESTORE_POLICY}"

  mkdir -p "$REPORT_DIR"

  local cluster_context
  cluster_context=$(get_cluster_context "$CLUSTER_NAME")

  # 检查备份是否存在
  log_step "检查备份是否存在: ${BACKUP_NAME}"
  if ! velero --kubecontext "$cluster_context" --namespace "$VELERO_NAMESPACE" \
    backup get "$BACKUP_NAME" >/dev/null 2>&1; then
    log_error "备份不存在: ${BACKUP_NAME}"
    exit 1
  fi
  log_info "备份存在"

  # 试运行模式
  if [[ "$DRY_RUN" == true ]]; then
    log_warn "试运行模式，不实际执行恢复"
    local policy_args
    policy_args=$(get_policy_args)
    log_info "将执行: velero restore create restore-${BACKUP_NAME} --from-backup ${BACKUP_NAME} ${policy_args}"
    exit 0
  fi

  # 生成恢复名称
  local restore_name="restore-${BACKUP_NAME}-$(date +%Y%m%d-%H%M%S)"
  local policy_args
  policy_args=$(get_policy_args)

  # 执行恢复
  log_step "执行 Velero restore: ${restore_name}"
  local start_time
  start_time=$(date +%s)

  # shellcheck disable=SC2086
  velero --kubecontext "$cluster_context" --namespace "$VELERO_NAMESPACE" \
    restore create "$restore_name" \
    --from-backup "$BACKUP_NAME" \
    $policy_args \
    --wait 2>&1 || {
      log_error "恢复创建失败"
      exit 1
    }

  # 等待恢复完成
  if ! wait_for_restore "$restore_name" "$cluster_context" "$RESTORE_TIMEOUT"; then
    log_error "恢复未成功完成"
    exit 1
  fi

  local end_time
  end_time=$(date +%s)
  local duration=$((end_time - start_time))

  # 验证恢复结果
  local verify_result
  verify_result=$(verify_restore "$cluster_context" "$restore_name")

  # 生成报告
  local report_file
  report_file=$(generate_report "$restore_name" "$duration" "$verify_result")

  log_info "=== 集群恢复完成 ==="
  log_info "耗时: ${duration}s | 报告: ${report_file}"
}

main "$@"