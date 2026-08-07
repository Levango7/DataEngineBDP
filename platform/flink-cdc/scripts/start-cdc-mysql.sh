#!/usr/bin/env bash
# ============================================================================
# 数擎大数据平台 - Flink CDC MySQL 管道启动脚本
# ----------------------------------------------------------------------------
# 用法:
#   ./start-cdc-mysql.sh <config.yaml> [flink-jobmanager-host]
#
# 示例:
#   ./start-cdc-mysql.sh cdc-mysql-template.yaml
#   ./start-cdc-mysql.sh cdc-mysql-template.yaml flink-jm:8081
#
# 环境变量:
#   FLINK_HOME     Flink 安装目录 (默认 /opt/flink)
#   CDC_JAR        CDC fat jar 路径 (默认自动查找 target/*.jar)
# ============================================================================

set -euo pipefail

# ---- 参数解析 ----
CONFIG_FILE="${1:-}"
FLINK_JM_HOST="${2:-}"

if [[ -z "${CONFIG_FILE}" ]]; then
  echo "用法: $0 <config.yaml> [flink-jobmanager-host]"
  echo "示例: $0 cdc-mysql-template.yaml"
  exit 1
fi

if [[ ! -f "${CONFIG_FILE}" ]]; then
  echo "错误: 配置文件不存在: ${CONFIG_FILE}"
  exit 1
fi

# ---- 环境变量默认值 ----
FLINK_HOME="${FLINK_HOME:-/opt/flink}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# 自动查找 fat jar
if [[ -z "${CDC_JAR:-}" ]]; then
  CDC_JAR="$(find "${PROJECT_DIR}/target" -name 'flink-cdc-*.jar' ! -name '*-tests.jar' ! -name '*-sources.jar' 2>/dev/null | head -1 || true)"
  if [[ -z "${CDC_JAR}" ]]; then
    echo "错误: 未找到 CDC fat jar，请先执行 mvn package"
    echo "  可通过 CDC_JAR 环境变量手动指定 jar 路径"
    exit 1
  fi
fi

echo "========================================"
echo " 数擎大数据平台 - Flink CDC 启动"
echo "========================================"
echo " 配置文件: ${CONFIG_FILE}"
echo " CDC JAR:  ${CDC_JAR}"
echo " Flink:    ${FLINK_HOME}"
if [[ -n "${FLINK_JM_HOST}" ]]; then
  echo " JM Host:  ${FLINK_JM_HOST}"
fi
echo "========================================"

# ---- 提交 Flink 作业 ----
FLINK_BIN="${FLINK_HOME}/bin/flink"

if [[ -n "${FLINK_JM_HOST}" ]]; then
  # 远程集群模式
  echo "提交作业到远程 Flink 集群: ${FLINK_JM_HOST}"
  "${FLINK_BIN}" run \
    --jobmanager "${FLINK_JM_HOST}" \
    --class com.shuqing.bigdata.flinkcdc.CdcFrameworkMain \
    "${CDC_JAR}" \
    --config "${CONFIG_FILE}"
else
  # 本地或默认集群模式
  echo "提交作业到默认 Flink 集群"
  "${FLINK_BIN}" run \
    --class com.shuqing.bigdata.flinkcdc.CdcFrameworkMain \
    "${CDC_JAR}" \
    --config "${CONFIG_FILE}"
fi

echo ""
echo "作业已提交。查看作业状态:"
echo "  ${FLINK_HOME}/bin/flink list"
echo ""
echo "查看作业日志:"
echo "  tail -f ${FLINK_HOME}/log/flink-*-taskexecutor-*.log"