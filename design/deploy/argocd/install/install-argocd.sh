#!/usr/bin/env bash
# 数据引擎大数据平台 · ArgoCD 安装脚本
# 目标：在 k3s 集群上安装 ArgoCD stable（manifest 方式）
#
# 用法：
#   bash design/deploy/argocd/install/install-argocd.sh                # 默认 stable
#   bash design/deploy/argocd/install/install-argocd.sh --version v2.11.0
#
# 前置：k3s 集群已就绪，kubectl 可用
set -uo pipefail

# ---------- 日志 ----------
log(){ echo -e "\033[36m[argocd]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $*"; }
err(){ echo -e "\033[31m[ERR]\033[0m $*" >&2; }

# ---------- 参数 ----------
ARGOCD_VERSION="stable"
MANIFEST_URL="https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      ARGOCD_VERSION="$2"
      MANIFEST_URL="https://raw.githubusercontent.com/argoproj/argo-cd/${ARGOCD_VERSION}/manifests/install.yaml"
      shift 2;;
    --url)
      MANIFEST_URL="$2"
      shift 2;;
    -h|--help)
      sed -n '2,12p' "$0"; exit 0;;
    *) err "未知参数: $1"; exit 1;;
  esac
done

# ---------- 前置检查 ----------
if ! command -v kubectl >/dev/null 2>&1; then
  err "kubectl 未找到，请先配置 k3s kubeconfig"; exit 1
fi

if ! kubectl get nodes >/dev/null 2>&1; then
  err "无法连接集群，请检查 kubeconfig"; exit 1
fi

# ---------- Step 1: namespace ----------
log "【Step 1/4】创建 argocd namespace..."
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
kubectl apply -f "$SCRIPT_DIR/namespace.yaml"
ok "namespace argocd 已创建"

# ---------- Step 2: 安装 ArgoCD manifest ----------
log "【Step 2/4】安装 ArgoCD（${ARGOCD_VERSION}）..."
log "manifest URL: $MANIFEST_URL"

if ! kubectl apply -n argocd -f "$MANIFEST_URL"; then
  err "ArgoCD manifest 安装失败"; exit 1
fi
ok "ArgoCD manifest 已应用"

# ---------- Step 3: 等待就绪 ----------
log "【Step 3/4】等待 ArgoCD Pod 就绪（最多 180s）..."

# 等待 CRD 注册
for i in $(seq 1 30); do
  if kubectl get crd applications.argoproj.io >/dev/null 2>&1; then
    ok "ArgoCD CRD 已注册"
    break
  fi
  sleep 2
done

# 等待关键 Pod
if ! kubectl wait --for=condition=Ready pod -l app.kubernetes.io/name=argocd-server \
     -n argocd --timeout=180s 2>/dev/null; then
  warn "argocd-server Pod 180s 内未全部就绪，继续..."
fi

if ! kubectl wait --for=condition=Ready pod -l app.kubernetes.io/name=argocd-repo-server \
     -n argocd --timeout=180s 2>/dev/null; then
  warn "argocd-repo-server Pod 180s 内未全部就绪，继续..."
fi

# ---------- Step 4: 暴露 UI（NodePort） ----------
log "【Step 4/4】配置 argocd-server 为 NodePort（方便 k3s 访问）..."
kubectl patch svc argocd-server -n argocd -p '{"spec":{"type":"NodePort"}}' 2>/dev/null || \
  warn "patch NodePort 失败（可能已是 NodePort）"

# ---------- 验证 ----------
echo
log "=== ArgoCD 部署状态 ==="
kubectl get pods -n argocd
echo
log "=== ArgoCD Service ==="
kubectl get svc -n argocd
echo
log "=== 获取 admin 密码 ==="
ADMIN_PASS=$(kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" 2>/dev/null | base64 -d 2>/dev/null)
if [[ -n "$ADMIN_PASS" ]]; then
  ok "admin 密码: $ADMIN_PASS"
else
  warn "尚未获取到 admin 密码，稍后执行: kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d"
fi

echo
echo "============================================================"
ok "ArgoCD 安装完成"
echo "============================================================"
log "后续步骤:"
echo "  1. 配置仓库凭证: kubectl apply -f design/deploy/argocd/repositories/repo-credentials.yaml"
echo "  2. 创建 AppProject: kubectl apply -f design/deploy/argocd/projects/"
echo "  3. 部署 Application: kubectl apply -f design/deploy/argocd/applications/"
echo "  4. 访问 UI: kubectl port-forward svc/argocd-server -n argocd 8080:443"
echo "     浏览器: https://localhost:8080  用户名: admin  密码: 上方输出"