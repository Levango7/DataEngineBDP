#!/usr/bin/env bash
# ============================================================================
# 数擎大数据平台 - Flink CDC MySQL 管道停止脚本
# ----------------------------------------------------------------------------
# 用法:
#   ./stop-cdc-mysql.sh <job-id|job-name>
#
# 示例:
#   ./stop-cdc-mysql.sh mysql-to-kafka
#   ./stop-cdc-mysql.sh 2024-01-01_12-00-00_abc123
#
# 环境变量:
#   FLINK_HOME     Flink 安装目录 (默认 /opt/flink)
#   FLINK_JM_HOST  Flink JobManager 地址 (可选, 如 flink-jm:8081)
# ============================================================================

set -euo pipefail

# ---- 参数解析 ----
JOB_TARGET="${1:-}"

if [[ -z "${JOB_TARGET}" ]]; then
  echo "用法: $0 <job-id|job-name>"
  echo "示例: $0 mysql-to-kafka"
  exit 1
fi

# ---- 环境变量默认值 ----
FLINK_HOME="${FLINK_HOME:-/opt/flink}"
FLINK_BIN="${FLINK_HOME}/bin/flink"

echo "========================================"
echo " 数擎大数据平台 - Flink CDC 停止"
echo "========================================"
echo " 目标作业: ${JOB_TARGET}"
echo " Flink:    ${FLINK_HOME}"
echo "========================================"

# ---- 构造 flink 命令参数 ----
ARGS=()
if [[ -n "${FLINK_JM_HOST:-}" ]]; then
  ARGS+=(--jobmanager "${FLINK_JM_HOST}")
fi

# ---- 尝试按 job-id 取消 ----
echo "尝试按 Job ID 取消: ${JOB_TARGET}"
if "${FLINK_BIN}" cancel "${ARGS[@]}" "${JOB_TARGET}" 2>/dev/null; then
  echo "作业 ${JOB_TARGET} 已成功取消 (按 Job ID)"
  exit 0
fi

# ---- 按 job-name 查找并取消 ----
echo "按 Job ID 取消失败，尝试按 Job Name 查找..."

# 列出运行中的作业，查找匹配的 job-id
JOB_LIST_OUTPUT="$("${FLINK_BIN}" list "${ARGS[@]}" 2>/dev/null || true)"

# 解析作业列表，格式: "<job-id> : <job-name> (RUNNING)"
JOB_ID="$(echo "${JOB_LIST_OUTPUT}" | grep ": ${JOB_TARGET} " | awk '{print $1}' | head -1 || true)"

if [[ -z "${JOB_ID}" ]]; then
  echo "错误: 未找到名称或 ID 为 '${JOB_TARGET}' 的运行中作业"
  echo ""
  echo "当前作业列表:"
  "${FLINK_BIN}" list "${ARGS[@]}" || true
  exit 1
fi

echo "找到作业: ${JOB_TARGET} -> Job ID: ${JOB_ID}"
"${FLINK_BIN}" cancel "${ARGS[@]}" "${JOB_ID}"

echo ""
echo "作业 ${JOB_TARGET} (Job ID: ${JOB_ID}) 已取消"