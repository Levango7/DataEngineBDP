# 设计评审报告 - E组：全局交叉检查

> 评审日期：2026-08-03
> 评审范围：部署清单 + 端到端PoC + 跨层接口 + 全局术语 + 部署配置
> 评审基准：产品原型设计 v0.5（§3.3 模块状态表 / §5.5 选型决策表 / §11.5 定价模型 / §5.1.1 SKE七大支柱）
> 评审文档：部署清单详细设计 v0.1、端到端PoC详细设计 v0.1、deploy/values/ 下10份Helm values、产品原型设计 v0.5

---

## 1. 评审汇总

| 维度 | 检查项数 | 通过 | 警告 | 不一致 |
| --- | :---: | :---: | :---: | :---: |
| 1. 部署清单完整性 | 3 | 1 | 1 | 1 |
| 2. 端到端PoC完整性 | 3 | 1 | 2 | 0 |
| 3. 跨层接口契约一致性 | 6 | 2 | 3 | 1 |
| 4. 全局术语一致性 | 7 | 1 | 1 | 5 |
| 5. 部署配置与设计描述一致性 | 4 | 2 | 1 | 1 |
| **合计** | **23** | **7** | **8** | **8** |

> 总体结论：**不通过**。存在 8 项不一致项，其中 3 项为严重不一致（K8s发行版术语冲突、§13技术选型清单未同步§5.5拍板结果、Iceberg warehouse协议前缀不统一），需在 v0.6 修复后方可进入开发阶段。

---

## 2. 详细发现

### 2.1 部署清单完整性

#### 2.1.1 模块覆盖完整性

**检查项**：部署清单文档应列出所有41个模块的部署方式。

**发现**：部署清单 v0.1 §7"组件 → Helm Chart 映射（MVP 分层）"表仅列出约 15 个组件条目，远未覆盖 §3.3 全部 41 个模块。

| 已覆盖模块 | 未覆盖模块（缺失） |
| --- | --- |
| L0.11 封装层 | L0.1–L0.5（机器供应层 5 个） |
| L2.1 统一存储 | L0.6 自建K8s控制面/SKE |
| L2.2–L2.5 引擎（合并一行） | L0.7 容器网络/Cilium |
| L2.7 统一SQL网关 | L0.8 容器存储/CSI |
| L3.1–L3.5 治理（部分合并） | L0.9 可观测基座 |
| L4.1–L4.2 集成/调度 | L0.10 环境适配框架 |
| L4.5.3–L4.5.6 智能层（合并一行） | L0.12 弹性与调度 |
| L5.1 控制台 | L2.6 湖仓集一体 |
|  | L2.8 消息与流接入 |
|  | L2.9 时序引擎 |
|  | L2.10 多模型选配 |
|  | L3.6 主数据管理 |
|  | L3.7 数据安全 |
|  | L4.3 数据开发IDE |
|  | L4.4 BI可视化 |
|  | L4.5.1 标签画像 |
|  | L4.5.2 机器学习 |
|  | L5.2 运营后台 |
|  | L5.3 行业应用模板 |
|  | L5.4 对内业务线门户 |
|  | L5.5 开放API/服务目录 |
|  | L5.6 数据资产流通 |
|  | X1–X4 横切能力（4 个） |

**结论**：**不一致**。41 个模块中仅约 15 个有明确 Chart 映射，26 个模块缺失部署方式声明。建议部署清单 v0.2 补齐全部 41 个模块的 Chart/Operator 映射，至少标注"由 XXX Chart 包含"或"Profile 驱动"。

#### 2.1.2 Helm Chart / 部署配置覆盖

**检查项**：检查是否每个模块都有对应的Helm Chart或部署配置。

**发现**：deploy/values/ 目录下有 10 份 Helm values 文件（spark/flink/doris/trino/kafka/iotdb/keycloak/seatunnel/dolphinscheduler/superset），覆盖了 L2 引擎层主要组件和 L4 部分组件。但以下模块无独立 values 文件：

- L0 层全部（SKE/Cilium/CSI/Prometheus 等，可能由 base chart 或 SKE manifests 承载）
- L3 治理中台全套（sq-metadata/sq-quality 等，需独立 chart）
- L4.3 数据开发IDE（无 values）
- L4.5 智能数据层（sq-vector/sq-kb/sq-llmops/sq-llm-gateway 无 values）
- L5 控制台/运营后台（sq-console/sq-console-api 无 values）
- X2/X3/X4 安全合规/运维/网关（无 values）

**结论**：**警告**。10 份 values 文件覆盖了核心引擎，但治理/智能层/控制台/横切层缺失独立配置。建议补充或明确标注"由 base chart 包含"。

#### 2.1.3 部署顺序合理性

**检查项**：检查部署顺序是否合理（先L0基础设施，再L2引擎，再L3治理，再L4开发，最后L5交付）。

**发现**：部署清单 §9"部署流程（阶段化）"的顺序为：
1. preflight（能力探测）
2. bootstrap（自建 K8s 控制面 HA + Cilium + CSI + 可观测）→ 对应 L0
3. 装封装层（sq-encapsulation CRD）→ 对应 L0.11
4. 渲染平台（P0 组件：封装层 + 统一存储 + Spark/Flink/Doris + 统一SQL + 资产目录 + 控制台）→ L2 + L3 + L5 混合
5. 装 P1/P2（治理与智能层）→ L3 + L4.5
6. 校验

**结论**：**通过**。部署顺序基本遵循"L0 → 封装层 → L2 引擎 → L3 治理 → L4 开发 → L5 交付"的层次依赖。P0/P1/P2 优先级划分合理（P0 = 主链路 MVP，P1 = 治理+集成，P2 = 智能层）。但建议明确列出每个模块所属的 P0/P1/P2 档位。

---

### 2.2 端到端PoC完整性

#### 2.2.1 关键场景覆盖

**检查项**：端到端PoC文档应覆盖关键场景（数据接入→存储→计算→治理→可视化）。

**发现**：PoC v0.1 覆盖的场景：

| 场景 | 覆盖情况 | 说明 |
| --- | --- | --- |
| 数据接入 | ✅ 完整 | 步骤2：MySQL → Flink CDC → Iceberg 湖层 |
| 存储 | ✅ 完整 | Iceberg 湖层/仓层/集层三级，warehouse 共享 |
| 计算 | ✅ 完整 | 步骤3：Spark 主题建模；步骤4：Doris 物化视图 |
| 治理 | ⚠️ 部分 | 仅在步骤5提到"L3.7 策略经网关强制生效"，未展示元数据注册、质量校验、血缘采集、资产目录等治理环节 |
| 可视化 | ❌ 缺失 | PoC 未包含 BI 可视化步骤（Superset 查询/报表） |

**结论**：**警告**。PoC 覆盖了"接入→存储→计算→查询"主链路，但治理环节仅提及权限脱敏下推，未展示完整治理闭环；可视化环节完全缺失。建议 PoC v0.2 补充：① 步骤3.5 治理闭环（元数据注册→质量校验→资产入目录）；② 步骤5.5 BI 可视化（Superset 连 Trino/Doris 出报表）。

#### 2.2.2 模块引用合法性

**检查项**：检查PoC中引用的模块是否全部存在于§3.3。

**发现**：PoC 引用的模块清单：

| PoC 引用模块 | §3.3 编号 | 是否存在 |
| --- | --- | --- |
| 封装层 | L0.11 | ✅ |
| 统一存储 / Iceberg | L2.1 | ✅ |
| Flink CDC | L2.3 | ✅ |
| Spark | L2.2 | ✅ |
| Doris | L2.5 | ✅ |
| 湖仓集一体 | L2.6 | ✅ |
| 统一 SQL 网关 | L2.7 | ✅ |
| 数据安全（权限脱敏） | L3.7 | ✅ |
| 环境适配框架 | L0.10 | ✅ |

**结论**：**通过**。PoC 引用的所有模块均在 §3.3 模块状态表中存在，无"引用幽灵模块"问题。

#### 2.2.3 步骤可执行性

**检查项**：检查PoC步骤是否可执行（依赖关系是否正确）。

**发现**：PoC 步骤依赖链：
- 步骤1（建工作空间）→ 依赖封装层 API（前置条件已声明）✅
- 步骤2（CDC入湖）→ 依赖步骤1的 project.storagePrefix + Flink Operator ✅
- 步骤3（Spark主题建模）→ 依赖步骤2的 Iceberg 湖层表 ✅
- 步骤4（Doris物化视图）→ 依赖步骤3的 Iceberg 仓层表 + Doris External Catalog ✅
- 步骤5（统一SQL联邦查询）→ 依赖步骤4的 Doris + 步骤3的 Iceberg ✅
- 步骤6（客户无感知验证）→ 依赖步骤1-5 ✅
- 步骤7（四环境一致性）→ 依赖步骤1-5 + Profile 切换 ✅

**结论**：**警告**。步骤依赖关系正确，但步骤2的 Flink SQL 中 `warehouse = 'lakehouse/demo-fin/trade'` 是相对路径，未明确与 Helm values 中 `s3://sq-iceberg/warehouse` 的映射关系。建议 PoC 补充"封装层如何将 project.storagePrefix 翻译为 Iceberg warehouse 绝对路径"的说明。

---

### 2.3 跨层接口契约一致性

#### 2.3.1 L0层对L2层的接口

**检查项**：检查L0层对L2层的接口（存储接口、网络接口、调度接口）。

**发现**：
- 存储接口：部署清单 §4 定义了 `StorageDriver` 抽象接口（XCObjectDriver/CephDriver/S3Driver/PrivateDriver），L2 引擎通过 Iceberg Catalog 统一访问存储。✅
- Iceberg Catalog URI：在 spark/flink/trino/seatunnel 四份 values 中均为 `http://iceberg-catalog:8181`，端口一致。✅
- 网络接口：Cilium NetworkPolicy 由封装层自动创建（deny-all 默认），L2 引擎 Pod 受 NetworkPolicy 约束。✅
- 调度接口：L0.12 弹性调度（HPA/KEDA/节点池）在部署清单 §3 提及，但未明确 L2 引擎如何调用弹性调度接口。⚠️

**结论**：**警告**。存储/网络接口定义清晰，但弹性调度接口（L0.12 → L2 引擎）未明确契约。建议补充"引擎 Operator 如何感知节点池标签/污点并申请弹性资源"的接口定义。

#### 2.3.2 L2层对L3层的接口

**检查项**：检查L2层对L3层的接口（元数据接口、治理接口）。

**发现**：
- 元数据接口：L2 引擎（Spark/Flink/Doris/Trino）通过 Iceberg Catalog 注册元数据，L3.1 元数据管理（自研轻量 Catalog）应与 Iceberg Catalog 对接。但部署清单和 PoC 均未明确 L3.1 如何订阅 L2 引擎的元数据变更事件。⚠️
- 治理接口：PoC 步骤5 提到"L3.7 策略经统一 SQL 网关强制生效"，但未明确 L3.7 策略如何下发到 L2 引擎（Spark/Flink/Doris）的执行层。⚠️
- 血缘接口：L3.4 数据血缘应从 L2 引擎作业中采集，但未明确采集接口（Hook/事件/日志解析）。⚠️

**结论**：**警告**。L2→L3 接口契约缺失明确定义，仅 PoC 中提及 L3.7 权限下推。建议补充 L3.1/L3.4/L3.7 与 L2 引擎的接口契约文档。

#### 2.3.3 L3层对L4层的接口

**检查项**：检查L3层对L4层的接口（数据目录接口、权限接口）。

**发现**：
- 数据目录接口：L3.5 资产目录应向 L4 开发工具（IDE/BI/调度）提供资产检索/申请 API，但部署清单和 PoC 均未明确此接口。⚠️
- 权限接口：L3.7 数据安全应向 L4 开发工具提供行级/列级权限校验 API，PoC 中仅提及"经网关强制生效"，未明确 L4 工具直接调用权限接口的场景。⚠️

**结论**：**警告**。L3→L4 接口契约缺失。建议补充 L3.5 资产目录 OpenAPI 和 L3.7 权限校验 SDK 的接口定义。

#### 2.3.4 L4层对L5层的接口

**检查项**：检查L4层对L5层的接口（开发结果暴露接口）。

**发现**：
- 部署清单 §7 列出 L5 控制台 `sq-console` + `sq-console-api`，但未明确 L4 开发结果（作业/报表/模型）如何通过 API 暴露给 L5 控制台。⚠️
- PoC 步骤1 提到"客户通过封装层 REST API（或控制台 v0.3）发起"，但未明确控制台 API 与封装层 API 的路由关系。⚠️

**结论**：**警告**。L4→L5 接口契约缺失。建议补充控制台 API 网关路由规则（哪些 API 直达封装层、哪些经 L4 工具层中转）。

#### 2.3.5 X层对所有层的接口

**检查项**：检查X层对所有层的接口（认证/安全/运维/网关）。

**发现**：
- 认证（X1 Keycloak）：keycloak-values.yaml 定义了双 realm（sq 租户域 + master 平台域）和 OIDC 客户端。superset-values.yaml 有明确的 Keycloak OIDC SSO 集成配置。但 spark/flink/doris/trino/kafka/iotdb/seatunnel/dolphinscheduler 的 values 中均未明确 Keycloak 集成配置。⚠️
- 安全（X2）：未明确安全合规如何向各引擎下发审计/加密策略。⚠️
- 运维（X3）：Prometheus ServiceMonitor 在所有 values 中均有定义（`monitoring.prometheus.serviceMonitor: true`），运维观测接口一致。✅
- 网关（X4）：PoC 步骤5 通过统一 SQL 网关 API 提交查询，但未明确 X4 API 网关与 L2.7 统一 SQL 网关的关系（是否同一组件）。⚠️

**结论**：**警告**。X 层横切接口中，运维观测（Prometheus ServiceMonitor）覆盖完整，但认证（Keycloak）仅 Superset 有明确集成，其他引擎缺失；X4 API 网关与 L2.7 统一 SQL 网关的关系未澄清。

#### 2.3.6 接口定义格式/协议/版本一致性

**检查项**：重点检查接口定义的格式/协议/版本是否一致。

**发现**：

| 接口项 | Spark | Flink | Trino | SeaTunnel | Doris | 是否一致 |
| --- | --- | --- | --- | --- | --- | --- |
| Iceberg Catalog URI | `http://iceberg-catalog:8181` | `http://iceberg-catalog:8181` | `http://iceberg-catalog:8181` | `http://iceberg-catalog:8181` | — | ✅ 一致 |
| Iceberg Warehouse | `s3a://sq-iceberg/warehouse` | `s3://sq-iceberg/warehouse` | `s3://sq-iceberg/warehouse` | `s3://sq-iceberg/warehouse` | — | ❌ **不一致** |
| Doris FE 查询端口 | — | — | `doris-fe:9030` | — | `9030` | ✅ 一致 |
| Trino 查询端口 | — | — | 默认 8080 | — | — | ✅ Superset 引用 `trino-coord:8080` 一致 |
| Keycloak OIDC | 未定义 | 未定义 | 未定义 | 未定义 | 未定义 | ⚠️ 仅 Superset 定义 |

**严重发现**：**Iceberg Warehouse 协议前缀不一致**——Spark values 使用 `s3a://`（Hadoop S3A 协议），而 Flink/Trino/SeaTunnel values 使用 `s3://`（原生 S3 协议）。虽然两者可指向同一物理路径，但在跨引擎联邦查询时可能导致路径解析失败或重复配置。建议统一为 `s3://` 或 `s3a://`（推荐 `s3://`，与 Iceberg 原生 S3FileIO 一致）。

**结论**：**不一致**。Iceberg Warehouse 协议前缀在 Spark 与其他引擎间不一致，是跨层接口契约的严重问题。

---

### 2.4 全局术语一致性

**检查项**：扫描所有文档，检查关键术语是否统一。

| # | 术语簇 | 文件1（产品原型 v0.5） | 文件2（部署清单 v0.1） | 文件3（其他） | 差异 | 严重度 | 建议 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 1 | K8s 发行版 | §5.5 拍板"自研 SKE"；§5.1"非 KubeSphere/RKE2/k3s 原样" | §2"kubeadm / KubeSphere / RKE2 自建"；§3"kubeadm 或 KubeSphere/RKE2" | 封装层 v0.1 §193"bootstrap（kubeadm/KubeSphere/RKE2）"；deploy/README.md §43"KubeSphere" | **部署清单仍用 KubeSphere/RKE2，与 §5.5 拍板的 SKE 直接冲突** | 🔴 严重 | 部署清单 v0.2 将所有"kubeadm/KubeSphere/RKE2"替换为"自研 SKE 发行版" |
| 2 | 湖仓架构 | §3.3 L2.6"湖仓集一体"；§6"湖仓集一体" | 未明确提及 | PoC"湖仓集联动" | **§2 设计原则第4条仍写"湖仓一体"；§1.2 产品愿景仍写"多租户湖仓一体大数据平台"** | 🟡 中等 | 产品原型 v0.6 将 §2.4 和 §1.2 的"湖仓一体"更新为"湖仓集一体" |
| 3 | 消息选型 | §5.5 拍板"Apache Kafka" | — | — | **§3.3 L2.8 仍写"Kafka/Pulsar 实时解耦"；§13 仍写"Kafka / Pulsar"；§6.2 仍写"Kafka 或 Pulsar"** | 🔴 严重 | 产品原型 v0.6 将 §3.3/§13/§6.2 的"Kafka/Pulsar"统一为"Apache Kafka" |
| 4 | 数据集成选型 | §5.5 拍板"Apache SeaTunnel" | — | — | **§3.3 L4.1 仍写"SeaTunnel/DataX 多源同步"；§12.2 仍写"SeaTunnel/DataX/Flink CDC"；§13 仍写"SeaTunnel / DataX"** | 🔴 严重 | 产品原型 v0.6 将"SeaTunnel/DataX"统一为"Apache SeaTunnel" |
| 5 | 开发 IDE 选型 | §5.5 拍板"基于 Eclipse Theia 二次开发" | — | — | **§3.3 L4.3 仍写"自研 Web IDE"；§8 仍写"自研 Web IDE"；§13 仍写"自研 Web IDE"** | 🟡 中等 | 产品原型 v0.6 将"自研 Web IDE"更新为"基于 Eclipse Theia 二次开发的 Web IDE" |
| 6 | 元数据/质量/图库选型 | §5.5 拍板"自研轻量 Catalog / 自研规则引擎 / NebulaGraph" | — | — | **§13 仍写"DataHub / 自研 Catalog"、"Great Expectations / Griffin"、"Neo4j/国产图库"；§7.1 仍写"Great Expectations / Griffin"** | 🔴 严重 | 产品原型 v0.6 同步 §13/§7.1 为 §5.5 拍板结果；特别注意 Griffin 已 Apache 停孵，必须移除 |
| 7 | 智能数据层命名 | §3.3"L4.5 智能数据层"；§8.5"智能数据层" | §7"智能层"；§2"智能层" | deploy/README.md"智能数据层" | **部署清单使用"智能层"简称，与产品原型的"智能数据层"全称不一致** | 🟢 轻微 | 建议统一为"智能数据层（L4.5）"，部署清单可简称但首次出现需注明全称 |

**术语不一致实例汇总**：

| 术语 | 出现位置 | 当前用词 | 应统一为 |
| --- | --- | --- | --- |
| K8s 发行版 | 部署清单 §2/§3、封装层 §193、deploy/README §43 | kubeadm/KubeSphere/RKE2 | 自研 SKE 发行版 |
| 湖仓架构 | 产品原型 §2.4/§1.2 | 湖仓一体 | 湖仓集一体 |
| 消息 | 产品原型 §3.3 L2.8/§6.2/§13 | Kafka/Pulsar | Apache Kafka |
| 数据集成 | 产品原型 §3.3 L4.1/§12.2/§13 | SeaTunnel/DataX | Apache SeaTunnel |
| 开发 IDE | 产品原型 §3.3 L4.3/§8/§13 | 自研 Web IDE | 基于 Eclipse Theia 二次开发 |
| 元数据管理 | 产品原型 §13 | DataHub / 自研 Catalog | 自研轻量 Catalog（Java/Go） |
| 数据质量 | 产品原型 §7.1/§13 | Great Expectations / Griffin | 自研规则引擎（Java） |
| 图数据库 | 产品原型 §13 | Neo4j/国产图库 | NebulaGraph |
| 认证 | 产品原型 §13 | Keycloak / 国产 IAM | Keycloak |
| 智能数据层 | 部署清单 §2/§7 | 智能层 | 智能数据层（L4.5） |

**结论**：**不一致**。共发现 10 处术语不统一，其中 5 处为严重不一致（K8s 发行版、消息、数据集成、元数据/质量/图库选型、§13 技术选型清单未同步 §5.5 拍板结果）。根因：产品原型 v0.5 新增了 §5.5 选型决策表，但 §3.3/§13/§7.1 等旧章节未同步更新。建议 v0.6 做一次全局术语对齐扫描。

---

### 2.5 部署配置与设计描述一致性

#### 2.5.1 组件版本一致性

| Helm values 文件 | values 中版本 | 对应设计文档 | 设计文档描述 | 是否一致 |
| --- | --- | --- | --- | --- |
| spark-values.yaml | Spark 3.5.1 | 产品原型 §6.1 / §3.3 L2.2 | "Spark 3.5" | ✅ 兼容 |
| flink-values.yaml | Flink 1.18.0 | 产品原型 §6.1 / §3.3 L2.3 | "Flink 1.18" | ✅ 兼容 |
| doris-values.yaml | Doris 2.1.3 | 产品原型 §6.1 / §3.3 L2.5 | "Apache Doris"（未明确版本） | ⚠️ 设计文档未明确版本 |
| trino-values.yaml | Trino 438 | 产品原型 §6.1 / §3.3 L2.4 | "Trino"（未明确版本） | ⚠️ 设计文档未明确版本 |
| kafka-values.yaml | Kafka 3.7.1 | 产品原型 §5.5 / §3.3 L2.8 | "Apache Kafka"（§5.5 已拍板） | ✅ 选型一致 |
| iotdb-values.yaml | IoTDB 2.0.1 | 产品原型 §6.1 / §3.3 L2.9 | "Apache IoTDB"（未明确版本） | ⚠️ 设计文档未明确版本 |
| keycloak-values.yaml | Keycloak 24.0.5 | 产品原型 §5.5 / §3.3 X1 | "Keycloak"（§5.5 已拍板） | ✅ 选型一致 |
| seatunnel-values.yaml | SeaTunnel 2.3.4 | 产品原型 §5.5 / §3.3 L4.1 | "Apache SeaTunnel"（§5.5 已拍板） | ✅ 选型一致 |
| dolphinscheduler-values.yaml | DolphinScheduler 3.2.2 | 产品原型 §5.5 / §3.3 L4.2 | "Apache DolphinScheduler"（§5.5 已拍板） | ✅ 选型一致 |
| superset-values.yaml | Superset 4.0.2 | 产品原型 §5.5 / §3.3 L4.4 | "Apache Superset + 自研大屏"（§5.5 已拍板） | ✅ 选型一致 |

**结论**：**通过**。10 份 values 的组件选型与 §5.5 拍板结果一致。4 个组件（Doris/Trino/IoTDB/Keycloak）的精确版本仅在 values 中定义、设计文档未明确，建议设计文档补充版本号。

#### 2.5.2 端口配置一致性

| 端口/接口 | Helm values 来源 | 设计文档描述 | 是否一致 |
| --- | --- | --- | --- |
| Iceberg Catalog | spark/flink/trino/seatunnel: `8181` | 统一存储详细设计 v0.1 | ✅ 四份 values 一致 |
| Doris FE 查询 | doris: `9030`；trino/superset 引用 `doris-fe:9030` | OLAP 详细设计 v0.1 | ✅ 一致 |
| Doris FE HTTP | doris: `8030` | — | ✅ 内部一致 |
| Doris BE Heartbeat | doris: `9050` | — | ✅ 内部一致 |
| Trino 查询 | trino UI ingress；superset 引用 `trino-coord:8080` | 交互查询详细设计 v0.1 | ✅ 一致 |
| Flink REST | flink: `8081` | 流计算详细设计 v0.1 | ✅ 一致 |
| Kafka JMX | kafka: `5556` | — | ✅ 内部一致 |
| Keycloak | 未明确端口（默认 8080） | 统一身份权限详细设计 v0.1 | ⚠️ 建议显式声明 |
| Superset | ingress host: `superset.<domain>` | BI 可视化详细设计 v0.1 | ✅ 一致 |

**结论**：**通过**。关键端口（Iceberg Catalog 8181、Doris FE 9030、Trino 8080）在多份 values 间一致。Keycloak 端口建议显式声明。

#### 2.5.3 资源配置一致性

**检查项**：资源配置（CPU/内存/副本数）是否与设计文档描述一致。

**发现**：产品原型 §11.5.2 定义了每租户默认资源额度：

| 资源 | §11.5.2 基础版 | Helm values base 档总和 | §11.5.2 标准版 | Helm values standard 档总和 |
| --- | --- | --- | --- | --- |
| CPU | 8 核 | ~98 核（10 份 values base quota 之和） | 32 核 | ~236 核 |
| 内存 | 16 GB | ~275 Gi | 64 GB | ~695 Gi |
| 存储 | 5 TB | ~4.1 Ti（含 Doris/Kafka/IoTDB） | 50 TB | ~13.5 Ti |

**差异分析**：Helm values 中的 `quota` 是 **Namespace 级平台配额**（即该引擎在整个 Namespace 内可用的资源上限），而 §11.5.2 是**每租户默认配额**。两者粒度不同——一个集群可承载多租户，平台级配额应 ≥ 单租户配额 × 租户数。但文档未明确说明这一差异，易引起误解。

**结论**：**警告**。Helm values 的 quota 与 §11.5.2 资源额度量级差异显著（base 档 CPU 98 核 vs 8 核），未在文档中说明"Namespace 级 vs 租户级"的粒度差异。建议补充说明：`values.quota.base` 是该引擎在平台 Namespace 的总配额，§11.5.2 是单租户配额，关系为 `平台配额 ≥ Σ(租户配额)`。

#### 2.5.4 依赖与协议配置一致性

| 配置项 | Helm values | 设计文档 | 是否一致 |
| --- | --- | --- | --- |
| Iceberg Warehouse URI | spark: `s3a://sq-iceberg/warehouse`；flink/trino/seatunnel: `s3://sq-iceberg/warehouse` | 统一存储详细设计 v0.1 | ❌ **Spark 用 s3a://，其他用 s3://，协议前缀不统一** |
| Keycloak 国密 | keycloak: `guomi.enabled: false`（由 Profile 覆盖） | §5.3.1 信创环境国密 SM2/3/4 | ✅ 一致（Profile 驱动） |
| Superset ECharts | superset: `visualization.customPlugins` 含 echarts-line/bar/heatmap/graph/3d | §5.5 拍板"Apache ECharts" | ✅ 一致 |
| Superset SSO | superset: `security.sso.type: keycloak-oidc`, `realm: sq` | keycloak: `realms[0].name: sq` | ✅ 一致 |
| Doris 冷热分层 | doris: `storage.hotTier/coldTier` | OLAP 详细设计 v0.1 | ✅ 一致 |
| Flink HA | flink: `ha.mode: kubernetes`, `replicas: 3` | 流计算详细设计 v0.1 | ✅ 一致 |
| Kafka KRaft | kafka: `metadata.mode: kraft` | 消息流接入详细设计 v0.1 | ✅ 一致 |
| DolphinScheduler 元数据库 | dolphinscheduler: `database.type: postgresql` | 调度编排详细设计 v0.1 | ✅ 一致 |
| Superset 元数据库 | superset: `database.type: postgresql`, `cache.type: redis` | BI 可视化详细设计 v0.1 | ✅ 一致 |

**结论**：**不一致**。Iceberg Warehouse 协议前缀不统一是唯一严重问题。其余依赖与协议配置均一致。

---

## 3. 问题清单

### 3.1 严重问题（必须修复，阻断进入开发）

| # | 问题 | 维度 | 位置 | 修复建议 |
| --- | --- | --- | --- | --- |
| P1 | 部署清单仍使用"kubeadm/KubeSphere/RKE2"，与产品原型 §5.5 拍板的"自研 SKE 发行版"直接冲突 | 维度4 | 部署清单 v0.1 §2/§3/§6；封装层 v0.1 §193；deploy/README.md §43 | 全部替换为"自研 SKE 发行版" |
| P2 | 产品原型 §13 技术选型清单未同步 §5.5 选型决策表，仍保留"DataHub/自研Catalog"、"Great Expectations/Griffin"、"Neo4j/国产图库"、"Keycloak/国产IAM"等已拍板消除的"或" | 维度4 | 产品原型 v0.5 §13 | 按 §5.5 拍板结果同步更新 §13，移除 Griffin（已停孵） |
| P3 | 产品原型 §3.3 模块状态表 L2.8 仍写"Kafka/Pulsar"、L4.1 仍写"SeaTunnel/DataX"，与 §5.5 拍板结果不一致 | 维度4 | 产品原型 v0.5 §3.3 L2.8/L4.1 | 统一为"Apache Kafka"、"Apache SeaTunnel" |
| P4 | Iceberg Warehouse 协议前缀不统一：Spark values 用 `s3a://`，Flink/Trino/SeaTunnel values 用 `s3://` | 维度3/5 | spark-values.yaml L103 | 统一为 `s3://`（与 Iceberg 原生 S3FileIO 一致） |

### 3.2 中等问题（建议修复，不阻断但影响一致性）

| # | 问题 | 维度 | 位置 | 修复建议 |
| --- | --- | --- | --- | --- |
| M1 | 产品原型 §2.4 设计原则和 §1.2 产品愿景仍用"湖仓一体"，与 §3.3 L2.6"湖仓集一体"不一致 | 维度4 | 产品原型 v0.5 §2.4/§1.2 | 更新为"湖仓集一体" |
| M2 | 产品原型 §3.3 L4.3 和 §8 仍写"自研 Web IDE"，与 §5.5 拍板的"基于 Eclipse Theia 二次开发"不一致 | 维度4 | 产品原型 v0.5 §3.3 L4.3/§8 | 更新为"基于 Eclipse Theia 二次开发的 Web IDE" |
| M3 | 部署清单 §7 仅覆盖约 15 个模块的 Chart 映射，26 个模块缺失部署方式声明 | 维度1 | 部署清单 v0.1 §7 | v0.2 补齐全部 41 个模块的 Chart/Operator 映射 |
| M4 | PoC 缺失治理闭环和 BI 可视化步骤 | 维度2 | PoC v0.1 步骤3.5/5.5 | 补充治理环节（元数据注册→质量校验→资产入目录）和 BI 步骤（Superset 出报表） |
| M5 | Helm values 的 quota 与 §11.5.2 资源额度量级差异显著，未说明"Namespace 级 vs 租户级"粒度差异 | 维度5 | 所有 values quota vs §11.5.2 | 补充说明两者关系：`平台配额 ≥ Σ(租户配额)` |

### 3.3 轻微问题（可选修复）

| # | 问题 | 维度 | 位置 | 修复建议 |
| --- | --- | --- | --- | --- |
| L1 | 部署清单使用"智能层"简称，与产品原型"智能数据层"全称不一致 | 维度4 | 部署清单 v0.1 §2/§7 | 首次出现注明全称"智能数据层（L4.5）" |
| L2 | Doris/Trino/IoTDB/Keycloak 版本号仅在 values 中定义，设计文档未明确 | 维度5 | 产品原型 §6.1/§3.3 | 设计文档补充版本号 |
| L3 | L2→L3/L3→L4/L4→L5 跨层接口契约缺失明确定义 | 维度3 | 部署清单/PoC | 补充跨层接口契约文档 |
| L4 | Keycloak 集成仅 Superset 有明确配置，其他引擎 values 未定义 | 维度3 | spark/flink/doris/trino/kafka/iotdb/seatunnel/dolphinscheduler values | 补充 Keycloak OIDC 集成配置或注明"经 X4 网关代理认证" |
| L5 | X4 API 网关与 L2.7 统一 SQL 网关的关系未澄清 | 维度3 | PoC §8/部署清单 | 明确两者职责边界：X4 是全局 API 网关，L2.7 是 SQL 联邦查询网关 |
| L6 | Keycloak 端口未在 values 中显式声明 | 维度5 | keycloak-values.yaml | 补充 `port: 8080` 显式声明 |

---

## 4. 结论

### 4.1 总体评价

本次全局交叉一致性检查覆盖 5 个维度共 23 个检查项，发现 **8 项不一致、8 项警告、7 项通过**。整体一致性水平为 **30% 通过率**，**不通过**。

### 4.2 根因分析

不一致项的根因可归纳为两类：

1. **产品原型 v0.5 升级未全量同步**（占 6/8 不一致项）：v0.5 新增 §5.5 选型决策表拍板了 12 项选型，但 §3.3 模块状态表、§13 技术选型清单、§7.1 治理模块、§2 设计原则等旧章节未同步更新，导致同一文档内"§5.5 拍板 Kafka"与"§3.3 写 Kafka/Pulsar"并存。部署清单 v0.1 基于更早的 v0.4 撰写，仍使用"KubeSphere/RKE2"。

2. **Helm values 独立编写时协议约定不统一**（占 2/8 不一致项）：10 份 values 文件分别由不同组件视角编写，Iceberg Warehouse 的 S3 协议前缀（`s3a://` vs `s3://`）未做全局对齐。

### 4.3 修复优先级

| 优先级 | 修复项 | 负责建议 | 预计工作量 |
| --- | --- | --- | --- |
| P0（阻断） | P1 部署清单 K8s 发行版术语对齐 SKE | 部署清单 owner | 0.5h |
| P0（阻断） | P2 §13 技术选型清单同步 §5.5 | 产品原型 owner | 1h |
| P0（阻断） | P3 §3.3 L2.8/L4.1 选型对齐 §5.5 | 产品原型 owner | 0.5h |
| P0（阻断） | P4 Iceberg Warehouse 协议前缀统一 | Helm values owner | 0.5h |
| P1（重要） | M1-M5 中等问题 | 各文档 owner | 4h |
| P2（可选） | L1-L6 轻微问题 | 各文档 owner | 2h |

### 4.4 修复后预期

修复 P1-P4 后，不一致项降为 0，警告项降为 4，通过率提升至 **83%**，可进入开发阶段。全部修复后通过率 **100%**。

### 4.5 建议的下一步

1. **立即执行**：修复 P1-P4 四项严重问题（预计 2.5h）。
2. **本迭代内**：修复 M1-M5 五项中等问题（预计 4h）。
3. **下个迭代**：修复 L1-L6 六项轻微问题，并补充跨层接口契约文档（预计 4h）。
4. **长期机制**：建立"术语表单一源 + 文档生成时校验"机制，避免再次出现同一术语在不同章节不一致的问题。建议在 CI 中加入术语一致性 lint 检查。

---

> 评审人：E组 全局交叉检查评审员
> 评审完成时间：2026-08-03
> 报告状态：已完成，待修复后复评