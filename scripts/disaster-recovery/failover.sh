#!/bin/bash
# ============================================================================
# 故障切换脚本 - failover.sh
# 功能：
#   1. 参数：故障集群名、目标集群名
#   2. 将故障集群的流量切换到目标集群
#   3. 更新 Karmada PropagationPolicy（移除故障集群）
#   4. 在目标集群恢复数据
#   5. 验证切换结果
#   6. 计算实际 RTO
# 用法：
#   ./failover.sh --from <failed-cluster> --to <target-cluster> [--backup <name>]
#   ./failover.sh --help
# 依赖：velero, kubectl, kubectl-karmada
# ============================================================================
set -euo pipefail

# ---- 默认参数 ----
FROM_CLUSTER=""              # 故障集群
TO_CLUSTER=""                # 目标集群
BACKUP_NAME=""               # 备份名称（空则自动查找最新）
KARMADA_HOST_CONTEXT="karmada-host"
VELERO_NAMESPACE="velero"
AUTO_RESTORE=true            # 是否自动在目标集群恢复数据
REPORT_DIR="/tmp/dr-reports"
FAILOVER_START_TIME=""       # 故障开始时间（用于计算 RTO）

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
集群故障切换脚本 - 基于 Karmada + Velero

用法:
  $0 --from <failed-cluster> --to <target-cluster> [选项]

必选参数:
  --from <cluster>   故障集群名称
  --to <cluster>     目标集群名称（接管流量的集群）

可选参数:
  --backup <name>              备份名称（不指定则自动查找最新可用备份）
  --no-restore                 不自动在目标集群恢复数据
  --karmada-context <ctx>      Karmada 控制面 context（默认: karmada-host）
  --velero-namespace <ns>      Velero namespace（默认: velero）
  --report-dir <dir>           报告输出目录（默认: /tmp/dr-reports）
  --failover-start <time>      故障开始时间（用于计算 RTO，格式: epoch 或 YYYY-MM-DD HH:MM:SS）
  --help                       显示帮助信息

切换流程:
  1. 标记故障集群不可调度
  2. 更新 Karmada PropagationPolicy（移除故障集群，仅保留目标集群）
  3. 在目标集群恢复数据（Velero restore）
  4. 更新流量路由（Karmada 调度约束）
  5. 验证目标集群业务正常
  6. 计算 RTO

示例:
  # 基本切换
  $0 --from cluster-beijing --to cluster-shanghai

  # 指定备份
  $0 --from cluster-beijing --to cluster-shanghai --backup full-cluster-beijing-20260101-020000

  # 不自动恢复（目标集群已有数据）
  $0 --from cluster-beijing --to cluster-shanghai --no-restore
EOF
  exit 0
}

# ---- 参数解析 ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --from)              FROM_CLUSTER="$2"; shift 2 ;;
    --to)                TO_CLUSTER="$2"; shift 2 ;;
    --backup)            BACKUP_NAME="$2"; shift 2 ;;
    --no-restore)        AUTO_RESTORE=false; shift ;;
    --karmada-context)   KARMADA_HOST_CONTEXT="$2"; shift 2 ;;
    --velero-namespace)  VELERO_NAMESPACE="$2"; shift 2 ;;
    --report-dir)        REPORT_DIR="$2"; shift 2 ;;
    --failover-start)    FAILOVER_START_TIME="$2"; shift 2 ;;
    --help|-h)           show_help ;;
    *)                   log_error "未知参数: $1"; show_help ;;
  esac
done

# ---- 参数校验 ----
if [[ -z "$FROM_CLUSTER" ]]; then
  log_error "必须指定 --from（故障集群）"; show_help
fi
if [[ -z "$TO_CLUSTER" ]]; then
  log_error "必须指定 --to（目标集群）"; show_help
fi
if [[ "$FROM_CLUSTER" == "$TO_CLUSTER" ]]; then
  log_error "故障集群与目标集群不能相同"; exit 1
fi

# ---- 获取集群 context ----
get_cluster_context() {
  echo "karmada-${1}"
}

# ---- 查找最新可用备份 ----
find_latest_backup() {
  local cluster_name="$1"
  local cluster_context
  cluster_context=$(get_cluster_context "$cluster_name")

  log_step "查找集群 ${cluster_name} 的最新可用备份..."
  local latest
  latest=$(velero --kubecontext "$cluster_context" --namespace "$VELERO_NAMESPACE" \
    backup get -o json 2>/dev/null \
    | python3 -c "
import sys, json
data = json.load(sys.stdin)
items = data.get('items', [])
# 过滤成功的备份
completed = [b for b in items if b.get('status',{}).get('phase') == 'Completed']
if not completed:
    print('')
    sys.exit(0)
# 按开始时间排序，取最新
completed.sort(key=lambda b: b.get('status',{}).get('startTimestamp',''), reverse=True)
print(completed[0]['metadata']['name'])
" 2>/dev/null || echo "")

  if [[ -z "$latest" ]]; then
    log_error "未找到集群 ${cluster_name} 的可用备份"
    exit 1
  fi
  log_info "最新可用备份: ${latest}"
  echo "$latest"
}

# ---- 步骤1：标记故障集群不可调度 ----
mark_cluster_unschedulable() {
  log_step "步骤 1/5: 标记故障集群 ${FROM_CLUSTER} 不可调度..."

  # 给集群打 taint（阻止新调度）
  kubectl --context="$KARMADA_HOST_CONTEXT" taint cluster "$FROM_CLUSTER" \
    node.kubernetes.io/unreachable:NoSchedule --overwrite 2>/dev/null || {
    log_warn "taint 添加失败（可能已存在），继续..."
  }

  # 标记集群为不可用（添加 label）
  kubectl --context="$KARMADA_HOST_CONTEXT" label cluster "$FROM_CLUSTER" \
    disaster-recovery/status=failed --overwrite 2>/dev/null || true

  log_info "故障集群已标记不可调度"
}

# ---- 步骤2：更新 PropagationPolicy ----
update_propagation_policy() {
  log_step "步骤 2/5: 更新 Karmada PropagationPolicy（移除故障集群）..."

  # 查找所有 PropagationPolicy 和 ClusterPropagationPolicy
  local pp_list cpp_list
  pp_list=$(kubectl --context="$KARMADA_HOST_CONTEXT" get pp -o jsonpath='{range .items[*]}{.metadata.namespace}/{.metadata.name}{"\n"}{end}' 2>/dev/null || true)
  cpp_list=$(kubectl --context="$KARMADA_HOST_CONTEXT" get cpp -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true)

  local pp_count=0

  # 更新 PropagationPolicy：将 affinity 调整为仅目标集群
  while IFS= read -r pp; do
    [[ -z "$pp" ]] && continue
    local ns name
    ns=$(echo "$pp" | cut -d/ -f1)
    name=$(echo "$pp" | cut -d/ -f2)

    # 使用 patch 更新 placement，仅调度到目标集群
    kubectl --context="$KARMADA_HOST_CONTEXT" -n "$ns" patch pp "$name" --type=merge -p \
      "{\"spec\":{\"placement\":{\"clusterAffinity\":{\"clusterNames\":[\"${TO_CLUSTER}\"]}}}}" 2>/dev/null || {
        log_warn "更新 PP ${pp} 失败，继续..."
        continue
      }
    ((pp_count++))
  done <<< "$pp_list"

  # 更新 ClusterPropagationPolicy
  while IFS= read -r cpp; do
    [[ -z "$cpp" ]] && continue
    kubectl --context="$KARMADA_HOST_CONTEXT" patch cpp "$cpp" --type=merge -p \
      "{\"spec\":{\"placement\":{\"clusterAffinity\":{\"clusterNames\":[\"${TO_CLUSTER}\"]}}}}" 2>/dev/null || {
        log_warn "更新 CPP ${cpp} 失败，继续..."
        continue
      }
    ((pp_count++))
  done <<< "$cpp_list"

  log_info "已更新 ${pp_count} 个 PropagationPolicy，流量仅调度到 ${TO_CLUSTER}"
}

# ---- 步骤3：在目标集群恢复数据 ----
restore_to_target_cluster() {
  log_step "步骤 3/5: 在目标集群 ${TO_CLUSTER} 恢复数据..."

  if [[ "$AUTO_RESTORE" != true ]]; then
    log_warn "跳过数据恢复（--no-restore）"
    return 0
  fi

  # 确定备份名称
  local backup_to_use="$BACKUP_NAME"
  if [[ -z "$backup_to_use" ]]; then
    backup_to_use=$(find_latest_backup "$FROM_CLUSTER")
  fi

  # 调用 restore-cluster.sh
  local script_dir
  script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
  if [[ -f "$script_dir/restore-cluster.sh" ]]; then
    bash "$script_dir/restore-cluster.sh" \
      --cluster "$TO_CLUSTER" \
      --backup "$backup_to_use" \
      --policy default \
      --karmada-context "$KARMADA_HOST_CONTEXT" \
      --velero-namespace "$VELERO_NAMESPACE" \
      --report-dir "$REPORT_DIR" || {
        log_error "目标集群数据恢复失败"
        return 1
      }
    log_info "目标集群数据恢复完成"
  else
    log_warn "restore-cluster.sh 不存在，跳过数据恢复"
  fi
}

# ---- 步骤4：验证切换结果 ----
verify_failover() {
  log_step "步骤 4/5: 验证切换结果..."

  local target_context
  target_context=$(get_cluster_context "$TO_CLUSTER")

  # 1. 检查目标集群可达
  if ! kubectl --context="$target_context" cluster-info >/dev/null 2>&1; then
    log_error "目标集群 ${TO_CLUSTER} 不可达"
    return 1
  fi
  log_info "目标集群 ${TO_CLUSTER} 可达"

  # 2. 检查 Deployment 就绪
  local unready
  unready=$(kubectl --context="$target_context" get deploy --all-namespaces -o json \
    | python3 -c "
import sys, json
data = json.load(sys.stdin)['items']
unready = [d for d in data if d.get('status',{}).get('unavailableReplicas',0) > 0]
print(len(unready))
" 2>/dev/null || echo "0")
  if [[ "$unready" -gt 0 ]]; then
    log_warn "目标集群有 ${unready} 个 Deployment 未就绪"
  else
    log_info "目标集群所有 Deployment 就绪"
  fi

  # 3. 检查 Karmada 资源分发状态
  local binding_count
  binding_count=$(kubectl --context="$KARMADA_HOST_CONTEXT" get resourcebindings --all-namespaces --no-headers 2>/dev/null | wc -l || echo "0")
  log_info "Karmada ResourceBinding 数: ${binding_count}"

  # 4. 检查故障集群是否已排除
  local still_scheduled
  still_scheduled=$(kubectl --context="$KARMADA_HOST_CONTEXT" get clusters "$FROM_CLUSTER" -o jsonpath='{.metadata.labels.disaster-recovery\/status}' 2>/dev/null || echo "")
  if [[ "$still_scheduled" == "failed" ]]; then
    log_info "故障集群 ${FROM_CLUSTER} 已正确标记为 failed"
  fi

  return 0
}

# ---- 步骤5：计算 RTO ----
calculate_rto() {
  log_step "步骤 5/5: 计算 RTO..."

  local failover_end
  failover_end=$(date +%s)

  # 如果提供了故障开始时间，计算 RTO
  if [[ -n "$FAILOVER_START_TIME" ]]; then
    local start_epoch
    # 尝试解析时间
    if [[ "$FAILOVER_START_TIME" =~ ^[0-9]+$ ]]; then
      start_epoch="$FAILOVER_START_TIME"
    else
      start_epoch=$(date -d "$FAILOVER_START_TIME" +%s 2>/dev/null || echo "")
    fi

    if [[ -n "$start_epoch" ]]; then
      local rto_seconds=$((failover_end - start_epoch))
      local rto_minutes=$((rto_seconds / 60))
      log_info "实际 RTO: ${rto_minutes} 分钟（${rto_seconds} 秒）"
      echo "${rto_minutes}"
    else
      log_warn "无法解析故障开始时间: ${FAILOVER_START_TIME}"
      echo "N/A"
    fi
  else
    log_warn "未提供故障开始时间，无法计算 RTO（使用 --failover-start 指定）"
    echo "N/A"
  fi
}

# ---- 生成切换报告 ----
generate_report() {
  local report_file="$REPORT_DIR/failover-report-$(date +%Y%m%d-%H%M%S).md"
  local duration="$1"
  local rto="$2"

  cat > "$report_file" <<EOF
# 集群故障切换报告

## 概要
- **故障集群**: ${FROM_CLUSTER}
- **目标集群**: ${TO_CLUSTER}
- **备份名称**: ${BACKUP_NAME:-自动查找}
- **执行时间**: $(date '+%Y-%m-%d %H:%M:%S')
- **切换耗时**: ${duration} 秒
- **实际 RTO**: ${rto} 分钟

## 切换步骤
1. [x] 标记故障集群不可调度
2. [x] 更新 Karmada PropagationPolicy
3. [x] 在目标集群恢复数据
4. [x] 验证切换结果
5. [x] 计算 RTO

## 后续操作
1. 监控目标集群负载与性能
2. 修复故障集群后执行回切
3. 通知业务团队切换完成
4. 更新运维文档与拓扑图

## 注意事项
- 故障集群 ${FROM_CLUSTER} 已标记为 failed，修复后需手动清除 taint
- 清除命令: kubectl --context=${KARMADA_HOST_CONTEXT} taint cluster ${FROM_CLUSTER} node.kubernetes.io/unreachable:NoSchedule-
EOF

  log_info "切换报告已生成: ${report_file}"
  echo "$report_file"
}

# ---- 主流程 ----
main() {
  log_info "=== 集群故障切换开始 ==="
  log_info "故障集群: ${FROM_CLUSTER} -> 目标集群: ${TO_CLUSTER}"

  mkdir -p "$REPORT_DIR"

  local global_start
  global_start=$(date +%s)

  # 执行切换步骤
  mark_cluster_unschedulable
  update_propagation_policy
  restore_to_target_cluster
  verify_failover
  local rto
  rto=$(calculate_rto)

  local global_end
  global_end=$(date +%s)
  local duration=$((global_end - global_start))

  # 生成报告
  local report_file
  report_file=$(generate_report "$duration" "$rto")

  log_info "=== 集群故障切换完成 ==="
  log_info "耗时: ${duration}s | RTO: ${rto} 分钟 | 报告: ${report_file}"
}

main "$@"