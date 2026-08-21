#!/usr/bin/env bash
# =============================================================================
# DataEngineBDP - Flink/Spark 真实集群集成测试启动脚本
# =============================================================================
# 用途：启动 Flink+Spark Docker 容器并运行集成测试
#   1. 检查/启动 Flink JobManager + TaskManager 容器
#   2. 检查/启动 Spark Master + Worker 容器
#   3. 等待 JM REST(8081) / Spark Master(7077,18080) 就绪
#   4. 运行 mvn test -Dtest=FlinkRestClientIT,SparkBatchSubmitterIT -Dinfra.it=true
#   5. 输出测试结果
#
# 运行环境：WSL + Docker + Maven（bash）
# 用法：bash scripts/infra/test-flink-spark-it.sh [--no-start] [--no-stop]
#   --no-start  跳过容器启动（假设已运行）
#   --no-stop   测试后不停止容器（默认不停止，保留环境）
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
DO_START=true
DO_STOP=false
for arg in "$@"; do
    case "${arg}" in
        --no-start)
            DO_START=false
            ;;
        --no-stop)
            DO_STOP=false
            ;;
        --stop-after)
            DO_STOP=true
            ;;
        --help|-h)
            echo "用法: bash scripts/infra/test-flink-spark-it.sh [--no-start] [--stop-after]"
            echo "  --no-start    跳过容器启动（假设 Flink/Spark 已运行）"
            echo "  --stop-after  测试后停止 Flink/Spark 容器"
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
MODULE_DIR="${PROJECT_ROOT}/platform/stream-batch-scheduler"

log_info "项目根目录: ${PROJECT_ROOT}"
log_info "测试模块:   ${MODULE_DIR}"

# ----------------------------------------------------------------------------
# 前置检查
# ----------------------------------------------------------------------------
if ! command -v docker >/dev/null 2>&1; then
    log_error "docker 命令未找到，请先安装 Docker"
    exit 1
fi
if ! docker info >/dev/null 2>&1; then
    log_error "Docker daemon 未运行，请先启动 Docker"
    exit 1
fi
if ! command -v mvn >/dev/null 2>&1; then
    log_error "mvn 命令未找到，请先安装 Maven"
    exit 1
fi

# ----------------------------------------------------------------------------
# 启动 Flink + Spark 容器
# ----------------------------------------------------------------------------
FLINK_SERVICES=(
    "de-flink-jobmanager"
    "de-flink-taskmanager-1"
    "de-flink-taskmanager-2"
)
SPARK_SERVICES=(
    "de-spark-master"
    "de-spark-worker-1"
    "de-spark-worker-2"
)

if [ "${DO_START}" = "true" ]; then
    log_step "启动 Flink + Spark 容器 ..."
    docker compose -f "${COMPOSE_FILE}" up -d \
        de-flink-jobmanager de-flink-taskmanager-1 de-flink-taskmanager-2 \
        de-spark-master de-spark-worker-1 de-spark-worker-2
    log_ok "容器启动命令已执行"
else
    log_info "跳过容器启动（--no-start）"
fi

# ----------------------------------------------------------------------------
# 等待服务就绪
# ----------------------------------------------------------------------------
wait_for_http() {
    local url="$1"
    local name="$2"
    local max_retries="${3:-60}"
    local i=0
    log_info "等待 ${name} 就绪: ${url}"
    while ! curl -fsS "${url}" >/dev/null 2>&1; do
        i=$((i + 1))
        if [ "${i}" -ge "${max_retries}" ]; then
            log_error "${name} 就绪超时（${max_retries} × 5s）"
            return 1
        fi
        printf "."
        sleep 5
    done
    echo ""
    log_ok "${name} 就绪"
}

echo ""
log_step "等待 Flink/Spark 服务就绪 ..."
wait_for_http "http://localhost:8081/overview" "Flink JobManager REST" 60
wait_for_http "http://localhost:18080/json/"   "Spark Master WebUI"    60

# 额外等待 TaskManager 注册到 JobManager
log_info "等待 Flink TaskManager 注册 ..."
for i in $(seq 1 30); do
    SLOTS=$(curl -fsS http://localhost:8081/overview 2>/dev/null | python3 -c "import sys,json; print(json.load(sys.stdin).get('taskslots-total',0))" 2>/dev/null || echo 0)
    if [ "${SLOTS}" -gt 0 ] 2>/dev/null; then
        log_ok "Flink TaskManager 已注册: taskSlotsTotal=${SLOTS}"
        break
    fi
    printf "."
    sleep 3
done
echo ""

# 等待 Spark Worker 注册到 Master
log_info "等待 Spark Worker 注册 ..."
for i in $(seq 1 30); do
    WORKERS=$(curl -fsS http://localhost:18080/json/ 2>/dev/null | python3 -c "import sys,json; print(len(json.load(sys.stdin).get('workers',[])))" 2>/dev/null || echo 0)
    if [ "${WORKERS}" -gt 0 ] 2>/dev/null; then
        log_ok "Spark Worker 已注册: workers=${WORKERS}"
        break
    fi
    printf "."
    sleep 3
done
echo ""

# ----------------------------------------------------------------------------
# 运行集成测试
# ----------------------------------------------------------------------------
echo ""
log_step "运行 Flink/Spark 集成测试 ..."
echo ""

cd "${MODULE_DIR}"
TEST_EXIT_CODE=0
mvn test \
    -Dtest=FlinkRestClientIT,SparkBatchSubmitterIT \
    -Dinfra.it=true \
    -Dspring.profiles.active=it \
    -Dspring.config.additional-location=classpath:application-it.yml \
    -pl . \
    -am \
    || TEST_EXIT_CODE=$?

echo ""
if [ "${TEST_EXIT_CODE}" -eq 0 ]; then
    log_ok "集成测试全部通过 ✅"
else
    log_error "集成测试失败（exitCode=${TEST_EXIT_CODE}）❌"
fi

# ----------------------------------------------------------------------------
# 测试结果摘要
# ----------------------------------------------------------------------------
echo ""
log_step "测试结果摘要"
echo "  Flink JobManager REST: http://localhost:8081"
echo "  Spark Master WebUI:    http://localhost:18080"
echo "  Spark Master RPC:      spark://localhost:7077"
echo ""
echo "  集成测试:"
echo "    - FlinkRestClientIT:       真实 Flink REST API 提交/查询/取消"
echo "    - SparkBatchSubmitterIT:   真实 Spark 集群执行 + 错误处理"
echo ""

if [ "${DO_STOP}" = "true" ]; then
    log_step "停止 Flink + Spark 容器 ..."
    docker compose -f "${COMPOSE_FILE}" stop \
        de-flink-jobmanager de-flink-taskmanager-1 de-flink-taskmanager-2 \
        de-spark-master de-spark-worker-1 de-spark-worker-2
    log_ok "容器已停止"
else
    log_info "容器保留运行（--stop-after 可停止）"
fi

exit "${TEST_EXIT_CODE}"