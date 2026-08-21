#!/usr/bin/env bash
# =============================================================================
# lineage-analyzer NebulaGraph 集成测试一键脚本
# =============================================================================
# 用途：启动 NebulaGraph 容器 → 等待 graphd 就绪 → 初始化 space/schema
#       → 运行 NebulaGraphClientIT + LineageGraphWriterIT → 输出结果
# 运行环境：WSL + Docker（bash）+ Maven
# 用法：bash scripts/infra/test-nebula-it.sh
#   --skip-start   跳过容器启动（已启动时复用）
#   --only-client  仅运行 NebulaGraphClientIT（跳过 Spring 上下文测试）
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
SKIP_START=false
ONLY_CLIENT=false
for arg in "$@"; do
    case "${arg}" in
        --skip-start)
            SKIP_START=true
            ;;
        --only-client)
            ONLY_CLIENT=true
            ;;
        --help|-h)
            echo "用法: bash scripts/infra/test-nebula-it.sh [选项]"
            echo "  --skip-start   跳过容器启动（已启动时复用）"
            echo "  --only-client  仅运行 NebulaGraphClientIT"
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
MODULE_DIR="${PROJECT_ROOT}/platform/governance/lineage-analyzer"

log_info "项目根目录: ${PROJECT_ROOT}"
log_info "测试模块: ${MODULE_DIR}"

# ----------------------------------------------------------------------------
# 前置检查
# ----------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
    log_error "docker 命令未找到，请先安装 Docker"
    exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
    log_error "mvn 命令未找到，请先安装 Maven"
    exit 1
fi

# ----------------------------------------------------------------------------
# 1. 启动 NebulaGraph 容器（仅 nebula 相关服务）
# ----------------------------------------------------------------------------
if [ "${SKIP_START}" = "true" ]; then
    log_info "跳过容器启动（--skip-start）"
else
    log_step "启动 NebulaGraph 容器（metad + graphd + storaged）..."

    if ! docker info >/dev/null 2>&1; then
        log_error "Docker daemon 未运行，请先启动 Docker"
        exit 1
    fi

    # 仅启动 nebula 三个服务（依赖关系会自动拉起）
    docker compose -f "${COMPOSE_FILE}" up -d \
        de-nebula-metad de-nebula-graphd de-nebula-storaged

    log_ok "NebulaGraph 容器已启动"
fi

# ----------------------------------------------------------------------------
# 2. 等待 graphd 就绪（端口 9669 + HTTP 19669）
# ----------------------------------------------------------------------------
log_step "等待 NebulaGraph graphd 就绪（端口 9669）..."

wait_for_http() {
    local name="$1"
    local url="$2"
    local max_retries="${3:-60}"
    local i=0
    while ! curl -fsS "${url}" >/dev/null 2>&1; do
        i=$((i + 1))
        if [ "${i}" -ge "${max_retries}" ]; then
            log_error "${name} 等待超时（${url}）"
            return 1
        fi
        printf "."
        sleep 3
    done
    echo ""
    log_ok "${name} 就绪"
}

wait_for_http "Nebula Meta"   "http://localhost:19559/status" 60
wait_for_http "Nebula Graph"  "http://localhost:19669/status" 60
wait_for_http "Nebula Storage" "http://localhost:19779/status" 60

# 等待 storaged 注册到 metad
log_info "等待 storaged 注册到 metad（约 20 秒）..."
sleep 20

# ----------------------------------------------------------------------------
# 3. 初始化 space 和 schema（ADD HOSTS + CREATE SPACE + TAG/EDGE）
# ----------------------------------------------------------------------------
log_step "初始化 NebulaGraph space 和 schema..."

# 优先使用容器内 nebula-console
CONSOLE_CMD=""
if command -v nebula-console >/dev/null 2>&1; then
    CONSOLE_CMD="nebula-console -addr 127.0.0.1 -port 9669 -user root -password nebula"
elif docker exec de-nebula-graphd which nebula-console >/dev/null 2>&1; then
    CONSOLE_CMD="docker exec de-nebula-graphd nebula-console -addr 127.0.0.1 -port 9669 -user root -password nebula"
fi

if [ -n "${CONSOLE_CMD}" ]; then
    log_info "使用 nebula-console 初始化..."

    ${CONSOLE_CMD} <<'NGQL'
-- 添加 storage host（Docker 网络内 storaged 地址）
ADD HOSTS 172.20.0.1:9779;

-- 创建集成测试专用 space（与生产 lineage 隔离）
CREATE SPACE IF NOT EXISTS it_lineage(
    partition_num = 10,
    replica_factor = 1,
    vid_type = FIXED_STRING(512)
);

-- 等待 space 生效（2 个心跳周期）
:sleep 5;

USE it_lineage;

-- 创建血缘节点 Tag（与 NebulaGraphClient.ensureSchema 一致）
CREATE TAG IF NOT EXISTS lineage_node(
    full_name string(512),
    node_type string(16),
    schema_name string(128),
    table_name string(128),
    column_name string(128),
    display_name string(256)
);

-- 创建血缘边 Edge
CREATE EDGE IF NOT EXISTS lineage_edge(
    relation_type string(32),
    source_sql string(4096),
    dialect string(16),
    expression string(1024)
);

-- 等待 schema 生效
:sleep 5;

SHOW TAGS;
SHOW EDGES;
NGQL
    log_ok "NebulaGraph space 和 schema 初始化完成"
else
    log_warn "nebula-console 未找到，将由 NebulaGraphClient 自动创建 space/schema"
    log_info "NebulaGraphClient.ensureSpace/ensureSchema 会幂等处理"
fi

# ----------------------------------------------------------------------------
# 4. 运行集成测试
# ----------------------------------------------------------------------------
echo ""
log_step "运行 lineage-analyzer NebulaGraph 集成测试..."

cd "${MODULE_DIR}"

if [ "${ONLY_CLIENT}" = "true" ]; then
    log_info "仅运行 NebulaGraphClientIT（--only-client）"
    mvn test \
        -Dtest=NebulaGraphClientIT \
        -Dnebula.it=true \
        -DfailIfNoTests=false
else
    log_info "运行 NebulaGraphClientIT + LineageGraphWriterIT"
    mvn test \
        -Dtest=NebulaGraphClientIT,LineageGraphWriterIT \
        -Dnebula.it=true \
        -Dspring.profiles.active=it \
        -DfailIfNoTests=false
fi

# ----------------------------------------------------------------------------
# 5. 输出结果
# ----------------------------------------------------------------------------
echo ""
log_ok "NebulaGraph 集成测试完成"
echo ""
log_info "查看 NebulaGraph 容器日志:"
log_info "  docker compose -f ${COMPOSE_FILE} logs -f de-nebula-graphd"
log_info "进入 nebula-console 查询验证:"
log_info "  docker exec -it de-nebula-graphd nebula-console -addr 127.0.0.1 -port 9669 -user root -password nebula"
log_info "  USE it_lineage; GO 1 STEPS FROM \"ods.orders\" OVER lineage_edge YIELD edge;"