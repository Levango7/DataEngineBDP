#!/usr/bin/env bash
# ============================================================
# 本地多集群 Karmada 部署（1 控制面 + 2 工作集群）
# 用于 v2.1.0-RC 联邦调度、跨集群资源分发验证
# 前置：kind、kubectl、helm、karmadactl（自动下载）
# 内存建议：16GB+，磁盘 20GB+
# ============================================================
set -euo pipefail

CONTROL_CLUSTER="karmada-control"
MEMBER_CLUSTERS=("member1" "member2")
KARMADA_VERSION="v1.11.0"

log() { echo "[$(date '\''+%H:%M:%S'\'')] $*"; }
fail() { log "FAIL: $*"; exit 1; }
pass() { log "PASS: $*"; }

# 1. 检查/安装 karmadactl
if ! command -v karmadactl >/dev/null 2>&1; then
  log "下载 karmadactl $KARMADA_VERSION..."
  curl -sSL "https://github.com/karmada-io/karmada/releases/download/${KARMADA_VERSION}/karmadactl-${KARMADA_VERSION}-linux-amd64.tar.gz" | tar xz -C /tmp
  sudo mv /tmp/karmadactl /usr/local/bin/
  pass "karmadactl 安装完成"
fi

# 2. 创建控制面集群
log "创建控制面集群: $CONTROL_CLUSTER"
kind get clusters | grep -qx "$CONTROL_CLUSTER" || kind create cluster --name "$CONTROL_CLUSTER" --config - <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
nodes:
- role: control-plane
  extraPortMappings:
  - containerPort: 30000
    hostPort: 30000
  - containerPort: 30001
    hostPort: 30001
EOF
kubectl config use-context "kind-$CONTROL_CLUSTER"
pass "控制面集群就绪"

# 3. 安装 Karmada 控制面
log "安装 Karmada 控制面..."
helm repo add karmada https://karmada-io.github.io/karmada >/dev/null 2>&1 || true
helm repo update >/dev/null
helm upgrade --install karmada karmada/karmada \
  -n karmada-system --create-namespace \
  --version "$KARMADA_VERSION" \
  --set karmada.apiserver.replicas=1 \
  --set karmada.controllerManager.replicas=1 \
  --set karmada.scheduler.replicas=1 \
  --set karmada.webhook.replicas=1 \
  --set karmada.apiserver.serviceType=NodePort \
  --set karmada.apiserver.nodePort=30000 \
  --wait --timeout 10m
pass "Karmada 控制面安装完成"

# 4. 创建工作集群并加入
for member in "${MEMBER_CLUSTERS[@]}"; do
  log "创建工作集群: $member"
  kind get clusters | grep -qx "$member" || kind create cluster --name "$member"
  
  log "注册工作集群 $member 到 Karmada..."
  karmadactl join "$member" \
    --cluster-kubeconfig="$HOME/.kube/config" \
    --cluster-context="kind-$member" \
    --karmada-context="kind-$CONTROL_CLUSTER" \
    --cluster-namespace="karmada-cluster" \
    --cluster-name="$member"
  pass "工作集群 $member 加入完成"
done

# 5. 验证
log "验证多集群状态..."
kubectl config use-context "kind-$CONTROL_CLUSTER"
kubectl get clusters -n karmada-system -o wide
kubectl get nodes --all-namespaces -l cluster.karmada.io/member-name

# 6. 部署测试应用（跨集群 Deployment）
log "部署测试应用（PropagationPolicy 跨集群分发）..."
kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: Deployment
metadata:
  name: nginx-test
  namespace: default
spec:
  replicas: 4
  selector:
    matchLabels:
      app: nginx-test
  template:
    metadata:
      labels:
        app: nginx-test
    spec:
      containers:
      - name: nginx
        image: nginx:alpine
        ports:
        - containerPort: 80
---
apiVersion: policy.karmada.io/v1alpha1
kind: PropagationPolicy
metadata:
  name: nginx-test-pp
  namespace: default
spec:
  resourceSelectors:
  - apiVersion: apps/v1
    kind: Deployment
    name: nginx-test
  placement:
    clusterAffinity:
      clusterNames:
      - member1
      - member2
    replicaScheduling: Weighted
    replicaSchedulingType: Divided
    weightPreference:
      staticWeightList:
      - targetCluster:
          clusterNames:
          - member1
        weight: 1
      - targetCluster:
          clusterNames:
          - member2
        weight: 1
EOF

# 7. 等待分发完成
log "等待分发完成..."
sleep 10
kubectl get deployment nginx-test -n default -o wide
kubectl get resourcebindings -n default
for member in "${MEMBER_CLUSTERS[@]}"; do
  log "检查 $member 集群 Pod..."
  kubectl --context "kind-$member" get pods -n default -l app=nginx-test
done

pass "=== 多集群 Karmada 部署验证完成 ==="
log "清理命令："
log "  kind delete cluster --name $CONTROL_CLUSTER"
for member in "${MEMBER_CLUSTERS[@]}"; do
  log "  kind delete cluster --name $member"
done