#!/usr/bin/env bash
# ============================================================
# 本地一键部署（kind + 核心链路）
#
# 前置要求（本机需自备，脚本会检查）：
#   docker / kind / kubectl / helm  （WSL2 或 Linux 环境）
#
# 用法：
#   bash deploy/local-up.sh                 # kind 集群 + 核心 5 服务
#   GIT_REPO=<url> bash deploy/local-up.sh --gitops   # 额外装 ArgoCD 并注册 Application
#
# 说明：
# - 仅启用核心可部署子集（封装层/SQL网关/目录/规则引擎/NL2SQL/开放API目录），
#   重型基础设施（Doris/Flink/Spark/Trino/Kafka 等）默认关闭，
#   完整栈请用 design/deploy/values/env 按环境装配。
# - 镜像来源为 GHCR（build.yml 主分支构建产物）；私有仓库需先
#   kubectl create secret docker-registry 并在 values 里挂 imagePullSecrets。
# ============================================================
set -euo pipefail

CLUSTER=${CLUSTER:-dataengine-local}
NS=dataengine
UMBRELLA=design/deploy/charts/dataenginebdp-umbrella
LOCAL_VALUES=deploy/local/values-local-core.yaml

for t in docker kind kubectl helm; do
  command -v "$t" >/dev/null || { echo "缺少 $t，请先安装"; exit 1; }
done

echo "== 1/5 kind 集群 =="
kind get clusters | grep -qx "$CLUSTER" || kind create cluster --name "$CLUSTER" --wait 300s
kubectl config use-context "kind-$CLUSTER"

echo "== 2/5 命名空间 =="
kubectl get ns "$NS" >/dev/null 2>&1 || kubectl create namespace "$NS"

echo "== 3/5 umbrella 依赖落盘 =="
helm dependency update "$UMBRELLA" >/dev/null

echo "== 4/5 安装核心子集 =="
helm upgrade --install dataengine "$UMBRELLA" \
  -n "$NS" -f "$LOCAL_VALUES" --wait --timeout 15m \
  || { echo "安装失败：排查镜像拉取（GHCR 私有仓见文件头说明）"; exit 1; }

echo "== 5/5 就绪状态 =="
kubectl get pods -n "$NS"

if [[ "${1:-}" == "--gitops" ]]; then
  : "${GIT_REPO:?--gitops 需要 GIT_REPO 环境变量指向本仓库的 git url}"
  echo "== ArgoCD（可选）=="
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
        - ../../deploy/local/values-local-core.yaml
  destination:
    server: https://kubernetes.default.svc
    namespace: ${NS}
  syncPolicy:
    automated:
      prune: true
      selfHeal: true
EOF
  echo "ArgoCD 已注册 Application（源仓库：${GIT_REPO}），初始同步由 direct-install 完成的资源接管需先卸载或接受 drift"
fi

echo "完成。访问入口查看：kubectl get svc -n ${NS}"
