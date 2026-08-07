# 数擎云核 SKE · k3s + Istio Service Mesh 控制面（轻量测试集群）

> 版本：v0.1 ｜ 日期：2026-08-07 ｜ 状态：可运行
> 所属：T001 Service Mesh 控制面部署
> 定位：在 WSL2 中以 **k3s 单主节点** 拉起轻量 K8s 集群，安装 **Istio minimal profile**，启用 **sidecar 自动注入** 与 **mTLS**，为 encaps-layer 等自研组件提供 Service Mesh 底座。

---

## 1. 与 SKE 主发行版的关系

| 项 | SKE 主发行版（`ske/ske.sh`） | 本目录（`ske/k3s/`） |
| --- | --- | --- |
| 底座 | kind / 真实 kubeadm + Cilium eBPF | k3s（内置 Flannel） |
| 规模 | 单节点 dev / 多节点 prod | **单主节点**（轻量测试） |
| 定位 | 深度定制高性能发行版 | **快速验证 Service Mesh 控制面** |
| Service Mesh | 设计层规划，未落地 | **Istio minimal + sidecar + mTLS 落地** |
| 适用 | 客户交付 | 开发联调 / Mesh 功能验证 |

> k3s 是 Rancher 出品的轻量 K8s，单二进制、内置 containerd + Flannel，适合在 WSL2 笔记本上快速拉起。本目录与 SKE 主发行版**互补**：主发行版负责生产级深度调优，本目录负责 Service Mesh 控制面快速验证。

---

## 2. 目录结构

```
ske/k3s/
├── deploy-all.sh                  # 一键部署：k3s + Istio + sidecar + mTLS
├── install-k3s.sh                 # k3s 单主节点安装（WSL2 Ubuntu）
├── install-istio.sh               # Istio minimal profile + sidecar 注入 + mTLS
├── uninstall.sh                   # 卸载（Istio / k3s / kubeconfig）
├── verify-cluster.sh              # 集群 + Mesh 状态验证
├── istio-operator-minimal.yaml    # Istio Operator overlay（minimal + mTLS + 访问日志）
├── namespace-istio-system.yaml    # istio-system namespace（pod-security 标签）
├── peer-authentication-mtls.yaml  # mTLS 服务端策略（PERMISSIVE → STRICT）
├── destination-rule-mtls.yaml     # mTLS 客户端策略（ISTIO_MUTUAL）
├── sample-mesh-test-sa.yaml       # sidecar 注入验证示例 · ServiceAccount
├── sample-mesh-test-svc.yaml      # sidecar 注入验证示例 · Service
├── sample-mesh-test-deploy.yaml   # sidecar 注入验证示例 · Deployment
└── README.md                      # 本文档
```

---

## 3. 快速开始

### 3.1 前置

- **WSL2 Ubuntu 22.04+**，systemd 已开启（见 `ske/WSL2-QUICKSTART.md` §1）
- 在 WSL2 Ubuntu 终端内执行（非 Windows PowerShell）
- 项目挂载路径：`/mnt/f/Agent/workbuddy/workspace/ShuqingBigDataPlatform`

### 3.2 一键部署

```bash
cd /mnt/f/Agent/workbuddy/workspace/ShuqingBigDataPlatform

# 一键完成：k3s 安装 → Istio minimal → sidecar 注入 → mTLS 启用
sudo bash ske/k3s/deploy-all.sh
```

### 3.3 分步部署

```bash
# Step 1: 安装 k3s（单主节点，禁用 traefik/servicelb/metrics-server）
sudo bash ske/k3s/install-k3s.sh

# Step 2: 安装 Istio + sidecar 注入 + mTLS
bash ske/k3s/install-istio.sh

# Step 3: 验证
bash ske/k3s/verify-cluster.sh
```

### 3.4 验证 sidecar 注入

```bash
# 部署示例应用（default namespace 已启用 istio-injection）
kubectl apply -f ske/k3s/sample-mesh-test-sa.yaml
kubectl apply -f ske/k3s/sample-mesh-test-svc.yaml
kubectl apply -f ske/k3s/sample-mesh-test-deploy.yaml

# 观察 Pod：应出现 2/2 容器（业务 + istio-proxy sidecar）
kubectl get pods -n default

# 查看 sidecar 注入详情
kubectl get pod -n default -l app=mesh-test -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.containers[*].name}{"\n"}{end}'
```

### 3.5 卸载

```bash
# 完整卸载（Istio + k3s）
sudo bash ske/k3s/uninstall.sh

# 仅卸载 Istio，保留 k3s
sudo bash ske/k3s/uninstall.sh --keep-k3s
```

---

## 4. 关键配置说明

### 4.1 k3s 安装参数

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `--disable=traefik` | 禁用 | 避免与 Istio ingress-gateway 冲突 |
| `--disable=servicelb` | 禁用 | Service Mesh 场景用 Istio 管理流量 |
| `--disable=metrics-server` | 禁用 | 轻量测试不需要 HPA |
| `--cluster-cidr` | 10.42.0.0/16 | Pod CIDR |
| `--service-cidr` | 10.43.0.0/16 | Service CIDR |
| `--flannel-backend` | vxlan | 默认网络后端 |

### 4.2 Istio minimal profile

minimal profile 仅安装 **istiod**（控制面），不含 ingress/egress gateway，资源占用最小：

| 组件 | 请求 | 限制 |
| --- | --- | --- |
| istiod | 100m / 128Mi | 500m / 512Mi |
| istio-proxy (sidecar) | 50m / 64Mi | 250m / 256Mi |

### 4.3 mTLS 策略

采用**两阶段切换**：

1. **阶段一（当前）**：`PeerAuthentication mode=PERMISSIVE` — 明文 + 密文都接受，避免破坏尚未注入 sidecar 的服务
2. **阶段二（全量 sidecar 就绪后）**：切 `STRICT` — 强制 mTLS，拒绝明文

切换方法：

```bash
# 编辑 PeerAuthentication，将 mode 改为 STRICT
kubectl edit peerauthentication default-mtls -n istio-system
```

### 4.4 sidecar 注入

通过 namespace 标签 `istio-injection=enabled` 启用自动注入。`install-istio.sh` 会为以下 namespace 启用：

- `default`（默认业务 namespace）
- `platform-ops`、`shuqing-system`、`encaps-system`（若存在）

---

## 5. encaps-layer K8s client 切真实模式

Service Mesh 控制面就绪后，encaps-layer 需从 mock 模式切换为真实 K8s 连接：

```yaml
# platform/encaps-layer/src/main/resources/application.yml
app:
  k8s:
    mock-enabled: ${K8S_MOCK_ENABLED:false}   # false = 连接真实 K8s
```

- `K8S_MOCK_ENABLED=false`（默认）：fabric8 KubernetesClient 从 `~/.kube/config` 自动发现 k3s 集群
- `K8S_MOCK_ENABLED=true`：返回 null，由测试代码注入 mock client

k3s 安装脚本已将 kubeconfig 写入 `~/.kube/config`，encaps-layer 启动即可连接。

---

## 6. 验证清单

| 检查项 | 命令 | 期望 |
| --- | --- | --- |
| k3s 节点 Ready | `kubectl get nodes` | 1 节点 Ready |
| 控制面 Pod | `kubectl get pods -A` | 全 Running |
| istiod 就绪 | `kubectl get deploy istiod -n istio-system` | readyReplicas≥1 |
| sidecar 注入 | `kubectl get ns default --show-labels` | istio-injection=enabled |
| mTLS 策略 | `kubectl get peerauthentication -n istio-system` | default-mtls PERMISSIVE |
| 配置分析 | `istioctl analyze` | 无 Error |
| encaps 模式 | `grep mock-enabled platform/encaps-layer/.../application.yml` | false |

一键验证：`bash ske/k3s/verify-cluster.sh`

---

## 7. 排错

| 现象 | 排查 |
| --- | --- |
| k3s 安装失败 | 检查网络（需访问 get.k3s.io）；确认 systemd 已开启 |
| 节点 NotReady | 等 Flannel 就绪（约 30s）；`kubectl get pods -n kube-system` 看 flannel |
| istiod 起不来 | `kubectl describe pod -n istio-system`；检查资源限额是否过小 |
| sidecar 未注入 | 确认 namespace 标签 `istio-injection=enabled`；Pod 需重建才会注入 |
| mTLS STRICT 后不通 | 先回 PERMISSIVE；确认所有服务均已注入 sidecar 再切 STRICT |
| encaps 连不上集群 | 确认 `~/.kube/config` 存在；`K8S_MOCK_ENABLED=false` |

---

## 8. 与上层组件的关系

```
┌──────────────────────────────────────────────────┐
│  encaps-layer (K8S_MOCK_ENABLED=false)           │
│  └─ fabric8 KubernetesClient → ~/.kube/config    │
├──────────────────────────────────────────────────┤
│  Istio Service Mesh (minimal)                     │
│  ├─ istiod (控制面)                               │
│  ├─ sidecar 自动注入 (namespace 标签)             │
│  └─ mTLS (PeerAuthentication PERMISSIVE)         │
├──────────────────────────────────────────────────┤
│  k3s 单主节点 (Flannel + containerd)              │
└──────────────────────────────────────────────────┘
```