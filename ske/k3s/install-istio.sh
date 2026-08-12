#!/usr/bin/env bash
# 数擎云核 SKE · Istio Service Mesh 控制面安装脚本
# 目标: 在 k3s 集群上安装 Istio（minimal profile）+ 创建 istio-system namespace + 启用 sidecar 注入 + 启用 mTLS
#
# 用法:
#   bash ske/k3s/install-istio.sh                          # 默认 minimal profile
#   bash ske/k3s/install-istio.sh --profile default       # 完整 profile
#   bash ske/k3s/install-istio.sh --version 1.22.3        # 指定 Istio 版本
#   bash ske/k3s/install-istio.sh --skip-mtls             # 不启用 mTLS
#
# 前置:
#   - k3s 已安装并运行（bash ske/k3s/install-k3s.sh）
#   - kubectl 可用（k3s 自动提供 /usr/local/bin/k3s，已配置 KUBECONFIG）
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
K3S_DIR="$ROOT/ske/k3s"

# ---------- 颜色与日志 ----------
log(){ echo -e "\033[36m[istio]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $*"; }
err(){ echo -e "\033[31m[ERR]\033[0m $*" >&2; }

# ---------- 参数解析 ----------
ISTIO_PROFILE="minimal"
ISTIO_VERSION=""
SKIP_MTLS=false
SKIP_SIDECAR=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --profile)       ISTIO_PROFILE="$2"; shift 2;;
    --version)       ISTIO_VERSION="$2"; shift 2;;
    --skip-mtls)     SKIP_MTLS=true; shift;;
    --skip-sidecar)  SKIP_SIDECAR=true; shift;;
    -h|--help)
      sed -n '2,15p' "$0"; exit 0;;
    *) err "未知参数: $1"; exit 1;;
  esac
done

# ---------- kubectl 选择 ----------
if command -v kubectl >/dev/null 2>&1; then
  KUBECTL=kubectl
elif command -v k3s >/dev/null 2>&1; then
  KUBECTL="k3s kubectl"
else
  err "未找到 kubectl 或 k3s，请先运行 ske/k3s/install-k3s.sh"; exit 1
fi

log "使用 kubectl: $KUBECTL"

# ---------- 检查集群可用 ----------
if ! $KUBECTL get nodes >/dev/null 2>&1; then
  err "集群不可达，请确认 k3s 已安装并运行"; exit 1
fi

log "当前集群节点:"
$KUBECTL get nodes -o wide
echo

# ---------- 安装 istioctl ----------
install_istioctl(){
  if command -v istioctl >/dev/null 2>&1; then
    ok "istioctl 已存在: $(istioctl version --remote=false 2>/dev/null || istioctl version 2>&1 | head -1)"
    return 0
  fi

  local ver="${ISTIO_VERSION:-1.22.3}"
  local os arch url
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"
  arch="$(uname -m)"
  case "$arch" in
    x86_64|amd64) arch="amd64";;
    aarch64|arm64) arch="arm64";;
    *) err "不支持的架构: $arch"; return 1;;
  esac

  url="https://github.com/istio/istio/releases/download/${ver}/istio-${ver}-${os}-${arch}.tar.gz"
  log "下载 istioctl ${ver} (${os}-${arch})..."
  local tmpdir
  tmpdir="$(mktemp -d)"
  if ! curl -fsSL "$url" -o "$tmpdir/istio.tar.gz"; then
    err "下载失败: $url"; rm -rf "$tmpdir"; return 1
  fi
  tar -xzf "$tmpdir/istio.tar.gz" -C "$tmpdir"
  local bin="$tmpdir/istio-${ver}/bin/istioctl"
  if [[ ! -f "$bin" ]]; then
    err "解压后未找到 istioctl"; rm -rf "$tmpdir"; return 1
  fi

  if [[ $EUID -eq 0 ]]; then
    mv "$bin" /usr/local/bin/istioctl
    chmod +x /usr/local/bin/istioctl
  else
    mkdir -p "$HOME/.local/bin"
    mv "$bin" "$HOME/.local/bin/istioctl"
    chmod +x "$HOME/.local/bin/istioctl"
    warn "istioctl 安装到 $HOME/.local/bin/istioctl，请确保该路径在 PATH 中"
  fi
  rm -rf "$tmpdir"
  ok "istioctl 安装完成: $(istioctl version --remote=false 2>/dev/null | head -1)"
}

install_istioctl || exit 1

# ---------- 创建 istio-system namespace ----------
log "创建 istio-system namespace..."
if $KUBECTL get namespace istio-system >/dev/null 2>&1; then
  ok "namespace istio-system 已存在"
else
  $KUBECTL apply -f "$K3S_DIR/namespace-istio-system.yaml" || {
    err "创建 istio-system namespace 失败"; exit 1
  }
  ok "namespace istio-system 已创建（已启用 istio-injection 标签）"
fi

# ---------- 安装 Istio 控制面 ----------
log "安装 Istio 控制面（profile=$ISTIO_PROFILE）..."

if [[ "$ISTIO_PROFILE" == "minimal" ]]; then
  # 使用 Operator overlay 配置（minimal + mTLS）
  if [[ "$SKIP_MTLS" == "false" ]]; then
    log "使用带 mTLS 的 minimal overlay 配置: $K3S_DIR/istio-operator-minimal.yaml"
    istioctl install -y -f "$K3S_DIR/istio-operator-minimal.yaml" || {
      err "Istio 安装失败（overlay）"; exit 1
    }
  else
    istioctl install -y --set profile="$ISTIO_PROFILE" || {
      err "Istio 安装失败"; exit 1
    }
  fi
else
  istioctl install -y --set profile="$ISTIO_PROFILE" || {
    err "Istio 安装失败（profile=$ISTIO_PROFILE）"; exit 1
  }
fi

ok "Istio 控制面已安装"

# ---------- 等待 Istio 控制面就绪 ----------
log "等待 Istio 控制面 Pod 就绪..."
$KUBECTL wait --for=condition=Ready pod -l app=istiod -n istio-system --timeout=180s || {
  warn "istiod Pod 未在 180s 内就绪，继续执行（可稍后 kubectl get pods -n istio-system 查看）"
}

echo
log "=== istio-system Pod ==="
$KUBECTL get pods -n istio-system -o wide
echo

# ---------- 启用全局 sidecar 注入 ----------
if [[ "$SKIP_SIDECAR" == "false" ]]; then
  log "为 default namespace 启用 sidecar 自动注入..."
  $KUBECTL label namespace default istio-injection=enabled --overwrite
  ok "default namespace 已启用 istio-injection=enabled"

  # 同时为平台命名空间启用（若存在）
  for ns in platform-ops shuqing-system encaps-system; do
    if $KUBECTL get namespace "$ns" >/dev/null 2>&1; then
      $KUBECTL label namespace "$ns" istio-injection=enabled --overwrite
      ok "namespace $ns 已启用 sidecar 注入"
    fi
  done
fi

# ---------- 启用 mTLS（PERMISSIVE 过渡 → STRICT） ----------
if [[ "$SKIP_MTLS" == "false" ]]; then
  log "启用 mTLS（先 PERMISSIVE 过渡，避免破坏现有明文服务）..."
  $KUBECTL apply -f "$K3S_DIR/peer-authentication-mtls.yaml" || {
    warn "mTLS PeerAuthentication 应用失败，可稍后手动应用"
  }
  $KUBECTL apply -f "$K3S_DIR/destination-rule-mtls.yaml" || {
    warn "mTLS DestinationRule 应用失败，可稍后手动应用"
  }
  ok "mTLS 已应用（PeerAuthentication PERMISSIVE + DestinationRule ISTIO_MUTUAL）"
  warn "待全量 sidecar 就绪后，可切换为 STRICT 模式（编辑 peer-authentication-mtls.yaml 的 mode）"
fi

# ---------- 验证 ----------
echo
log "=== Istio 版本（远程） ==="
istioctl version 2>/dev/null || true
echo
log "=== Sidecar 注入状态 ==="
$KUBECTL get namespace -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.metadata.labels.istio-injection}{"\n"}{end}' | column -t 2>/dev/null || $KUBECTL get namespace --show-labels
echo
ok "Istio Service Mesh 控制面部署完成"
ok "后续可执行: bash ske/k3s/verify-cluster.sh  验证集群与 Mesh 状态"