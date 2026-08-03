# 设计评审报告 - A组：L0基础设施层

> 评审日期：2026-08-03
> 评审员：设计评审员（GLM-5.2）
> 评审文档：9 份（L0.1/L0.2/L0.3/L0.4/L0.5/L0.8/L0.9/L0.11/L0.12）
> 评审基准：产品设计 v0.5（`多平台多租户大数据平台_产品原型设计_v0.4.md`，文件名 v0.4 但文档内标注 v0.5）
> 评审维度：6 个（接口契约 / 关联引用 / 技术选型 / 术语 / 部署配置 / MVP 范围）

---

## 1. 评审汇总

| 维度 | 检查项数 | 通过 | 警告 | 不一致 |
| --- | :---: | :---: | :---: | :---: |
| 维度1 接口契约一致性 | 9 | 0 | 8 | 1 |
| 维度2 关联引用正确性 | 9 | 2 | 6 | 1 |
| 维度3 技术选型一致性 | 9 | 7 | 0 | 2 |
| 维度4 术语一致性 | 7 | 2 | 4 | 1 |
| 维度5 部署配置一致性 | 9 | 7 | 0 | 2 |
| 维度6 MVP范围一致性 | 9 | 0 | 9 | 0 |
| **合计** | **52** | **18** | **27** | **7** |

> 评分口径：通过=1.0，警告=0.5，不一致=0。一致性评分 = (18×1.0 + 27×0.5 + 7×0) / 52 = 31.5/52 ≈ **60.6/100**。
> 严重问题数：4（维度3×2 + 维度5×2，均涉及 CNI 选型与 K8s 发行版选型回退）。

---

## 2. 详细发现

### 2.1 接口契约一致性

> 评审重点：L0.5 跨环境抽象应定义统一接口（NodePool/StoragePool/NetworkPool 三原语），L0.1-L0.4 四环境供应应实现该接口；L0.11 封装层应封装 L0.6-L0.12 能力。

#### [警告] L0.1 信创资源供应 - 接口路径未显式映射到 L0.5 三原语
- L0.1 §6 定义 REST 接口 `POST /api/l0/v1/xc/machines`、`PATCH /api/l0/v1/xc/machines/{id}/labels` 等，但未显式标注对应 L0.5 `NodePool.createPool` / `NodePool.listNodes` 等哪个原语方法。
- L0.5 §3 表格称"信创（L0.1）→ XCBaremetalDriver"，但 L0.1 文档内未出现 `XCBaremetalDriver` 命名，驱动命名单向引用未闭环。

#### [不一致] L0.2 本地数据中心供应 - 接口前缀与其他三环境不统一
- L0.2 §6 接口前缀为 `/api/infra/v1/machines/bootstrap`、`/api/infra/v1/ceph/cluster`。
- L0.1/L0.3/L0.4 均用 `/api/l0/v1/...` 前缀。
- 四环境同属 L0 层，前缀应统一为 `/api/l0/v1/...`；`infra` 前缀破坏层级命名一致性，且与 L0.5 "四环境零改动切换"的统一抽象目标相悖。

#### [警告] L0.3 公有云VM供应 - 接口路径未显式映射到 L0.5 三原语
- L0.3 §7 接口 `POST /api/l0/v1/vms`、`POST /api/l0/v1/vms/{id}/join`，未标注对应 L0.5 `NodePool.createPool` / `NodePool.scalePool`。
- L0.5 §3 称"公有云 VM（L0.3）→ CloudVMDriver"，L0.3 文档未出现 `CloudVMDriver` 命名。

#### [警告] L0.4 私有云VM供应 - 接口路径未显式映射到 L0.5 三原语
- L0.4 §6 接口 `POST /api/l0/v1/nodepools/{pool}/nodes`，未标注对应 L0.5 哪个原语。
- L0.5 §3 称"私有云（L0.4）→ PrivateVMDriver"，L0.4 文档未出现 `PrivateVMDriver` 命名，仅出现 `OpenStackDriver`/`HuaweiStackDriver`/`DomesticDriver` 三子驱动。

#### [警告] L0.5 跨环境供给抽象 - 三原语为伪代码 interface，未给出 REST 端点契约
- L0.5 §2 用 `interface NodePool { createPool(...); scalePool(...); }` 伪代码定义接口，未给出 REST 路径、请求/响应 schema、错误码。
- 作为"唯一抽象入口"，应给出与四环境 REST 实现可对照的统一契约（至少给出方法名→REST 路径映射表），否则 L0.1-L0.4 实现无可对照的契约基准。

#### [警告] L0.8 容器存储 - 接口路径未统一到封装层门面
- L0.8 §7 接口 `/api/storage/v1/volumes`，注释"经封装层"暴露，但路径前缀 `/api/storage/` 未体现封装层门面统一前缀。
- L0.11 §3 定义业务语义 API（Workspace/Project/Task/Quota），未给出 REST 路径前缀，也未声明 `/api/storage/` 是其子路径。

#### [警告] L0.9 可观测基座 - 缺少对外接口契约章节
- L0.9 全文未定义"接口契约"章节，仅描述 Grafana/Prometheus 内部访问与 X3 分工。
- 虽客户不可直连，但与 X3 统一运维观测的接口边界（哪些指标/日志/链路查询能力透出给 X3）未明确，跨模块集成缺契约。

#### [警告] L0.12 弹性调度 - 接口路径未统一到封装层门面
- L0.12 §7 接口 `/api/elastic/v1/policies`、`/api/elastic/v1/quotas`、`/api/elastic/v1/pools`，注释"经封装层"，但路径前缀 `/api/elastic/` 与 L0.8 `/api/storage/` 各自为政，未统一到封装层门面前缀。

#### [警告] L0.11 封装层 - 业务语义 API 未给出明确 REST 路径前缀
- L0.11 §3 用 YAML schema 定义 Workspace/Project/Task/Quota，但未给出 REST 路径（如 `/api/encaps/v1/workspaces`）。
- 作为"对上暴露业务语义 API"的门面，应明确统一前缀，并声明 L0.8/L0.12 等子模块接口是其子路径或经其翻译，否则下游模块接口路径各自为政（已见 L0.8/L0.12 警告）。

---

### 2.2 关联引用正确性

> 评审重点：引用的模块编号/名称是否与 §3.3 一致。

#### [通过] L0.1 信创资源供应 - 关联引用正确
- 头部关联：L0.5 跨环境统一供给抽象、L0.6 SKE 控制面、L0.10 环境适配框架。编号与 §3.3 一致。

#### [警告] L0.2 本地数据中心供应 - 未反向引用 L0.5
- 头部关联：L2.1 统一存储、L0.6 SKE 控制面、L0.10 环境适配框架。
- 缺失：作为四环境之一，应反向引用 L0.5 跨环境统一供给抽象（L0.5 §3 明确"本地数据中心（L0.2）"是其实现）。

#### [警告] L0.3 公有云VM供应 - 未反向引用 L0.5
- 头部关联：L0.6 SKE 控制面、L0.10 环境适配框架、L2.1 统一存储。
- 缺失：未引用 L0.5。L0.5 §3 明确"公有云 VM（L0.3）"是其实现。

#### [警告] L0.4 私有云VM供应 - 未反向引用 L0.5
- 头部关联：L0.6 SKE 控制面、L0.10 环境适配框架、L2.1 统一存储。
- 缺失：未引用 L0.5。L0.5 §3 明确"私有云（L0.4）"是其实现。

#### [通过] L0.5 跨环境供给抽象 - 关联引用正确
- 头部关联：L0.1~L0.4、L0.10、L0.6。编号与 §3.3 一致，且双向引用 L0.1-L0.4。

#### [警告] L0.8 容器存储 - 关联章节用"SKE"而非"L0.6 SKE 控制面"
- 头部关联：L2.1 统一存储、L0.11 封装层、SKE。
- "SKE"未用 §3.3 编号 L0.6 引用，与其他文档用"L0.6 SKE 控制面"不严格一致。

#### [警告] L0.9 可观测基座 - 关联章节用"SKE"而非"L0.6"
- 头部关联：X3 统一运维观测、SKE、L0.11 封装层。
- "SKE"未用 L0.6 编号引用。

#### [警告] L0.12 弹性调度 - 关联章节"SKE 拓扑调度"未用 L0.6 编号
- 头部关联：L0.11 封装层、L0.6 K8s 控制面、L2.* 引擎、SKE 拓扑调度。
- 已引用 L0.6，但"SKE 拓扑调度"作为 L0.6 子能力未明确归属，轻微不一致。

#### [不一致] L0.11 封装层 - 缺少"关联"章节，格式与其他8份不统一
- L0.11 头部用"所属/定位/目标"三行，无"关联：L0.6/L0.8/L0.9/L0.12/L2.*/L5.*"行。
- 其他8份文档均有"关联：..."行，L0.11 缺失导致无法快速识别其封装的下游模块清单。
- 作为"封装 L0.6-L0.12 能力"的核心层，应显式列出封装的模块编号清单。

---

### 2.3 技术选型一致性

> 评审重点：是否与 §5.5 的12项决策一致，特别关注 K8s 发行版（决策1：自研 SKE）、CNI（§5.1：Cilium）。

#### [通过] L0.1 信创资源供应 - 选型一致
- SKE 控制面、国密 SM2/SM4、StorageDriver 均与 §5.5 决策1/6/§13 一致。

#### [通过] L0.2 本地数据中心供应 - 选型一致
- SKE、Ceph RGW/RBD/CephFS、rook-ceph、MetalLB 均与 §13 选型一致。

#### [通过] L0.3 公有云VM供应 - 选型一致
- SKE、containerd、S3Driver，禁用云托管 K8s/DB/MQ，与 §5.5 决策1、§4 共性约束一致。

#### [不一致] L0.4 私有云VM供应 - CNI 选型与 §5.1/§13 冲突
- L0.4 §5 表格"CNI: SKE 默认 Calico | 厂商 VPC/安全组对接"。
- §5.1 明确"容器网络：Cilium（eBPF，取代 kube-proxy）"，§13 选型清单"L0 网络: Cilium"。
- §5.1.1 支柱②"网络 eBPF: Cilium 取代 kube-proxy"是 SKE 七大性能支柱之一。
- L0.4 用 Calico 与产品设计明确选型 Cilium 冲突，且 Calico 非 eBPF 优先，削弱 SKE 性能支柱。
- **建议修复**：将"SKE 默认 Calico"改为"SKE 默认 Cilium（eBPF）"，厂商 VPC/安全组对接保持。

#### [通过] L0.5 跨环境供给抽象 - 选型一致
- SKE、Cluster API、Terraform Provider 抽象，无冲突选型。

#### [通过] L0.8 容器存储 - 选型一致
- CSI、JuiceFS、Redis/TiKV/对象存储元数据引擎，与 §13 选型一致。

#### [通过] L0.9 可观测基座 - 选型一致
- Prometheus+Loki+Tempo+Grafana、OpenTelemetry、Thanos，与 §5.1/§13 选型一致。

#### [通过] L0.12 弹性调度 - 选型一致
- HPA、KEDA、Cluster Autoscaler、SKE Scheduler Extender，与 §5.2/§5.1.1 支柱④一致。

#### [不一致] L0.11 封装层 - bootstrap 选型与 §5.5 决策1 冲突
- L0.11 §7"封装层本身以 Deployment 部署于每个 K8s 集群，由 bootstrap（kubeadm/KubeSphere/RKE2）统一拉起"。
- §5.5 决策1 明确"自研 SKE，非 KubeSphere/RKE2/k3s/kind 原样"，v0.5 升级说明"① §5.1 K8s 发行版选型对齐自研 SKE（消除 KubeSphere/RKE2 矛盾）"。
- L0.11 仍写 KubeSphere/RKE2 是选型回退，与 v0.5 拍板决策直接冲突。
- **建议修复**：改为"由 SKE installer 统一拉起（基于 kubeadm 二次封装，非 KubeSphere/RKE2 原样）"。

---

### 2.4 术语一致性

> 评审重点：SKE、四环境、多租户、Namespace 隔离、封装层等术语跨文档统一。

#### [警告] L0.11 封装层 - 全文未出现"SKE"术语
- L0.11 全文用"自建 K8s"、"K8s"，未出现"SKE"。
- 其他8份文档频繁使用"SKE"（L0.1/L0.2/L0.3/L0.4/L0.5/L0.8/L0.9/L0.12 均用）。
- §5.1.1 明确 SKE 是自研发行版名称，封装层作为"K8s 产品化封装"应使用 SKE 术语指代其封装对象。
- **建议修复**：将"自建 K8s"在首次出现处改为"自研 SKE（K8s 发行版）"，后续可用 SKE 简称。

#### [通过] 四环境术语跨文档一致
- L0.1 信创、L0.2 本地数据中心、L0.3 公有云、L0.4 私有云、L0.5/L0.8/L0.9/L0.12 均用"信创/本地数据中心/公有云/私有云"或等价表述，无歧义。

#### [通过] Namespace 隔离术语跨文档一致
- L0.11"Namespace + ResourceQuota + LimitRange + NetworkPolicy"、L0.12"namespace（= workspace/project）"、L0.8"按租户命名空间隔离"、L0.9"Loki tenant_id = namespace"均一致。

#### [警告] L0.4/L0.5 - 封装层引用未用 L0.11 编号
- L0.4 全文用"封装层"未加编号 L0.11；L0.5 全文未提"L0.11 封装层"。
- L0.1/L0.2/L0.3/L0.8/L0.9/L0.12 均用"L0.11 封装层"编号引用。
- **建议修复**：L0.4/L0.5 在提及封装层时显式加"L0.11"编号。

#### [不一致] CNI 术语跨文档冲突
- L0.4 §5"SKE 默认 Calico"；L0.2 §5 隐含 Cilium（MetalLB 暴露 LoadBalancer，与 §5.1 Cilium 一致）；§5.1/§13 明确 Cilium。
- CNI 术语不统一：Calico vs Cilium，且与产品设计选型冲突。
- **建议修复**：统一为 Cilium（与维度3问题9联动修复）。

#### [警告] NodePool 中英文混用
- L0.5/L0.3 用"NodePool"（英文）；L0.2/L0.4/L0.12 用"节点池"（中文）。
- 同一概念中英文混用，建议统一为"节点池（NodePool）"首次出现后任选其一。

#### [警告] L0.11 - "Encapsulation Layer"英文术语未在其他文档对齐
- L0.11 §1"封装层（Encapsulation Layer）"给出英文术语，其他8份文档仅用"封装层"。
- 轻文档术语统一性建议：要么全部加英文，要么统一不加。

---

### 2.5 部署配置一致性

> 评审重点：文档中配置/参数是否与 `ske/` 目录下的实际配置文件对应。

#### [通过] L0.1 - 内核调优参数与 ske/tuning/kernel.sh 对应
- L0.1 §5 提到 `vm.max_map_count=262144`、`net.core.somaxconn=32768`、关 swap、THP=madvise，写入 `/etc/sysctl.d/99-ske.conf`。
- `ske/tuning/kernel.sh` 存在，对应 §5.1.1 支柱①内核调优。

#### [通过] L0.2 - Ceph/MetalLB 配置与 ske/ 目录对应
- L0.2 §5 提到 rook-ceph、MetalLB L2/BGP，与 §13 选型一致，无 ske/ 冲突配置。

#### [通过] L0.3 - containerd/云 CSI 配置对应
- L0.3 §5 提到 containerd、云厂商 CSI 可选，与 §5.1 一致。

#### [不一致] L0.4 - "SKE 默认 Calico"在 ske/ 目录无对应配置
- L0.4 §5"SKE 默认 Calico"，但 `ske/manifests/` 仅有 `cilium-values.yaml`，无 Calico 配置文件。
- 实际部署用 Cilium，文档写 Calico 是配置与文档不一致。
- **建议修复**：改为"SKE 默认 Cilium"，与 `ske/manifests/cilium-values.yaml` 对齐。

#### [通过] L0.5 - Profile YAML 与 ske/profiles/ 对应
- L0.5 §4 提到 `profile-xinchuang.yaml`，`ske/profiles/xinchuang.yaml` 存在。
- `ske/profiles/` 含 xinchuang/onprem/publiccloud/privatecloud/local 五个 Profile，与 L0.5 §4 四环境 + 本地开发一致。

#### [通过] L0.8 - etcd-tuning/scheduler-policy/nodepool-crd 对应
- L0.8 §5"etcd 专属盘 Local PV 绑定 NVMe，IO_uring"对应 `ske/manifests/etcd-tuning.yaml`、`ske/tuning/storage.sh`。
- L0.8 §5"ske.io/nvme=true 标签"对应 `ske/manifests/nodepool-crd.yaml`。

#### [通过] L0.9 - Prometheus/Loki/Tempo 配置对应
- L0.9 §6 保留期 15d/30d/7d 为 Helm values 配置范围，Thanos 远程写为可选长期存储，ske/ 目录无冲突配置。

#### [通过] L0.12 - HPA/KEDA/Scheduler Extender 配置对应
- L0.12 §3 HPA/KEDA 对应 `ske/manifests/hpa-templates.yaml`。
- L0.12 §5 SKE Scheduler Extender 对应 `ske/manifests/scheduler-policy.yaml`，与 §5.1.1 支柱④一致。

#### [不一致] L0.11 - "bootstrap（kubeadm/KubeSphere/RKE2）"在 ske/ 目录无 KubeSphere/RKE2 配置
- L0.11 §7 提到 kubeadm/KubeSphere/RKE2，`ske/manifests/` 仅有 `kubeadm-config.yaml` 和 `kubeadm-config.wsl2.yaml`，无 KubeSphere/RKE2 配置。
- `ske/README.md`、`ske/ske.sh` 表明 ske/ 是自研 SKE 路线，与 KubeSphere/RKE2 无关。
- 文档写 KubeSphere/RKE2 与实际 ske/ 实现不一致，且与 §5.5 决策1 冲突。
- **建议修复**：改为"由 SKE installer（基于 kubeadm 二次封装）统一拉起"，删除 KubeSphere/RKE2。

---

### 2.6 MVP范围一致性

> 评审重点：是否与 §11.5 三档套餐矩阵一致（L0.1-L0.12 在三档均为 ✅，属基础版）。

#### [警告] L0.1 信创资源供应 - 未显式标注套餐归属
- 文档头部"归属：L0 机器供应层"，未标注"套餐：基础版/标准版/旗舰版"。
- §11.5 §11.5.1 矩阵 L0.1 三档均为 ✅，属基础版。文档未回标，无法从单份文档确认 MVP 范围。

#### [警告] L0.2 本地数据中心供应 - 未显式标注套餐归属
- 同上，未标注套餐归属。

#### [警告] L0.3 公有云VM供应 - 未显式标注套餐归属
- 同上，未标注套餐归属。

#### [警告] L0.4 私有云VM供应 - 未显式标注套餐归属
- 同上，未标注套餐归属。

#### [警告] L0.5 跨环境供给抽象 - 未显式标注套餐归属
- 同上，未标注套餐归属。

#### [警告] L0.8 容器存储 - 未显式标注套餐归属
- 同上，未标注套餐归属。

#### [警告] L0.9 可观测基座 - 未显式标注套餐归属
- 同上，未标注套餐归属。

#### [警告] L0.11 封装层 - 未显式标注套餐归属
- 同上，未标注套餐归属。

#### [警告] L0.12 弹性与调度 - 未显式标注套餐归属
- 同上，未标注套餐归属。

> 补充说明：9份文档均未将 L0 模块错误标注为"标准版"或"旗舰版"独占，与 §11.5 无冲突，仅缺回标。建议在文档头部"归属"行统一补充"套餐：基础版（三档全含）"。

---

## 3. 问题清单（需修复）

| # | 严重度 | 文件 | 维度 | 问题描述 | 建议修复 |
| :---: | :---: | --- | :---: | --- | --- |
| 1 | **严重** | L0.4 私有云VM供应 | 维度3 | §5 表格"SKE 默认 Calico"与 §5.5/§5.1/§13 选型 Cilium 冲突，削弱 SKE eBPF 性能支柱② | 改为"SKE 默认 Cilium（eBPF）"，厂商 VPC/安全组对接保持 |
| 2 | **严重** | L0.11 封装层 | 维度3 | §7"bootstrap（kubeadm/KubeSphere/RKE2）"与 §5.5 决策1"自研 SKE，非 KubeSphere/RKE2 原样"冲突 | 改为"由 SKE installer（基于 kubeadm 二次封装）统一拉起"，删除 KubeSphere/RKE2 |
| 3 | **严重** | L0.4 私有云VM供应 | 维度5 | "SKE 默认 Calico"在 `ske/manifests/` 无对应配置，实际仅有 `cilium-values.yaml` | 同 #1，改为 Cilium 对齐实际配置 |
| 4 | **严重** | L0.11 封装层 | 维度5 | "bootstrap（KubeSphere/RKE2）"在 `ske/` 目录无对应配置，ske/ 是自研 SKE 路线 | 同 #2，改为 SKE installer 对齐实际实现 |
| 5 | 中 | L0.2 本地数据中心供应 | 维度1 | 接口前缀 `/api/infra/v1/` 与其他三环境 `/api/l0/v1/` 不统一 | 统一为 `/api/l0/v1/...`，如 `/api/l0/v1/onprem/machines` |
| 6 | 中 | L0.11 封装层 | 维度2 | 缺少"关联"章节，未列出封装的下游模块清单 | 头部补充"关联：L0.6/L0.8/L0.9/L0.12/L2.*/L5.*" |
| 7 | 中 | L0.5 跨环境供给抽象 | 维度1 | 三原语为伪代码 interface，未给出 REST 端点契约，L0.1-L0.4 无可对照基准 | 补充三原语→REST 路径映射表，或给出 OpenAPI schema |
| 8 | 中 | L0.11 封装层 | 维度1 | 业务语义 API 未给出统一 REST 路径前缀，导致 L0.8/L0.12 路径各自为政 | 定义封装层门面前缀（如 `/api/encaps/v1/`），声明子模块接口经其翻译 |
| 9 | 中 | L0.9 可观测基座 | 维度1 | 缺少对外接口契约章节，与 X3 接口边界未明确 | 补充"接口契约"章节，明确透出给 X3 的指标/日志/链路查询能力 |
| 10 | 低 | L0.11 封装层 | 维度4 | 全文未出现"SKE"术语，仅用"自建 K8s"，与其他8份文档不统一 | 首次出现处改为"自研 SKE（K8s 发行版）" |
| 11 | 低 | L0.4/L0.5 | 维度4 | 封装层引用未用 L0.11 编号 | 提及封装层时显式加"L0.11"编号 |
| 12 | 低 | L0.2/L0.3/L0.4 | 维度2 | 未反向引用 L0.5 跨环境统一供给抽象 | 头部"关联"补充 L0.5 |
| 13 | 低 | L0.8/L0.9/L0.12 | 维度2 | 关联章节用"SKE"而非"L0.6 SKE 控制面"编号 | 改为"L0.6 SKE 控制面" |
| 14 | 低 | L0.1-L0.4 | 维度1 | 接口未显式映射到 L0.5 三原语，驱动命名单向引用未闭环 | 在接口章节注释对应 L0.5 哪个原语方法，并出现 L0.5 定义的 Driver 命名 |
| 15 | 低 | 全部9份 | 维度6 | 未显式标注套餐归属（§11.5 L0 模块三档全含） | 头部"归属"行补充"套餐：基础版（三档全含）" |
| 16 | 低 | L0.5/L0.3 vs L0.2/L0.4/L0.12 | 维度4 | NodePool 中英文混用 | 统一为"节点池（NodePool）"首次出现后任选 |
| 17 | 低 | L0.11 | 维度4 | "Encapsulation Layer"英文术语未在其他文档对齐 | 统一加英文或统一不加 |
| 18 | 低 | L0.11 | 维度2 | 头部引用"产品原型设计 v0.4"，当前基准已 v0.5 | 改为"v0.5" |

---

## 4. 结论

- **评审文档数**：9（L0.1/L0.2/L0.3/L0.4/L0.5/L0.8/L0.9/L0.11/L0.12）
- **检查项总数**：52
- **发现问题数**：34（通过18 / 警告27 / 不一致7，对应问题清单18条）
- **严重问题数**：4（均涉及 CNI Calico vs Cilium、K8s 发行版 KubeSphere/RKE2 vs SKE 的选型回退，集中在 L0.4 和 L0.11）
- **一致性评分**：**60.6 / 100**

### 4.1 总体评价

L0 基础设施层9份详细设计文档**整体框架完整、职责边界清晰、信创/多环境/封装理念贯穿一致**，与产品设计 v0.5 §3.3 模块清单和 §5.1.1 SKE 七大性能支柱的对齐度较高。但存在两类**严重回退问题**：

1. **选型回退**：L0.4 §5"CNI: SKE 默认 Calico"与 L0.11 §7"bootstrap（kubeadm/KubeSphere/RKE2）"分别回退了 §5.5 决策1（自研 SKE）和 §5.1/§13（Cilium）的拍板结论，且与 `ske/` 目录实际配置（`cilium-values.yaml`、`kubeadm-config.yaml`）不一致。这两处是 v0.5 明确"消除 KubeSphere/RKE2 矛盾"后遗留的旧选型文本，必须修复以避免实现侧误用。

2. **接口契约缺位**：L0.5 三原语为伪代码 interface 无 REST 契约，L0.11 封装层门面无统一路径前缀，导致 L0.1-L0.4 四环境接口路径风格不一（L0.2 前缀 `/api/infra/` 异常）、L0.8/L0.12 子模块接口各自为政。跨模块集成缺乏可对照的契约基准。

### 4.2 修复优先级

| 优先级 | 问题 # | 修复内容 |
| :---: | :---: | --- |
| P0（必须立即修复） | 1, 3 | L0.4 CNI: Calico → Cilium |
| P0（必须立即修复） | 2, 4 | L0.11 bootstrap: 删除 KubeSphere/RKE2，改为 SKE installer |
| P1（本迭代内修复） | 5, 6, 7, 8, 9 | 接口前缀统一 / 关联章节补全 / 三原语 REST 契约 / 封装层门面前缀 / L0.9 接口契约 |
| P2（下一迭代修复） | 10-18 | 术语统一 / 编号引用 / 套餐回标 / 版本号对齐 |

### 4.3 一致性评分明细

| 维度 | 通过率 | 评分 |
| --- | :---: | :---: |
| 维度1 接口契约 | 0/9 | 44% |
| 维度2 关联引用 | 2/9 | 56% |
| 维度3 技术选型 | 7/9 | 89% |
| 维度4 术语一致性 | 2/7 | 64% |
| 维度5 部署配置 | 7/9 | 89% |
| 维度6 MVP范围 | 0/9 | 50% |
| **总体** | **18/52** | **60.6%** |

> 评分说明：维度3/维度5 评分高（89%）但因含2处严重选型回退，实际阻塞实现；维度1/维度6 评分低但因多为文档完整性问题（警告），不阻塞实现但阻塞跨模块集成。建议优先修复 P0 严重问题后重新评分。

---

> 评审完成时间：2026-08-03
> 下一步：将本报告提交至 team leader，由 B/C/D 组评审完成后汇总至总评审报告。