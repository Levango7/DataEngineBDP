#!/usr/bin/env bash
# 数擎云核 SKE · 一键 bootstrap
# 子命令: up | down | status | tune-host | build-image
#   up   --profile <local|xinchuang|onprem|publiccloud|privatecloud> --mode <dev|prod> [--target kind|wsl2] [--name NAME]
#   --target kind : kind 容器 + 自定义 SKE 节点镜像 + Cilium eBPF (跑在 Docker Desktop 的 WSL2 后端)
#   --target wsl2 : 独立 WSL2 Ubuntu / VM / 裸金属上跑真实 kubeadm (最忠实于 SKE 发行版身份)
#   dev 模式(kind): 功能验证与演示
#   prod 模式: 自有 VM 镜像/裸金属 kubeadm
#
# 前置(你的笔记本):
#   kind 目标: Docker Desktop 已启动; 含 kubectl
#   wsl2 目标: 已装 WSL2 Ubuntu 且 systemd 开启; 先跑 ske/wsl2/setup-host.sh
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKE_DIR="$ROOT/ske"
PROFILE="local"
MODE="dev"
TARGET="kind"          # kind=Docker Desktop(WSL2后端) / wsl2=独立 WSL2 Ubuntu 真 kubeadm
CLUSTER="shuqing-ske"
NODE_IMG="${SKE_NODE_IMAGE:-shuqing-ske:dev}"
TUNING_IMG="${SKE_TUNING_IMAGE:-debian:bookworm-slim}"
KIND_BIN="kind"
HELM_BIN="helm"

log(){ echo -e "\033[36m[SKE]\033[0m $*"; }
ok(){ echo -e "\033[32m[OK]\033[0m $*"; }
warn(){ echo -e "\033[33m[WARN]\033[0m $*"; }
err(){ echo -e "\033[31m[ERR]\033[0m $*" >&2; }

require(){ command -v "$1" >/dev/null 2>&1 || { err "缺少依赖: $1"; return 1; }; }

# ---------- 安装 kind (若缺失) ----------
ensure_kind(){
  if command -v "$KIND_BIN" >/dev/null 2>&1; then ok "kind 已存在: $($KIND_BIN --version 2>&1|head -1)"; return 0; fi
  warn "未检测到 kind, 尝试自动下载 (需网络)..."
  local v="v0.23.0" os arch
  os="$(uname -s | tr '[:upper:]' '[:lower:]')"; arch="amd64"
  local url="https://github.com/kubernetes-sigs/kind/releases/download/${v}/kind-${os}-${arch}"
  if curl -fsSL "$url" -o /usr/local/bin/kind 2>/dev/null && chmod +x /usr/local/bin/kind; then
    ok "kind 已安装: $(/usr/local/bin/kind --version 2>&1|head -1)"
  else
    err "kind 自动下载失败. 请手动安装: go install sigs.k8s.io/kind@latest 或见 https://kind.sigs.k8s.io"; return 1
  fi
}

# ---------- 构建/确认节点镜像 ----------
ensure_node_image(){
  if docker image inspect "$NODE_IMG" >/dev/null 2>&1; then ok "节点镜像已存在: $NODE_IMG"; return 0; fi
  warn "节点镜像缺失, 开始构建 (kind build node-image)..."
  bash "$SKE_DIR/node-image/build.sh" || return 1
}

# ---------- 生成 kind 集群配置 (内嵌 SKE 深度调优) ----------
gen_kind_config(){
  local out="$1"
  cat > "$out" <<EOF
kind: Cluster
apiVersion: kind.x-k8s.io/v1alpha4
name: ${CLUSTER}
# 禁用默认 CNI: Cilium eBPF 接管数据面
disableDefaultCNI: true
# 使用 SKE 自定义节点镜像(烘焙 kubelet/scheduler 调优)
nodes:
  - role: control-plane
    image: ${NODE_IMG}
    kubeadmConfigDir: /etc/kubernetes
    extraPortMappings:
      - containerPort: 30080
        hostPort: 30080
        protocol: TCP
      - containerPort: 30090
        hostPort: 30090
        protocol: TCP
      - containerPort: 30888
        hostPort: 30888
        protocol: TCP
# kubelet 使用烘焙的 SKE 配置
nodeRegistration:
  kubeletExtraArgs:
    config: /etc/kubernetes/kubelet-config.yaml
    container-runtime-endpoint: unix:///run/containerd/containerd.sock
# 深度定制 kubeadm 集群配置 (对齐 manifests/kubeadm-config.yaml)
clusterConfiguration:
  kubernetesVersion: v1.30.0
  networking:
    podSubnet: 10.244.0.0/16
    serviceSubnet: 10.96.0.0/12
    dnsDomain: cluster.local
  # 禁用 kube-proxy: Cilium 完全接管
  kubeProxy:
    mode: "none"
  apiServer:
    certSANs: [localhost, 127.0.0.1]
    extraArgs:
      max-requests-inflight: "3000"
      max-mutating-requests-inflight: "2000"
      default-watch-cache-size: "1000"
  scheduler:
    extraArgs:
      config: /etc/kubernetes/scheduler-policy.yaml
  etcd:
    local:
      extraArgs:
        quota-backend-bytes: "8589934592"
EOF
  ok "kind 配置已生成: $out"
}

# ---------- 安装 Cilium (eBPF) ----------
install_cilium(){
  log "安装 Cilium (eBPF 数据面)..."
  if command -v cilium >/dev/null 2>&1; then
    cilium install --values "$SKE_DIR/manifests/cilium-values.yaml" || warn "cilium install 失败, 请检查网络/镜像"
  elif command -v "$HELM_BIN" >/dev/null 2>&1; then
    "$HELM_BIN" repo add cilium https://helm.cilium.io/ 2>/dev/null || true
    "$HELM_BIN" repo update cilium 2>/dev/null || true
    "$HELM_BIN" install cilium cilium/cilium -n kube-system -f "$SKE_DIR/manifests/cilium-values.yaml" || warn "helm 安装 Cilium 失败"
  else
    warn "未找到 cilium-cli 或 helm, 跳过 Cilium 安装. 请手动: cilium install --values $SKE_DIR/manifests/cilium-values.yaml"
  fi
}

# ---------- 应用节点调优 DaemonSet ----------
apply_tuning(){
  log "应用节点级调优 DaemonSet..."
  local tmp; tmp="$(mktemp)"
  sed "s#{{SKE_TUNING_IMAGE}}#${TUNING_IMG}#" "$SKE_DIR/manifests/tuning-daemonset.yaml" > "$tmp"
  kubectl apply -f "$tmp" && ok "tuning-daemonset 已应用" || warn "tuning-daemonset 应用失败"
  rm -f "$tmp"
}

# ---------- up ----------
up(){
  if [ "$TARGET" = "wsl2" ]; then up_wsl2; return $?; fi
  require docker || return 1
  require kubectl || return 1
  ensure_kind || return 1
  ensure_node_image || return 1

  if "$KIND_BIN" get clusters 2>/dev/null | grep -q "^${CLUSTER}$"; then
    warn "集群 ${CLUSTER} 已存在, 跳过创建 (先 ske.sh down 再 up 可重建)"
  else
    local cfg; cfg="$(mktemp -d)/kind.yaml"
    gen_kind_config "$cfg"
    log "创建 SKE 集群 (mode=$MODE, profile=$PROFILE)..."
    "$KIND_BIN" create cluster --config "$cfg" --wait 120s || { err "集群创建失败"; return 1; }
  fi

  export KUBECONFIG="$HOME/.kube/config"
  kubectl cluster-info --context "kind-${CLUSTER}" 2>/dev/null || true

  install_cilium
  apply_tuning

  # 标签: 数据面节点 (dev 单节点即承载)
  kubectl label node "${CLUSTER}-control-plane" ske.io/role=data --overwrite 2>/dev/null || true
  kubectl label node "${CLUSTER}-control-plane" topology.kubernetes.io/numa=0 --overwrite 2>/dev/null || true

  ok "SKE 集群就绪 (mode=$MODE). 下一步: bash platform/bootstrap.sh --profile $PROFILE"
  echo "   kubectl 上下文: kind-${CLUSTER}"
}

# ---------- up_wsl2 (真实 kubeadm, 独立 WSL2 Ubuntu / VM / 裸金属) ----------
up_wsl2(){
  case "$(uname -s)" in
    Linux) : ;;
    *) err "target=wsl2 仅支持 Linux 宿主 (WSL2 Ubuntu / VM). 在 Windows/macOS 请用默认 kind 模式 (去掉 --target)."; return 1;;
  esac
  require kubeadm || { err "缺少 kubeadm, 请先: bash ske/wsl2/setup-host.sh"; return 1; }
  require kubelet || { err "缺少 kubelet, 请先: bash ske/wsl2/setup-host.sh"; return 1; }
  require containerd || { err "缺少 containerd, 请先: bash ske/wsl2/setup-host.sh"; return 1; }
  require kubectl || return 1

  local kdir="/etc/kubernetes"
  mkdir -p "$kdir"
  cp "$SKE_DIR/manifests/scheduler-policy.yaml" "$kdir/scheduler-policy.yaml"
  local cfg="$SKE_DIR/manifests/kubeadm-config.wsl2.yaml"

  if [ -f /etc/kubernetes/admin.conf ] && kubectl --kubeconfig /etc/kubernetes/admin.conf get nodes >/dev/null 2>&1; then
    warn "SKE 集群已存在 (使用 /etc/kubernetes/admin.conf), 跳过 kubeadm init"
  else
    log "kubeadm init (SKE 深度定制, target=wsl2)..."
    kubeadm init --config "$cfg" --upload-certs 2>&1 | tail -40 || { err "kubeadm init 失败"; return 1; }
  fi

  mkdir -p "$HOME/.kube"
  cp -f /etc/kubernetes/admin.conf "$HOME/.kube/config"
  chmod 600 "$HOME/.kube/config"
  export KUBECONFIG="$HOME/.kube/config"

  # 单节点: 控制面兼跑负载
  kubectl taint nodes --all node-role.kubernetes.io/control-plane- 2>/dev/null || true
  kubectl taint nodes --all node-role.kubernetes.io/master- 2>/dev/null || true

  install_cilium
  apply_tuning

  local node; node="$(kubectl get nodes -o jsonpath='{.items[0].metadata.name}' 2>/dev/null)"
  kubectl label node "$node" ske.io/role=data --overwrite 2>/dev/null || true
  kubectl label node "$node" topology.kubernetes.io/numa=0 --overwrite 2>/dev/null || true

  ok "SKE 集群就绪 (target=wsl2). 下一步: bash platform/bootstrap.sh --profile $PROFILE"
  echo "   kubectl 配置: $HOME/.kube/config"
}

# ---------- down ----------
down(){
  if [ "$TARGET" = "wsl2" ]; then
    require kubeadm || return 1
    log "销毁 SKE 集群 (kubeadm reset)..."
    kubeadm reset -f 2>/dev/null || warn "kubeadm reset 失败"
    ok "done"
    return 0
  fi
  require "$KIND_BIN" || return 1
  log "销毁 SKE 集群 ${CLUSTER}..."
  "$KIND_BIN" delete cluster --name "$CLUSTER" || warn "销毁失败或集群不存在"
  ok "done"
}

# ---------- status ----------
status(){
  require kubectl || return 1
  if [ "$TARGET" = "wsl2" ]; then
    echo "== SKE 集群状态 (target=wsl2) =="
    kubectl get nodes -o wide 2>/dev/null || true
    kubectl -n kube-system get pods 2>/dev/null | grep -E "cilium|ske-node" || true
    return 0
  fi
  require "$KIND_BIN" || return 1
  echo "== SKE 集群状态 =="
  "$KIND_BIN" get clusters 2>/dev/null
  kubectl get nodes -o wide 2>/dev/null || true
  kubectl -n kube-system get pods 2>/dev/null | grep -E "cilium|ske-node" || true
}

# ---------- tune-host ----------
tune_host(){
  log "宿主机内核/存储/控制面尽力调优..."
  bash "$SKE_DIR/tuning/kernel.sh"
  bash "$SKE_DIR/tuning/storage.sh"
  bash "$SKE_DIR/tuning/controlplane.sh"
  ok "tune-host 完成 (受限项见上方提示; 笔记本 dev 模式部分内核项需重启生效)"
}

# ---------- build-image ----------
build_image(){ ensure_kind; ensure_node_image; }

# ---------- 参数解析 ----------
while [ $# -gt 0 ]; do
  case "$1" in
    up) ACTION=up; shift;;
    down) ACTION=down; shift;;
    status) ACTION=status; shift;;
    tune-host) ACTION=tune-host; shift;;
    build-image) ACTION=build-image; shift;;
    --profile) PROFILE="$2"; shift 2;;
    --mode) MODE="$2"; shift 2;;
    --target) TARGET="$2"; shift 2;;
    -h|--help) ACTION=help; shift;;
    *) warn "未知参数: $1"; ACTION=help; shift;;
  esac
done
ACTION="${ACTION:-help}"

case "$ACTION" in
  up) up;;
  down) down;;
  status) status;;
  tune-host) tune_host;;
  build-image) build_image;;
  help)
    echo "数擎云核 SKE 用法:"
    echo "  bash ske.sh up --profile local --mode dev               # 拉起 SKE (kind + Docker Desktop WSL2 后端)"
    echo "  bash ske.sh up --target wsl2 --profile local            # 独立 WSL2 Ubuntu 真 kubeadm (先 ske/wsl2/setup-host.sh)"
    echo "  bash ske.sh status                            # 查看状态"
    echo "  bash ske.sh tune-host                         # 宿主机调优"
    echo "  bash ske.sh build-image                       # 构建自定义节点镜像"
    echo "  bash ske.sh down                              # 销毁集群"
    ;;
esac
