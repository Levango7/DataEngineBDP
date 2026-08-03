#!/usr/bin/env bash
# 数擎云核 SKE · WSL2/VM 宿主准备 (在 WSL2 Ubuntu 内运行)
# 安装 containerd + kubeadm/kubelet/kubectl + cri-tools, 关闭 swap, 载入内核模块, 设 sysctl
# 前置: WSL2 Ubuntu 已安装且 /etc/wsl.conf 开启 systemd (见 WSL2-QUICKSTART.md)
set -uo pipefail
export DEBIAN_FRONTEND=noninteractive

log(){ echo -e "\033[36m[SKE-HOST]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
err(){ echo -e "\033[31m[ERR]\033[0m $*" >&2; }

# 1) 关闭 swap (kubeadm 要求)
log "关闭 swap..."
swapoff -a 2>/dev/null || true
sed -i '/ swap / s/^/#/' /etc/fstab 2>/dev/null || true

# 2) 内核模块 + sysctl (WSL2 默认缺 br_netfilter)
log "载入内核模块并设置 sysctl..."
mkdir -p /etc/modules-load.d /etc/sysctl.d
cat > /etc/modules-load.d/k8s.conf <<'EOF'
overlay
br_netfilter
EOF
modprobe overlay 2>/dev/null || true
modprobe br_netfilter 2>/dev/null || true
cat > /etc/sysctl.d/k8s.conf <<'EOF'
net.bridge.bridge-nf-call-iptables  = 1
net.bridge.bridge-nf-call-ip6tables = 1
net.ipv4.ip_forward                 = 1
EOF
sysctl --system 2>/dev/null || true

# 3) 安装 containerd
log "安装 containerd..."
apt-get update -y
apt-get install -y containerd
mkdir -p /etc/containerd
containerd config default > /etc/containerd/config.toml
sed -i 's/SystemdCgroup = false/SystemdCgroup = true/' /etc/containerd/config.toml
systemctl restart containerd && systemctl enable containerd
ok "containerd 就绪"

# 4) 安装 kubeadm/kubelet/kubectl/cri-tools (v1.30)
log "安装 kubeadm/kubelet/kubectl..."
apt-get install -y apt-transport-https ca-certificates curl gpg
mkdir -p /etc/apt/keyrings
if [ ! -f /etc/apt/keyrings/kubernetes-apt-keyring.gpg ]; then
  curl -fsSL https://pkgs.k8s.io/core:/stable:/v1.30/deb/Release.key | gpg --dearmor -o /etc/apt/keyrings/kubernetes-apt-keyring.gpg
  echo "deb [signed-by=/etc/apt/keyrings/kubernetes-apt-keyring.gpg] https://pkgs.k8s.io/core:/stable:/v1.30/deb/ /" > /etc/apt/sources.list.d/kubernetes.list
fi
apt-get update -y
apt-get install -y kubelet kubeadm kubectl cri-tools
systemctl enable --now kubelet
ok "kubeadm/kubelet/kubectl 就绪"

echo "=== 下一步 (在 WSL2 Ubuntu 内) ==="
echo "  bash ske/ske.sh tune-host"
echo "  bash ske/ske.sh up --target wsl2 --profile local"
