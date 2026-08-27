#!/usr/bin/env bash
# ============================================================
# 本地一键部署 v2.1.0-RC（kind + 核心 5 服务 + 可选 ArgoCD GitOps）
#
# 前置要求：
#   docker / kind / kubectl / helm  （WSL2 或 Linux 环境）
#   Docker Desktop 需开启 "Use WSL 2 based engine"
#   镜像来源：GHCR（build.yml 主分支构建产物）
#   私有仓库需先：kubectl create secret docker-registry harbor-secret -n dataengine ...
#
# 用法：
#   bash deploy/local-up.sh                 # kind 集群 + 核心 5 服务
#   GIT_REPO=<url> bash deploy/local-up.sh --gitops   # 额外装 ArgoCD 并注册 Application
#
# 说明：
# - 仅启用核心可部署子集（封装层/SQL网关/目录/规则引擎/NL2SQL/开放API目录），
#   重型基础设施（Doris/Flink/Spark/Trino/Kafka 等）默认关闭，
#   完整栈请用 design/deploy/values/env 按环境装配。
# - 镜像拉取失败时可用镜像站拉取后重打 tag，例如：
#   docker pull docker.1ms.run/kindest/node:v1.31.2 && docker tag ... kindest/node:v1.31.2
#   自研服务镜像本地构建后 kind load docker-image <img> --name ，并 --set image.tag=<本地tag>
# ============================================================
set -euo pipefail

CLUSTER=${CLUSTER:-dataengine-local}
NS=dataengine
UMBRELLA=design/deploy/charts/dataenginebdp-umbrella
LOCAL_VALUES=deploy/local/values-local-core.yaml

log() { echo "[$(date '\''+%H:%M:%S'\'')] $*"; }
fail() { log "FAIL: $*"; exit 1; }
pass() { log "PASS: $*"; }

for t in docker kind kubectl helm; do
  command -v "$t" >/dev/null || { echo "缺少 $t，请先安装"; exit 1; }
done

echo "== 1/6 kind 集群 =="
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" --wait 300s
kubectl config use-context "kind-$CLUSTER"
pass "集群就绪: $(kubectl config current-context)"

echo "== 2/6 命名空间 =="
kubectl get ns "$NS" >/dev/null 2>&1 || kubectl create namespace "$NS"
pass "命名空间: $NS"

echo "== 3/6 Helm 依赖更新 =="
helm dependency update "$UMBRELLA" >/dev/null
pass "依赖更新完成"

echo "== 4/6 安装核心子集（封装层/SQL网关/目录/规则引擎/NL2SQL/开放API目录） =="
# 注入本地开发 JWT 密钥（≥32 字符），catalog chart 默认值为空（fail-fast）
LOCAL_JWT="local-dev-signing-key-change-me-0123456789abcdef"
helm upgrade --install dataengine "$UMBRELLA" \
  -n "$NS" -f "$LOCAL_VALUES" \
  --set "catalog.auth.jwtSigningKey=$LOCAL_JWT" \
  --wait --timeout 15m \
  || { log "安装失败：排查镜像拉取（见文件头说明）"; exit 1; }
pass "核心子集安装完成"

echo "== 5/6 就绪状态 =="
kubectl get pods -n "$NS" -o wide

echo "== 6/6 服务端口验证（ClusterIP） =="
for svc in encaps-layer sql-gateway catalog rule-engine nl2sql open-api-catalog; do
  port=$(kubectl get svc -n "$NS" "$svc" -o jsonpath='{.spec.ports[0].port}' 2>/dev/null || echo "")
  [[ -n "$port" ]] && pass "$svc Service 端口: $port" || log "WARN: $svc Service 未找到"
done

# 可选：ArgoCD GitOps
if [[ "${1:-}" == "--gitops" ]]; then
  : "${GIT_REPO:?--gitops 需要 GIT_REPO 环境变量指向本仓库的 git url}"
  log "== ArgoCD（可选）=="
  helm repo add argo https://argoproj.github.io/argo-helm >/dev/null 2>&1 || true
  helm upgrade --install argocd argo/argo-cd -n argocd --create-namespace --wait --timeout 10m
  kubectl apply -n argocd -f - <<EOF
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: dataengine-core
  namespace: argocd
spec:
  project: default
  source:
    repoURL: "${GIT_REPO}"
    targetRevision: HEAD
    path: design/deploy/charts/dataenginebdp-umbrella
    helm:
      valueFiles:
        # 相对路径以 source.path 为基准（design/deploy/charts/dataenginebdp-umbrella），
        # 需 4 级 .. 回到仓库根再进入 deploy/local/
        - ../../../../deploy/local/values-local-core.yaml
  destination:
    server: https://kubernetes.default.svc
    namespace: ${NS}
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
EOF
  log "ArgoCD 已注册 Application（源仓库：${GIT_REPO}），初始同步由 direct-install 完成的资源接管需先卸载或接受 drift"
fi

echo
log "完成。"
log "查看 Pod:    kubectl get pods -n $NS"
log "查看 Service: kubectl get svc -n $NS"
log "端口转发测试: kubectl port-forward -n $NS svc/catalog 8080:8080  # 然后 curl http://localhost:8080/health"
log "运行冒烟测试: bash scripts/smoke-test.sh"
log "Profile 快速校验: bash scripts/profile-render-check.sh"
log "清理集群: kind delete cluster --name $CLUSTER"