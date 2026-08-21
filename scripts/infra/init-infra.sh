#!/usr/bin/env bash
# =============================================================================
# DataEngineBDP 基础设施初始化脚本
# =============================================================================
# 用途：等待所有服务健康就绪后，执行各组件的初始化操作
#   - NebulaGraph：创建 space lineage，创建 tag TableField 和 edge FieldLineage
#   - Doris：创建测试数据库和表
#   - Trino：验证连接
#   - MLflow：验证连接
#   - 输出各服务端点信息
# 运行环境：WSL + Docker（bash）
# 用法：bash scripts/infra/init-infra.sh
# =============================================================================
set -euo pipefail

# ----------------------------------------------------------------------------
# 颜色输出
# ----------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m' # No Color

log_info()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
log_error() { echo -e "${RED}[ERROR]${NC} $*"; }
log_step()  { echo -e "${CYAN}[STEP]${NC}  $*"; }
log_ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }

# ----------------------------------------------------------------------------
# 等待函数
# ----------------------------------------------------------------------------
wait_for_http() {
    local name="$1"
    local url="$2"
    local max_retries="${3:-60}"
    local i=0
    log_info "等待 ${name} 就绪 (${url}) ..."
    while ! curl -fsS "${url}" >/dev/null 2>&1; do
        i=$((i + 1))
        if [ "${i}" -ge "${max_retries}" ]; then
            log_error "${name} 在 ${max_retries} 次重试后仍未就绪"
            return 1
        fi
        printf "."
        sleep 5
    done
    echo ""
    log_ok "${name} 已就绪"
}

wait_for_container() {
    local name="$1"
    local max_retries="${2:-60}"
    local i=0
    log_info "等待容器 ${name} 运行 ..."
    while ! docker ps --filter "name=${name}" --filter "status=running" --format '{{.Names}}' | grep -q "${name}"; do
        i=$((i + 1))
        if [ "${i}" -ge "${max_retries}" ]; then
            log_error "容器 ${name} 在 ${max_retries} 次重试后仍未运行"
            return 1
        fi
        printf "."
        sleep 3
    done
    echo ""
    log_ok "容器 ${name} 正在运行"
}

# ----------------------------------------------------------------------------
# 端点信息
# ----------------------------------------------------------------------------
print_endpoints() {
    echo ""
    echo -e "${CYAN}============================================================${NC}"
    echo -e "${CYAN}  DataEngineBDP 基础设施服务端点${NC}"
    echo -e "${CYAN}============================================================${NC}"
    echo -e "${GREEN}PostgreSQL${NC}        : localhost:5432  (db=dataengine, user=deadmin)"
    echo -e "${GREEN}Trino${NC}            : http://localhost:8080  (UI + API)"
    echo -e "${GREEN}Doris FE HTTP${NC}    : http://localhost:8030  (UI)"
    echo -e "${GREEN}Doris FE MySQL${NC}   : localhost:9020  (user=root, no password)"
    echo -e "${GREEN}Doris FE Query${NC}   : localhost:9030"
    echo -e "${GREEN}Doris BE${NC}         : localhost:8040 (HTTP), localhost:9060 (Thrift)"
    echo -e "${GREEN}NebulaGraph${NC}      : localhost:9669  (Thrift, user=root, password=nebula)"
    echo -e "${GREEN}NebulaGraph HTTP${NC} : http://localhost:19669"
    echo -e "${GREEN}MLflow${NC}           : http://localhost:5000  (UI)"
    echo -e "${GREEN}Flink WebUI${NC}      : http://localhost:8081"
    echo -e "${GREEN}Flink RPC${NC}        : localhost:6123"
    echo -e "${GREEN}Spark Master${NC}     : spark://localhost:7077"
    echo -e "${GREEN}Spark WebUI${NC}      : http://localhost:18080"
    echo -e "${CYAN}============================================================${NC}"
    echo ""
}

# ----------------------------------------------------------------------------
# Trino 验证
# ----------------------------------------------------------------------------
init_trino() {
    log_step "初始化 Trino ..."
    wait_for_http "Trino" "http://localhost:8080/v1/info" 60

    # 验证连接器
    log_info "验证 Trino 连接器 ..."
    local catalogs
    catalogs=$(curl -fsS "http://localhost:8080/v1/catalog" 2>/dev/null || echo "[]")
    log_info "可用 catalog: ${catalogs}"

    # 执行测试查询（memory 连接器）
    if command -v trino >/dev/null 2>&1; then
        log_info "执行 memory 连接器测试查询 ..."
        trino --server http://localhost:8080 --execute "SELECT 1 AS test" 2>/dev/null && log_ok "Trino memory 查询成功" || log_warn "Trino memory 查询跳过（客户端未配置）"
    else
        log_info "通过 REST API 执行测试查询 ..."
        local query_result
        query_result=$(curl -fsS -X POST "http://localhost:8080/v1/statement" \
            -H "Content-Type: text/plain" \
            -d "SELECT 1 AS test" 2>/dev/null || echo "failed")
        log_info "Trino 查询响应: ${query_result:0:200}..."
    fi
    log_ok "Trino 初始化完成"
}

# ----------------------------------------------------------------------------
# Doris 初始化
# ----------------------------------------------------------------------------
init_doris() {
    log_step "初始化 Doris ..."
    wait_for_http "Doris FE" "http://localhost:8030/api/bootstrap" 90
    wait_for_http "Doris BE" "http://localhost:8040/api/health" 90

    # 等待 BE 注册到 FE
    log_info "等待 BE 注册到 FE（约 30 秒）..."
    sleep 30

    # 通过 MySQL 协议创建测试数据库和表
    if command -v mysql >/dev/null 2>&1; then
        log_info "创建 Doris 测试数据库和表 ..."
        mysql -h 127.0.0.1 -P 9030 -u root --connect-timeout=10 <<'SQL'
-- 创建测试数据库
CREATE DATABASE IF NOT EXISTS dataengine_test;

-- 切换数据库
USE dataengine_test;

-- 创建 OLAP 表（Duplicate 模型）
CREATE TABLE IF NOT EXISTS events (
    event_id BIGINT,
    event_time DATETIME,
    event_type VARCHAR(64),
    user_id BIGINT,
    payload VARCHAR(4096)
)
DUPLICATE KEY(event_id)
DISTRIBUTED BY HASH(event_id) BUCKETS 4
PROPERTIES("replication_num" = "1");

-- 创建 OLAP 表（Aggregate 模型）
CREATE TABLE IF NOT EXISTS user_metrics (
    user_id BIGINT,
    metric_date DATE,
    click_count BIGINT SUM,
    view_count BIGINT SUM
)
AGGREGATE KEY(user_id, metric_date)
DISTRIBUTED BY HASH(user_id) BUCKETS 4
PROPERTIES("replication_num" = "1");

-- 创建 OLAP 表（Unique 模型）
CREATE TABLE IF NOT EXISTS dim_users (
    user_id BIGINT,
    user_name VARCHAR(128),
    email VARCHAR(256),
    created_at DATETIME
)
UNIQUE KEY(user_id)
DISTRIBUTED BY HASH(user_id) BUCKETS 4
PROPERTIES("replication_num" = "1");

-- 查看表
SHOW TABLES;
SQL
        log_ok "Doris 测试数据库和表创建完成"
    else
        log_warn "mysql 客户端未安装，跳过 Doris 表创建。请手动执行：mysql -h 127.0.0.1 -P 9030 -u root"
    fi
    log_ok "Doris 初始化完成"
}

# ----------------------------------------------------------------------------
# NebulaGraph 初始化
# ----------------------------------------------------------------------------
init_nebula() {
    log_step "初始化 NebulaGraph ..."
    wait_for_http "Nebula Meta" "http://localhost:19559/status" 60
    wait_for_http "Nebula Graph" "http://localhost:19669/status" 60
    wait_for_http "Nebula Storage" "http://localhost:19779/status" 60

    # 等待 storaged 注册
    log_info "等待 storaged 注册到 metad（约 20 秒）..."
    sleep 20

    # 通过 nebula-console 初始化（如果安装了）
    if command -v nebula-console >/dev/null 2>&1 || docker exec de-nebula-graphd which nebula-console >/dev/null 2>&1; then
        log_info "创建 NebulaGraph space 和 schema ..."

        # 使用容器内的 nebula-console（如果存在），否则使用 docker exec
        local console_cmd
        if command -v nebula-console >/dev/null 2>&1; then
            console_cmd="nebula-console -addr 127.0.0.1 -port 9669 -user root -password nebula"
        else
            console_cmd="docker exec de-nebula-graphd nebula-console -addr 127.0.0.1 -port 9669 -user root -password nebula"
        fi

        ${console_cmd} <<'NGQL'
-- 添加 storage host
ADD HOSTS 172.20.0.1:9779;

-- 创建血缘存储 space
CREATE SPACE IF NOT EXISTS lineage(
    partition_num = 10,
    replica_factor = 1,
    vid_type = FIXED_STRING(128)
);

-- 等待 space 创建完成
:sleep 10;

-- 切换到 lineage space
USE lineage;

-- 创建 tag：TableField（表字段元数据）
CREATE TAG IF NOT EXISTS TableField(
    table_name string,
    field_name string,
    field_type string,
    nullable bool DEFAULT true,
    description string DEFAULT "",
    created_at timestamp DEFAULT now()
);

-- 创建 edge：FieldLineage（字段血缘关系）
CREATE EDGE IF NOT EXISTS FieldLineage(
    transformation_type string,
    transformation_expr string,
    job_id string,
    confidence double DEFAULT 1.0,
    created_at timestamp DEFAULT now()
);

-- 创建 tag：Table（表元数据）
CREATE TAG IF NOT EXISTS Table(
    schema_name string,
    table_name string,
    description string DEFAULT "",
    owner string DEFAULT "",
    created_at timestamp DEFAULT now()
);

-- 创建 edge：TableLineage（表级血缘）
CREATE EDGE IF NOT EXISTS TableLineage(
    transformation_type string,
    job_id string,
    created_at timestamp DEFAULT now()
);

-- 创建 tag：Job（作业元数据）
CREATE TAG IF NOT EXISTS Job(
    job_id string,
    job_type string,
    status string,
    started_at timestamp,
    finished_at timestamp
);

-- 查看创建结果
SHOW TAGS;
SHOW EDGES;
NGQL
        log_ok "NebulaGraph space 和 schema 创建完成"
    else
        log_warn "nebula-console 未找到，跳过 NebulaGraph schema 创建"
        log_info "请手动执行初始化："
        log_info "  docker exec -it de-nebula-graphd nebula-console -addr 127.0.0.1 -port 9669 -user root -password nebula"
    fi
    log_ok "NebulaGraph 初始化完成"
}

# ----------------------------------------------------------------------------
# MLflow 验证
# ----------------------------------------------------------------------------
init_mlflow() {
    log_step "初始化 MLflow ..."
    wait_for_http "MLflow" "http://localhost:5000/health" 60

    # 验证 API
    log_info "验证 MLflow API ..."
    local experiments
    experiments=$(curl -fsS "http://localhost:5000/api/2.0/mlflow/experiments/search" 2>/dev/null || echo "{}")
    log_info "MLflow experiments: ${experiments:0:200}..."

    # 创建默认实验
    curl -fsS -X POST "http://localhost:5000/api/2.0/mlflow/experiments/create" \
        -H "Content-Type: application/json" \
        -d '{"name": "dataengine_default"}' 2>/dev/null && log_ok "MLflow 默认实验创建成功" || log_warn "MLflow 默认实验可能已存在"

    log_ok "MLflow 初始化完成"
}

# ----------------------------------------------------------------------------
# Flink 验证
# ----------------------------------------------------------------------------
init_flink() {
    log_step "初始化 Flink ..."
    wait_for_http "Flink JobManager" "http://localhost:8081/overview" 60

    local overview
    overview=$(curl -fsS "http://localhost:8081/overview" 2>/dev/null || echo "{}")
    log_info "Flink overview: ${overview:0:200}..."

    # 查询 TaskManager 数量
    local tm_count
    tm_count=$(curl -fsS "http://localhost:8081/taskmanagers" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('taskmanagers',[])))" 2>/dev/null || echo "0")
    log_info "Flink TaskManager 数量: ${tm_count}"

    log_ok "Flink 初始化完成"
}

# ----------------------------------------------------------------------------
# Spark 验证
# ----------------------------------------------------------------------------
init_spark() {
    log_step "初始化 Spark ..."
    wait_for_http "Spark Master" "http://localhost:18080/" 60

    local workers
    workers=$(curl -fsS "http://localhost:18080/json" 2>/dev/null | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('workers',[])))" 2>/dev/null || echo "0")
    log_info "Spark Worker 数量: ${workers}"

    log_ok "Spark 初始化完成"
}

# ----------------------------------------------------------------------------
# PostgreSQL 验证
# ----------------------------------------------------------------------------
init_postgres() {
    log_step "初始化 PostgreSQL ..."
    wait_for_container "de-postgres" 60

    if docker exec de-postgres pg_isready -U deadmin -d dataengine >/dev/null 2>&1; then
        log_ok "PostgreSQL 已就绪"
        # 验证 schema
        local schemas
        schemas=$(docker exec de-postgres psql -U deadmin -d dataengine -t -c "SELECT schema_name FROM information_schema.schemata WHERE schema_name IN ('metadata','lineage','ml_tracking','audit') ORDER BY schema_name;" 2>/dev/null || echo "")
        log_info "PostgreSQL schemas: ${schemas}"
    else
        log_error "PostgreSQL 未就绪"
        return 1
    fi
    log_ok "PostgreSQL 初始化完成"
}

# ----------------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------------
main() {
    echo -e "${CYAN}============================================================${NC}"
    echo -e "${CYAN}  DataEngineBDP 基础设施初始化${NC}"
    echo -e "${CYAN}  时间: $(date '+%Y-%m-%d %H:%M:%S')${NC}"
    echo -e "${CYAN}============================================================${NC}"
    echo ""

    # 检查 docker 可用性
    if ! docker info >/dev/null 2>&1; then
        log_error "Docker 未运行，请先启动 Docker Desktop / WSL Docker"
        exit 1
    fi

    # 依次初始化各组件（PostgreSQL 先，其他组件可能依赖它）
    init_postgres
    init_trino
    init_doris
    init_nebula
    init_mlflow
    init_flink
    init_spark

    # 输出端点信息
    print_endpoints

    echo -e "${GREEN}============================================================${NC}"
    echo -e "${GREEN}  所有基础设施服务初始化完成！${NC}"
    echo -e "${GREEN}============================================================${NC}"
}

main "$@"