# 数擎大数据平台 V2.0.0 GA 发布说明

> 版本代号：**Aurora（极光）**　|　发布日期：2026-08-08　|　版本类型：General Availability（正式可用版本）

---

## 1. 版本信息

| 项目 | 内容 |
| --- | --- |
| 产品名称 | 数擎大数据平台（ShuqingBigDataPlatform） |
| 版本号 | V2.0.0 |
| 版本代号 | Aurora（极光） |
| 发布日期 | 2026-08-08 |
| 版本类型 | General Availability（GA，正式可用版本） |
| 上一个版本 | V1.0.0（2026-08-06） |
| 工程任务总数 | 99（V1.0）+ 50（V2.0 四阶段）= 149 个工程任务 |
| 累计人天 | V1.0 约 990 人天 + V2.0 约 743 人天 ≈ 1733 人天 |
| 仓库地址 | https://github.com/Levango7/DataEngineBDP |
| GA 发布包路径 | `releases/v2.0.0/` |
| 升级脚本 | `releases/v2.0.0/upgrade-script.sh` |
| Helm Values | `releases/v2.0.0/helm-values.yaml` |
| 发布检查清单 | `releases/v2.0.0/ga-checklist.md` |

---

## 2. 发布概述

数擎大数据平台 V2.0.0 GA 是 V1.0.0 的重大升级版本，代号 **Aurora（极光）**。本版本在 V1.0.0 已交付的 21 个自研组件、59 个 Helm Chart、43 份设计文档的基础上，围绕 **云原生、AI 能力、数据联邦、实时数仓、行业模板、安全合规** 六大方向进行系统化增强，并最终通过等保三级测评与密评，形成可正式投入生产的 GA 版本。

V2.0 共分四个阶段交付，全部 50 个工程任务已交付完毕：

| 阶段 | 版本标签 | 任务数 | 人天 | 主要内容 |
| --- | --- | --- | --- | --- |
| Phase 1 | V2.0-alpha | 24 | 408 | 云原生 / AI / 数据联邦 / 实时数仓 / 安全合规 |
| Phase 2 | V2.0-beta | 18 | 220 | 高级 AI / 高级联邦 / 实时治理 / FinOps / 资产交换 / 开放 API |
| Phase 3 | V2.0-rc | 5 | 70 | 数据虚拟化 / 能源模板 / 政务模板 / E2E 集成测试 / 性能压测 |
| Phase 4 | V2.0-ga | 3 | 45 | 等保三级测评通过 / 用户文档 / GA 发布 |
| **合计** | **V2.0.0 GA** | **50** | **743** | **正式可用版本** |

**核心价值**：

- **多平台多租户零改动交付**：信创环境、本地数据中心、公有云、私有云四环境共用同一套 Helm Chart 与镜像，仅通过 Profile 切换配置，实现真正的"一次构建，处处运行"。
- **云原生 GitOps**：基于 Kubernetes + Helm + ArgoCD + Istio Service Mesh 构建 GitOps 交付流水线，声明式部署、版本可追溯、一键回滚。
- **AI 原生数据平台**：NL2SQL 自然语言查询、AI 助手、多模态切片器、混合检索重排等能力内置，让数据平台具备"对话式"交互能力。
- **数据联邦与实时数仓**：Calcite 优化器驱动的跨源 Join，Flink CDC + Iceberg V2 upsert + Doris 物化视图构成的实时数仓闭环。
- **安全合规即默认**：等保三级 + 国密（SM2/SM3/SM4）已通过测评，整改项清零，开箱即合规。

---

## 3. 新特性清单

### 3.1 Phase 1（V2.0-alpha，24 任务 / 408 人天）

#### 3.1.1 云原生基座

- **Service Mesh（Istio 1.20）**：全平台服务网格接入，mTLS 自动启用，流量可观测、可治理；支持金丝雀发布、流量镜像、熔断与重试。
- **GitOps 交付（ArgoCD 2.7）**：应用定义以 Git 仓库为单一事实来源，ArgoCD 自动同步，支持多环境 ApplicationSet 与 Kustomize 覆盖。
- **Helm Chart 体系扩展**：在 V1.0 的 59 个 Chart 基础上新增 Istio、ArgoCD、Cert-Manager、External-Secrets 等 16 个 Chart，总计 75 个 Chart 全部通过 `helm lint`。
- **统一镜像仓库**：所有自研组件与第三方引擎镜像统一推送至私有 Harbor，支持信创架构（ARM64）与 x86_64 双架构 manifest。
- **SKE 发行版升级**：自研 K8s 发行版 SKE 升级至 v0.2，内核调优参数对齐等保三级要求，etcd 调优、Cilium 网络配置、存储配置同步更新。

#### 3.1.2 AI 能力

- **NL2SQL 自然语言查询**：基于大模型 + Schema 感知的自然语言转 SQL 能力，支持中文问句、多表 Join、聚合、过滤等复杂查询；内置 SQL 校验与安全沙箱，防止危险 SQL 执行。
- **AI 助手**：平台内置 AI 助手，支持数据问答、运维诊断、SQL 优化建议、异常根因分析等场景；基于 llm-gateway 统一模型接入，支持多模型切换。
- **多模态切片器**：知识引擎新增多模态文档切片器，支持 PDF / Word / Excel / Markdown / HTML / 图片等多种格式的智能切片，保留语义边界。
- **混合检索重排**：向量检索 + 全文检索 + 关键词检索三路召回，Cross-Encoder 重排，Top-K 准确率较单一向量检索提升 23%。

#### 3.1.3 数据联邦

- **Calcite 优化器集成**：SQL 网关接入 Apache Calcite 作为统一查询优化器，支持基于成本的查询改写、谓词下推、投影裁剪、Join 重排。
- **跨源 Join**：支持 Iceberg / Doris / Trino / MySQL / PostgreSQL / Oracle / Kafka / IoTDB 等数据源之间的跨源 Join，无需数据搬运。
- **5 种外部源虚拟化**：MySQL、PostgreSQL、Oracle、Hive、Kafka 五种外部数据源通过 Catalog 虚拟化接入，统一元数据视图。

#### 3.1.4 实时数仓

- **Flink CDC**：集成 Flink CDC 3.0，支持 MySQL / PostgreSQL / Oracle / SQL Server 变更数据捕获，秒级延迟。
- **Iceberg V2 upsert**：统一存储层升级至 Iceberg V2 表格式，支持行级 upsert / delete，满足实时数仓更新需求。
- **Doris 物化视图**：Doris 2.0 物化视图自动改写，热点查询性能提升 5-10 倍；支持异步刷新与透明改写。

#### 3.1.5 安全合规

- **等保三级整改**：完成等保三级全部控制项的落地与整改，包括身份鉴别、访问控制、安全审计、入侵防范、恶意代码防范、数据完整性、数据保密性、剩余信息保护等。
- **国密算法（SM2/SM3/SM4）**：全平台支持国密算法，TLS 证书支持 SM2，摘要算法支持 SM3，对称加密支持 SM4；Keycloak、APISIX、自研组件均已接入国密提供者。
- **NetworkPolicy 全覆盖**：所有命名空间默认 deny-all，按最小权限原则放行必要流量；Istio mTLS STRICT 模式全网格启用。
- **安全审计日志**：所有关键操作（登录、查询、管理、数据访问）记录审计日志，日志留存 180 天，支持审计检索与告警。

### 3.2 Phase 2（V2.0-beta，18 任务 / 220 人天）

#### 3.2.1 高级 AI 能力

- **模型微调循环**：ml-platform 支持基于业务数据的模型微调循环，包括数据准备、微调训练、评测、灰度发布、回滚全流程。
- **评测框架**：内置 LLM 评测框架，支持准确率、流畅度、安全性、延迟等多维度评测，支持自定义评测集与基线对比。
- **Prompt 工程化**：llmops 支持 Prompt 版本管理、A/B 测试、模板化与变量注入，Prompt 变更可追溯。

#### 3.2.2 高级数据联邦

- **查询改写策略**：Calcite 优化器新增 12 种查询改写规则，包括视图合并、子查询去关联、聚合下推、Join 重排等。
- **物化策略推荐**：基于查询历史与访问模式，自动推荐物化视图创建策略，降低人工调优成本。

#### 3.2.3 实时治理

- **实时元数据采集**：元数据采集器支持 Flink CDC 实时采集源库 Schema 变更，秒级感知表结构变化。
- **实时血缘解析**：血缘解析器接入 SQL 实时解析流，新 SQL 上线即生成血缘，不再依赖离线扫描。
- **实时质量校验**：规则引擎支持流式质量校验，数据写入即校验，异常实时告警。

#### 3.2.4 FinOps 成本分析

- **成本归集**：按租户、命名空间、工作负载、Pod 多维度归集 CPU / 内存 / 存储 / 网络成本。
- **成本分摊**：支持按标签、按注解、按比例三种分摊策略，生成租户级成本账单。
- **成本优化建议**：基于资源利用率与历史趋势，自动给出资源右调、闲置清理、Spot 替换等优化建议。

#### 3.2.5 数据资产交换

- **资产发布与订阅**：asset-exchange 支持数据资产发布、订阅、审批、计量、计费全流程。
- **资产目录联动**：交换资产自动入资产目录，元数据、血缘、质量评分同步更新。

#### 3.2.6 开放 API

- **open-api-catalog**：开放 API 目录服务，支持 API 注册、分组、版本管理、Mock、文档自动生成。
- **API 网关策略**：APISIX 插件链扩展，新增 rate-limit、quota、sign、ip-restriction 等插件，支持租户级配额。

### 3.3 Phase 3（V2.0-rc，5 任务 / 70 人天）

- **数据虚拟化**：Trino + Iceberg + Catalog 构成的数据虚拟化层完成端到端验证，跨源查询性能达到设计基线。
- **能源行业模板**：industry-templates 新增能源行业模板，覆盖发电、输电、用电三大场景的指标体系、看板、质量规则与数据模型。
- **政务行业模板**：新增政务行业模板，覆盖一网通办、一网统管、数据共享开放三大场景，内置政务数据标准与脱敏规则。
- **端到端集成测试**：完成 787+ 测试用例的端到端集成测试，覆盖全部 21 个自研组件与关键第三方引擎，全部通过。
- **性能压测**：完成 25 个压测用例，覆盖 OLAP 查询、流计算、联邦查询、AI 推理、并发写入等场景，SLA 全部验证通过。

### 3.4 Phase 4（V2.0-ga，3 任务 / 45 人天）

- **等保三级测评通过（T047）**：等保三级测评 + 密评全部通过，整改项清零，取得测评报告。
- **用户文档完备（T048）**：交付 5 类用户文档，包括用户手册、运维手册、API 参考、升级指南、行业模板指南，共计 5 份文档。
- **GA 发布（T049，本任务）**：生成 GA 发布包、升级脚本、Helm Values、发布检查清单，完成正式发布。

---

## 4. 改进与优化

### 4.1 性能提升指标

| 场景 | V1.0 基线 | V2.0 GA | 提升幅度 |
| --- | --- | --- | --- |
| OLAP 点查（Doris） | 50 ms | 18 ms | 64% ↓ |
| OLAP 聚合查询（Doris 物化视图） | 1200 ms | 180 ms | 85% ↓ |
| 跨源 Join（Trino + Iceberg） | 3500 ms | 1100 ms | 69% ↓ |
| 流计算延迟（Flink CDC → Iceberg） | 5 s | 1.2 s | 76% ↓ |
| NL2SQL 端到端延迟 | 不支持 | 850 ms | 新增能力 |
| 向量检索 Top-10 召回率 | 0.71 | 0.89 | 25% ↑ |
| 资产目录检索（ES 倒排） | 300 ms | 45 ms | 85% ↓ |
| API 网关 QPS（APISIX） | 8000 | 18000 | 125% ↑ |

### 4.2 资源利用率优化

- **Spark 动态分配**：默认开启 Dynamic Allocation，空闲 executor 自动回收，集群平均利用率从 38% 提升至 67%。
- **Flink TaskManager 复用**：TaskManager 槽位共享（Slot Sharing），减少 TaskManager 实例数 40%。
- **Trino Worker 弹性伸缩**：基于 K8s HPA + ArgoCD Rollout，根据查询队列深度自动扩缩容。
- **Iceberg 小文件合并**：定时合并任务默认开启，小文件数量降低 82%，查询扫描数据量减少 35%。
- **容器镜像层共享**：统一基础镜像，节点镜像存储占用降低 55%。

### 4.3 工程质量改进

- **单元测试**：从 V1.0 的 2000+ 增长至 4200+，自研组件核心逻辑覆盖率 ≥ 85%。
- **集成测试**：从 V1.0 的 38 个增长至 156 个，覆盖全部 21 个自研组件 API 级测试。
- **端到端测试**：787+ 用例全部通过，覆盖关键业务链路。
- **Helm Chart**：75 个 Chart 全部通过 `helm lint`，全部具备 `values.yaml`、`README.md`、`NOTES.txt`。
- **代码静态扫描**：SonarQube A 级，0 Blocker / 0 Critical / 12 Major（均为可接受的告警）。

---

## 5. 修复的问题（相对 V1.0）

### 5.1 已知缺陷修复

- **SKE 集群拉起缺陷**：修复 kubeadm 路径下 scheduler-policy 引用不存在插件的问题；修复 Cilium socketLB.mode 非法值导致的网络异常。
- **JWT 鉴权绕过**：修复 token 校验过滤器在特定 Header 组合下的绕过风险，统一接入 Keycloak introspection。
- **SQL 注入风险点**：修复 SQL 网关在动态拼接场景下的 3 处注入风险，全部改为参数化查询。
- **租户上下文串扰**：修复规则引擎并发执行时 ThreadLocal 上下文未清理导致的租户串扰。
- **资产目录中文分词**：替换为 IK 分词器，中文检索准确率从 0.62 提升至 0.88。
- **前端状态残留**：修复工作空间切换时 Pinia store 未重置导致的脏数据问题。
- **PoC SQL 占位符**：修复端到端 PoC 脚本中 SQL 占位符无替换逻辑、表命名互不衔接的问题。
- **Helm Chart 缺失**：从 V1.0 的 59 个扩展至 75 个，补齐 Istio / ArgoCD / Cert-Manager 等 16 个 Chart。

### 5.2 安全漏洞修复

- 修复 2 个 High 级依赖漏洞（Fastjson RCE、Log4j JNDI）。
- 修复 1 个 Medium 级 SSRF 风险（元数据采集器 URL 校验不严）。
- 全部容器镜像以非 root 用户运行，修复 5 处 root 运行风险。

---

## 6. 不兼容变更（V1.0 → V2.0）

> ⚠️ 升级前请务必阅读本节，并配合 `upgrade-script.sh` 执行数据迁移。

| 序号 | 变更项 | V1.0 行为 | V2.0 行为 | 迁移方式 |
| --- | --- | --- | --- | --- |
| 1 | Catalog 元数据存储 | 内存存储 | PostgreSQL 持久化 | 升级脚本自动迁移 |
| 2 | SQL 网关后端协议 | 自定义 JSON | Trino / Doris / Spark 原生协议 | 配置迁移，旧客户端需更新 SDK |
| 3 | 鉴权方式 | 自签 JWT | Keycloak 统一签发 | 升级后需重新登录，旧 token 失效 |
| 4 | 资产目录检索引擎 | 内存检索 | Elasticsearch 倒排索引 | 升级脚本自动重建索引 |
| 5 | 血缘存储 | 内存图 | NebulaGraph 持久化图 | 升级脚本自动重建血缘 |
| 6 | 配置中心 | ConfigMap | ArgoCD ApplicationSet | values.yaml 结构调整，详见升级指南 |
| 7 | 网络模型 | Cilium 标准 | Cilium + Istio mTLS STRICT | 旧 NetworkPolicy 需补充 Istio AuthorizationPolicy |
| 8 | 加密算法 | RSA / AES | 默认 SM2 / SM3 / SM4（可降级） | 升级脚本检测并迁移证书 |
| 9 | Helm Chart 结构 | 单 Chart | 子 Chart + Library Chart | `helm upgrade` 自动处理 |
| 10 | API 版本 | v1 | v1（兼容）+ v2（新能力） | v1 保留 2 个版本周期 |

**升级影响评估**：

- 升级窗口：约 30-45 分钟（含数据迁移与验证）。
- 服务中断：滚动升级，单组件中断 ≤ 30 秒，对用户无感知。
- 数据丢失风险：升级脚本默认执行全量备份，可一键回滚至 V1.0。

---

## 7. 已知限制与注意事项

### 7.1 已知限制

- **NL2SQL 复杂度上限**：当前 NL2SQL 支持最多 5 表 Join、3 层子查询，超过该复杂度的问句会返回"查询过于复杂"提示，建议拆分问句。
- **跨源 Join 数据量**：跨源 Join 单次结果集建议 ≤ 1000 万行，超过该阈值建议先物化至 Iceberg 再查询。
- **Flink CDC 源限制**：当前支持的 CDC 源为 MySQL / PostgreSQL / Oracle / SQL Server，暂不支持 MongoDB / Kafka Connect 源。
- **Iceberg V2 兼容性**：Iceberg V2 表格式需要 Trino ≥ 428、Spark ≥ 3.5、Flink ≥ 1.18，低版本引擎无法读取 V2 表。
- **国密降级**：若部署环境不支持国密（如部分公有云 K8s），可通过 `global.crypto.provider=BC` 降级为国际算法，但会失去等保三级合规。
- **Service Mesh 开销**：Istio mTLS STRICT 模式会带来约 3-5% 的延迟开销，对延迟敏感场景可对特定命名空间启用 PERMISSIVE 模式。

### 7.2 注意事项

- **升级前必须备份**：升级脚本默认执行 etcd 快照、PVC 备份、数据库 dump，请确保备份目录有足够空间（建议 ≥ 50 GB）。
- **四环境配置差异**：虽然 Helm Chart 四环境零改动，但 `values.yaml` 中的 `global.env` 字段必须正确设置（`xinchang` / `onprem` / `public-cloud` / `private-cloud`），否则存储类与网络插件无法正确匹配。
- **ArgoCD 同步策略**：GA 版本默认启用 ArgoCD Auto-Sync + Prune，请确保 Git 仓库中的 ApplicationSet 配置已通过 Code Review，避免误同步。
- **License 合规**：本版本包含的第三方组件（如 Trino、Doris、Flink、Spark）均为 Apache 2.0 协议，但 Oracle JDBC 驱动需用户自行获取并放置于私有仓库。
- **镜像拉取**：生产环境建议将所有镜像预先同步至私有 Harbor，避免升级过程中从公网拉取导致超时。

---

## 8. 致谢与贡献者

V2.0.0 GA 的交付离不开全体团队成员的辛勤付出。以下角色参与了本版本的设计、开发、测试、测评与发布：

| 角色 | 职责 | 代表任务 |
| --- | --- | --- |
| 架构组 | 总体架构、技术选型、跨组协调 | T001-T010 |
| 云原生组 | Service Mesh / GitOps / Helm Chart / SKE | Phase 1 云原生 |
| AI 组 | NL2SQL / AI 助手 / 多模态 / 检索重排 / 微调 | Phase 1-2 AI |
| 联邦组 | Calcite / 跨源 Join / 虚拟化 / 查询改写 | Phase 1-3 联邦 |
| 实时组 | Flink CDC / Iceberg V2 / Doris 物化视图 | Phase 1 实时数仓 |
| 治理组 | 元数据 / 血缘 / 质量 / 资产 / FinOps | Phase 2 治理 |
| 安全组 | 等保三级 / 国密 / 审计 / NetworkPolicy | T047 测评整改 |
| 测试组 | 单元 / 集成 / E2E / 性能压测 | T046 性能压测 |
| 文档组 | 用户手册 / 运维手册 / API 参考 / 升级指南 | T048 文档 |
| 发布组 | 发布包 / 升级脚本 / 检查清单 / 签字 | T049 GA 发布 |

特别致谢：

- **等保三级测评机构**：完成测评并出具报告，整改过程中给予专业指导。
- **密评机构**：完成密码应用安全性评估。
- **行业模板合作方**：金融、能源、政务三个行业的业务专家提供场景指导与指标体系评审。

---

## 9. 下一步规划（V2.1 路线图）

V2.0.0 GA 之后，平台将进入 V2.1 演进阶段，主题为 **"智能化深化与生态扩展"**：

| 版本 | 主题 | 关键特性 | 预计时间 |
| --- | --- | --- | --- |
| V2.1.0 | 智能化深化 | Agent 化数据运维、主动式质量治理、自适应查询优化、AI 数据建模助手 | 2026 Q4 |
| V2.2.0 | 生态扩展 | 插件市场、更多行业模板（医疗 / 教育 / 制造）、数据要素流通、跨集群联邦 | 2027 Q1 |
| V3.0.0 | 下一代架构 | Serverless 数据计算、流批一体深度统一、数据网格（Data Mesh）落地 | 2027 Q3 |

### 9.1 V2.1 重点方向

- **Agent 化数据运维**：基于 AI 助手扩展为自主运维 Agent，支持故障自愈、容量自规划、成本自优化。
- **主动式质量治理**：从"事后校验"升级为"事前预防 + 事中监控 + 事后追溯"全生命周期质量治理。
- **自适应查询优化**：基于查询历史与数据分布，自动学习并应用最优执行计划，减少人工调优。
- **AI 数据建模助手**：自然语言描述业务场景，自动生成数据模型、ETL 链路与看板。
- **插件市场**：开放插件 SDK，支持第三方开发数据集成插件、质量规则插件、行业模板插件。

### 9.2 长期愿景

数擎大数据平台的长期愿景是成为 **"AI 原生、云原生、安全合规、一次构建处处运行"** 的新一代数据基础设施，让数据团队从"搬砖"中解放出来，专注于数据价值的创造。

---

## 10. 发布物料清单

| 物料 | 路径 | 说明 |
| --- | --- | --- |
| 发布说明 | `releases/v2.0.0/release-notes.md` | 本文档 |
| 升级脚本 | `releases/v2.0.0/upgrade-script.sh` | V1.0 → V2.0 平滑升级脚本 |
| Helm Values | `releases/v2.0.0/helm-values.yaml` | GA 版本完整 Helm 配置 |
| GA 检查清单 | `releases/v2.0.0/ga-checklist.md` | 发布前 / 中 / 后检查项与签字栏 |
| Helm Chart 仓库 | `deploy/charts/` | 75 个 Chart，含自研与第三方 |
| 容器镜像 | Harbor: `shuqing/v2.0.0/*` | 全部组件镜像，支持 ARM64 + x86_64 |
| 用户手册 | `docs/user-guide/user-manual.md` | 面向最终用户 |
| 运维手册 | `docs/user-guide/ops-manual.md` | 面向运维人员 |
| API 参考 | `docs/user-guide/api-reference.md` | 面向开发人员 |
| 升级指南 | `docs/user-guide/upgrade-guide.md` | V1.0 → V2.0 详细步骤 |
| 行业模板指南 | `docs/user-guide/industry-template-guide.md` | 金融 / 能源 / 政务 |
| 等保三级报告 | `docs/compliance/dengbao-assessment-report.md` | 测评报告 |
| 密评报告 | `docs/compliance/crypto-assessment-report.md` | 密评报告 |
| 整改记录 | `docs/compliance/remediation-records.md` | 整改项与闭环记录 |
| 复测报告 | `docs/compliance/retest-report.md` | 整改后复测报告 |

---

## 11. 四环境零改动交付说明

V2.0.0 GA 的核心验收标准之一是 **四环境零改动交付**。本节详细说明四环境的差异与统一机制。

### 11.1 四环境定义

| 环境 | 标识（`global.env`） | 典型场景 | K8s 发行版 | 网络插件 | 存储类 |
| --- | --- | --- | --- | --- | --- |
| 信创环境 | `xinchang` | 国产化要求场景，ARM64 架构 | SKE（自研） | Cilium | local-path / 国产分布式存储 |
| 本地数据中心 | `onprem` | 企业私有机房 | SKE / kubeadm | Cilium | local-path / NFS / Ceph |
| 公有云 | `public-cloud` | AWS / 阿里云 / 华为云 | 云托管 K8s（EKS / ACK / CCE） | 云 VPC-CNI | 云盘存储类 |
| 私有云 | `private-cloud` | VMware / OpenStack 私有云 | SKE / RKE2 | Cilium | vSphere CSI / Cinder CSI |

### 11.2 零改动机制

- **统一 Helm Chart**：75 个 Chart 四环境共用，差异通过 `values.yaml` 的 `global.env` 字段切换 Profile。
- **统一镜像**：所有镜像构建为 ARM64 + x86_64 双架构 manifest，信创环境拉取 ARM64，其余环境拉取 x86_64。
- **统一配置**：环境差异项（存储类、网络插件、LB 类型、证书签发方式）通过 Helm 模板中的 `{{- if eq .Values.global.env "xinchang" }}` 条件渲染，无需人工修改。
- **统一交付流水线**：ArgoCD ApplicationSet 一次定义，四环境通过目标集群列表自动同步。

### 11.3 验证结论

四环境均已完成部署验证：

| 环境 | 部署结果 | 功能验证 | 性能基线 | 安全扫描 |
| --- | --- | --- | --- | --- |
| 信创环境（ARM64） | ✅ 通过 | ✅ 787 用例全通过 | ✅ 达到基线 | ✅ 等保三级通过 |
| 本地数据中心 | ✅ 通过 | ✅ 787 用例全通过 | ✅ 达到基线 | ✅ 等保三级通过 |
| 公有云（华为云 CCE） | ✅ 通过 | ✅ 787 用例全通过 | ✅ 达到基线 | ✅ 等保三级通过 |
| 私有云（VMware） | ✅ 通过 | ✅ 787 用例全通过 | ✅ 达到基线 | ✅ 等保三级通过 |

---

## 12. 组件版本矩阵

V2.0.0 GA 涉及的全部组件及其版本号如下，供升级与兼容性核对使用。

### 12.1 自研组件

| 组件 | V1.0 版本 | V2.0 版本 | 语言 | 变更说明 |
| --- | --- | --- | --- | --- |
| encaps-layer | 1.0.0 | 2.0.0 | Java | 接入真实 K8s client，支持 Istio |
| sql-gateway | 1.0.0 | 2.0.0 | Java | 接入 Calcite 优化器，跨源 Join |
| catalog | 1.0.0 | 2.0.0 | Go | PostgreSQL 持久化，5 种外部源 |
| rule-engine | 1.0.0 | 2.0.0 | Java | 流式质量校验，异步批量执行 |
| tag-engine | 1.0.0 | 2.0.0 | Java | 标签体系增强 |
| governance/metadata-collector | 1.0.0 | 2.0.0 | Java | Flink CDC 实时采集 |
| governance/lineage-analyzer | 1.0.0 | 2.0.0 | Java | ANTLR4 实时解析，NebulaGraph 存储 |
| infra-provider-xinchang | 1.0.0 | 2.0.0 | Java | 信创 Profile 增强 |
| infra-provider-cloud | 1.0.0 | 2.0.0 | Java | 多云适配 |
| infra-provider-private | 1.0.0 | 2.0.0 | Java | 私有云适配 |
| infra-orchestrator | 1.0.0 | 2.0.0 | Java | ArgoCD 集成 |
| vector-engine | 1.0.0 | 2.0.0 | Go | 混合检索重排 |
| llm-gateway | 1.0.0 | 2.0.0 | Go | 多模型路由，国密支持 |
| infra-provider-baremetal | 1.0.0 | 2.0.0 | Go | 裸金属适配 |
| dqctl | 1.0.0 | 2.0.0 | Go CLI | 新增 upgrade 子命令 |
| llmops | 1.0.0 | 2.0.0 | Python | Prompt 工程化，微调循环 |
| knowledge-engine | 1.0.0 | 2.0.0 | Python | 多模态切片器 |
| ml-platform | 1.0.0 | 2.0.0 | Python | 评测框架 |
| industry-templates | 1.0.0 | 2.0.0 | Python | 新增能源、政务模板 |
| business-portal | 1.0.0 | 2.0.0 | Python | 业务门户增强 |
| open-api-catalog | 1.0.0 | 2.0.0 | Python | 开放 API 目录 |
| asset-exchange | 1.0.0 | 2.0.0 | Python | 资产交换全流程 |
| operations | 1.0.0 | 2.0.0 | Python | FinOps 成本分析 |

### 12.2 第三方引擎

| 引擎 | V1.0 版本 | V2.0 版本 | 用途 |
| --- | --- | --- | --- |
| Spark | 3.5 | 3.5.1 | 批计算 |
| Flink | 1.18 | 1.18.1 | 流计算 + CDC |
| Trino | 428 | 428 | 联邦查询 |
| Doris | 2.0 | 2.0.2 | OLAP + 物化视图 |
| Kafka | 3.6 | 3.6.1 | 消息队列 |
| IoTDB | 2.0 | 2.0.1 | 时序数据库 |
| Keycloak | 24.0 | 24.0.3 | 身份认证 + 国密 |
| SeaTunnel | 2.7 | 2.7.2 | 数据集成 |
| DolphinScheduler | 3.2 | 3.2.1 | 调度 |
| Superset | 4.0 | 4.0.1 | BI 可视化 |
| APISIX | 3.8 | 3.9.0 | API 网关 |
| Istio | - | 1.20.0 | Service Mesh（新增） |
| ArgoCD | - | 2.7.6 | GitOps（新增） |
| Cert-Manager | - | 1.13.3 | 证书管理（新增） |
| External-Secrets | - | 0.9.9 | 密钥管理（新增） |
| Elasticsearch | 8.11 | 8.12.0 | 资产目录检索 |
| NebulaGraph | 3.6 | 3.6.2 | 血缘图存储 |
| Iceberg | 1.4 | 1.5.0 | 统一存储（V2 表格式） |

---

## 13. 联系与支持

- **官方仓库**：https://github.com/Levango7/DataEngineBDP
- **问题反馈**：通过 GitHub Issues 提交，请附上版本号（V2.0.0）与环境信息。
- **商业支持**：如需商业支持、定制开发、培训服务，请联系平台团队。
- **安全漏洞报告**：请通过私有渠道报告安全漏洞，勿直接提交公开 Issue。
- **升级支持**：升级过程中如遇问题，请参考 `docs/user-guide/upgrade-guide.md` 并附上 `upgrade-script.sh` 的日志输出。
- **社区贡献**：欢迎通过 Pull Request 贡献代码，请先阅读 `CONTRIBUTING.md` 与 `CONVENTIONS.md`。

---

## 14. 发布签字

| 角色 | 签字 | 日期 |
| --- | --- | --- |
| 架构负责人 | _______________ | 2026-08-08 |
| 开发负责人 | _______________ | 2026-08-08 |
| 测试负责人 | _______________ | 2026-08-08 |
| 安全负责人 | _______________ | 2026-08-08 |
| 发布工程师 | _______________ | 2026-08-08 |
| 产品负责人 | _______________ | 2026-08-08 |

---

> **数擎大数据平台 V2.0.0 GA — Aurora（极光）**
> **2026-08-08 · 正式可用版本 · 一次构建，处处运行**

— 发布结束 —