# 数擎云核 SKE（DataEngine Kubernetes Engine）· kubeadm/kind 封装的 K8s 交付底座

> 版本：v0.1 ｜ 日期：2026-08-02 ｜ 状态：发行版设计 + 可运行 bootstrap
> 所属：数据引擎大数据平台（Shuqing BigData Platform）运行时底座
> 定位：**不是开源内置默认款（kubeadm/k3s/kind 原样），而是深度定制、高度封装、性能优先的自有 K8s 发行版**。客户完全感知不到 K8s 的存在。

---

## 1. 命名与定位

| 项 | 值 |
| --- | --- |
| 中文名 | **数擎云核** |
| 代号 | **SKE（DataEngine Kubernetes Engine）** |
| 项目根目录 | `F:\Agent\workbuddy\workspace\DataEngineBDP`（本机运行时） |
| 角色 | 数据引擎大数据平台的**唯一、不可见、高性能**资源底座 |
| 对标 | 星环 TCOS（自研云原生操作系统）、华为 FusionInsight 底座 |
| 差异化 | 标准上游 K8s 二进制 + 自有深度调优与封装层，**不绑云、不绑单一发行版、可四环境一致交付** |

**为什么不用"开源内置默认款"**：vanilla kubeadm/k3s/kind 出厂即用的集群，内核未调优、网络走 iptables 慢路径、调度不感知数据本地性、控制面与 etcd 混部争抢资源——这在大数据/AI 负载下性能与稳定性都不达标。SKE 在**保留上游 K8s 兼容性与生态**的前提下，把底座做成"出厂即高性能、出厂即封装好"的发行版。

---

## 2. 性能架构：七大支柱

```
┌──────────────────────────────────────────────────────────────┐
│                      数擎云核 SKE                              │
│  对外仅暴露: ske CLI + 工作空间/项目/任务 语义 (客户无 K8s 概念)  │
├──────────┬──────────┬──────────┬──────────┬───────────────────┤
│ ①内核调优 │ ②网络eBPF│ ③存储IO  │ ④拓扑调度│ ⑤控制面隔离       │
│ 大页/NUMA │ Cilium   │ NVMe+    │ CPU/     │ etcd专属NVMe      │
│ /CPU pin  │ 绕过iptab│ IO_uring│ NUMA/    │ +控制组件CPU隔离   │
│           │         │ /LocalPV │ 数据本地 │ +apiserver高并发    │
├──────────┼──────────┼──────────┼──────────┼───────────────────┤
│ ⑥组件精简 │ ⑦封装收敛                                              │
│ 去云厂商/ │ SKE→封装层→工作空间; 客户不持 kubeconfig              │
│ 去非必要  │                                                        │
└──────────┴──────────┴──────────┴──────────┴───────────────────┘
```

### 2.1 内核调优（支柱①）
- **大页**：预留 2MB/1GB HugePages 给 etcd、Doris BE、Spark executor 堆外；关闭 THP（避免抖动），显式挂载大页。
- **NUMA 绑核**：控制面组件与数据引擎绑定独立 NUMA node，避免跨片内存访问。
- **CPU 隔离**：`isolcpus` + `kubelet --cpu-manager-policy=static` + `topology-manager=single-numa-node`，保障低延迟任务独占核。
- **网络栈**：`net.core.somaxconn`、`net.ipv4.tcp_tw_reuse`、socket 缓冲调大；`irqbalance` 亲和到数据网卡。

### 2.2 网络 eBPF（支柱②）
- **Cilium 取代 kube-proxy**：eBPF HostRouting + BPF 伪装，绕过 iptables/netfilter 慢路径，Pod 网络直连，延迟下降一个数量级。
- 启用 `kubeProxyReplacement=true`、`bpf.masquerade=true`、`endpointRoutes=true`。
- 网络策略（NetworkPolicy）由 eBPF 强制，支撑租户隔离。

### 2.3 存储 IO（支柱③）
- **NVMe 直通 + IO_uring**：本地盘以 Local PV 暴露，引擎写路径走 IO_uring（减少 syscall 与上下文切换）。
- **etcd 专属 NVMe**：etcd 数据目录独占一块 NVMe，禁用写入合并、开启 `noatime`。
- 对象存储（湖仓底座）走 S3 兼容协议，由 `StorageDriver` 适配（信创国产对象存储/Ceph/MinIO/云 S3），与引擎分离。

### 2.4 拓扑感知调度（支柱④）
- `topologyKey: kubernetes.io/numa` + `topology-manager=single-numa-node`：同任务 CPU/内存落在同一 NUMA。
- **自定义调度扩展器（SKE Scheduler Extender）**：感知数据本地性——将 Spark/Flink 计算尽量调度到持有其 Iceberg 分片/缓存的节点或 zone，降低跨网络 shuffle。

### 2.5 控制面隔离（支柱⑤）
- etcd 独立部署，独占 NVMe 与 CPU 核；`--max-requests-inflight=3000`、`--max-mutating-requests-inflight=2000`、watch 缓存调大。
- kube-apiserver / controller-manager / scheduler 绑定独立 CPU 核，与数据面互不干扰。

### 2.6 组件精简（支柱⑥）
- 移除 cloud-provider 集成、移除非必要 addon、关闭未用特性门。
- kubelet 关闭 `--cloud-provider`、收紧 `--protect-kernel-defaults`、仅启用必要 cgroup 驱动（systemd）。
- 单发行版打包：SKE 以一组静态 Pod + 自研 installer 交付，升级原子化。

### 2.7 封装收敛（支柱⑦）
- SKE 不直接暴露给客户；由**封装层（L1.6）**翻译成「工作空间/数据项目/计算任务/套餐配额」。
- 客户不持有 kubeconfig、无 K8s API 权限；运维台也仅平台方可见（见控制台 v0.3「运营后台」）。

---

## 3. 两种运行模式

| 模式 | 场景 | 实现 | 内核级调优 |
| --- | --- | --- | --- |
| `dev` | 开发者笔记本（你当前环境：Win+x86_64 + Docker Desktop） | kind 容器 + **自定义 SKE 节点镜像**（烘焙 kubelet drop-in + Cilium 预载）+ 我们的 kubeadm/kubelet/scheduler 配置 | 尽力而为：eBPF/CPU-TM/调度生效；大页/NUMA 需宿主机支持，`ske tune-host` 尝试设置并提示 |
| `prod` | 客户交付（信创/本地/云 VM/私有云） | 自有 VM 镜像（node-image 构建产物）或裸金属 kubeadm，全量调优 | 完整生效（内核参数在镜像内固化） |

> 说明：kind 节点是**容器、共享宿主内核**，无法逐节点改内核参数；因此"深度内核调优"在生产 VM/裸金属上才完整生效。`dev` 模式用于功能验证与演示，性能调优项会尽力应用并显式标注哪些受限于宿主。

---

## 4. 与数据引擎大数据平台的关系

```
客户业务 ──> 控制台(L5) ──> 封装层(L1.6) ──> 数擎云核 SKE ──> 物理/虚拟资源
                                      │
                       SKE 内部: Cilium(eBPF) / 调优 kubelet / 拓扑调度 / 控制面隔离
                                      │
                       SKE 之上是: 统一存储(L2.1)/引擎(L2)/统一SQL(L2.7)/治理(L3)/智能层(L4.5)
```

SKE 替代原 `deploy` 体系中 `k8s.distro: k3s/kind` 的位置；Profile 中 `k8s.distro: ske` 即启用本发行版（见 `ske/profiles/*.yaml`）。

---

## 5. 目录结构（本发行版）

```
ske/
├── ske.sh                 # 一键 bootstrap: up/down/status/tune-host
├── README.md              # 本文档
├── tuning/
│   ├── kernel.sh          # 大页/NUMA/CPU pin/网络栈 sysctl
│   ├── storage.sh         # NVMe/IO_uring/LocalPV 调优
│   └── controlplane.sh    # etcd 专属盘 + 控制面 CPU 隔离
├── manifests/
│   ├── kubeadm-config.yaml     # 深度定制 kubeadm (feature gates/调度配置)
│   ├── kubelet-config.yaml     # kubelet 深度调优 (CPU/TM/大页/cgroup)
│   ├── scheduler-policy.yaml   # 拓扑感知调度 + 数据本地性扩展器
│   ├── cilium-values.yaml      # Cilium eBPF 网络
│   └── tuning-daemonset.yaml   # 节点级 sysctl/hugepages 兜底应用
├── node-image/
│   └── build.sh           # 构建自定义 SKE 节点镜像(烘焙 kubelet drop-in + Cilium)
└── profiles/              # 环境 Profile (复用部署设计 8 维度, distro=ske)
    ├── local.yaml └── xinchuang/onprem/publiccloud/privatecloud.yaml
```

---

## 6. 快速开始（在你的笔记本上执行）

> 注意：本发行版文件由 WorkBuddy 在沙箱内生成；**真正的集群拉起需在你的笔记本终端运行**（沙箱内 Docker 守护进程不可达）。

SKE 有两种本地拉起方式，底层都落在 WSL2：

- **方式一 · kind（最省事）**：直接在本机 Windows/macOS 终端跑，kind 把集群建在 Docker Desktop 的 WSL2 后端里。适合快速看功能。
- **方式二 · 独立 WSL2 Ubuntu 真 kubeadm（最忠实）**：在一个独立 WSL2 Ubuntu 发行版（开 systemd）里跑真实 kubeadm，完整验证 SKE 的 kubeadm/kubelet/scheduler/Cilium 深度定制。详见 **`ske/WSL2-QUICKSTART.md`**。

### 方式一：kind（Docker Desktop WSL2 后端）
> 前置：安装并启动 **Docker Desktop**（你已具备）；本机有 `kubectl`（你已具备）。

```bash
cd /f/Agent/workbuddy/workspace/DataEngineBDP
bash ske/ske.sh tune-host                       # 宿主机尽力调优（受限项会提示）
bash ske/ske.sh up --profile local --mode dev   # 单节点 kind + 自定义节点镜像 + Cilium
bash platform/bootstrap.sh --profile local      # 封装层骨架 + 本地 MinIO
bash examples/run-demo.sh                       # 端到端 PoC（数据非硬编码，cleanup 可清）
bash ske/ske.sh status
bash ske/ske.sh down
```

### 方式二：独立 WSL2 Ubuntu 真 kubeadm
```bash
# 在 Windows 侧：wsl --install -d Ubuntu；并在 Ubuntu 内开 systemd（见 WSL2-QUICKSTART.md）
# 进入 WSL2 Ubuntu 后：
cd /mnt/f/Agent/workbuddy/workspace/DataEngineBDP                 # 项目挂载路径
sudo bash ske/wsl2/setup-host.sh                 # 装 containerd + kubeadm/kubelet/kubectl
sudo bash ske/ske.sh tune-host                   # 内核/网络栈/大页尽力调优
sudo bash ske/ske.sh up --target wsl2 --profile local   # 真实 kubeadm 拉起 SKE
bash platform/bootstrap.sh --profile local       # MinIO 自动以 in-cluster 方式部署
bash examples/run-demo.sh
bash ske/ske.sh status
bash ske/ske.sh down                             # kubeadm reset
```

---

## 7. 设计约束（贯穿全局）

1. **K8s 一律自建**：SKE 在客户自有机器（信创/本地/云 VM/私有云）上自建，绝不启用任何云厂商托管 K8s（ACK/EKS/TKE/CCE）。
2. **深度定制非重写**：基于上游 K8s 二进制做发行版级调优与封装，保证生态兼容与可升级，而非从零造内核。
3. **客户无感知**：SKE 与 K8s 概念对客户完全透明，仅「工作空间/项目/任务」可见。
4. **四环境一致**：同一条 `ske up` + Profile，差异只在适配驱动与镜像变体，作业与平台行为字节级一致。
5. **性能可度量**：调优项逐项有基准对照（见 `tuning/` 脚本内的注释基准），便于验收。
