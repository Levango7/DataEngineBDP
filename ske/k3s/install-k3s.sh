#!/usr/bin/env bash
# 数擎云核 SKE · k3s 轻量测试集群安装脚本
# 目标: 在 WSL2 Ubuntu 中安装 k3s 单主节点（轻量测试），为 Service Mesh 控制面提供底座
#
# 用法:
#   sudo bash ske/k3s/install-k3s.sh                 # 默认安装最新 stable
#   sudo bash ske/k3s/install-k3s.sh --version v1.29.6+k3s1
#   sudo bash ske/k3s/install-k3s.sh --channel v1.29
#
# 前置:
#   - WSL2 Ubuntu 22.04+，systemd 已开启（见 ske/WSL2-QUICKSTART.md §1）
#   - root 权限（k3s 写 /usr/local/bin、/etc/rancher、/var/lib/rancher）
set -uo pipefail

# ---------- 颜色与日志 ----------
log(){ echo -e "\033[36m[k3s]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $*"; }
err(){ echo -e "\033[31m[ERR]\033[0m $*" >&2; }

# ---------- 参数解析 ----------
K3S_VERSION=""
K3S_CHANNEL=""
INSTALL_URL="https://get.k3s.io"
# k3s 默认 Flannel，Service Mesh 场景保留 Flannel 即可（Istio 用 iptables/eBPF sidecar，不依赖 CNI 实现）
K3S_EXEC_ARGS="--disable=traefik --disable=servicelb --disable=metrics-server"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)  K3S_VERSION="$2"; shift 2;;
    --channel)  K3S_CHANNEL="$2"; shift 2;;
    --url)      INSTALL_URL="$2"; shift 2;;
    --full)     K3S_EXEC_ARGS=""; shift;;   # 保留 traefik/servicelb/metrics-server
    -h|--help)
      sed -n '2,15p' "$0"; exit 0;;
    *) err "未知参数: $1"; exit 1;;
  esac
done

# ---------- 前置检查 ----------
if [[ $EUID -ne 0 ]]; then
  err "k3s 安装需要 root 权限，请用 sudo 运行"; exit 1
fi

if ! grep -qi microsoft /proc/version 2>/dev/null; then
  warn "未检测到 WSL2 环境（/proc/version 不含 microsoft）。脚本仍可运行，但推荐在 WSL2 Ubuntu 中执行。"
fi

# systemd 检查（k3s 默认以 systemd unit 运行）
if ! ps -p 1 -o comm= 2>/dev/null | grep -q systemd; then
  err "PID 1 不是 systemd。请在 WSL2 中开启 systemd（见 ske/WSL2-QUICKSTART.md §1）"; exit 1
fi

# ---------- 已安装则跳过 ----------
if command -v k3s >/dev/null 2>&1 && [[ -x /usr/local/bin/k3s ]]; then
  ok "k3s 已安装: $(k3s --version)"
  if systemctl is-active --quiet k3s; then
    ok "k3s 服务已运行"
    log "如需重装，先执行: sudo bash ske/k3s/uninstall.sh"
    exit 0
  fi
  warn "k3s 已安装但服务未运行，尝试启动..."
  systemctl start k3s || { err "k3s 启动失败"; exit 1; }
  exit 0
fi

# ---------- 关闭 swap ----------
log "关闭 swap..."
swapoff -a 2>/dev/null || true
sed -i.bak '/swap/d' /etc/fstab 2>/dev/null || true
ok "swap 已关闭"

# ---------- 载入内核模块 ----------
log "载入 br_netfilter / overlay 内核模块..."
modprobe br_netfilter 2>/dev/null || warn "br_netfilter 载入失败（可能已内建）"
modprobe overlay 2>/dev/null || warn "overlay 载入失败"
cat > /etc/modules-load.d/k3s.conf <<'EOF'
br_netfilter
overlay
EOF
ok "内核模块已配置"

# ---------- sysctl ----------
log "配置 sysctl (iptables/bridge-nf)..."
cat > /etc/sysctl.d/99-k3s.conf <<'EOF'
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF
sysctl --system >/dev/null 2>&1 || true
ok "sysctl 已应用"

# ---------- 构造安装命令 ----------
INSTALL_CMD=(curl -sfL "$INSTALL_URL")

ENV_VARS=()
if [[ -n "$K3S_VERSION" ]]; then
  ENV_VARS+=(INSTALL_K3S_VERSION="$K3S_VERSION")
  log "指定版本: $K3S_VERSION"
elif [[ -n "$K3S_CHANNEL" ]]; then
  ENV_VARS+=(INSTALL_K3S_CHANNEL="$K3S_CHANNEL")
  log "指定 channel: $K3S_CHANNEL"
fi

if [[ -n "$K3S_EXEC_ARGS" ]]; then
  ENV_VARS+=(INSTALL_K3S_EXEC="$K3S_EXEC_ARGS")
  log "禁用组件: traefik servicelb metrics-server（如需保留加 --full）"
fi

# 写入集群 CIDR / Service CIDR（与 Istio 默认不冲突）
ENV_VARS+=(
  INSTALL_K3S_EXEC="${K3S_EXEC_ARGS} --cluster-cidr=10.42.0.0/16 --service-cidr=10.43.0.0/16 --flannel-backend=vxlan"
)

# ---------- 执行安装 ----------
log "开始安装 k3s（单主节点，轻量测试）..."
log "执行: ${ENV_VARS[*]} sh -"
if ! env "${ENV_VARS[@]}" bash -c 'curl -sfL "$0" | sh -' "$INSTALL_URL"; then
  err "k3s 安装失败，请检查网络或手动执行: curl -sfL https://get.k3s.io | sh -"
  exit 1
fi

# ---------- 等待节点 Ready ----------
log "等待 k3s 节点 Ready..."
KUBE=/usr/local/bin/k3s
for i in $(seq 1 30); do
  if $KUBE kubectl get nodes >/dev/null 2>&1; then
    break
  fi
  sleep 2
done

if ! $KUBE kubectl get nodes >/dev/null 2>&1; then
  err "k3s 节点 30s 内未就绪"; exit 1
fi

# ---------- 配置 kubeconfig 给普通用户 ----------
log "配置 kubeconfig..."
KUBECONFIG_DIR="/etc/rancher/k3s"
KUBECONFIG_FILE="$KUBECONFIG_DIR/k3s.yaml"
USER_HOME_KUBE="$HOME/.kube"

if [[ -f "$KUBECONFIG_FILE" ]]; then
  # 替换默认 localhost:6443 为本机 IP，方便外部 kubectl 接入
  NODE_IP=$(hostname -I | awk '{print $1}')
  sed "s|https://127.0.0.1:6443|https://${NODE_IP}:6443|g" "$KUBECONFIG_FILE" > /tmp/k3s-kubeconfig
  chmod 600 /tmp/k3s-kubeconfig

  # 写到 root 家目录
  mkdir -p /root/.kube
  cp /tmp/k3s-kubeconfig /root/.kube/config
  chmod 600 /root/.kube/config

  # 若有 SUDO_USER，也写到该用户家目录
  if [[ -n "${SUDO_USER:-}" ]]; then
    USER_HOME=$(getent passwd "$SUDO_USER" | cut -d: -f6)
    if [[ -n "$USER_HOME" ]]; then
      mkdir -p "$USER_HOME/.kube"
      cp /tmp/k3s-kubeconfig "$USER_HOME/.kube/config"
      chown -R "$SUDO_USER" "$USER_HOME/.kube"
      chmod 600 "$USER_HOME/.kube/config"
      ok "kubeconfig 已写入 $USER_HOME/.kube/config"
    fi
  fi
  rm -f /tmp/k3s-kubeconfig
fi

# ---------- 验证 ----------
ok "k3s 安装完成"
echo
log "=== 集群信息 ==="
$KUBE --version
echo
$KUBE kubectl get nodes -o wide
echo
log "=== 控制面 Pod ==="
$KUBE kubectl get pods -A
echo
ok "后续可执行: bash ske/k3s/install-istio.sh  安装 Istio Service Mesh"