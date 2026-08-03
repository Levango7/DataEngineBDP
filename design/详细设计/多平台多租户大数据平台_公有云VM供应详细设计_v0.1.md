# L0.3 公有云 VM 供应 · 详细设计（v0.1）

> 归属：多平台多租户大数据平台 · L0 机器供应层
> 套餐：基础版（三档全含）
> 对标：AWS EC2 / 阿里云 ECS / 华为云 ECS / 腾讯云 CVM（客户自购裸 VM）
> 关联：L0.5 跨环境统一供给抽象（本模块为四环境之一）；L0.6 SKE 控制面（在云 VM 上自建 K8s）；L0.10 环境适配框架（publiccloud Profile）；L0.11 封装层（接口经其暴露）；L2.1 统一存储（云 S3/OBS/OSS 作为对象存储后端，客户提供密钥）

## 1. 定位与价值

公有云 VM 供应是 L0 层在**公有云环境**的机器供应实现：客户在自有云账号下自购裸 VM，平台在其上 bootstrap 自建 K8s，**云仅当算力**，不绑定任何云托管服务。

客户价值：
- "云上自建、不锁云"——客户用自有云账号采购 VM，平台在其上自建 K8s，随时可迁回本地或信创。
- "云弹性可用、成本客户控"——可选调云 API 扩缩 VM，但平台不代购，账单归客户云账号。
- "统一运维"——云 VM 上的 K8s 与本地/信创同构，运维动作一致。

平台价值：
- 机器供应经 **MachineProvider 抽象**，四环境（信创/本地/云VM/私有云）零改动切换。
- 云厂商差异收敛到 L0.3 内部，上层 SKE 控制面无感知。

## 2. 云 VM 约束（不绑云托管）

```text
┌──────── 公有云 VM 供应边界（只用这三样） ────────┐
│  ① 裸 VM（EC2/ECS/CVM，标准 OS 镜像）            │
│  ② 块存储（云盘/EBS/云硬盘，作 K8s PV 本地缓存）  │
│  ③ 网络（VPC/子网/SG，控制面与节点互通）          │
└──────────────────────────────────────────────────┘
        ✗ 禁用：云托管 K8s（EKS/ACK/CCE/TKE）
        ✗ 禁用：云托管 DB（RDS/PolarDB/GaussDB）
        ✗ 禁用：云托管 MQ（Kafka 服务版/MQ for RocketMQ）
        ✗ 禁用：云 IAM 托管（平台自带租户体系，不绑云账号 IAM）
```

> 关键约束：云只提供"裸算力 + 块存储 + 网络"，平台在其上自建 K8s/DB/MQ——保持可迁移、不锁云。对象存储密钥由客户提供，平台不代管云 IAM 凭证。

## 3. VM 纳管（cloud-init bootstrap → 打标签 → 加入节点池）

VM 纳管流程（平台不采购 VM，只纳管客户已购 VM）：

1. **客户录入 VM**：客户提供 VM IP/SSH 密钥或 cloud-init userData，平台不持有云账号 AK/SK。
2. **cloud-init bootstrap**：注入 SKE 安装脚本 → 装 containerd → 拉 K8s 组件 → join 控制面或节点。
3. **打标签**：按角色（control-plane/worker/edge）、规格（cpu/mem/disk）、用途（lake/compute/online）打 K8s label/taint。
4. **加入节点池**：注册到 L0.6 SKE NodePool，受 L0.11 封装层配额约束。

```text
客户自购 VM → 录入(IP/SSH) → cloud-init bootstrap → 打标签 → 加入 NodePool → SKE 调度
```

VM 纳管约束：
- 平台只持有 SSH 密钥或 cloud-init userData，**不持有客户云账号 AK/SK**（弹性凭证单独录入、最小权限）。
- bootstrap 失败重试 3 次后标记 `NotReady`，告警人工介入，不自动调云 API 重建。

## 4. 存储接入（云对象存储作 L2.1 后端）

云 VM 上 K8s 的存储分两类，均**不绑云托管**：

| 存储类型 | 后端 | 接入方式 | 说明 |
| --- | --- | --- | --- |
| 对象存储（湖仓底座） | 客户云 S3/OBS/OSS | L2.1 S3Driver，**客户提供密钥** | 密钥存 K8s Secret，平台不代管云 IAM |
| 块存储（本地缓存/PV） | 云盘/EBS/云硬盘 | 云 CSI Driver 或本地 Path | 仅作缓存与有状态 Pod PV，非湖仓主存 |

> 关键：对象存储密钥由客户在控制台录入，平台加密存 K8s Secret，绝不调用云 IAM 托管生成临时凭证——保持可迁移，换云只换密钥。

## 5. SKE 在云 VM 上适配

SKE 自研发行版在云 VM 上的适配项（publiccloud Profile）：

- **内核**：标准 Linux 内核（云 VM 自带），按 SKE 基线调参（sysctl、cgroup v2、iptables/nftables）。
- **容器运行时**：containerd，版本与本地/信创一致。
- **K8s 组件**：kubelet/kube-proxy/kube-apiserver 自建，**不用云托管控制面**。
- **块存储 CSI**：可选装云厂商 CSI（AWS EBS CSI / 阿里云 CSI / 华为云 CSI / 腾讯云 CSI）作 PV，但湖仓主存仍走对象存储。
- **负载均衡**：云 LB 接入**非必需**——控制面 HA 可用云 LB 或自建 keepalived；Ingress 可用云 LB 或自建 nginx-ingress。

> 原则：能用自建就自建，云 LB/CSI 仅作可选增强，不写死到 SKE 主链路——保证迁回本地/信创时零改动。

publiccloud Profile 与其他 Profile 的差异收敛点：

| 适配项 | 信创/本地 Profile | publiccloud Profile |
| --- | --- | --- |
| 内核 | 国产内核/自调参 | 云标准内核 + SKE 基线调参 |
| 块存储 CSI | 本地 Path/Ceph CSI | 云 CSI（可选）或本地 Path |
| 对象存储驱动 | XCObject/CephDriver | S3Driver（客户提供密钥） |
| LB | 自建 keepalived/nginx | 云 LB（可选）或自建 |

## 6. 弹性（可选调云 API 扩 VM，平台不代购）

弹性扩缩容策略：

- **客户自有云账号**：平台不持有客户云 AK/SK，不代购 VM、不代付账单。
- **可选云 API 扩 VM**：客户在控制台录入**受限云凭证**（仅限 RunInstances/DescribeInstances 权限），平台按节点池水位调云 API 扩 VM → bootstrap → 加入 NodePool。
- **缩容**：按节点池空闲水位缩 VM，先 drain 再调云 API 释放。
- **不代购兜底**：若客户未录入云凭证，弹性降级为"告警人工扩"，平台不自动采购。

```text
节点池水位高 → (有云凭证?) → 调云 API 扩 VM → cloud-init → 加入 NodePool
                ↓ 无
              告警人工扩（平台不代购）
```

## 7. 接口契约与风险

接口契约（内部，经 L0.10 环境适配框架）：

```
POST /api/l0/v1/vms              { provider, ips, sshKey|userData, role, spec } → 纳管 VM           // ↔ L0.5 NodePool.createPool（CloudVMDriver）
POST /api/l0/v1/vms/{id}/join    { nodePool } → bootstrap 并加入节点池（NodePool）                    // ↔ L0.5 NodePool.listNodes 注册
POST /api/l0/v1/nodepools/{p}/scale  { targetReplicas } → 弹性扩缩（需云凭证）                       // ↔ L0.5 NodePool.scalePool
PUT  /api/l0/v1/cloud-credentials { provider, ak, sk, scope } → 录入受限云凭证
```

风险与对策：

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 客户云账号密钥泄露 | 越权操作云资源 | 密钥加密存 K8s Secret，最小权限 scope，审计每次调云 API |
| 云厂商 CSI 差异 | PV 行为不一致 | 块存储仅作缓存，湖仓主存走对象存储 S3Driver 屏蔽差异 |
| 云 VM 规格异构 | 调度不均 | bootstrap 时打规格标签，SKE 调度按 label/taint 分配 |
| 平台误代购云资源 | 客户账单失控 | 弹性默认"告警人工扩"，调云 API 需客户显式录入凭证 |
| 云托管服务诱惑 | 锁云、不可迁 | L0.3 硬约束禁用云托管 K8s/DB/MQ，架构评审拦截 |

> 与 UI 对应：控制台「基础设施」页录入云 VM、录入对象存储密钥、节点池弹性策略均基于此契约。本文件是云 VM 供应侧的设计依据。