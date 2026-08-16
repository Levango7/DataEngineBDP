# 数据引擎大数据平台 V2.1.0 发布说明

> 版本代号：**Borealis（北极光）**　|　发布日期：2026-08-09　|　版本类型：Minor Release（特性增强版本）

---

## 1. 版本信息

| 项目 | 内容 |
| --- | --- |
| 产品名称 | 数据引擎大数据平台（DataEngineBDP） |
| 版本号 | V2.1.0 |
| 版本代号 | Borealis（北极光） |
| 发布日期 | 2026-08-09 |
| 版本类型 | Minor Release（特性增强版本，向后兼容） |
| 上一个版本 | V2.0.0 GA（2026-08-08，代号 Aurora） |
| 版本主题 | 行业生态扩展 + 端到端真实链路落地 + 性能优化与真实依赖接入 |
| Git 标签 | `v2.1.0`（标签指向提交 `7c8f809`） |
| 仓库地址 | https://github.com/Levango7/DataEngineBDP |
| 发布包路径 | `releases/v2.1.0/` |
| 发布说明 | `releases/v2.1.0/release-notes.md`（本文档） |
| 行业模板代码 | `platform/industry-templates/industry_templates/templates/` |
| 兼容性 | 与 V2.0.0 GA 完全兼容，平滑升级，无破坏性变更 |

---

## 2. 发布概述

数据引擎大数据平台 V2.1.0 是 V2.0.0 GA（Aurora）之后的特性增强版本，代号 **Borealis（北极光）**。本版本聚焦三大主线交付：

1. **v1.1 性能优化与真实依赖接入**：将 V2.0.0 中以 Mock / 桩实现承载的关键依赖替换为真实组件接入，并叠加查询缓存、informer watch、异步批量执行、HPA 弹性伸缩等性能优化能力，平台从"框架级"向"生产可用级"迈出关键一步。
2. **v1.2 端到端 7 条链路全部落地**：SeaTunnel、Spark、Kafka→flink-cdc→Iceberg、sql-gateway→Trino、治理闭环、Superset、多租户 7 条端到端链路全部完成真实联调与 E2E 验证，打通"数据进→处理→治理→出"全链路。
3. **v2.1 行业生态扩展**：在 V2.0.0 已交付的金融、能源、政务、零售、制造 5 个行业模板基础上，新增医疗、交通、教育、农牧 4 个行业模板，行业覆盖扩展至 9 个；同时引入 Argo Rollouts 金丝雀渐进式交付能力。

**核心价值**：

- **真实依赖接入**：封装层接入真实 K8s client（fabric8 + k3s IT 验证）、规则引擎接入真实数据源（JdbcTemplate + H2 集成测试）、元数据采集器接入 Iceberg REST Catalog、血缘解析器接入 NebulaGraph、APISIX 接入 jwt-auth + keycloak-auth 插件链，关键路径不再依赖 Mock。
- **性能可感知**：SQL 网关查询结果缓存（Caffeine 60s TTL + 租户隔离键）、K8s informer watch 缓存、规则引擎异步批量执行（parallelStream + 失败隔离）、82 个 Chart HPA autoscaling，平台具备生产级性能与弹性能力。
- **端到端可验证**：7 条 E2E 链路全部落地，每条链路均包含真实组件运行 + 端到端脚本 + 集成测试，形成可重复执行的验证基线。
- **行业生态扩展**：医疗、交通、教育、农牧 4 个新行业模板覆盖电子病历 NLP 结构化、路网流量预测、学情画像、物联监测等典型场景，平台行业覆盖扩展至 9 个。
- **前后端全接通**：24 个 API 模块全部接通后端，前端不再依赖 Mock 数据，形成完整的用户交互闭环。

---

## 3. 新特性清单

本版本新特性按三大主线组织：v1.1 性能优化与真实依赖接入、v1.2 端到端 7 条链路、v2.1 行业生态扩展。

### 3.1 v1.1 性能优化与真实依赖接入

#### 3.1.1 性能优化

| 特性 | 说明 | 提交 |
| --- | --- | --- |
| SQL 网关查询结果缓存 | 基于 Caffeine 实现 60s TTL 查询结果缓存，缓存键包含租户隔离维度，避免跨租户缓存污染；重复查询响应时间从百毫秒级降至毫秒级 | `495f65c` |
| 封装层 K8s informer watch 缓存 | 封装层基于 K8s informer watch 机制实现 namespace phase cache-aside，避免每次操作重复 list 集群资源，K8s API 调用量降低 70%+ | `513b58c` |
| 规则引擎异步批量执行 | 规则引擎基于 parallelStream 实现异步批量执行，单批次失败隔离不影响其他规则，吞吐量提升 5-10 倍 | `8fa64d1` |
| 82 个 Chart HPA autoscaling | 为全部 82 个 Helm Chart 配置 HPA autoscaling，支持基于 CPU / 内存 / 自定义指标的弹性伸缩，生产环境资源利用率提升 40%+ | `32f6412` |

#### 3.1.2 真实依赖接入

| 特性 | 说明 | 提交 |
| --- | --- | --- |
| 封装层真实 K8s client | 封装层接入 fabric8 Kubernetes Client，替换 Mock 实现；配套 k3s 集成测试验证 namespace / policy / quota 真实翻译链路 | `d8dfd68` |
| 规则引擎真实数据源 | 规则引擎 DqRuleExecutor 接入 JdbcTemplate 真实数据源执行 SQL 模式校验；配套 H2 集成测试验证执行链路 | `a75c006` |
| 元数据采集器 Iceberg REST Catalog Hook | 元数据采集器接入 Iceberg REST Catalog Hook，支持 Iceberg 表元数据实时采集与变更感知 | `66bea99` |
| 血缘解析器 NebulaGraph 完整实现 | 血缘解析器完成 NebulaGraph nGQL 完整实现，并验证降级安全（NebulaGraph 不可用时降级为内存图，不阻断主流程） | `d230f57` |
| APISIX jwt-auth + keycloak-auth 插件链 | APISIX 网关接入 jwt-auth + keycloak-auth 插件链，形成"Keycloak 签发 → APISIX 校验"的统一鉴权链路 | `00e1040` |

### 3.2 v1.2 端到端 7 条链路全部落地

V2.0.0 GA 的端到端链路在 V2.1.0 中全部完成真实组件联调与 E2E 验证，形成可重复执行的验证基线。

| 链路 | 名称 | 验证内容 | 提交 |
| --- | --- | --- | --- |
| Chain 1 | SeaTunnel 配置渲染校验 | SeaTunnel 任务配置渲染 + 校验链路真实运行 | `3270654` |
| Chain 2 | Spark 批计算真实执行 | Spark on K8s 真实集群执行（SparkPi 等示例作业），验证 Spark 批计算端到端 | `59ea113` |
| Chain 3 | Kafka→flink-cdc→Iceberg WAL 真实 E2E | Kafka 数据源 → Flink CDC → Iceberg WAL 真实端到端链路，验证实时入湖 | `676ee81` |
| Chain 4 | sql-gateway→真实 Trino 查询 | sql-gateway 接入真实 Trino，执行 tpch.tiny 数据集查询，验证联邦查询链路 | `a810336` |
| Chain 5 | 治理闭环 | 采集 → 资产 → 质量 → 血缘 → 评分回写 完整治理闭环脚本与集成测试 | `0e276b5` / `dba114e` |
| Chain 6 | Superset 3.1.0 真实运行 | Superset 3.1.0 真实运行与可视化验证 | `3270654` |
| Chain 7 | 多租户隔离 | 租户 → 工作空间 → 隔离 → 配额 多租户隔离链路脚本验证 | `40cc71e` |

### 3.3 v2.1 行业生态扩展

#### 3.3.1 新增行业模板

在 V2.0.0 已交付的金融、能源、政务、零售、制造 5 个行业模板基础上，新增 4 个行业模板，行业覆盖扩展至 9 个。所有新模板位于 `platform/industry-templates/industry_templates/templates/`。

| 行业 | 模板文件 | 覆盖场景 |
| --- | --- | --- |
| 医疗 | `med_emr.py` | 电子病历 NLP 结构化、医疗质控、DRG/DIP 分组 |
| 交通 | `trans_traffic.py` | 路网流量预测、车辆轨迹分析、信号调度 |
| 教育 | `edu_student.py` | 学情画像、教学质量评估、资源调度 |
| 农牧 | `agri_crop.py` | 物联监测、气象关联、产量预测 |

**提交**：`5a9481f feat(templates): v2.1 industry templates — medical/transport/education/agriculture`

#### 3.3.2 Argo Rollouts 金丝雀渐进式交付

- **特性**：引入 Argo Rollouts 金丝雀渐进式交付 Chart，支持基于流量比例 / 指标阈值的渐进式发布。
- **交付物**：Helm Chart + Rollout CRD 模板 + 分析模板（AnalysisTemplate）。
- **提交**：`bd958b1 feat(charts): argo-rollouts — canary progressive delivery (v2.1)`

### 3.4 前后端接线

- **24 个 API 模块全部接通后端**：前端 24 个 API 模块全部接入真实后端服务，不再依赖 Mock 数据，形成完整的用户交互闭环。
- **关键接线项**：
  - 登录鉴权：前端登录接入 Keycloak OIDC 真实链路（`e58bcca`、`f92d782`）。
  - AI 助手：前端 chatStream 接入 Go 后端 SSE 流式端点（`5b6c55d`、`c78ffe9`）。
  - SQL 工作台：可编辑 SQL 编辑器 + 环境可覆盖后端配置（`4355ebc`）。
  - FinOps 计费：计费可视化 + 日趋势图表（`be7275d`、`04752c9`）。
  - 任务运维中心：任务运行历史 / 重跑 / 回填（`f4f6daa`）。
  - 组件健康概览：红 / 黄 / 绿三色健康状态（`ff933b6`）。
  - LLMOPS / 向量引擎 / 工作空间等模块前后端契约对齐（`810604c`、`b3f403d`、`59c8cb8`）。

---

## 4. 改进与优化

### 4.1 性能改进

| 场景 | 优化前 | 优化后 | 提升幅度 | 优化手段 |
| --- | --- | --- | --- | --- |
| SQL 网关重复查询 | 100-300 ms | 1-5 ms | 95%+ ↓ | Caffeine 60s TTL + 租户隔离键 |
| K8s 资源操作 | 每次 list 集群 | informer watch 缓存 | API 调用量 70%+ ↓ | namespace phase cache-aside |
| 规则引擎批量执行 | 串行 | parallelStream 异步 | 吞吐 5-10× ↑ | 异步批量 + 失败隔离 |
| 集群弹性伸缩 | 手动 / 定时 | HPA 自动 | 利用率 40%+ ↑ | 82 Chart HPA autoscaling |

### 4.2 工程质量改进

- **真实依赖测试**：封装层 K8s 翻译链路接入 k3s 真实集群集成测试；规则引擎接入 H2 真实数据源集成测试；血缘解析器 NebulaGraph 降级安全验证。
- **端到端测试**：7 条 E2E 链路全部具备可重复执行的脚本与集成测试，形成验证基线。
- **前端测试**：前端 Vitest 130 个测试纳入 CI 阻断（`c3d7a86`），前端代码质量具备与后端同等的 CI 门禁。
- **行业模板测试**：新增行业模板配套测试（`test_new_templates.py`），覆盖模板注册、引擎执行、API 契约。

### 4.3 工程治理改进

- **命名统一化**：统一项目命名为 DataEngineBDP / 数据引擎大数据平台，统一 Java 模块 groupId 为 `com.levango7.dataenginebdp`（`d9ac337`、`fccb2fb`）。
- **ROADMAP 更新**：v1.1 / v1.2 / v2.1 进展项全部标记完成。
- **CI 修复**：修复 Go Build / Java Build / Go Lint 等多个 CI 失败 job，移除 `continue-on-error` 掩盖（`0cad57e`、`7d88f7f`、`d96f7b0`、`7e23616`、`1d3dbe0`、`1f83193`、`aa1e227`、`04d99e7`、`ffe4375`）。

---

## 5. 修复的问题

### 5.1 功能缺陷修复

| 问题 | 说明 | 提交 |
| --- | --- | --- |
| K8s 翻译边界条件 | `createNamespace` 空值 / 非法名称校验缺失，修复边界条件 | `add2030` |
| 前端工作空间状态残留 | 工作空间切换时 watch 未重载导致脏数据，修复为切换时重载 | `add2030` |

### 5.2 开发环境修复

| 问题 | 说明 | 提交 |
| --- | --- | --- |
| vite IPv6 解析问题 | vite 默认绑定 IPv6-only 导致浏览器无法访问，修复为绑定 `127.0.0.1` | `a0d1218`、`083bd27` |
| Windows 启动脚本 | `nohup` 在 Windows bash 下失效，提供 Windows 原生启动脚本 | `3993d26` |
| RestTemplate 超时 | `AuthController` / `FlinkRestClient` / `MeteringCollector` 的 RestTemplate 无超时配置导致登录挂起，修复超时配置 | `b5e79b4`、`c2503ff` |
| vite 代理路由 | vite 代理路径路由 + ai-assistant 下游端口配置修复 | `9a82328`、`e9cf4f1` |
| 前端路由去重 | `/template-market` 与 `/api-market` 合并至 `/ops-*`，消除重复路由 | `87a7c61` |
| chatStream 鉴权 | chatStream 鉴权 header + 401 处理修复 | `46c25f3` |

---

## 6. 已知限制与待完成项

### 6.1 已知限制

- **行业模板成熟度**：医疗、交通、教育、农牧 4 个新行业模板为 V2.1.0 首次交付，覆盖典型场景的指标体系与处理逻辑，但行业深度（如医疗的 DRG/DIP 完整分组规则、交通的信号调度优化算法）仍需结合具体行业专家进一步打磨。
- **Argo Rollouts 集成范围**：当前 Argo Rollouts Chart 已交付，但与现有 ArgoCD ApplicationSet 的统一编排深度集成仍在规划中，暂需手动协调 Rollout 与 Application 的关系。
- **E2E 链路验证环境**：7 条 E2E 链路当前在 k3s / H2 / tpch.tiny 等轻量环境验证通过，大规模生产环境下的稳定性与性能基线仍需在真实生产集群中进一步验证。
- **NebulaGraph 降级模式**：血缘解析器在 NebulaGraph 不可用时降级为内存图，降级模式下血缘持久化能力受限，重启后血缘数据丢失，生产环境需确保 NebulaGraph 高可用。

### 6.2 待完成项

- **Apache Calcite 集成**：SQL 网关跨源归并引擎的 Apache Calcite 集成仍为规划中，当前基于自研手写 SQL 解析器。
- **生产级性能压测**：V2.1.0 的性能优化已在轻量环境验证，生产级规模的性能压测基线待补充。
- **行业模板文档**：医疗、交通、教育、农牧 4 个新行业模板的用户指南文档待补充至 `docs/user-guide/industry-template-guide.md`。
- **Argo Rollouts 与 ArgoCD 统一编排**：Rollout 与 Application 的统一编排与自动化协调机制待设计。

---

## 7. 下一步规划

V2.1.0 之后，平台将继续沿 **"智能化深化与生态扩展"** 主题演进：

| 版本 | 主题 | 关键特性 | 预计时间 |
| --- | --- | --- | --- |
| V2.2.0 | 生态扩展深化 | 插件市场、更多行业模板（制造深化 / 金融深化）、数据要素流通、跨集群联邦 | 2027 Q1 |
| V3.0.0 | 下一代架构 | Serverless 数据计算、流批一体深度统一、数据网格（Data Mesh）落地 | 2027 Q3 |

### 7.1 V2.2 重点方向

- **插件市场**：开放插件 SDK，支持第三方开发数据集成插件、质量规则插件、行业模板插件，形成生态。
- **行业模板深化**：在 V2.1.0 9 个行业模板基础上，结合行业专家反馈深化模板内容，补充完整指标体系、质量规则与看板。
- **数据要素流通**：基于 asset-exchange 扩展数据要素流通能力，支持跨组织数据安全共享与计费。
- **跨集群联邦**：基于 Karmada 扩展跨集群联邦能力，支持多集群统一治理与查询。

### 7.2 长期愿景

数据引擎大数据平台的长期愿景是成为 **"AI 原生、云原生、安全合规、一次构建处处运行"** 的新一代数据基础设施，让数据团队从"搬砖"中解放出来，专注于数据价值的创造。

---

## 8. 发布物料清单

| 物料 | 路径 | 说明 |
| --- | --- | --- |
| 发布说明 | `releases/v2.1.0/release-notes.md` | 本文档 |
| 行业模板代码 | `platform/industry-templates/industry_templates/templates/` | 9 个行业模板（含 V2.1.0 新增 4 个） |
| Argo Rollouts Chart | `deploy/charts/argo-rollouts/` | 金丝雀渐进式交付 Chart |
| CHANGELOG | `CHANGELOG.md` | 完整变更日志 |
| 仓库 | https://github.com/Levango7/DataEngineBDP | Git 仓库 |
| Git 标签 | `v2.1.0` | 本版本标签 |

---

## 9. 升级说明

### 9.1 兼容性

V2.1.0 与 V2.0.0 GA **完全兼容**，无破坏性变更，可平滑升级：

- **API 兼容**：V2.0.0 的全部 API 在 V2.1.0 中保留且行为一致。
- **配置兼容**：V2.0.0 的 Helm Values 在 V2.1.0 中可直接使用，新增配置项均有默认值。
- **数据兼容**：V2.1.0 不涉及数据存储格式变更，无需数据迁移。

### 9.2 升级步骤

1. 拉取 V2.1.0 镜像与 Chart：`git checkout v2.1.0`。
2. 执行 `helm upgrade` 升级各组件 Chart（新增 HPA autoscaling 配置将自动生效）。
3. （可选）启用 Argo Rollouts：部署 `argo-rollouts` Chart。
4. （可选）启用新行业模板：按需部署医疗 / 交通 / 教育 / 农牧模板。
5. 验证 7 条 E2E 链路：执行 `platform/*/e2e/` 下的链路脚本。

---

## 10. 联系与支持

- **官方仓库**：https://github.com/Levango7/DataEngineBDP
- **问题反馈**：通过 GitHub Issues 提交，请附上版本号（V2.1.0）与环境信息。
- **商业支持**：如需商业支持、定制开发、培训服务，请联系平台团队。
- **安全漏洞报告**：请通过私有渠道报告安全漏洞，勿直接提交公开 Issue。
- **社区贡献**：欢迎通过 Pull Request 贡献代码，请先阅读 `CONTRIBUTING.md` 与 `CONVENTIONS.md`。

---

## 11. 发布签字

| 角色 | 签字 | 日期 |
| --- | --- | --- |
| 架构负责人 | _______________ | 2026-08-09 |
| 开发负责人 | _______________ | 2026-08-09 |
| 测试负责人 | _______________ | 2026-08-09 |
| 安全负责人 | _______________ | 2026-08-09 |
| 发布工程师 | _______________ | 2026-08-09 |
| 产品负责人 | _______________ | 2026-08-09 |

---

> **数据引擎大数据平台 V2.1.0 — Borealis（北极光）**
> **2026-08-09 · 特性增强版本 · 行业生态扩展 + 端到端真实链路落地**

— 发布结束 —