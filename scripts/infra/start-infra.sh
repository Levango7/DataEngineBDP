#!/usr/bin/env bash
# =============================================================================
# DataEngineBDP 基础设施启动脚本
# =============================================================================
# 用途：启动所有基础设施容器并执行初始化
#   1. docker compose -f docker-compose.infra.yml up -d
#   2. 等待服务就绪
#   3. 运行 init-infra.sh
# 运行环境：WSL + Docker（bash）
# 用法：bash scripts/infra/start-infra.sh [--no-init]
#   --no-init  仅启动容器，不执行初始化
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
# 解析参数
# ----------------------------------------------------------------------------
RUN_INIT=true
for arg in "$@"; do
    case "${arg}" in
        --no-init)
            RUN_INIT=false
            ;;
        --help|-h)
            echo "用法: bash scripts/infra/start-infra.sh [--no-init]"
            echo "  --no-init  仅启动容器，不执行初始化"
            exit 0
            ;;
        *)
            log_warn "未知参数: ${arg}"
            ;;
    esac
done

# ----------------------------------------------------------------------------
# 定位项目根目录（兼容 Windows/WSL 路径）
# ----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# scripts/infra -> scripts -> 项目根目录
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/docker-compose.infra.yml"

log_info "项目根目录: ${PROJECT_ROOT}"
log_info "Compose 文件: ${COMPOSE_FILE}"

# ----------------------------------------------------------------------------
# 前置检查
# ----------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
    log_error "docker 命令未找到，请先安装 Docker"
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    log_error "Docker daemon 未运行，请先启动 Docker Desktop 或 WSL Docker"
    exit 1
fi

if ! docker compose version >/dev/null 2>&1; then
    log_error "docker compose 子命令不可用，请安装 Docker Compose V2"
    exit 1
fi

if [ ! -f "${COMPOSE_FILE}" ]; then
    log_error "Compose 文件不存在: ${COMPOSE_FILE}"
    exit 1
fi

# ----------------------------------------------------------------------------
# 启动容器
# ----------------------------------------------------------------------------
log_step "启动 DataEngineBDP 基础设施容器 ..."
echo ""

# 拉取镜像（可选，如果已存在会跳过）
log_info "检查并拉取所需镜像（首次可能较慢）..."
docker compose -f "${COMPOSE_FILE}" pull 2>&1 | while read -r line; do log_info "${line}"; done || log_warn "部分镜像拉取失败，将继续尝试启动"

echo ""
log_info "启动所有服务 ..."
docker compose -f "${COMPOSE_FILE}" up -d

echo ""
log_ok "容器已启动，查看状态："
docker compose -f "${COMPOSE_FILE}" ps

# ----------------------------------------------------------------------------
# 等待服务就绪
# ----------------------------------------------------------------------------
echo ""
log_step "等待服务就绪 ..."

# 等待关键服务健康检查通过
wait_for_health() {
    local service="$1"
    local max_retries="${2:-60}"
    local i=0
    log_info "等待 ${service} 健康检查通过 ..."
    while [ "$(docker inspect --format='{{.State.Health.Status}}' "${service}" 2>/dev/null || echo "none")" != "healthy" ]; do
        i=$((i + 1))
        if [ "${i}" -ge "${max_retries}" ]; then
            local status
            status=$(docker inspect --format='{{.State.Health.Status}}' "${service}" 2>/dev/null || echo "unknown")
            log_warn "${service} 健康检查超时（当前状态: ${status}）"
            return 0  # 不中断，继续等待其他服务
        fi
        printf "."
        sleep 5
    done
    echo ""
    log_ok "${service} 健康检查通过"
}

# 依次等待各服务（最长等待 5 分钟）
SERVICES=(
    "de-postgres"
    "de-trino"
    "de-doris-fe"
    "de-doris-be"
    "de-nebula-metad"
    "de-nebula-graphd"
    "de-nebula-storaged"
    "de-mlflow"
    "de-flink-jobmanager"
    "de-spark-master"
)

for svc in "${SERVICES[@]}"; do
    wait_for_health "${svc}" 60
done

# ----------------------------------------------------------------------------
# 执行初始化
# ----------------------------------------------------------------------------
if [ "${RUN_INIT}" = "true" ]; then
    echo ""
    log_step "执行初始化脚本 ..."
    INIT_SCRIPT="${SCRIPT_DIR}/init-infra.sh"
    if [ -f "${INIT_SCRIPT}" ]; then
        bash "${INIT_SCRIPT}"
    else
        log_warn "初始化脚本不存在: ${INIT_SCRIPT}"
    fi
else
    log_info "跳过初始化（--no-init）"
fi

echo ""
log_ok "DataEngineBDP 基础设施启动完成！"
echo ""
log_info "查看日志: docker compose -f ${COMPOSE_FILE} logs -f"
log_info "停止服务: bash ${SCRIPT_DIR}/stop-infra.sh"