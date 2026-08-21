#!/usr/bin/env bash
# =============================================================================
# DataEngineBDP Karmada 联邦集成测试启动脚本
# =============================================================================
# 用途：用 kind 创建多集群 + 安装 Karmada 控制面 + 部署 mock API + 运行集成测试
#
# 流程：
#   1. 检查依赖（kind, kubectl, docker, mvn）
#   2. 创建 3 个 kind 集群：karmada-host（控制面）, cluster-a, cluster-b（成员）
#   3. 在 karmada-host 安装 Karmada 控制面
#   4. 将 cluster-a, cluster-b 加入 Karmada 联邦
#   5. 在 cluster-a, cluster-b 部署 mock Catalog/Lineage/Quality API（nginx + 静态 JSON）
#   6. kubectl port-forward 暴露 mock API 到 localhost:18090, localhost:18091
#   7. 运行 mvn test -Dtest=RealClusterIT -Dinfra.it=true
#   8. 清理 kind 集群
#
# 运行环境：WSL + Docker + kind + kubectl + karmadactl
# 用法：bash scripts/infra/test-karmada-it.sh
#
# 选项：
#   --skip-cleanup   保留 kind 集群（调试用）
#   --only-test      跳过集群创建，仅运行测试（假设集群已就绪）
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
# 选项解析
# ----------------------------------------------------------------------------
SKIP_CLEANUP=false
ONLY_TEST=false
for arg in "$@"; do
    case "$arg" in
        --skip-cleanup) SKIP_CLEANUP=true ;;
        --only-test)    ONLY_TEST=true ;;
        *) log_warn "未知选项: $arg" ;;
    esac
done

# ----------------------------------------------------------------------------
# 配置
# ----------------------------------------------------------------------------
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MODULE_DIR="${PROJECT_ROOT}/platform/karmada/federated-query"

KARMADA_HOST_CLUSTER="karmada-host"
CLUSTER_A="cluster-a"
CLUSTER_B="cluster-b"

# mock API 在 host 上暴露的端口
PORT_A=18090
PORT_B=18091
# Karmada 控制面 API 端口
KARMADA_API_PORT=5443

KIND_VERSION="v0.24.0"
KARMADA_VERSION="v1.12.0"

# ----------------------------------------------------------------------------
# 依赖检查
# ----------------------------------------------------------------------------
check_deps() {
    log_step "检查依赖..."
    local missing=()
    command -v docker >/dev/null 2>&1 || missing+=("docker")
    command -v kind >/dev/null 2>&1 || missing+=("kind")
    command -v kubectl >/dev/null 2>&1 || missing+=("kubectl")
    command -v mvn >/dev/null 2>&1 || missing+=("mvn")

    if [ ${#missing[@]} -gt 0 ]; then
        log_error "缺少依赖: ${missing[*]}"
        log_error "请安装: docker, kind (${KIND_VERSION}), kubectl, maven"
        return 1
    fi

    # karmadactl 可选（若已安装 Karmada 控制面则不需要）
    if ! command -v karmadactl >/dev/null 2>&1; then
        log_warn "未找到 karmadactl，将尝试下载"
    fi
    log_ok "依赖检查通过"
}

# ----------------------------------------------------------------------------
# 创建 kind 集群
# ----------------------------------------------------------------------------
create_kind_cluster() {
    local name="$1"
    log_step "创建 kind 集群: ${name}"
    if kind get clusters 2>/dev/null | grep -q "^${name}$"; then
        log_warn "kind 集群 ${name} 已存在，跳过创建"
        return 0
    fi
    cat <<EOF | kind create cluster --name "${name}" --wait 120s -
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
networking:
  apiServerAddress: 127.0.0.1
nodeRoleExtraMounts:
- role: control-plane
  extraMounts:
  - hostPath: /var/run/docker.sock
    containerPath: /var/run/docker.sock
EOF
    log_ok "kind 集群 ${name} 创建完成"
}

# ----------------------------------------------------------------------------
# 安装 Karmada 控制面
# ----------------------------------------------------------------------------
install_karmada() {
    log_step "安装 Karmada 控制面（在 ${KARMADA_HOST_CLUSTER} 上）..."
    kind export kubeconfig --name "${KARMADA_HOST_CLUSTER}" --kubeconfig /tmp/karmada-host.kubeconfig

    # 尝试使用已安装的 karmadactl，否则下载
    local karmadactl_bin="karmadactl"
    if ! command -v karmadactl >/dev/null 2>&1; then
        log_info "下载 karmadactl ${KARMADA_VERSION}..."
        local tmpdir="$(mktemp -d)"
        curl -fsSL "https://github.com/karmada-io/karmada/releases/download/${KARMADA_VERSION}/karmadactl-linux-amd64" \
            -o "${tmpdir}/karmadactl"
        chmod +x "${tmpdir}/karmadactl"
        karmadactl_bin="${tmpdir}/karmadactl"
    fi

    # 初始化 Karmada 控制面
    if ! kubectl --kubeconfig /tmp/karmada-host.kubeconfig get namespace karmada-system >/dev/null 2>&1; then
        "${karmadactl_bin}" init \
            --kubeconfig /tmp/karmada-host.kubeconfig \
            --host-cluster-context "kind-${KARMADA_HOST_CLUSTER}" \
            --wait 300s 2>/dev/null || {
            log_warn "karmadactl init 失败，尝试使用 helm 方式"
            install_karmada_helm
        }
    else
        log_warn "karmada-system 已存在，跳过初始化"
    fi

    # 获取 Karmada 控制面 kubeconfig
    "${karmadactl_bin}" config get-kubeconfig --kubeconfig /tmp/karmada-host.kubeconfig \
        --name "${KARMADA_HOST_CLUSTER}" > /tmp/karmada.kubeconfig 2>/dev/null || true

    log_ok "Karmada 控制面安装完成"
}

install_karmada_helm() {
    log_info "使用 helm 安装 Karmada..."
    if ! command -v helm >/dev/null 2>&1; then
        log_error "helm 未安装，无法用 helm 方式安装 Karmada"
        return 1
    fi
    helm repo add karmada https://raw.githubusercontent.com/karmada-io/karmada/master/charts >/dev/null 2>&1 || true
    helm repo update >/dev/null 2>&1
    helm upgrade --install karmada karmada/karmada \
        --namespace karmada-system --create-namespace \
        --kubeconfig /tmp/karmada-host.kubeconfig \
        --wait --timeout 300s
}

# ----------------------------------------------------------------------------
# 加入成员集群
# ----------------------------------------------------------------------------
join_member_cluster() {
    local member="$1"
    log_step "将 ${member} 加入 Karmada 联邦..."
    kind export kubeconfig --name "${member}" --kubeconfig "/tmp/${member}.kubeconfig"

    local karmadactl_bin="karmadactl"
    if ! command -v karmadactl >/dev/null 2>&1; then
        # 复用 install_karmada 中下载的 karmadactl
        karmadactl_bin="$(command -v karmadactl 2>/dev/null || echo /tmp/karmadactl)"
    fi

    "${karmadactl_bin}" join "${member}" \
        --cluster-kubeconfig "/tmp/${member}.kubeconfig" \
        --cluster-context "kind-${member}" \
        --karmada-kubeconfig /tmp/karmada.kubeconfig 2>/dev/null || {
        log_warn "加入 ${member} 失败（可能已加入），继续"
    }
    log_ok "${member} 已加入 Karmada 联邦"
}

# ----------------------------------------------------------------------------
# 部署 mock API 服务
# ----------------------------------------------------------------------------
deploy_mock_api() {
    local cluster="$1"
    local port="$2"
    local kubeconfig="/tmp/${cluster}.kubeconfig"
    log_step "在 ${cluster} 部署 mock Catalog/Lineage/Quality API..."

    # 生成静态 JSON 响应的 ConfigMap
    # Catalog API: /api/v1/catalog/tables
    # Lineage API: /api/v1/lineage/edges, /api/v1/lineage/nodes
    # Quality API: /api/v1/quality/execute
    generate_mock_data "${cluster}" "${kubeconfig}"

    # 部署 nginx 服务（使用内置 nginx 镜像，挂载 ConfigMap）
    cat <<EOF | kubectl --kubeconfig "${kubeconfig}" apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mock-api
  namespace: default
  labels:
    app: mock-api
spec:
  replicas: 1
  selector:
    matchLabels:
      app: mock-api
  template:
    metadata:
      labels:
        app: mock-api
    spec:
      containers:
      - name: nginx
        image: nginx:1.25-alpine
        ports:
        - containerPort: 80
        volumeMounts:
        - name: api-data
          mountPath: /usr/share/nginx/html
          readOnly: true
      volumes:
      - name: api-data
        configMap:
          name: mock-api-data
---
apiVersion: v1
kind: Service
metadata:
  name: mock-api
  namespace: default
spec:
  selector:
    app: mock-api
  ports:
  - port: 80
    targetPort: 80
EOF

    # 等待 Deployment 就绪
    kubectl --kubeconfig "${kubeconfig}" -n default rollout status deployment/mock-api --timeout=120s

    log_ok "${cluster} mock API 部署完成"
}

generate_mock_data() {
    local cluster="$1"
    local kubeconfig="$2"

    # 创建目录结构（通过 ConfigMap 的 key 模拟路径）
    # nginx 默认 try_files 会按路径查找文件

    if [ "${cluster}" = "cluster-a" ]; then
        # cluster-a: 表 db.orders, db.customers; 血缘 db.raw -> db.orders
        kubectl --kubeconfig "${kubeconfig}" create configmap mock-api-data --from-literal="api/v1/catalog/tables.json"='{"data":[{"id":"cluster-a:db.orders","databaseName":"db","tableName":"orders","tableType":"MANAGED","columns":[{"name":"id","type":"INT","ordinal":0},{"name":"amount","type":"DOUBLE","ordinal":1}],"properties":{"cluster":"cluster-a"},"rowCount":1000,"sizeInBytes":102400},{"id":"cluster-a:db.customers","databaseName":"db","tableName":"customers","tableType":"MANAGED","columns":[{"name":"id","type":"INT","ordinal":0},{"name":"name","type":"STRING","ordinal":1}],"properties":{"cluster":"cluster-a"},"rowCount":500}]}' \
            --from-literal="api/v1/lineage/edges.json"='{"data":{"edges":[{"edgeId":"e-a-1","sourceNodeId":"cluster-a:db.raw","targetNodeId":"cluster-a:db.orders","edgeType":"DIRECT","transformation":"ETL","crossCluster":false,"sourceClusterId":"cluster-a","targetClusterId":"cluster-a"}],"nodes":[{"nodeId":"cluster-a:db.raw","name":"raw","nodeType":"TABLE","clusterId":"cluster-a","database":"db","label":"db.raw"},{"nodeId":"cluster-a:db.orders","name":"orders","nodeType":"TABLE","clusterId":"cluster-a","database":"db","label":"db.orders"}]}}' \
            --from-literal="api/v1/lineage/nodes.json"='{"data":{"nodes":[{"nodeId":"cluster-a:db.raw","name":"raw","nodeType":"TABLE","clusterId":"cluster-a","database":"db","label":"db.raw"},{"nodeId":"cluster-a:db.orders","name":"orders","nodeType":"TABLE","clusterId":"cluster-a","database":"db","label":"db.orders"},{"nodeId":"cluster-a:db.customers","name":"customers","nodeType":"TABLE","clusterId":"cluster-a","database":"db","label":"db.customers"}]}}' \
            --from-literal="api/v1/quality/execute.json"='{"data":[{"tableId":"cluster-a:db.orders","tableName":"orders","clusterId":"cluster-a","ruleResults":{"rule-1":true},"dimensionScores":{"COMPLETENESS":95.0},"overallScore":95.0,"checkedRows":1000,"failedRows":50},{"tableId":"cluster-a:db.customers","tableName":"customers","clusterId":"cluster-a","ruleResults":{"rule-1":true},"dimensionScores":{"COMPLETENESS":90.0},"overallScore":90.0,"checkedRows":500,"failedRows":50}]}' \
            --dry-run=client -o yaml | kubectl --kubeconfig "${kubeconfig}" apply -f -
    else
        # cluster-b: 表 db.orders, db.shipments; 血缘 db.orders -> db.shipments
        kubectl --kubeconfig "${kubeconfig}" create configmap mock-api-data --from-literal="api/v1/catalog/tables.json"='{"data":[{"id":"cluster-b:db.orders","databaseName":"db","tableName":"orders","tableType":"MANAGED","columns":[{"name":"id","type":"INT","ordinal":0},{"name":"amount","type":"DOUBLE","ordinal":1}],"properties":{"cluster":"cluster-b"},"rowCount":800,"sizeInBytes":81920},{"id":"cluster-b:db.shipments","databaseName":"db","tableName":"shipments","tableType":"MANAGED","columns":[{"name":"id","type":"INT","ordinal":0},{"name":"order_id","type":"INT","ordinal":1}],"properties":{"cluster":"cluster-b"},"rowCount":300}]}' \
            --from-literal="api/v1/lineage/edges.json"='{"data":{"edges":[{"edgeId":"e-b-1","sourceNodeId":"cluster-b:db.orders","targetNodeId":"cluster-b:db.shipments","edgeType":"DIRECT","transformation":"JOIN","crossCluster":false,"sourceClusterId":"cluster-b","targetClusterId":"cluster-b"},{"edgeId":"e-cross-1","sourceNodeId":"cluster-a:db.orders","targetNodeId":"cluster-b:db.shipments","edgeType":"COPY","transformation":"cross-cluster copy","crossCluster":true,"sourceClusterId":"cluster-a","targetClusterId":"cluster-b"}],"nodes":[{"nodeId":"cluster-b:db.orders","name":"orders","nodeType":"TABLE","clusterId":"cluster-b","database":"db","label":"db.orders"},{"nodeId":"cluster-b:db.shipments","name":"shipments","nodeType":"TABLE","clusterId":"cluster-b","database":"db","label":"db.shipments"}]}}' \
            --from-literal="api/v1/lineage/nodes.json"='{"data":{"nodes":[{"nodeId":"cluster-b:db.orders","name":"orders","nodeType":"TABLE","clusterId":"cluster-b","database":"db","label":"db.orders"},{"nodeId":"cluster-b:db.shipments","name":"shipments","nodeType":"TABLE","clusterId":"cluster-b","database":"db","label":"db.shipments"}]}}' \
            --from-literal="api/v1/quality/execute.json"='{"data":[{"tableId":"cluster-b:db.orders","tableName":"orders","clusterId":"cluster-b","ruleResults":{"rule-1":true},"dimensionScores":{"COMPLETENESS":85.0},"overallScore":85.0,"checkedRows":800,"failedRows":120},{"tableId":"cluster-b:db.shipments","tableName":"shipments","clusterId":"cluster-b","ruleResults":{"rule-1":false},"dimensionScores":{"COMPLETENESS":70.0},"overallScore":70.0,"checkedRows":300,"failedRows":90}]}' \
            --dry-run=client -o yaml | kubectl --kubeconfig "${kubeconfig}" apply -f -
    fi
}

# ----------------------------------------------------------------------------
# port-forward mock API
# ----------------------------------------------------------------------------
start_port_forward() {
    local cluster="$1"
    local host_port="$2"
    local kubeconfig="/tmp/${cluster}.kubeconfig"
    log_step "port-forward ${cluster} mock-api 到 localhost:${host_port}..."

    # 后台启动 port-forward
    kubectl --kubeconfig "${kubeconfig}" -n default port-forward svc/mock-api "${host_port}:80" \
        > "/tmp/portforward-${cluster}.log" 2>&1 &
    echo $! > "/tmp/portforward-${cluster}.pid"

    # 等待端口就绪
    local i=0
    while ! curl -fsS "http://localhost:${host_port}/" >/dev/null 2>&1; do
        i=$((i + 1))
        if [ $i -ge 30 ]; then
            log_error "port-forward ${cluster} 在 30s 后未就绪"
            cat "/tmp/portforward-${cluster}.log" || true
            return 1
        fi
        sleep 1
    done
    log_ok "${cluster} mock-api 已暴露在 localhost:${host_port}"
}

stop_port_forward() {
    local cluster="$1"
    local pidfile="/tmp/portforward-${cluster}.pid"
    if [ -f "${pidfile}" ]; then
        kill "$(cat ${pidfile})" 2>/dev/null || true
        rm -f "${pidfile}"
        log_info "已停止 ${cluster} port-forward"
    fi
}

# ----------------------------------------------------------------------------
# 运行集成测试
# ----------------------------------------------------------------------------
run_tests() {
    log_step "运行集成测试 RealClusterIT..."
    cd "${MODULE_DIR}"

    # 设置 Karmada API 端点（port-forward 控制面）
    export KARMADA_API="https://localhost:${KARMADA_API_PORT}"

    # 运行测试
    set +e
    mvn test \
        -Dtest=RealClusterIT \
        -Dinfra.it=true \
        -Dspring.profiles.active=it \
        -Dfailsafe.skipAfterFailureCount=0 \
        -pl . \
        2>&1 | tee /tmp/karmada-it-test.log
    local rc=${PIPESTATUS[0]}
    set -e

    if [ ${rc} -eq 0 ]; then
        log_ok "集成测试通过"
    else
        log_error "集成测试失败（退出码 ${rc}）"
    fi
    return ${rc}
}

# ----------------------------------------------------------------------------
# 清理
# ----------------------------------------------------------------------------
cleanup() {
    log_step "清理资源..."
    stop_port_forward "${CLUSTER_A}"
    stop_port_forward "${CLUSTER_B}"

    if [ "${SKIP_CLEANUP}" = "true" ]; then
        log_warn "跳过 kind 集群清理（--skip-cleanup）"
        return 0
    fi

    for cluster in "${KARMADA_HOST_CLUSTER}" "${CLUSTER_A}" "${CLUSTER_B}"; do
        if kind get clusters 2>/dev/null | grep -q "^${cluster}$"; then
            log_info "删除 kind 集群 ${cluster}..."
            kind delete cluster --name "${cluster}" 2>/dev/null || true
        fi
    done
    rm -f /tmp/karmada-host.kubeconfig /tmp/karmada.kubeconfig \
          /tmp/cluster-a.kubeconfig /tmp/cluster-b.kubeconfig 2>/dev/null || true
    log_ok "清理完成"
}

# ----------------------------------------------------------------------------
# 主流程
# ----------------------------------------------------------------------------
main() {
    echo -e "${CYAN}============================================================${NC}"
    echo -e "${CYAN}  DataEngineBDP Karmada 联邦集成测试${NC}"
    echo -e "${CYAN}============================================================${NC}"

    check_deps || exit 1

    # 注册清理 trap
    trap cleanup EXIT

    if [ "${ONLY_TEST}" = "false" ]; then
        # 1. 创建 kind 集群
        create_kind_cluster "${KARMADA_HOST_CLUSTER}"
        create_kind_cluster "${CLUSTER_A}"
        create_kind_cluster "${CLUSTER_B}"

        # 2. 安装 Karmada 控制面
        install_karmada

        # 3. 加入成员集群
        join_member_cluster "${CLUSTER_A}"
        join_member_cluster "${CLUSTER_B}"

        # 4. 部署 mock API
        deploy_mock_api "${CLUSTER_A}" "${PORT_A}"
        deploy_mock_api "${CLUSTER_B}" "${PORT_B}"

        # 5. port-forward
        start_port_forward "${CLUSTER_A}" "${PORT_A}"
        start_port_forward "${CLUSTER_B}" "${PORT_B}"
    else
        log_warn "跳过集群创建（--only-test），假设集群已就绪"
    fi

    # 6. 运行测试
    run_tests
    local test_rc=$?

    echo ""
    echo -e "${CYAN}============================================================${NC}"
    if [ ${test_rc} -eq 0 ]; then
        echo -e "${GREEN}  集成测试结果: 通过${NC}"
    else
        echo -e "${RED}  集成测试结果: 失败${NC}"
    fi
    echo -e "${CYAN}============================================================${NC}"

    exit ${test_rc}
}

main "$@"