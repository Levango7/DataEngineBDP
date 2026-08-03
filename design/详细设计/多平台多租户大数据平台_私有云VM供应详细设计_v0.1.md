# L0.4 私有云 VM 供应 · 详细设计（v0.1）

> 归属：多平台多租户大数据平台 · L0 机器供应层
> 套餐：基础版（三档全含）
> 对标：OpenStack Magnum / 华为云 CCE（自管节点池） / Rancher node driver
> 关联：L0.5 跨环境统一供给抽象（本模块为四环境之一）；L0.6 SKE 控制面（K8s 自建发行版）；L0.10 环境适配框架（privatecloud Profile）；L0.11 封装层（接口经其暴露）；L2.1 统一存储（厂商对象存储/MinIO 作后端）

## 1. 定位与价值

私有云 VM 供应是 L0 层在**客户私有云环境**下的机器供应实现：调用客户既有虚拟化/私有云 API 申请 VM，经 bootstrap 注入 SKE 组件后加入 K8s 节点池（NodePool），K8s 全程**自建自管**，不依赖云托管 K8s 服务。

客户价值：
- 复用客户既有私有云投资，无需采购裸金属即可落地大数据平台。
- K8s 由 SKE 发行版统一交付，客户对 K8s 版本/组件无感知，运维收敛到平台侧。
- 存储与网络复用私有云既有设施（厂商对象存储/MinIO、厂商 CNI），降低改造代价。

平台价值：
- 机器供应经 **NodeDriver 抽象**，OpenStack/华为云栈/国产化云三类私有云零改动切换。
- 与 L0.10 privatecloud Profile 解耦：供应层只产 VM + 标签，Profile 负责后续适配。

## 2. 私有云类型

```text
┌──────── 私有云 NodeDriver（按 Profile 选择） ────────┐
│   OpenStackDriver   HuaweiStackDriver   DomesticDriver  │
└──────────────────────┬──────────────────────────┘
                        ▼
              私有云 API（Nova/ECS/国产云 ECS）
                        ▼
                    VM 实例 → bootstrap → 加入节点池
```

表：私有云类型对照表

| 类型 | API 体系 | 鉴权 | 典型场景 | 驱动 |
| --- | --- | --- | --- | --- |
| OpenStack | Nova/Neutron/Cinder | Keystone v3 token | 开源私有云、运营商云 | OpenStackDriver |
| 华为云栈 | ECS/VPC/EVS（栈版） | AK/SK 或 token | 政企私有云、合营云 | HuaweiStackDriver |
| 国产化云 | 国产 ECS（类 S3 接口） | 国产 IAM | 信创私有云、行业云 | DomesticDriver |

> 三类驱动实现同一 NodeDriver 接口，差异仅在 API 端点、鉴权与镜像引用方式；调用方无感知。

## 3. VM 纪管

```text
申请 VM → 调私有云 API 创建 → bootstrap(user-data) 注入 SKE → 打标签 → 加入节点池
```

- **创建**：按节点池规格（flavor/镜像/网络/安全组）调私有云 API 批量起 VM，超时与配额失败重试。
- **bootstrap**：经 user-data/cloud-init 注入 SKE kubelet、容器运行时、节点注册脚本；不依赖云托管节点池服务。
- **打标签**：写入 `node-role.shuqing.io=<master|worker>`、`node-pool=<name>`、`provider=privatecloud`、`cloud-kind=<openstack|huawei|domestic>`。
- **加入节点池**：kubelet 向 SKE 控制面注册，控制面按标签调度；节点池伸缩由 L0.6 控制面驱动，本模块只供 VM。
- **回收**：节点下线先 drain（L0.6 执行），再调私有云 API 释放 VM，标签与注册信息同步清理。

## 4. 存储接入

私有云环境下 L2.1 统一存储后端二选一，由 privatecloud Profile 决定：

- **厂商对象存储**：客户私有云自带对象存储服务（如华为云栈 OBS、OpenStack Swift/Ceph RGW），经 S3 兼容接口接入，PrivateDriver 直接对接。
- **MinIO 自建**：私有云无对象存储时，在指定 VM/裸盘上部署 MinIO 集群，PrivateDriver 对接 MinIO S3 端点。

> 关键约束：存储后端只作"裸对象存储"，绝不启用私有云托管数据库/消息服务——保持四环境可迁移、不锁厂商。CSI 仅用于临时盘/缓存卷，持久化数据落对象存储。

## 5. SKE 在私有云上适配

K8s 控制面与 kubelet 均由 SKE 自建，私有云只提供基础设施；厂商组件经 L0.10 privatecloud Profile 注入：

| 组件 | 公共实现 | 私有云适配（厂商） | 说明 |
| --- | --- | --- | --- |
| CNI | SKE 默认 Cilium（eBPF） | 厂商 VPC/安全组对接 | 复用私有云网络平面，Pod CIDR 与 VPC 子网协调；eBPF 取代 kube-proxy，对齐 §5.1 SKE 性能支柱② |
| CSI | SKE 临时卷 CSI | 厂商 EVS/Cinder CSI | 仅临时盘/缓存，持久化走对象存储 |
| KMS | SKE 内置密钥管理 | 厂商 KMS（可选） | 信创场景优先国产 KMS |

> K8s 仍由 SKE 自建自管：不调私有云托管 K8s API（如 CCE/CCS），控制面版本、升级、组件配置统一由 SKE 发行版交付，客户无感知。

## 6. 接口契约与风险

```
POST   /api/l0/v1/nodepools/{pool}/nodes          { count, flavor } → 创建 VM 并加入   // ↔ L0.5 NodePool.createPool / scalePool（PrivateVMDriver）
GET    /api/l0/v1/nodepools/{pool}/nodes          → [{ vmId, status, labels }]          // ↔ L0.5 NodePool.listNodes
DELETE /api/l0/v1/nodes/{vmId}                   → drain + 释放 VM                      // ↔ L0.5 NodePool.drainNode
POST   /api/l0/v1/privatecloud/preflight          { profile } → 能力探测                // ↔ L0.5 preflight（能力矩阵回填 Profile.capabilities）
```

表：风险与对策表

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 私有云 API 限流/超时 | 节点扩容失败 | 指数退避重试 + 异步队列 + 部分成功回查 |
| 厂商镜像/网络差异 | bootstrap 失败 | preflight 探测镜像与网络；Profile 内固化镜像 ID |
| 客户配额不足 | 大规模扩容中断 | 创建前查配额，按余量分批；不足即告警不静默失败 |
| 国产化云兼容性 | 接口/语义偏差 | DomesticDriver 做能力探测，缺失能力降级或告警 |
| K8s 自建运维责任 | 升级/故障定责 | SKE 统一交付，控制面升级由 L0.6 主导，本模块只保 VM 可用 |

> 与 UI 的对应：控制台「环境管理」页选择私有云类型并填写 Profile；「节点池」页展示 VM 列表与标签，扩缩容按钮经本模块接口落地。本文件是其私有云供应侧契约依据。