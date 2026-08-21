#!/usr/bin/env bash
# =============================================================================
# DataEngineBDP 基础设施停止脚本
# =============================================================================
# 用途：停止并清理所有基础设施容器和卷
#   docker compose -f docker-compose.infra.yml down -v
# 运行环境：WSL + Docker（bash）
# 用法：bash scripts/infra/stop-infra.sh [--keep-volumes]
#   --keep-volumes  保留数据卷（默认会删除）
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
KEEP_VOLUMES=false
for arg in "$@"; do
    case "${arg}" in
        --keep-volumes)
            KEEP_VOLUMES=true
            ;;
        --help|-h)
            echo "用法: bash scripts/infra/stop-infra.sh [--keep-volumes]"
            echo "  --keep-volumes  保留数据卷（默认会删除）"
            exit 0
            ;;
        *)
            log_warn "未知参数: ${arg}"
            ;;
    esac
done

# ----------------------------------------------------------------------------
# 定位项目根目录
# ----------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${PROJECT_ROOT}/docker-compose.infra.yml"

log_info "项目根目录: ${PROJECT_ROOT}"
log_info "Compose 文件: ${COMPOSE_FILE}"

# ----------------------------------------------------------------------------
# 前置检查
# ----------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
    log_error "docker 命令未找到"
    exit 1
fi

if [ ! -f "${COMPOSE_FILE}" ]; then
    log_error "Compose 文件不存在: ${COMPOSE_FILE}"
    exit 1
fi

# ----------------------------------------------------------------------------
# 查看当前运行状态
# ----------------------------------------------------------------------------
log_step "当前运行的 DataEngineBDP 基础设施容器："
docker compose -f "${COMPOSE_FILE}" ps 2>/dev/null || log_warn "无运行中的容器"

echo ""

# ----------------------------------------------------------------------------
# 停止并清理
# ----------------------------------------------------------------------------
if [ "${KEEP_VOLUMES}" = "true" ]; then
    log_step "停止容器（保留数据卷）..."
    docker compose -f "${COMPOSE_FILE}" down
    log_ok "容器已停止，数据卷已保留"
else
    log_step "停止容器并删除数据卷 ..."
    log_warn "此操作将删除所有数据卷，数据将丢失！"
    log_info "3 秒后开始执行，按 Ctrl+C 取消 ..."
    sleep 3

    docker compose -f "${COMPOSE_FILE}" down -v
    log_ok "容器和数据卷已清理"
fi

# ----------------------------------------------------------------------------
# 清理 dangling 镜像（可选）
# ----------------------------------------------------------------------------
echo ""
log_step "清理 dangling 镜像 ..."
docker image prune -f 2>/dev/null | while read -r line; do log_info "${line}"; done || true

# ----------------------------------------------------------------------------
# 清理网络（如果还存在）
# ----------------------------------------------------------------------------
if docker network inspect dataengine-infra >/dev/null 2>&1; then
    log_info "清理网络 dataengine-infra ..."
    docker network rm dataengine-infra 2>/dev/null && log_ok "网络已清理" || log_warn "网络清理失败（可能有容器仍在使用）"
fi

echo ""
log_ok "DataEngineBDP 基础设施已完全停止"
echo ""
log_info "重新启动: bash ${SCRIPT_DIR}/start-infra.sh"