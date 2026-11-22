#!/usr/bin/env bash
# 数据引擎大数据平台 · 在 SKE 之上部署平台运行时
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

# ---- 3. 本地存储底座 (仅 local Profile: 起 RustFS) ----
# 实现替换（台账 #17）：MinIO 社区版镜像 2026-09-11 被上游整体删除（runner 实测
# pull access denied）⇒ 改用 RustFS 1.0.6（Apache-2.0，上游仓 rustfs/rustfs @ 1.0.6，
# runner 实拉 PULL_OK）。容器名/endpoint 仍叫 minio：业务侧按该名字寻址。
# 凭据 env 必须是 RUSTFS_ACCESS_KEY / RUSTFS_SECRET_KEY（上游 cli.rs:1551/:1559
# 的 clap env 绑定 + entrypoint.sh:181/:182；MINIO_ROOT_* 上游不读）。
# 建桶客户端换 amazon/aws-cli（minio/mc 已断供；botocore 对自定义 --endpoint-url
# 默认 path-style ⇒ 直连 S3 兼容端点）。
if [ "$PROFILE" = "local" ]; then
  if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
    # Docker Desktop 可达 (kind 模式): 宿主起 RustFS
    log "local Profile: 启动本地对象存储 (RustFS, Docker)..."
    if ! docker ps --format '{{.Names}}' 2>/dev/null | grep -q '^minio$'; then
      docker run -d --name minio -p 9000:9000 -p 9001:9001 \
        -e RUSTFS_ACCESS_KEY=minio -e RUSTFS_SECRET_KEY=minio123 \
        -e RUSTFS_VOLUMES=/data \
        -e RUSTFS_ADDRESS=0.0.0.0:9000 \
        -e RUSTFS_CONSOLE_ADDRESS=0.0.0.0:9001 \
        rustfs/rustfs:1.0.6 2>&1 | tail -1 || warn "RustFS 启动失败(网络/镜像?)"
    fi
    docker run --rm --network host \
      -e AWS_ACCESS_KEY_ID=minio -e AWS_SECRET_ACCESS_KEY=minio123 -e AWS_DEFAULT_REGION=us-east-1 \
      amazon/aws-cli:2.17.23 \
      s3api create-bucket --endpoint-url http://localhost:9000 --bucket "$MINIO_BUCKET" >/dev/null 2>&1 || true
    MINIO_ENDPOINT="http://minio.local:9000"
  else
    # 无 docker 可达 (WSL2 真 kubeadm): 对象存储跑进集群内
    log "local Profile: 以 in-cluster 方式部署 RustFS (WSL2/VM 无宿主 docker)..."
    kubectl apply -f "$ROOT/platform/minio-incluster.yaml"
    MINIO_ENDPOINT="http://minio.platform-ops:9000"
  fi
  # 落 k8s secret 供封装层/统一存储读取
  kubectl -n "$WS" create secret generic lakehouse-creds \
    --from-literal=accessKey=minio --from-literal=secretKey=minio123 \
    --from-literal=endpoint="$MINIO_ENDPOINT" --dry-run=client -o yaml | kubectl apply -f -
  ok "对象存储就绪 (RustFS): $MINIO_ENDPOINT  bucket=$MINIO_BUCKET (secret 已注入 $WS)"
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
