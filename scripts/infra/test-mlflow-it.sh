#!/usr/bin/env bash
# =============================================================================
# MLflow 集成测试启动脚本
# =============================================================================
# 用途：启动 MLflow 容器，等待就绪，运行 ml-platform 与 business-portal 的
#       MLflow 集成测试，验证真实指标来源。
# 运行环境：WSL + Docker + Python 3.10+
# 用法：bash scripts/infra/test-mlflow-it.sh
# =============================================================================
set -euo pipefail

# ----------------------------------------------------------------------------
# 颜色输出
# ----------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()  { echo -e "${CYAN}[STEP]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }

# ----------------------------------------------------------------------------
# 定位项目根目录
# ----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/docker-compose.infra.yml"

log_info "项目根目录: ${PROJECT_ROOT}"

# ----------------------------------------------------------------------------
# 前置检查
# ----------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
    log_error "docker 命令未找到，请先安装 Docker"
    exit 1
fi

if ! command -v pytest >/dev/null 2>&1; then
    log_error "pytest 命令未找到，请先安装: pip install pytest pytest-asyncio"
    exit 1
fi

# ----------------------------------------------------------------------------
# 启动 MLflow 容器
# ----------------------------------------------------------------------------
log_step "启动 MLflow 容器 ..."

if [ ! -f "${COMPOSE_FILE}" ]; then
    log_error "Compose 文件不存在: ${COMPOSE_FILE}"
    exit 1
fi

# 仅启动 de-mlflow 服务
docker compose -f "${COMPOSE_FILE}" up -d de-mlflow

log_ok "MLflow 容器已启动"

# ----------------------------------------------------------------------------
# 等待 MLflow 就绪
# ----------------------------------------------------------------------------
log_step "等待 MLflow 就绪 ..."

MLFLOW_URL="http://localhost:5000/health"
MAX_RETRIES=60
i=0
while ! curl -fsS "${MLFLOW_URL}" >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "${i}" -ge "${MAX_RETRIES}" ]; then
        log_error "MLflow 健康检查超时（${MLFLOW_URL}）"
        docker compose -f "${COMPOSE_FILE}" logs de-mlflow --tail=50
        exit 1
    fi
    printf "."
    sleep 2
done
echo ""
log_ok "MLflow 就绪 (${MLFLOW_URL})"

# ----------------------------------------------------------------------------
# 运行集成测试
# ----------------------------------------------------------------------------
echo ""
log_step "运行 ml-platform MLflow 集成测试 ..."
echo ""

export MLFLOW_ENABLED=true
export ML_MLFLOW_URI="http://localhost:5000"
export ML_BACKEND_TYPE="mlflow"
export ML_EXPERIMENT_STORE_TYPE="mlflow"

ML_PLATFORM_DIR="${PROJECT_ROOT}/platform/ml-platform"
BP_DIR="${PROJECT_ROOT}/platform/business-portal"

ML_RESULT=0
BP_RESULT=0

cd "${ML_PLATFORM_DIR}"
pytest tests/test_mlflow_backend.py -v --tb=short || {
    ML_RESULT=$?
    log_warn "ml-platform 集成测试失败 (exit=${ML_RESULT})"
}

echo ""
log_step "运行 business-portal MLflow 集成测试 ..."
echo ""

export BP_MLFLOW_ENABLED=true
export BP_MLFLOW_URI="http://localhost:5000"
export BP_STORE_TYPE="mock"

cd "${BP_DIR}"
pytest tests/test_mlflow_integration.py -v --tb=short || {
    BP_RESULT=$?
    log_warn "business-portal 集成测试失败 (exit=${BP_RESULT})"
}

# ----------------------------------------------------------------------------
# 汇总结果
# ----------------------------------------------------------------------------
echo ""
echo "=============================================="
log_step "MLflow 集成测试结果汇总"
echo "=============================================="
if [ "${ML_RESULT}" -eq 0 ]; then
    log_ok "ml-platform MLflow 集成测试: 通过"
else
    log_error "ml-platform MLflow 集成测试: 失败 (exit=${ML_RESULT})"
fi
if [ "${BP_RESULT}" -eq 0 ]; then
    log_ok "business-portal MLflow 集成测试: 通过"
else
    log_error "business-portal MLflow 集成测试: 失败 (exit=${BP_RESULT})"
fi
echo "=============================================="

# ----------------------------------------------------------------------------
# 清理（可选）
# ----------------------------------------------------------------------------
if [ "${1:-}" = "--keep" ]; then
    log_info "保留 MLflow 容器（--keep）"
else
    log_step "停止 MLflow 容器 ..."
    docker compose -f "${COMPOSE_FILE}" stop de-mlflow >/dev/null 2>&1 || true
    log_ok "MLflow 容器已停止"
fi

# 退出码：任一失败则非零
if [ "${ML_RESULT}" -ne 0 ] || [ "${BP_RESULT}" -ne 0 ]; then
    exit 1
fi
exit 0