#!/bin/bash
# ============================================================================
# 跨集群备份脚本 - backup-all-clusters.sh
# 功能：
#   1. 遍历所有 Karmada 成员集群
#   2. 对每个集群执行 Velero backup
#   3. 计算备份耗时和大小
#   4. 生成备份报告
#   5. 验证备份完整性
# 用法：
#   ./backup-all-clusters.sh [--type full|incremental] [--namespaces ns1,ns2]
#   ./backup-all-clusters.sh --help
# 依赖：velero, kubectl, kubectl-karmada 插件
# ============================================================================
set -euo pipefail

# ---- 默认参数 ----
BACKUP_TYPE="full"            # full | incremental
NAMESPACES=""                 # 空表示所有 namespace
KARMADA_HOST_CONTEXT="karmada-host"  # Karmada 控制面 context
VELERO_NAMESPACE="velero"     # Velero 所在 namespace
BACKUP_TIMEOUT="1800"         # 单集群备份超时（秒，默认 30 分钟）
REPORT_DIR="/tmp/dr-reports"  # 报告输出目录
VERIFY_BACKUP=true            # 是否验证备份

# ---- 颜色输出 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info()  { echo -e "${GREEN}[INFO]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $(date '+%Y-%m-%d %H:%M:%S') $*" >&2; }
log_step()  { echo -e "${BLUE}[STEP]${NC}  $(date '+%Y-%m-%d %H:%M:%S') $*"; }

# ---- 帮助信息 ----
show_help() {
  cat <<EOF
跨集群备份脚本 - 基于 Velero + Karmada

用法:
  $0 [选项]

选项:
  --type <full|incremental>   备份类型（默认: full）
  --namespaces <ns1,ns2>      指定 namespace（逗号分隔，默认: 所有）
  --karmada-context <ctx>     Karmada 控制面 context（默认: karmada-host）
  --velero-namespace <ns>     Velero namespace（默认: velero）
  --timeout <seconds>         单集群备份超时（默认: 1800）
  --report-dir <dir>          报告输出目录（默认: /tmp/dr-reports）
  --no-verify                 跳过备份验证
  --help                      显示帮助信息

示例:
  # 全量备份所有集群
  $0 --type full

  # 增量备份指定 namespace
  $0 --type incremental --namespaces production,staging

  # 自定义 Karmada context
  $0 --karmada-context karmada-apiserver

环境变量:
  KARMADA_HOST_CONTEXT  Karmada 控制面 context
  VELERO_NAMESPACE      Velero namespace
EOF
  exit 0
}

# ---- 参数解析 ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --type)
      BACKUP_TYPE="$2"; shift 2 ;;
    --namespaces)
      NAMESPACES="$2"; shift 2 ;;
    --karmada-context)
      KARMADA_HOST_CONTEXT="$2"; shift 2 ;;
    --velero-namespace)
      VELERO_NAMESPACE="$2"; shift 2 ;;
    --timeout)
      BACKUP_TIMEOUT="$2"; shift 2 ;;
    --report-dir)
      REPORT_DIR="$2"; shift 2 ;;
    --no-verify)
      VERIFY_BACKUP=false; shift ;;
    --help|-h)
      show_help ;;
    *)
      log_error "未知参数: $1"; show_help ;;
  esac
done

# ---- 前置检查 ----
check_prerequisites() {
  log_step "检查前置条件..."
  local missing=()
  command -v velero   >/dev/null 2>&1 || missing+=("velero")
  command -v kubectl  >/dev/null 2>&1 || missing+=("kubectl")
  if [[ ${#missing[@]} -gt 0 ]]; then
    log_error "缺少依赖命令: ${missing[*]}"
    exit 1
  fi
  # 检查 Karmada context 是否可用
  if ! kubectl config get-contexts "$KARMADA_HOST_CONTEXT" >/dev/null 2>&1; then
    log_error "Karmada context 不存在: $KARMADA_HOST_CONTEXT"
    exit 1
  fi
  log_info "前置条件检查通过"
}

# ---- 获取所有 Karmada 成员集群 ----
get_member_clusters() {
  log_step "获取 Karmada 成员集群列表..."
  # 通过 karmada 插件获取成员集群
  local clusters
  clusters=$(kubectl --context="$KARMADA_HOST_CONTEXT" get clusters -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}' 2>/dev/null || true)
  if [[ -z "$clusters" ]]; then
    log_error "未找到 Karmada 成员集群，请检查 Karmada 控制面连接"
    exit 1
  fi
  echo "$clusters"
}

# ---- 获取集群的 kubeconfig context ----
get_cluster_context() {
  local cluster_name="$1"
  # Karmada 成员集群的 context 命名规则：karmada-apiserver 中通过 cluster name 访问
  # 使用 kubectl --context=karmada-host 的 cluster proxy 或直接使用成员集群 context
  echo "karmada-${cluster_name}"
}

# ---- 执行单集群备份 ----
backup_single_cluster() {
  local cluster_name="$1"
  local cluster_context
  cluster_context=$(get_cluster_context "$cluster_name")

  local timestamp
  timestamp=$(date +%Y%m%d-%H%M%S)
  local backup_name="${BACKUP_TYPE}-${cluster_name}-${timestamp}"

  log_step "开始备份集群: ${cluster_name} (backup: ${backup_name})"

  # 记时开始
  local start_time
  start_time=$(date +%s)

  # 构建 velero backup 命令
  local velero_args=(
    --kubecontext "$cluster_context"
    --namespace "$VELERO_NAMESPACE"
    create backup "$backup_name"
    --wait
    --timeout "${BACKUP_TIMEOUT}s"
  )

  # 按类型添加参数
  if [[ "$BACKUP_TYPE" == "full" ]]; then
    velero_args+=(--include-cluster-resources=true)
  else
    velero_args+=(--include-cluster-resources=false)
  fi

  # namespace 范围
  if [[ -n "$NAMESPACES" ]]; then
    velero_args+=(--include-namespaces "$NAMESPACES")
  else
    velero_args+=(--include-namespaces '*')
  fi

  # 排除系统 namespace
  velero_args+=(--exclude-namespaces 'kube-system,kube-public,velero')

  # 执行备份
  if velero "${velero_args[@]}" 2>&1; then
    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - start_time))

    # 获取备份大小
    local backup_size
    backup_size=$(velero --kubecontext "$cluster_context" --namespace "$VELERO_NAMESPACE" \
      backup describe "$backup_name" -o json 2>/dev/null \
      | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('status',{}).get('progress',{}).get('totalBytes',0))" 2>/dev/null || echo "0")

    log_info "集群 ${cluster_name} 备份成功 | 耗时: ${duration}s | 大小: ${backup_size} bytes"

    # 记录到报告
    echo "${cluster_name}|${backup_name}|SUCCESS|${duration}|${backup_size}|$(date -d @${start_time} '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date '+%Y-%m-%d %H:%M:%S')" \
      >> "$REPORT_DIR/backup-results.csv"

    # 验证备份
    if [[ "$VERIFY_BACKUP" == true ]]; then
      local script_dir
      script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
      if [[ -f "$script_dir/verify-backup.sh" ]]; then
        log_step "验证备份: ${backup_name}"
        bash "$script_dir/verify-backup.sh" --backup "$backup_name" --cluster "$cluster_name" \
          --karmada-context "$KARMADA_HOST_CONTEXT" || log_warn "备份验证失败: ${backup_name}"
      fi
    fi

    echo "$backup_name"
  else
    local end_time
    end_time=$(date +%s)
    local duration=$((end_time - start_time))
    log_error "集群 ${cluster_name} 备份失败 | 耗时: ${duration}s"
    echo "${cluster_name}|${backup_name}|FAILED|${duration}|0|$(date '+%Y-%m-%d %H:%M:%S')" \
      >> "$REPORT_DIR/backup-results.csv"
    return 1
  fi
}

# ---- 生成备份报告 ----
generate_report() {
  local report_file="$REPORT_DIR/backup-report-$(date +%Y%m%d-%H%M%S).md"
  local total_clusters=$1
  local success_count=$2
  local fail_count=$3
  local total_duration=$4

  cat > "$report_file" <<EOF
# 跨集群备份报告

## 概要
- **备份类型**: ${BACKUP_TYPE}
- **执行时间**: $(date '+%Y-%m-%d %H:%M:%S')
- **总集群数**: ${total_clusters}
- **成功数**: ${success_count}
- **失败数**: ${fail_count}
- **总耗时**: ${total_duration} 秒
- **成功率**: $(echo "scale=1; ${success_count}*100/${total_clusters}" | bc 2>/dev/null || echo "N/A")%

## 详细结果
| 集群 | 备份名称 | 状态 | 耗时(秒) | 大小(bytes) | 开始时间 |
|------|----------|------|----------|-------------|----------|
EOF

  if [[ -f "$REPORT_DIR/backup-results.csv" ]]; then
    while IFS='|' read -r cluster backup status duration size start; do
      echo "| ${cluster} | ${backup} | ${status} | ${duration} | ${size} | ${start} |" >> "$report_file"
    done < "$REPORT_DIR/backup-results.csv"
  fi

  cat >> "$report_file" <<EOF

## 建议
- 如有失败集群，请检查 Velero 日志与集群连接状态
- 定期清理过期备份：velero backup get --namespace ${VELERO_NAMESPACE}
- 验证备份可恢复性：执行 restore-cluster.sh 到临时集群
EOF

  log_info "备份报告已生成: ${report_file}"
  echo "$report_file"
}

# ---- 主流程 ----
main() {
  log_info "=== 跨集群备份开始 ==="
  log_info "备份类型: ${BACKUP_TYPE} | Karmada: ${KARMADA_HOST_CONTEXT}"

  check_prerequisites

  # 创建报告目录
  mkdir -p "$REPORT_DIR"
  : > "$REPORT_DIR/backup-results.csv"  # 清空结果文件

  # 获取成员集群
  local clusters
  clusters=$(get_member_clusters)
  local total_clusters
  total_clusters=$(echo "$clusters" | wc -l)
  log_info "发现 ${total_clusters} 个成员集群: $(echo "$clusters" | tr '\n' ' ')"

  # 全局计时
  local global_start
  global_start=$(date +%s)

  # 逐集群备份
  local success_count=0
  local fail_count=0
  local failed_clusters=()

  while IFS= read -r cluster; do
    [[ -z "$cluster" ]] && continue
    if backup_single_cluster "$cluster"; then
      ((success_count++))
    else
      ((fail_count++))
      failed_clusters+=("$cluster")
    fi
  done <<< "$clusters"

  local global_end
  global_end=$(date +%s)
  local total_duration=$((global_end - global_start))

  # 生成报告
  local report_file
  report_file=$(generate_report "$total_clusters" "$success_count" "$fail_count" "$total_duration")

  # 汇总
  log_info "=== 跨集群备份完成 ==="
  log_info "成功: ${success_count}/${total_clusters} | 失败: ${fail_count} | 总耗时: ${total_duration}s"
  if [[ ${fail_count} -gt 0 ]]; then
    log_warn "失败集群: ${failed_clusters[*]}"
  fi
  log_info "报告: ${report_file}"

  # 失败则退出码非 0
  [[ ${fail_count} -eq 0 ]] || exit 1
}

main "$@"