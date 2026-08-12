#!/usr/bin/env bash
# 数擎云核 SKE · k3s + Istio 一键部署脚本
# 目标: 一步完成 k3s 安装 → Istio 安装 → sidecar 注入 → mTLS 启用
#
# 用法:
#   sudo bash ske/k3s/deploy-all.sh                       # 全量部署
#   sudo bash ske/k3s/deploy-all.sh --skip-istio          # 仅装 k3s
#   sudo bash ske/k3s/deploy-all.sh --istio-profile default
#
# 前置: WSL2 Ubuntu 22.04+，systemd 已开启
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K3S_DIR="$ROOT/ske/k3s"

# ---------- 颜色与日志 ----------
log(){ echo -e "\033[36m[deploy]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $*"; }
err(){ echo -e "\033[31m[ERR]\033[0m $*" >&2; }

# ---------- 参数 ----------
SKIP_ISTIO=false
ISTIO_PROFILE="minimal"
K3S_ARGS=()
ISTIO_ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --skip-istio)        SKIP_ISTIO=true; shift;;
    --istio-profile)     ISTIO_PROFILE="$2"; shift 2;;
    --k3s-version)       K3S_ARGS+=(--version "$2"); shift 2;;
    --k3s-channel)       K3S_ARGS+=(--channel "$2"); shift 2;;
    --skip-mtls)         ISTIO_ARGS+=(--skip-mtls); shift;;
    --skip-sidecar)      ISTIO_ARGS+=(--skip-sidecar); shift;;
    -h|--help)
      sed -n '2,12p' "$0"; exit 0;;
    *) err "未知参数: $1"; exit 1;;
  esac
done

echo "============================================================"
log "数擎云核 SKE · Service Mesh 控制面一键部署"
log "k3s(1主) + Istio($ISTIO_PROFILE) + Sidecar注入 + mTLS"
echo "============================================================"
echo

# ---------- Step 1: k3s ----------
log "【Step 1/3】安装 k3s..."
bash "$K3S_DIR/install-k3s.sh" "${K3S_ARGS[@]}" || { err "k3s 安装失败，终止"; exit 1; }
echo

# ---------- Step 2: Istio ----------
if [[ "$SKIP_ISTIO" == "false" ]]; then
  log "【Step 2/3】安装 Istio + sidecar 注入 + mTLS..."
  bash "$K3S_DIR/install-istio.sh" --profile "$ISTIO_PROFILE" "${ISTIO_ARGS[@]}" || {
    err "Istio 安装失败，k3s 集群已就绪，可手动重试 install-istio.sh"
    exit 1
  }
  echo
else
  warn "跳过 Istio 安装（--skip-istio）"
fi

# ---------- Step 3: 验证 ----------
log "【Step 3/3】验证集群..."
bash "$K3S_DIR/verify-cluster.sh" || warn "验证存在告警，请检查上方输出"

echo
echo "============================================================"
ok "Service Mesh 控制面部署完成"
echo "============================================================"
echo
log "后续步骤:"
echo "  1. 确认 encaps-layer 切真实模式: K8S_MOCK_ENABLED=false"
echo "     文件: platform/encaps-layer/src/main/resources/application.yml"
echo "  2. 部署业务服务到 default namespace（已自动注入 sidecar）"
echo "  3. 全量 sidecar 就绪后切 mTLS STRICT:"
echo "     kubectl edit peerauthentication default-mtls -n istio-system  # mode: STRICT"
echo "  4. 卸载: sudo bash ske/k3s/uninstall.sh"