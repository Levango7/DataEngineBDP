#!/usr/bin/env bash
# 数据引擎大数据平台 · ArgoCD 部署验证脚本
# 目标：验证 ArgoCD 安装、Project、Application、UI 可访问性
#
# 用法：
#   bash design/deploy/argocd/verify/verify-argocd.sh
#   bash design/deploy/argocd/verify/verify-argocd.sh --port-forward  # 启动 port-forward
#
# 前置：kubectl 已配置，ArgoCD 已安装
set -uo pipefail

# ---------- 日志 ----------
log(){ echo -e "\033[36m[verify]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $*"; }
err(){ echo -e "\033[31m[ERR]\033[0m $*" >&2; }

PORT_FORWARD=false
[[ "${1:-}" == "--port-forward" ]] && PORT_FORWARD=true

# ---------- 前置检查 ----------
if ! command -v kubectl >/dev/null 2>&1; then
  err "kubectl 未找到"; exit 1
fi

if ! kubectl get nodes >/dev/null 2>&1; then
  err "无法连接集群"; exit 1
fi

PASS=0
FAIL=0
WARN_COUNT=0

check() {
  local desc="$1"
  local result="$2"
  local detail="${3:-}"
  if [[ "$result" == "pass" ]]; then
    ok "$desc"
    [[ -n "$detail" ]] && log "  $detail"
    PASS=$((PASS+1))
  elif [[ "$result" == "warn" ]]; then
    warn "$desc"
    [[ -n "$detail" ]] && log "  $detail"
    WARN_COUNT=$((WARN_COUNT+1))
  else
    err "$desc"
    [[ -n "$detail" ]] && log "  $detail"
    FAIL=$((FAIL+1))
  fi
}

echo "============================================================"
log "数据引擎大数据平台 · ArgoCD 部署验证"
echo "============================================================"
echo

# ---------- 1. namespace ----------
log "【1/7】检查 argocd namespace..."
if kubectl get namespace argocd >/dev/null 2>&1; then
  INJECTION=$(kubectl get namespace argocd -o jsonpath='{.metadata.labels.istio-injection}' 2>/dev/null)
  check "argocd namespace 存在" "pass" "istio-injection=$INJECTION"
else
  check "argocd namespace 存在" "fail"
fi
echo

# ---------- 2. ArgoCD Pod ----------
log "【2/7】检查 ArgoCD Pod 状态..."
POD_COUNT=$(kubectl get pods -n argocd --no-headers 2>/dev/null | wc -l)
READY_PODS=$(kubectl get pods -n argocd --no-headers 2>/dev/null | awk '{print $2}' | grep -c '^[0-9]*/\1$' || echo 0)
ALL_READY=$(kubectl get pods -n argocd --no-headers 2>/dev/null | awk '{print $2}' | grep -v '^[0-9]*/\1$' | head -1)

if [[ "$POD_COUNT" -gt 0 ]] && [[ -z "$ALL_READY" ]]; then
  check "ArgoCD Pod 全部就绪" "pass" "$POD_COUNT 个 Pod Running"
else
  check "ArgoCD Pod 全部就绪" "fail" "Pod 数: $POD_COUNT, 未就绪: $ALL_READY"
fi

# 关键组件
for comp in argocd-server argocd-repo-server argocd-application-controller argocd-redis; do
  if kubectl get deploy "$comp" -n argocd >/dev/null 2>&1; then
    READY=$(kubectl get deploy "$comp" -n argocd -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
    REPLICAS=$(kubectl get deploy "$comp" -n argocd -o jsonpath='{.spec.replicas}' 2>/dev/null)
    if [[ "$READY" == "$REPLICAS" ]] && [[ -n "$READY" ]]; then
      check "  $comp" "pass" "readyReplicas=$READY/$REPLICAS"
    else
      check "  $comp" "warn" "readyReplicas=${READY:-0}/$REPLICAS"
    fi
  else
    check "  $comp" "warn" "Deployment 不存在"
  fi
done
echo

# ---------- 3. CRD ----------
log "【3/7】检查 ArgoCD CRD..."
for crd in applications.argoproj.io appprojects.argoproj.io applicationsets.argoproj.io; do
  if kubectl get crd "$crd" >/dev/null 2>&1; then
    check "CRD $crd" "pass"
  else
    check "CRD $crd" "fail"
  fi
done
echo

# ---------- 4. AppProject ----------
log "【4/7】检查 AppProject..."
for proj in platform-dev platform-staging platform-prod; do
  if kubectl get appproject "$proj" -n argocd >/dev/null 2>&1; then
    check "AppProject $proj" "pass"
  else
    check "AppProject $proj" "warn" "未创建（需 kubectl apply -f projects/）"
  fi
done
echo

# ---------- 5. Application ----------
log "【5/7】检查 Application..."
for app in root-dev root-staging root-prod; do
  if kubectl get application "$app" -n argocd >/dev/null 2>&1; then
    SYNC=$(kubectl get application "$app" -n argocd -o jsonpath='{.status.sync.status}' 2>/dev/null)
    HEALTH=$(kubectl get application "$app" -n argocd -o jsonpath='{.status.health.status}' 2>/dev/null)
    check "Application $app" "pass" "Sync=$SYNC Health=$HEALTH"
  else
    check "Application $app" "warn" "未创建（需 kubectl apply -f applications/）"
  fi
done
echo

# ---------- 6. UI 可访问 ----------
log "【6/7】检查 ArgoCD UI 可访问性..."
SVC_TYPE=$(kubectl get svc argocd-server -n argocd -o jsonpath='{.spec.type}' 2>/dev/null)
if [[ -n "$SVC_TYPE" ]]; then
  check "argocd-server Service" "pass" "type=$SVC_TYPE"

  if [[ "$SVC_TYPE" == "NodePort" ]]; then
    NODE_PORT=$(kubectl get svc argocd-server -n argocd -o jsonpath='{.spec.ports[?(@.name=="https")].nodePort}' 2>/dev/null)
    NODE_IP=$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null)
    check "NodePort 访问" "pass" "https://$NODE_IP:$NODE_PORT"
  elif [[ "$SVC_TYPE" == "ClusterIP" ]]; then
    check "ClusterIP 访问" "warn" "需 port-forward: kubectl port-forward svc/argocd-server -n argocd 8080:443"
  fi
else
  check "argocd-server Service" "fail"
fi

# admin 密码
ADMIN_PASS=$(kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" 2>/dev/null | base64 -d 2>/dev/null)
if [[ -n "$ADMIN_PASS" ]]; then
  check "admin 密码可获取" "pass" "密码: $ADMIN_PASS"
else
  check "admin 密码可获取" "warn"
fi
echo

# ---------- 7. Git 仓库连通 ----------
log "【7/7】检查 Git 仓库凭证..."
if kubectl get secret repo-levango7-dataenginebdp -n argocd >/dev/null 2>&1; then
  REPO_URL=$(kubectl get secret repo-levango7-dataenginebdp -n argocd \
    -o jsonpath='{.data.url}' 2>/dev/null | base64 -d 2>/dev/null)
  check "Git 仓库凭证" "pass" "url=$REPO_URL"
else
  check "Git 仓库凭证" "warn" "未创建（需 kubectl apply -f repositories/）"
fi
echo

# ---------- port-forward ----------
if [[ "$PORT_FORWARD" == "true" ]]; then
  log "启动 port-forward（Ctrl+C 退出）..."
  log "浏览器访问: https://localhost:8080"
  log "用户名: admin  密码: $ADMIN_PASS"
  kubectl port-forward svc/argocd-server -n argocd 8080:443
fi

# ---------- 汇总 ----------
echo "============================================================"
log "验证汇总"
echo "============================================================"
ok "通过: $PASS"
[[ "$WARN_COUNT" -gt 0 ]] && warn "告警: $WARN_COUNT"
[[ "$FAIL" -gt 0 ]] && err "失败: $FAIL"
echo

if [[ "$FAIL" -gt 0 ]]; then
  err "存在失败项，请检查上方输出"
  exit 1
elif [[ "$WARN_COUNT" -gt 0 ]]; then
  warn "存在告警项（可能是未 apply 的配置，属正常）"
  exit 0
else
  ok "ArgoCD 部署验证全部通过"
  exit 0
fi