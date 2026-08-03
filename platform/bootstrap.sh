#!/usr/bin/env bash
# 数擎大数据平台 · 在 SKE 之上部署平台运行时
# 职责: 建平台运维命名空间 + 演示租户(封装层落地) + 本地存储(MinIO, local Profile)
# 说明: 引擎/治理/智能层组件按各详细设计文档经 Helm/Operator 部署; 此处完成"封装层"骨架与存储底座
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROFILE="${DQCTL_PROFILE:-local}"
WS="ws-demo"            # 演示租户工作空间 (封装层→Namespace)
MINIO_BUCKET="lakehouse"

log(){ echo -e "\033[36m[PLATFORM]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $*"; }

require(){ command -v "$1" >/dev/null 2>&1 || { echo -e "\033[31m[ERR]\033[0m 缺少依赖: $1" >&2; return 1; }; }

require kubectl || exit 1

# ---- 1. 平台运维命名空间 (客户不可见) ----
log "创建 platform-ops 命名空间 (平台侧, 客户不可见)..."
kubectl create namespace platform-ops --dry-run=client -o yaml | kubectl apply -f -

# ---- 2. 演示租户: 封装层将"工作空间"翻译为 Namespace+Quota+deny-all ----
log "创建演示租户工作空间 $WS (封装层: Namespace+ResourceQuota+deny-all NetworkPolicy)..."
kubectl create namespace "$WS" --dry-run=client -o yaml | kubectl apply -f -

cat <<EOF | kubectl apply -f -
apiVersion: v1
kind: ResourceQuota
metadata:
  name: ws-demo-quota
  namespace: ${WS}
spec:
  hard:
    requests.cpu: "8"
    requests.memory: 16Gi
    limits.cpu: "16"
    limits.memory: 32Gi
    pods: "50"
    persistentvolumeclaims: "20"
---
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny-all
  namespace: ${WS}
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
  # 默认拒绝一切; 后续由封装层按"项目/服务"显式放行 (L1.6 R8 隔离)
---
apiVersion: v1
kind: ServiceAccount
metadata:
  name: ws-demo-runtime
  namespace: ${WS}
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: ws-demo-runtime
  namespace: ${WS}
rules:
  - apiGroups: [""]
    resources: [pods, services, configmaps, secrets, persistentvolumeclaims]
    verbs: [get, list, watch, create, update, delete]
  - apiGroups: ["batch"]
    resources: [jobs]
    verbs: [get, list, watch, create, delete]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: ws-demo-runtime
  namespace: ${WS}
subjects:
  - kind: ServiceAccount
    name: ws-demo-runtime
    namespace: ${WS}
roleRef:
  kind: Role
  name: ws-demo-runtime
  apiGroup: rbac.authorization.k8s.io
EOF
ok "租户 $WS 封装骨架就绪 (Namespace+Quota+deny-all+RBAC)"

# ---- 3. 本地存储底座 (仅 local Profile: 起 MinIO) ----
if [ "$PROFILE" = "local" ]; then
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    # Docker Desktop 可达 (kind 模式): 宿主起 MinIO
    log "local Profile: 启动本地 MinIO 存储驱动 (MinIODriver, Docker)..."
    if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^minio$'; then
      docker run -d --name minio -p 9000:9000 -p 9001:9001 \
        -e MINIO_ROOT_USER=minio -e MINIO_ROOT_PASSWORD=minio123 \
        minio/minio server /data --console-address ":9001" 2>&1 | tail -1 || warn "MinIO 启动失败(网络/镜像?)"
    fi
    docker run --rm --network host minio/mc alias set local http://localhost:9000 minio minio123 >/dev/null 2>&1 || true
    docker run --rm --network host minio/mc mb -p local/$MINIO_BUCKET >/dev/null 2>&1 || true
    MINIO_ENDPOINT="http://minio.local:9000"
  else
    # 无 docker 可达 (WSL2 真 kubeadm): MinIO 跑进集群内
    log "local Profile: 以 in-cluster 方式部署 MinIO (WSL2/VM 无宿主 docker)..."
    kubectl apply -f "$ROOT/platform/minio-incluster.yaml"
    MINIO_ENDPOINT="http://minio.platform-ops:9000"
  fi
  # 落 k8s secret 供封装层/统一存储读取
  kubectl -n "$WS" create secret generic lakehouse-creds \
    --from-literal=accessKey=minio --from-literal=secretKey=minio123 \
    --from-literal=endpoint="$MINIO_ENDPOINT" --dry-run=client -o yaml | kubectl apply -f -
  ok "MinIO 就绪: $MINIO_ENDPOINT  bucket=$MINIO_BUCKET (secret 已注入 $WS)"
fi

# ---- 4. 提示引擎/治理/智能层部署 ----
log "封装层骨架 + 存储底座完成. 引擎/治理/智能层按设计文档经 Helm/Operator 部署:"
echo "    L2.1 统一存储   -> deploy/values-base.yaml + ske/profiles/$PROFILE.yaml"
echo "    L2.7 统一SQL    -> 统一SQL网关详细设计 v0.1"
echo "    L3.* 治理中台   -> 治理中台/资产目录/安全脱敏 详细设计 v0.1"
echo "    L4.5 智能数据层 -> 智能数据层详细设计 v0.1"
echo "    L5   运营后台   -> 运营后台实现落地 v0.1 (deploy/services/operations)"
echo
ok "平台运行时引导完成 (Profile=$PROFILE). 下一步: bash examples/run-demo.sh"
