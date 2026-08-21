#!/bin/bash
# ============================================================================
# 备份验证脚本 - verify-backup.sh
# 功能：
#   1. 检查备份是否存在
#   2. 验证备份描述信息
#   3. 检查备份包含的资源数量
#   4. 验证备份可恢复性
# 用法：
#   ./verify-backup.sh --backup <name> [--cluster <name>]
#   ./verify-backup.sh --help
# 依赖：velero, kubectl
# ============================================================================
set -euo pipefail

# ---- 默认参数 ----
BACKUP_NAME=""               # 备份名称
CLUSTER_NAME=""              # 集群名（空则使用当前 context）
KARMADA_HOST_CONTEXT="karmada-host"
VELERO_NAMESPACE="velero"
DEEP_VERIFY=false            # 深度验证（创建测试 restore）

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
备份验证脚本 - 验证 Velero 备份完整性与可恢复性

用法:
  $0 --backup <name> [选项]

必选参数:
  --backup <name>   Velero 备份名称

可选参数:
  --cluster <name>          集群名（默认: 当前 context）
  --karmada-context <ctx>   Karmada 控制面 context（默认: karmada-host）
  --velero-namespace <ns>   Velero namespace（默认: velero）
  --deep                    深度验证（创建测试 restore 验证可恢复性）
  --help                    显示帮助信息

验证项:
  1. 备份是否存在
  2. 备份状态是否 Completed
  3. 备份包含的资源数量
  4. 备份包含的 namespace
  5. 备份大小与耗时
  6. 卷快照状态（如存在）
  7. 可恢复性验证（--deep 时）

示例:
  # 基本验证
  $0 --backup full-cluster-beijing-20260101-020000

  # 指定集群
  $0 --backup backup-xxx --cluster cluster-beijing

  # 深度验证
  $0 --backup backup-xxx --deep
EOF
  exit 0
}

# ---- 参数解析 ----
while [[ $# -gt 0 ]]; do
  case "$1" in
    --backup)            BACKUP_NAME="$2"; shift 2 ;;
    --cluster)           CLUSTER_NAME="$2"; shift 2 ;;
    --karmada-context)   KARMADA_HOST_CONTEXT="$2"; shift 2 ;;
    --velero-namespace)  VELERO_NAMESPACE="$2"; shift 2 ;;
    --deep)              DEEP_VERIFY=true; shift ;;
    --help|-h)           show_help ;;
    *)                   log_error "未知参数: $1"; show_help ;;
  esac
done

if [[ -z "$BACKUP_NAME" ]]; then
  log_error "必须指定 --backup"
  show_help
fi

# ---- 构建 velero 命令前缀 ----
velero_cmd() {
  local args=(--namespace "$VELERO_NAMESPACE")
  if [[ -n "$CLUSTER_NAME" ]]; then
    args+=(--kubecontext "karmada-${CLUSTER_NAME}")
  fi
  velero "${args[@]}" "$@"
}

# ---- 验证结果计数 ----
PASS_COUNT=0
FAIL_COUNT=0
WARN_COUNT=0

check_pass() { log_info "✅ $*"; ((PASS_COUNT++)); }
check_fail() { log_error "❌ $*"; ((FAIL_COUNT++)); }
check_warn() { log_warn "⚠️  $*"; ((WARN_COUNT++)); }

# ---- 1. 检查备份是否存在 ----
verify_existence() {
  log_step "1. 检查备份是否存在..."

  if ! velero_cmd backup get "$BACKUP_NAME" >/dev/null 2>&1; then
    check_fail "备份不存在: ${BACKUP_NAME}"
    return 1
  fi
  check_pass "备份存在: ${BACKUP_NAME}"
  return 0
}

# ---- 2. 检查备份状态 ----
verify_status() {
  log_step "2. 检查备份状态..."

  local phase
  phase=$(velero_cmd backup get "$BACKUP_NAME" -o jsonpath='{.status.phase}' 2>/dev/null || echo "Unknown")

  case "$phase" in
    Completed)
      check_pass "备份状态: Completed"
      ;;
    PartiallyFailed)
      check_warn "备份状态: PartiallyFailed（部分资源备份失败）"
      ;;
    Failed)
      check_fail "备份状态: Failed"
      return 1
      ;;
    InProgress|New|"")
      check_warn "备份状态: ${phase:-Unknown}（尚未完成）"
      return 1
      ;;
    *)
      check_warn "备份状态: ${phase}（未知状态）"
      ;;
  esac
  return 0
}

# ---- 3. 检查资源数量 ----
verify_resource_count() {
  log_step "3. 检查备份包含的资源数量..."

  local total_items
  total_items=$(velero_cmd backup get "$BACKUP_NAME" -o jsonpath='{.status.progress.totalItems}' 2>/dev/null || echo "0")
  local items_backed_up
  items_backed_up=$(velero_cmd backup get "$BACKUP_NAME" -o jsonpath='{.status.progress.itemsBackedUp}' 2>/dev/null || echo "0")

  if [[ "$total_items" -gt 0 ]]; then
    check_pass "资源总数: ${total_items}，已备份: ${items_backed_up}"
  else
    check_warn "资源总数为 0（可能为空备份或元数据不完整）"
  fi

  # 检查是否有资源未备份
  if [[ "$items_backed_up" -lt "$total_items" ]]; then
    local missing=$((total_items - items_backed_up))
    check_warn "${missing} 个资源未备份"
  fi
}

# ---- 4. 检查 namespace ----
verify_namespaces() {
  log_step "4. 检查备份包含的 namespace..."

  local namespaces
  namespaces=$(velero_cmd backup describe "$BACKUP_NAME" --details 2>/dev/null \
    | grep -A 100 "Namespaces:" | grep -E "^\s+\S+" | head -20 || echo "")

  if [[ -n "$namespaces" ]]; then
    local ns_count
    ns_count=$(echo "$namespaces" | wc -l)
    check_pass "包含 ${ns_count} 个 namespace"
    log_info "namespace 列表:"
    echo "$namespaces" | sed 's/^/    /'
  else
    check_warn "无法获取 namespace 列表"
  fi
}

# ---- 5. 检查大小与耗时 ----
verify_size_duration() {
  log_step "5. 检查备份大小与耗时..."

  local start_time end_time
  start_time=$(velero_cmd backup get "$BACKUP_NAME" -o jsonpath='{.status.startTimestamp}' 2>/dev/null || echo "")
  end_time=$(velero_cmd backup get "$BACKUP_NAME" -o jsonpath='{.status.completionTimestamp}' 2>/dev/null || echo "")

  if [[ -n "$start_time" && -n "$end_time" ]]; then
    local start_epoch end_epoch duration
    start_epoch=$(date -d "$start_time" +%s 2>/dev/null || echo "0")
    end_epoch=$(date -d "$end_time" +%s 2>/dev/null || echo "0")
    if [[ "$start_epoch" -gt 0 && "$end_epoch" -gt 0 ]]; then
      duration=$((end_epoch - start_epoch))
      check_pass "备份耗时: ${duration} 秒"
    fi
  else
    check_warn "无法获取备份时间信息"
  fi

  # 检查备份大小（从 progress.totalBytes）
  local total_bytes
  total_bytes=$(velero_cmd backup get "$BACKUP_NAME" -o jsonpath='{.status.progress.totalBytes}' 2>/dev/null || echo "0")
  if [[ "$total_bytes" -gt 0 ]]; then
    # 转换为人类可读格式
    local size_hr
    if [[ "$total_bytes" -ge 1073741824 ]]; then
      size_hr=$(echo "scale=2; ${total_bytes}/1073741824" | bc 2>/dev/null || echo "?")" GB"
    elif [[ "$total_bytes" -ge 1048576 ]]; then
      size_hr=$(echo "scale=2; ${total_bytes}/1048576" | bc 2>/dev/null || echo "?")" MB"
    elif [[ "$total_bytes" -ge 1024 ]]; then
      size_hr=$(echo "scale=2; ${total_bytes}/1024" | bc 2>/dev/null || echo "?")" KB"
    else
      size_hr="${total_bytes} B"
    fi
    check_pass "备份大小: ${size_hr}"
  else
    check_warn "无法获取备份大小"
  fi
}

# ---- 6. 检查卷快照 ----
verify_volume_snapshots() {
  log_step "6. 检查卷快照状态..."

  local snapshot_count
  snapshot_count=$(velero_cmd backup get "$BACKUP_NAME" -o jsonpath='{.status.volumeSnapshotsAttempted}' 2>/dev/null || echo "0")
  local snapshot_completed
  snapshot_completed=$(velero_cmd backup get "$BACKUP_NAME" -o jsonpath='{.status.volumeSnapshotsCompleted}' 2>/dev/null || echo "0")

  if [[ "$snapshot_count" -gt 0 ]]; then
    if [[ "$snapshot_completed" == "$snapshot_count" ]]; then
      check_pass "卷快照: ${snapshot_completed}/${snapshot_count} 完成"
    else
      check_warn "卷快照: ${snapshot_completed}/${snapshot_count} 完成（部分失败）"
    fi
  else
    log_info "无卷快照（可能未配置 PV 备份）"
  fi
}

# ---- 7. 深度验证：可恢复性 ----
verify_restorability() {
  log_step "7. 深度验证：测试恢复可恢复性..."

  if [[ "$DEEP_VERIFY" != true ]]; then
    log_info "跳过深度验证（使用 --deep 启用）"
    return 0
  fi

  local test_restore_name="verify-restore-${BACKUP_NAME}-$(date +%s)"

  log_info "创建测试 restore: ${test_restore_name}"
  # 创建 dry-run restore 验证可恢复性
  if velero_cmd restore create "$test_restore_name" \
    --from-backup "$BACKUP_NAME" \
    --dry-run 2>&1; then
    check_pass "备份可恢复（dry-run 验证通过）"
  else
    check_fail "备份可恢复性验证失败"
    return 1
  fi

  # 检查备份描述中的错误
  local errors
  errors=$(velero_cmd backup describe "$BACKUP_NAME" --details 2>/dev/null | grep -i "error" | head -5 || echo "")
  if [[ -n "$errors" ]]; then
    check_warn "备份描述中存在错误信息:"
    echo "$errors" | sed 's/^/    /'
  fi
}

# ---- 主流程 ----
main() {
  log_info "=== 备份验证开始 ==="
  log_info "备份: ${BACKUP_NAME} | 集群: ${CLUSTER_NAME:-当前context}"

  # 执行验证步骤
  verify_existence     || { log_error "备份不存在，终止验证"; exit 1; }
  verify_status        || { log_error "备份状态异常，终止验证"; exit 1; }
  verify_resource_count
  verify_namespaces
  verify_size_duration
  verify_volume_snapshots
  verify_restorability

  # 汇总
  log_info "=== 备份验证完成 ==="
  log_info "通过: ${PASS_COUNT} | 警告: ${WARN_COUNT} | 失败: ${FAIL_COUNT}"

  if [[ "$FAIL_COUNT" -eq 0 ]]; then
    log_info "备份验证结果: 通过"
    exit 0
  else
    log_error "备份验证结果: 失败"
    exit 1
  fi
}

main "$@"