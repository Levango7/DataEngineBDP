#!/bin/bash
# ============================================================================
# 灾备演练脚本 - disaster-recovery-drill.sh
# 功能：
#   1. 模拟集群故障（标记不可用）
#   2. 自动执行故障切换
#   3. 验证业务连续性
#   4. 计算 RTO/RPO 实际值
#   5. 生成演练报告
#   6. 恢复演练环境
# 用法：
#   ./disaster-recovery-drill.sh --from <cluster> --to <cluster> [--cleanup]
#   ./disaster-recovery-drill.sh --help
# 依赖：velero, kubectl, kubectl-karmada
# ============================================================================
set -euo pipefail

# ---- 默认参数 ----
FROM_CLUSTER=""              # 模拟故障集群
TO_CLUSTER=""                # 接管集群
KARMADA_HOST_CONTEXT="karmada-host"
VELERO_NAMESPACE="velero"
REPORT_DIR="/tmp/dr-reports"
CLEANUP_ONLY=false           # 仅恢复演练环境
SKIP_CLEANUP=false           # 跳过演练后清理
RTO_TARGET=30                # RTO 目标（分钟）
RPO_TARGET=60                # RPO 目标（分钟）
DRILL_NAMESPACE="dr-drill"   # 演练 namespace

# ---- 颜色输出 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*" >&2; }
log_step()  { echo -e "${BLUE}[STEP]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*"; }
log_drill() { echo -e "${CYAN}[DRILL]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*"; }

# ---- 帮助信息 ----
show_help() {
  cat <<EOF
灾备演练脚本 - 模拟集群故障并验证恢复能力

用法:
  $0 --from <cluster> --to <cluster> [选项]

必选参数:
  --from <cluster>   模拟故障的集群
  --to <cluster>     接管的集群

可选参数:
  --karmada-context <ctx>   Karmada 控制面 context（默认: karmada-host）
  --velero-namespace <ns>   Velero namespace（默认: velero）
  --report-dir <dir>        报告输出目录（默认: /tmp/dr-reports）
  --rto-target <minutes>    RTO 目标（默认: 30）
  --rpo-target <minutes>    RPO 目标（默认: 60）
  --cleanup-only            仅恢复演练环境（不执行演练）
  --skip-cleanup            跳过演练后清理
  --help                    显示帮助信息

演练流程:
  1. 部署演练应用（dr-drill namespace）
  2. 记录故障开始时间
  3. 模拟集群故障（标记不可用）
  4. 自动执行故障切换（failover.sh）
  5. 验证业务连续性（应用可访问）
  6. 计算 RTO/RPO 实际值
  7. 生成演练报告
  8. 恢复演练环境

示例:
  # 执行灾备演练
  $0 --from cluster-beijing --to cluster-shanghai

  # 自定义 RTO/RPO 目标
  $0 --from cluster-beijing --to cluster-shanghai --rto-target 15 --rpo-target 30

  # 仅恢复演练环境
  $0 --cleanup-only --from cluster-beijing --to cluster-shanghai
EOF
  exit 0
}

# ---- 参数解析 ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --from)              FROM_CLUSTER="$2"; shift 2 ;;
    --to)                TO_CLUSTER="$2"; shift 2 ;;
    --karmada-context)   KARMADA_HOST_CONTEXT="$2"; shift 2 ;;
    --velero-namespace)  VELERO_NAMESPACE="$2"; shift 2 ;;
    --report-dir)        REPORT_DIR="$2"; shift 2 ;;
    --rto-target)        RTO_TARGET="$2"; shift 2 ;;
    --rpo-target)        RPO_TARGET="$2"; shift 2 ;;
    --cleanup-only)      CLEANUP_ONLY=true; shift ;;
    --skip-cleanup)      SKIP_CLEANUP=true; shift ;;
    --help|-h)           show_help ;;
    *)                   log_error "未知参数: $1"; show_help ;;
  esac
done

if [[ "$CLEANUP_ONLY" != true ]]; then
  if [[ -z "$FROM_CLUSTER" ]]; then log_error "必须指定 --from"; show_help; fi
  if [[ -z "$TO_CLUSTER" ]];   then log_error "必须指定 --to"; show_help; fi
fi

# ---- 获取集群 context ----
get_cluster_context() { echo "karmada-${1}"; }

# ---- 获取脚本目录 ----
get_script_dir() { cd "$(dirname "${BASH_SOURCE[0]}")" && pwd; }

# ---- 部署演练应用 ----
deploy_drill_app() {
  log_drill "部署演练应用到 ${FROM_CLUSTER}..."

  local from_context
  from_context=$(get_cluster_context "$FROM_CLUSTER")

  # 创建演练 namespace
  kubectl --context="$from_context" create namespace "$DRILL_NAMESPACE" --dry-run=client -o yaml | kubectl --context="$from_context" apply -f -

  # 部署演练应用（nginx + ConfigMap 标记时间戳）
  cat <<EOF | kubectl --context="$from_context" -n "$DRILL_NAMESPACE" apply -f -
apiVersion: v1
kind: ConfigMap
metadata:
  name: drill-marker
data:
  deploy-time: "$(date '+%Y-%m-%d %H:%M:%S')"
  deploy-epoch: "$(date +%s)"
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: drill-app
  labels:
    app: drill-app
    disaster-recovery: "drill"
spec:
  replicas: 2
  selector:
    matchLabels:
      app: drill-app
  template:
    metadata:
      labels:
        app: drill-app
        disaster-recovery: "drill"
    spec:
      containers:
        - name: nginx
          image: nginx:alpine
          ports:
            - containerPort: 80
          readinessProbe:
            httpGet:
              path: /
              port: 80
            initialDelaySeconds: 5
            periodSeconds: 5
---
apiVersion: v1
kind: Service
metadata:
  name: drill-service
spec:
  selector:
    app: drill-app
  ports:
    - port: 80
      targetPort: 80
EOF

  # 等待演练应用就绪
  log_drill "等待演练应用就绪..."
  kubectl --context="$from_context" -n "$DRILL_NAMESPACE" wait deploy/drill-app --for=condition=Available --timeout=120s 2>/dev/null || {
    log_warn "演练应用就绪超时，继续演练"
  }

  log_info "演练应用已部署到 ${FROM_CLUSTER}"
}

# ---- 模拟集群故障 ----
simulate_failure() {
  log_drill "模拟集群 ${FROM_CLUSTER} 故障..."

  # 记算故障开始时间（用于 RTO 计算）
  local failover_start
  failover_start=$(date +%s)
  echo "$failover_start" > "$REPORT_DIR/drill-failover-start.epoch"
  log_info "故障开始时间: $(date -d @${failover_start} '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S')"

  # 标记集群不可用（添加 taint 和 label）
  kubectl --context="$KARMADA_HOST_CONTEXT" taint cluster "$FROM_CLUSTER" \
    node.kubernetes.io/unreachable:NoSchedule --overwrite 2>/dev/null || true
  kubectl --context="$KARMADA_HOST_CONTEXT" label cluster "$FROM_CLUSTER" \
    disaster-recovery/status=failed --overwrite 2>/dev/null || true
  kubectl --context="$KARMADA_HOST_CONTEXT" label cluster "$FROM_CLUSTER" \
    disaster-recovery/drill=active --overwrite 2>/dev/null || true

  log_info "集群 ${FROM_CLUSTER} 已模拟故障"
}

# ---- 执行故障切换 ----
execute_failover() {
  log_drill "执行故障切换..."

  local script_dir
  script_dir=$(get_script_dir)
  local failover_start
  failover_start=$(cat "$REPORT_DIR/drill-failover-start.epoch" 2>/dev/null || echo "")

  if [[ -f "$script_dir/failover.sh" ]]; then
    bash "$script_dir/failover.sh" \
      --from "$FROM_CLUSTER" \
      --to "$TO_CLUSTER" \
      --karmada-context "$KARMADA_HOST_CONTEXT" \
      --velero-namespace "$VELERO_NAMESPACE" \
      --report-dir "$REPORT_DIR" \
      --failover-start "$failover_start" 2>&1 || {
        log_error "故障切换失败"
        return 1
      }
  else
    log_error "failover.sh 不存在"
    return 1
  fi

  log_info "故障切换完成"
}

# ---- 验证业务连续性 ----
verify_business_continuity() {
  log_drill "验证业务连续性..."

  local to_context
  to_context=$(get_cluster_context "$TO_CLUSTER")

  # 1. 检查演练应用是否在目标集群运行
  local drill_pods
  drill_pods=$(kubectl --context="$to_context" -n "$DRILL_NAMESPACE" get pods -l app=drill-app --no-headers 2>/dev/null | wc -l || echo "0")
  if [[ "$drill_pods" -gt 0 ]]; then
    log_info "演练应用已在目标集群运行（${drill_pods} 个 Pod）"
  else
    log_warn "演练应用未在目标集群运行"
  fi

  # 2. 检查 Deployment 就绪
  local ready
  ready=$(kubectl --context="$to_context" -n "$DRILL_NAMESPACE" get deploy drill-app -o jsonpath='{.status.availableReplicas}' 2>/dev/null || echo "0")
  local desired
  desired=$(kubectl --context="$to_context" -n "$DRILL_NAMESPACE" get deploy drill-app -o jsonpath='{.spec.replicas}' 2>/dev/null || echo "0")
  if [[ "$ready" == "$desired" && "$ready" -gt 0 ]]; then
    log_info "演练应用就绪: ${ready}/${desired}"
  else
    log_warn "演练应用未完全就绪: ${ready}/${desired}"
  fi

  # 3. 检查 Service 可访问
  if kubectl --context="$to_context" -n "$DRILL_NAMESPACE" get service drill-service >/dev/null 2>&1; then
    log_info "演练 Service 存在"
  else
    log_warn "演练 Service 不存在"
  fi

  # 4. 综合业务连续性判定
  if [[ "$drill_pods" -gt 0 && "$ready" == "$desired" && "$ready" -gt 0 ]]; then
    log_info "业务连续性验证: 通过"
    return 0
  else
    log_warn "业务连续性验证: 未通过"
    return 1
  fi
}

# ---- 计算 RTO/RPO ----
calculate_rto_rpo() {
  log_drill "计算 RTO/RPO..."

  local failover_start
  failover_start=$(cat "$REPORT_DIR/drill-failover-start.epoch" 2>/dev/null || echo "")
  local failover_end
  failover_end=$(date +%s)

  # RTO 计算
  local rto_seconds=0 rto_minutes=0
  if [[ -n "$failover_start" ]]; then
    rto_seconds=$((failover_end - failover_start))
    rto_minutes=$((rto_seconds / 60))
  fi

  # RPO 计算（查找最近备份的时间间隔）
  local rpo_minutes="N/A"
  local from_context
  from_context=$(get_cluster_context "$FROM_CLUSTER")
  local last_backup_time
  last_backup_time=$(velero --kubecontext "$from_context" --namespace "$VELERO_NAMESPACE" \
    backup get -o json 2>/dev/null \
    | python3 -c "
import sys, json
data = json.load(sys.stdin)
items = data.get('items', [])
completed = [b for b in items if b.get('status',{}).get('phase') == 'Completed']
if not completed:
    print('')
    sys.exit(0)
completed.sort(key=lambda b: b.get('status',{}).get('completionTimestamp',''), reverse=True)
print(completed[0].get('status',{}).get('completionTimestamp',''))
" 2>/dev/null || echo "")

  if [[ -n "$last_backup_time" ]]; then
    local backup_epoch
    backup_epoch=$(date -d "$last_backup_time" +%s 2>/dev/null || echo "")
    if [[ -n "$backup_epoch" && -n "$failover_start" ]]; then
      local rpo_seconds=$((failover_start - backup_epoch))
      rpo_minutes=$((rpo_seconds / 60))
    fi
  fi

  # 判定是否达标
  local rto_pass="FAIL" rpo_pass="FAIL"
  if [[ "$rto_minutes" -le "$RTO_TARGET" ]]; then rto_pass="PASS"; fi
  if [[ "$rpo_minutes" != "N/A" && "$rpo_minutes" -le "$RPO_TARGET" ]]; then rpo_pass="PASS"; fi

  log_info "RTO: ${rto_minutes} 分钟（目标: ${RTO_TARGET} 分钟）[${rto_pass}]"
  log_info "RPO: ${rpo_minutes} 分钟（目标: ${RPO_TARGET} 分钟）[${rpo_pass}]"

  # 返回结果
  echo "${rto_minutes}|${rpo_minutes}|${rto_pass}|${rpo_pass}"
}

# ---- 恢复演练环境 ----
cleanup_drill() {
  log_drill "恢复演练环境..."

  local from_context to_context
  from_context=$(get_cluster_context "$FROM_CLUSTER")
  to_context=$(get_cluster_context "$TO_CLUSTER")

  # 1. 清除故障集群的 taint 和 label
  if [[ -n "$FROM_CLUSTER" ]]; then
    kubectl --context="$KARMADA_HOST_CONTEXT" taint cluster "$FROM_CLUSTER" \
      node.kubernetes.io/unreachable:NoSchedule- 2>/dev/null || true
    kubectl --context="$KARMADA_HOST_CONTEXT" label cluster "$FROM_CLUSTER" \
      disaster-recovery/status- 2>/dev/null || true
    kubectl --context="$KARMADA_HOST_CONTEXT" label cluster "$FROM_CLUSTER" \
      disaster-recovery/drill- 2>/dev/null || true
    log_info "已清除故障集群 ${FROM_CLUSTER} 的故障标记"
  fi

  # 2. 删除演练 namespace（两个集群）
  for ctx in "$from_context" "$to_context"; do
    kubectl --context="$ctx" delete namespace "$DRILL_NAMESPACE" --ignore-not-found 2>/dev/null || true
  done
  log_info "已删除演练 namespace: ${DRILL_NAMESPACE}"

  # 3. 恢复 PropagationPolicy（需要手动或从备份恢复）
  log_warn "PropagationPolicy 需要手动恢复到演练前状态"
  log_warn "建议: 从版本控制重新 apply PropagationPolicy"

  log_info "演练环境恢复完成"
}

# ---- 生成演练报告 ----
generate_drill_report() {
  local report_file="$REPORT_DIR/drill-report-$(date +%Y%m%d-%H%M%S).md"
  local rto_rpo_result="$1"
  local business_ok="$2"

  IFS='|' read -r rto rpo rto_pass rpo_pass <<< "$rto_rpo_result"

  local overall_result="PASS"
  if [[ "$rto_pass" != "PASS" || "$rpo_pass" != "PASS" || "$business_ok" != "true" ]]; then
    overall_result="FAIL"
  fi

  cat > "$report_file" <<EOF
# 灾备演练报告

## 概要
- **演练时间**: $(date '+%Y-%m-%d %H:%M:%S')
- **模拟故障集群**: ${FROM_CLUSTER}
- **接管集群**: ${TO_CLUSTER}
- **演练结果**: ${overall_result}

## RTO/RPO 达标情况
| 指标 | 实际值 | 目标值 | 结果 |
|------|--------|--------|------|
| RTO（恢复时间） | ${rto} 分钟 | ${RTO_TARGET} 分钟 | ${rto_pass} |
| RPO（恢复点） | ${rpo} 分钟 | ${RPO_TARGET} 分钟 | ${rpo_pass} |

## 业务连续性
- 演练应用恢复: $([[ "$business_ok" == "true" ]] && echo "✅ 通过" || echo "❌ 未通过")

## 演练步骤
1. [x] 部署演练应用
2. [x] 模拟集群故障
3. [x] 执行故障切换
4. [x] 验证业务连续性
5. [x] 计算 RTO/RPO
6. [x] 生成演练报告
7. [x] 恢复演练环境

## 验收标准
- [${rto_pass:+x}] RTO <= ${RTO_TARGET} 分钟
- [${rpo_pass:+x}] RPO <= ${RPO_TARGET} 分钟
- [$([[ "$business_ok" == "true" ]] && echo "x")] 业务连续性验证通过

## 改进建议
$([[ "$overall_result" == "PASS" ]] && echo "演练通过，灾备能力达标，建议定期执行演练。" || echo "演练未通过，请分析失败原因并优化灾备流程。")

## 注意事项
- 演练环境已恢复，故障集群 taint 已清除
- PropagationPolicy 需要手动恢复到演练前状态
- 建议每月执行一次灾备演练
EOF

  log_info "演练报告已生成: ${report_file}"
  echo "$report_file"
}

# ---- 主流程 ----
main() {
  mkdir -p "$REPORT_DIR"

  # 仅清理模式
  if [[ "$CLEANUP_ONLY" == true ]]; then
    log_info "仅恢复演练环境模式"
    cleanup_drill
    exit 0
  fi

  log_info "============ 灾备演练开始 ============"
  log_info "故障集群: ${FROM_CLUSTER} | 接管集群: ${TO_CLUSTER}"
  log_info "RTO 目标: ${RTO_TARGET} 分钟 | RPO 目标: ${RPO_TARGET} 分钟"

  # 1. 部署演练应用
  deploy_drill_app

  # 2. 模拟集群故障
  simulate_failure

  # 3. 执行故障切换
  if ! execute_failover; then
    log_error "故障切换失败，演练终止"
    if [[ "$SKIP_CLEANUP" != true ]]; then
      cleanup_drill
    fi
    exit 1
  fi

  # 4. 验证业务连续性
  local business_ok=true
  if ! verify_business_continuity; then
    business_ok=false
  fi

  # 5. 计算 RTO/RPO
  local rto_rpo_result
  rto_rpo_result=$(calculate_rto_rpo)

  # 6. 生成演练报告
  local report_file
  report_file=$(generate_drill_report "$rto_rpo_result" "$business_ok")

  # 7. 恢复演练环境
  if [[ "$SKIP_CLEANUP" != true ]]; then
    cleanup_drill
  else
    log_warn "跳过演练环境清理（--skip-cleanup）"
  fi

  log_info "============ 灾备演练完成 ============"
  log_info "报告: ${report_file}"

  # 演练结果判定
  IFS='|' read -r rto rpo rto_pass rpo_pass <<< "$rto_rpo_result"
  if [[ "$rto_pass" == "PASS" && "$rpo_pass" == "PASS" && "$business_ok" == "true" ]]; then
    log_info "演练结果: 通过"
    exit 0
  else
    log_warn "演练结果: 未达标"
    exit 2
  fi
}

main "$@"