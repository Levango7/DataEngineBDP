#!/usr/bin/env bash
# 数擎云核 SKE · k3s + Istio 卸载脚本
# 目标: 清理 Istio 控制面 + k3s 集群（保留 kubeconfig 可选）
#
# 用法:
#   sudo bash ske/k3s/uninstall.sh                 # 卸载 k3s（含 Istio）
#   sudo bash ske/k3s/uninstall.sh --keep-k3s      # 仅卸载 Istio，保留 k3s
#   sudo bash ske/k3s/uninstall.sh --purge-config  # 同时清理 kubeconfig
set -uo pipefail

# ---------- 颜色与日志 ----------
log(){ echo -e "\033[36m[uninstall]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $*"; }
err(){ echo -e "\033[31m[ERR]\033[0m $*" >&2; }

KEEP_K3S=false
PURGE_CONFIG=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep-k3s)       KEEP_K3S=true; shift;;
    --purge-config)   PURGE_CONFIG=true; shift;;
    -h|--help) sed -n '2,10p' "$0"; exit 0;;
    *) err "未知参数: $1"; exit 1;;
  esac
done

# ---------- kubectl 选择 ----------
if command -v kubectl >/dev/null 2>&1; then
  KUBECTL=kubectl
elif command -v k3s >/dev/null 2>&1; then
  KUBECTL="k3s kubectl"
fi

# ---------- 卸载 Istio ----------
if [[ -n "${KUBECTL:-}" ]] && $KUBECTL get namespace istio-system >/dev/null 2>&1; then
  log "卸载 Istio 控制面..."

  # 移除 namespace 的 sidecar 注入标签
  for ns in default platform-ops shuqing-system encaps-system; do
    if $KUBECTL get namespace "$ns" >/dev/null 2>&1; then
      $KUBECTL label namespace "$ns" istio-injection- 2>/dev/null || true
    fi
  done

  # istioctl uninstall（若可用）
  if command -v istioctl >/dev/null 2>&1; then
    istioctl uninstall --purge -y 2>/dev/null || {
      warn "istioctl uninstall 失败，手动删除 istio-system namespace"
      $KUBECTL delete namespace istio-system --ignore-not-found 2>/dev/null || true
    }
  else
    $KUBECTL delete namespace istio-system --ignore-not-found 2>/dev/null || true
  fi

  # 清理 Istio CRD（可选，保留不影响重装）
  warn "Istio CRD 未删除（保留可加速重装）；如需彻底清理:"
  echo "  kubectl get crd | grep istio.io | xargs kubectl delete crd"

  ok "Istio 控制面已卸载"
else
  warn "未检测到 Istio 安装，跳过"
fi

# ---------- 卸载 k3s ----------
if [[ "$KEEP_K3S" == "false" ]]; then
  if command -v k3s >/dev/null 2>&1 && [[ -x /usr/local/bin/k3s-uninstall.sh ]]; then
    log "卸载 k3s..."
    /usr/local/bin/k3s-uninstall.sh || { err "k3s 卸载失败"; exit 1; }
    ok "k3s 已卸载"
  else
    warn "未检测到 k3s 安装，跳过"
  fi
else
  ok "保留 k3s（--keep-k3s）"
fi

# ---------- 清理 kubeconfig ----------
if [[ "$PURGE_CONFIG" == "true" ]]; then
  log "清理 kubeconfig..."
  rm -f "$HOME/.kube/config" /root/.kube/config 2>/dev/null || true
  ok "kubeconfig 已清理"
fi

echo
ok "卸载完成"