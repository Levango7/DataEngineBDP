#!/usr/bin/env bash
# =============================================================================
# 环境部署冒烟测试脚本（env-deploy-smoke-test.sh）
#
# 用途：对指定环境（dev/staging/pre-prod/prod）执行部署后冒烟测试，
#       验证 K8s 集群、核心服务 Pod、数据库连通性、API 网关、前端页面可达性。
#
# 用法：
#   ./env-deploy-smoke-test.sh dev              # 测试开发环境
#   ./env-deploy-smoke-test.sh staging          # 测试预发环境
#   ./env-deploy-smoke-test.sh pre-prod         # 测试预生产环境
#   ./env-deploy-smoke-test.sh prod             # 测试生产环境
#   ./env-deploy-smoke-test.sh --all            # 遍历所有环境
#   ./env-deploy-smoke-test.sh dev --verbose    # 详细输出
#
# 退出码：
#   0 — 所有检查通过
#   1 — 一个或多个检查失败
#   2 — 参数错误或依赖缺失
#
# 依赖：kubectl, curl, bash 4+
# =============================================================================
set -euo pipefail

# -----------------------------------------------------------------------------
# 全局变量与默认配置
# -----------------------------------------------------------------------------
SCRIPT_NAME="$(basename "$0")"
VERBOSE=false
TIMESTAMP="$(date '+%Y-%m-%dT%H:%M:%S%z')"

# 环境列表
VALID_ENVS=("dev" "staging" "pre-prod" "prod")

# 检查结果统计
TOTAL_CHECKS=0
PASSED_CHECKS=0
FAILED_CHECKS=0
FAILED_DETAILS=()

# 颜色输出（如终端支持）
if [[ -t 1 ]]; then
    COLOR_RED='\033[0;31m'
    COLOR_GREEN='\033[0;32m'
    COLOR_YELLOW='\033[1;33m'
    COLOR_BLUE='\033[0;34m'
    COLOR_RESET='\033[0m'
else
    COLOR_RED=''; COLOR_GREEN=''; COLOR_YELLOW=''; COLOR_BLUE=''; COLOR_RESET=''
fi

# -----------------------------------------------------------------------------
# 环境配置映射
# 每个环境定义：kube_context, namespace, api_gateway_url, frontend_url,
#               db_host, db_port, core_services[]
# -----------------------------------------------------------------------------
declare -A ENV_KUBE_CONTEXT=(
    ["dev"]="kind-dataengine-dev"
    ["staging"]="k3s-dataengine-staging"
    ["pre-prod"]="dataengine-pre-prod"
    ["prod"]="dataengine-prod"
)

declare -A ENV_NAMESPACE=(
    ["dev"]="dataengine-dev"
    ["staging"]="dataengine-staging"
    ["pre-prod"]="dataengine-pre-prod"
    ["prod"]="dataengine-prod"
)

declare -A ENV_API_GATEWAY=(
    ["dev"]="http://localhost:30080"
    ["staging"]="http://staging-api.dataengine.internal:8080"
    ["pre-prod"]="https://preprod-api.dataengine.example.com"
    ["prod"]="https://api.dataengine.example.com"
)

declare -A ENV_FRONTEND_URL=(
    ["dev"]="http://localhost:30081"
    ["staging"]="http://staging-portal.dataengine.internal:80"
    ["pre-prod"]="https://preprod-portal.dataengine.example.com"
    ["prod"]="https://portal.dataengine.example.com"
)

declare -A ENV_DB_HOST=(
    ["dev"]="localhost"
    ["staging"]="staging-pg.dataengine.internal"
    ["pre-prod"]="preprod-pg.dataengine.example.com"
    ["prod"]="prod-pg.dataengine.example.com"
)

declare -A ENV_DB_PORT=(
    ["dev"]="5432"
    ["staging"]="5432"
    ["pre-prod"]="5432"
    ["prod"]="5432"
)

# 核心服务列表（Deployment 名称）
CORE_SERVICES=(
    "encaps-layer"
    "encaps-gateway"
    "sql-gateway"
    "rule-engine"
    "nl2sql"
    "llm-gateway"
    "knowledge-engine"
    "catalog"
    "business-portal"
    "operations-api"
)

# 数据组件列表（StatefulSet/Deployment 名称）
DATA_COMPONENTS=(
    "postgresql"
    "doris-fe"
    "doris-be"
    "kafka"
)

# -----------------------------------------------------------------------------
# 日志函数
# -----------------------------------------------------------------------------
log_info() {
    printf "${COLOR_BLUE}[INFO]${COLOR_RESET} %s\n" "$*"
}

log_pass() {
    printf "${COLOR_GREEN}[PASS]${COLOR_RESET} %s\n" "$*"
}

log_fail() {
    printf "${COLOR_RED}[FAIL]${COLOR_RESET} %s\n" "$*"
}

log_warn() {
    printf "${COLOR_YELLOW}[WARN]${COLOR_RESET} %s\n" "$*"
}

log_debug() {
    if [[ "$VERBOSE" == "true" ]]; then
        printf "[DEBUG] %s\n" "$*"
    fi
}

# 记录检查结果
record_check() {
    local description="$1"
    local status="$2"
    local detail="${3:-}"

    TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
    if [[ "$status" == "PASS" ]]; then
        PASSED_CHECKS=$((PASSED_CHECKS + 1))
        log_pass "$description"
        if [[ -n "$detail" ]]; then
            log_debug "  └─ $detail"
        fi
    else
        FAILED_CHECKS=$((FAILED_CHECKS + 1))
        FAILED_DETAILS+=("$description: $detail")
        log_fail "$description"
        if [[ -n "$detail" ]]; then
            printf "       %s\n" "$detail"
        fi
    fi
}

# -----------------------------------------------------------------------------
# 前置依赖检查
# -----------------------------------------------------------------------------
check_dependencies() {
    local missing=()

    if ! command -v kubectl &>/dev/null; then
        missing+=("kubectl")
    fi
    if ! command -v curl &>/dev/null; then
        missing+=("curl")
    fi

    if [[ ${#missing[@]} -gt 0 ]]; then
        log_fail "缺失依赖工具: ${missing[*]}"
        log_info "请安装缺失的工具后重试。"
        exit 2
    fi

    log_debug "依赖检查通过: kubectl=$(kubectl version --client --short 2>/dev/null || echo 'unknown')"
}

# -----------------------------------------------------------------------------
# 检查 1：K8s 集群连通性
# -----------------------------------------------------------------------------
check_k8s_cluster() {
    local env="$1"
    local context="${ENV_KUBE_CONTEXT[$env]}"

    log_info "检查 K8s 集群连通性 (context: $context)..."

    # 尝试切换 context
    if ! kubectl config use-context "$context" &>/dev/null; then
        record_check "K8s 集群 context 可达 ($context)" "FAIL" "无法切换到 context '$context'，可能未配置"
        return 1
    fi

    # 检查集群节点就绪
    local node_count
    if node_count=$(kubectl get nodes --no-headers 2>/dev/null | wc -l) && [[ "$node_count" -gt 0 ]]; then
        local ready_count
        ready_count=$(kubectl get nodes -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null | grep -c "True" || echo 0)
        if [[ "$ready_count" -eq "$node_count" ]]; then
            record_check "K8s 集群节点全部就绪 ($ready_count/$node_count)" "PASS" "所有节点 Ready=True"
        else
            record_check "K8s 集群节点就绪 ($ready_count/$node_count)" "FAIL" "$((node_count - ready_count)) 个节点未就绪"
        fi
    else
        record_check "K8s 集群节点可达" "FAIL" "无法获取节点列表"
    fi

    return 0
}

# -----------------------------------------------------------------------------
# 检查 2：核心服务 Pod 状态
# -----------------------------------------------------------------------------
check_core_services() {
    local env="$1"
    local namespace="${ENV_NAMESPACE[$env]}"

    log_info "检查核心服务 Pod 状态 (namespace: $namespace)..."

    for svc in "${CORE_SERVICES[@]}"; do
        local pod_status
        local ready

        # 获取 Deployment 的 Pod 就绪状态
        if pod_status=$(kubectl get deployment -n "$namespace" "$svc" -o jsonpath='{.status.readyReplicas}/{.status.replicas}' 2>/dev/null) && [[ -n "$pod_status" ]]; then
            ready="${pod_status%%/*}"
            local total="${pod_status##*/}"
            if [[ -n "$ready" && -n "$total" && "$ready" -eq "$total" && "$total" -gt 0 ]]; then
                record_check "核心服务 $svc 就绪 ($ready/$total)" "PASS" "Deployment $svc 所有 Pod Ready"
            else
                record_check "核心服务 $svc 就绪 ($pod_status)" "FAIL" "就绪副本数不足，期望 $total，实际 ${ready:-0}"
            fi
        else
            record_check "核心服务 $svc 存在" "FAIL" "Deployment $svc 在 namespace $namespace 中不存在或无法查询"
        fi
    done
}

# -----------------------------------------------------------------------------
# 检查 3：数据组件 Pod 状态
# -----------------------------------------------------------------------------
check_data_components() {
    local env="$1"
    local namespace="${ENV_NAMESPACE[$env]}"

    log_info "检查数据组件 Pod 状态 (namespace: $namespace)..."

    for comp in "${DATA_COMPONENTS[@]}"; do
        local pod_status

        # 先尝试 Deployment，再尝试 StatefulSet
        if pod_status=$(kubectl get deployment -n "$namespace" "$comp" -o jsonpath='{.status.readyReplicas}/{.status.replicas}' 2>/dev/null) && [[ -n "$pod_status" ]]; then
            local ready="${pod_status%%/*}"
            local total="${pod_status##*/}"
            if [[ -n "$ready" && -n "$total" && "$ready" -eq "$total" && "$total" -gt 0 ]]; then
                record_check "数据组件 $comp 就绪 ($ready/$total)" "PASS"
            else
                record_check "数据组件 $comp 就绪 ($pod_status)" "FAIL" "Deployment 副本数不足"
            fi
        elif pod_status=$(kubectl get statefulset -n "$namespace" "$comp" -o jsonpath='{.status.readyReplicas}/{.status.replicas}' 2>/dev/null) && [[ -n "$pod_status" ]]; then
            local ready="${pod_status%%/*}"
            local total="${pod_status##*/}"
            if [[ -n "$ready" && -n "$total" && "$ready" -eq "$total" && "$total" -gt 0 ]]; then
                record_check "数据组件 $comp 就绪 ($ready/$total)" "PASS"
            else
                record_check "数据组件 $comp 就绪 ($pod_status)" "FAIL" "StatefulSet 副本数不足"
            fi
        else
            record_check "数据组件 $comp 存在" "FAIL" "组件 $comp 在 namespace $namespace 中不存在"
        fi
    done
}

# -----------------------------------------------------------------------------
# 检查 4：数据库连通性
# -----------------------------------------------------------------------------
check_database_connectivity() {
    local env="$1"
    local db_host="${ENV_DB_HOST[$env]}"
    local db_port="${ENV_DB_PORT[$env]}"

    log_info "检查数据库连通性 ($db_host:$db_port)..."

    # TCP 端口连通性检查（使用 bash /dev/tcp 或 nc）
    if timeout 10 bash -c "echo >/dev/tcp/$db_host/$db_port" 2>/dev/null; then
        record_check "数据库 TCP 连通 ($db_host:$db_port)" "PASS" "端口 $db_port 可达"
    else
        # 在 CI/无网络环境中，通过 kubectl exec 进入 Pod 检查
        local namespace="${ENV_NAMESPACE[$env]}"
        if kubectl exec -n "$namespace" deployment/sql-gateway -- pg_isready -h "$db_host" -p "$db_port" &>/dev/null 2>&1; then
            record_check "数据库连通 ($db_host:$db_port) via Pod" "PASS" "通过 sql-gateway Pod 验证连通"
        else
            record_check "数据库连通 ($db_host:$db_port)" "FAIL" "TCP 连接失败且无法通过 Pod 验证"
        fi
    fi
}

# -----------------------------------------------------------------------------
# 检查 5：API 网关健康端点
# -----------------------------------------------------------------------------
check_api_gateway() {
    local env="$1"
    local gateway_url="${ENV_API_GATEWAY[$env]}"

    log_info "检查 API 网关健康端点 ($gateway_url)..."

    local http_code
    local curl_opts=(-s -o /dev/null -w '%{http_code}' --connect-timeout 10 --max-time 30)

    # 健康端点检查
    if http_code=$(curl "${curl_opts[@]}" "$gateway_url/actuator/health" 2>/dev/null) && [[ "$http_code" == "200" ]]; then
        record_check "API 网关 /actuator/health 返回 200" "PASS" "HTTP $http_code"
    else
        # 尝试备用健康端点
        if http_code=$(curl "${curl_opts[@]}" "$gateway_url/health" 2>/dev/null) && [[ "$http_code" == "200" ]]; then
            record_check "API 网关 /health 返回 200" "PASS" "HTTP $http_code"
        else
            record_check "API 网关健康端点可达" "FAIL" "HTTP ${http_code:-000} ($gateway_url)"
        fi
    fi

    # API 网关根路径可达性（允许 200/302/401）
    if http_code=$(curl "${curl_opts[@]}" "$gateway_url/" 2>/dev/null) && [[ "$http_code" =~ ^(200|302|401)$ ]]; then
        record_check "API 网关根路径可达" "PASS" "HTTP $http_code"
    else
        record_check "API 网关根路径可达" "FAIL" "HTTP ${http_code:-000}"
    fi
}

# -----------------------------------------------------------------------------
# 检查 6：前端页面可访问性
# -----------------------------------------------------------------------------
check_frontend() {
    local env="$1"
    local frontend_url="${ENV_FRONTEND_URL[$env]}"

    log_info "检查前端页面可访问性 ($frontend_url)..."

    local http_code
    local curl_opts=(-s -o /dev/null -w '%{http_code}' --connect-timeout 10 --max-time 30 -L)

    if http_code=$(curl "${curl_opts[@]}" "$frontend_url" 2>/dev/null) && [[ "$http_code" == "200" ]]; then
        record_check "前端页面返回 HTTP 200" "PASS" "HTTP $http_code ($frontend_url)"
    else
        record_check "前端页面返回 HTTP 200" "FAIL" "HTTP ${http_code:-000} ($frontend_url)"
    fi
}

# -----------------------------------------------------------------------------
# 单环境冒烟测试
# -----------------------------------------------------------------------------
run_smoke_test() {
    local env="$1"

    # 重置计数器
    TOTAL_CHECKS=0
    PASSED_CHECKS=0
    FAILED_CHECKS=0
    FAILED_DETAILS=()

    printf "\n"
    printf "=============================================================================\n"
    printf "  环境冒烟测试: %s\n" "$env"
    printf "  时间: %s\n" "$TIMESTAMP"
    printf "=============================================================================\n\n"

    log_info "开始对环境 [$env] 执行冒烟测试...\n"

    # 执行各项检查（每项独立，不因单项失败而中断）
    check_k8s_cluster "$env" || true
    check_core_services "$env" || true
    check_data_components "$env" || true
    check_database_connectivity "$env" || true
    check_api_gateway "$env" || true
    check_frontend "$env" || true

    # 输出汇总
    printf "\n"
    printf "-----------------------------------------------------------------------------\n"
    printf "  测试汇总: %s\n" "$env"
    printf "-----------------------------------------------------------------------------\n"
    printf "  总检查项:  %d\n" "$TOTAL_CHECKS"
    printf "  通过:      ${COLOR_GREEN}%d${COLOR_RESET}\n" "$PASSED_CHECKS"
    printf "  失败:      ${COLOR_RED}%d${COLOR_RESET}\n" "$FAILED_CHECKS"
    printf "  结果:      "
    if [[ "$FAILED_CHECKS" -eq 0 ]]; then
        printf "${COLOR_GREEN}PASS (全部通过)${COLOR_RESET}\n"
    else
        printf "${COLOR_RED}FAIL (存在失败项)${COLOR_RESET}\n"
        printf "\n  失败详情:\n"
        for detail in "${FAILED_DETAILS[@]}"; do
            printf "    - %s\n" "$detail"
        done
    fi
    printf "-----------------------------------------------------------------------------\n\n"

    # 返回结果（供 --all 模式汇总）
    if [[ "$FAILED_CHECKS" -eq 0 ]]; then
        return 0
    else
        return 1
    fi
}

# -----------------------------------------------------------------------------
# 用法说明
# -----------------------------------------------------------------------------
usage() {
    cat << USAGE_EOF
用法: $SCRIPT_NAME <环境> [选项]

参数:
  <环境>          目标环境: dev | staging | pre-prod | prod
  --all           遍历所有环境执行测试
  --verbose, -v   详细输出（含 DEBUG 日志）
  --help, -h      显示此帮助信息

示例:
  $SCRIPT_NAME dev              # 测试开发环境
  $SCRIPT_NAME staging          # 测试预发环境
  $SCRIPT_NAME pre-prod         # 测试预生产环境
  $SCRIPT_NAME prod             # 测试生产环境
  $SCRIPT_NAME --all            # 遍历所有环境
  $SCRIPT_NAME dev --verbose    # 详细模式

退出码:
  0 — 所有检查通过
  1 — 一个或多个检查失败
  2 — 参数错误或依赖缺失
USAGE_EOF
}

# -----------------------------------------------------------------------------
# 参数解析
# -----------------------------------------------------------------------------
ENV_ARG=""
ALL_MODE=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --all)
            ALL_MODE=true
            shift
            ;;
        --verbose|-v)
            VERBOSE=true
            shift
            ;;
        --help|-h)
            usage
            exit 0
            ;;
        dev|staging|pre-prod|prod)
            ENV_ARG="$1"
            shift
            ;;
        *)
            log_fail "未知参数: $1"
            usage
            exit 2
            ;;
    esac
done

# 校验参数组合
if [[ "$ALL_MODE" == "false" && -z "$ENV_ARG" ]]; then
    log_fail "未指定环境参数"
    usage
    exit 2
fi

if [[ "$ALL_MODE" == "true" && -n "$ENV_ARG" ]]; then
    log_warn "同时指定了 --all 和环境参数，将以 --all 模式执行（忽略 $ENV_ARG）"
    ENV_ARG=""
fi

# -----------------------------------------------------------------------------
# 主流程
# -----------------------------------------------------------------------------
main() {
    check_dependencies

    local overall_exit=0

    if [[ "$ALL_MODE" == "true" ]]; then
        log_info "全环境冒烟测试模式：将遍历 ${VALID_ENVS[*]}"
        local env_results=()

        for env in "${VALID_ENVS[@]}"; do
            if run_smoke_test "$env"; then
                env_results+=("$env: PASS")
            else
                env_results+=("$env: FAIL")
                overall_exit=1
            fi
        done

        # 全环境汇总
        printf "=============================================================================\n"
        printf "  全环境测试汇总\n"
        printf "=============================================================================\n"
        for result in "${env_results[@]}"; do
            printf "    %s\n" "$result"
        done
        printf "=============================================================================\n"
    else
        if ! run_smoke_test "$ENV_ARG"; then
            overall_exit=1
        fi
    fi

    if [[ "$overall_exit" -eq 0 ]]; then
        log_info "冒烟测试全部通过 ✅"
    else
        log_fail "冒烟测试存在失败项 ❌，请检查上方详情"
    fi

    exit "$overall_exit"
}

main