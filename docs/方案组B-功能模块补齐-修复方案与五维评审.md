# 方案组B - 功能模块补齐：修复方案与五维评审

> 评审日期：2026-09-12
> 评审范围：8 个功能模块补齐问题（高严重度 2 / 中严重度 3 / 低严重度 3）
> 评审方法：五维评审（成本性/风险性/收益性/兼容性/可持续性），各 1-5 分，总分 25
> 评分标准：>17 分 ✅ 推荐做 / 13-17 分 ⚠️ 需讨论 / <13 分 ❌ 不推荐做
> 成本性否决规则：成本性 = 1 分（❌ 不可行，≥1 人月）直接判定"不通过"

---

## 前置：代码现状核实结果

> 依据经验库指导 `2026-09-12-review-code-status-verify-before-task-description`，对每个问题的真实代码状态逐项读码核实，不依赖任务描述。

| # | 问题 | 核实方式 | 真实代码状态 |
| --- | --- | --- | --- |
| 1 | AI 组件全 Mock | 读 7 个组件配置/源码 | **确认**：vector-engine 默认构建回退 Mock（`milvus_stub.go` build tag `!milvus_enabled`）；registry `REGISTRY_MOCK_MODE=True` 默认（`main.py:59`），DeploymentManager Mock 下"不实际启动容器即标记 running"（`deployment_manager.py:71-77`）；llm-gateway/llmops/ml-platform/model-finetuning 均有 Mock 兜底默认。7 个 AI 组件 Mock 默认属实 |
| 2 | 多集群联邦全骨架 | 读 karmada 三子目录 | **确认**：`karmada/api`（Go+GORM，有 go.mod/internal/main.go）、`karmada/federated-query`（Java+SpringBoot，有 pom.xml/src）、`karmada/failover`（Go，有 api/engine 两子模块）。三组件骨架属实，PropagationPolicy CRUD 仅落本地库 |
| 3 | L3.2 数据标准无独立模块 | grep 设计文档 + ls platform/ | **确认**：`部署清单详细设计:299` 明列 `sq-data-standard` 组件名归属 sq-governance；`治理中台详细设计:131` 写"自研标准项库 + 落标校验引擎，Java"。但 `platform/` 下无 data-standard/standard 独立模块。设计有、实现无 |
| 4 | L3.6 主数据管理无独立模块 | 读设计文档 + ls platform/ | **确认**：`design/详细设计/多平台多租户大数据平台_主数据详细设计_v0.1.md` 存在。但 `platform/` 下无 master-data/standard 独立模块。设计有、实现无 |
| 5 | 前端 5 页面对应 Mock 后端 | ls frontend/src/views + ls api | **确认**：`views/` 下有 Vector.vue/Kb.vue/Llmops.vue/Gateway.vue/Develop.vue（对应 DevMl）；`api/` 下有 vector.ts/knowledge.ts/llmops.ts/gateway.ts/dev-ml.ts。后端组件本身 Mock 默认（问题 1），前端调用返回 Mock 数据 |
| 6 | operations-api 未纳入矩阵 | ls platform/operations-api + 读 pyproject.toml | **确认**：`platform/operations-api` 有完整实现（pyproject.toml: `sq-operations-api v0.1.0`，`operations_api/` 下有 api/models/repositories/services 完整 MVC 结构）。但 `component-maturity.md` 未列入 |
| 7 | knowledge-engine 矩阵描述滞后 | 读 Dockerfile + 对比矩阵 | **确认**：Dockerfile 第 51-55 行明确注释"不在镜像内烘焙 mock——防止生产拿到假实现而无感知"、"K8s 内若未配置真实 NebulaGraph 连接，服务将启动失败（fail-fast）"。但矩阵第 61 行仍写"交付 Dockerfile 内置 `KE_STORE_TYPE=mock`、`KE_EXTRACTOR_TYPE=mock`"。描述滞后属实 |
| 8 | 组件计数口径不一致 | 数矩阵实列行数 | **确认**：矩阵标题第 3 行写"44 个自研组件（Java 22 / Go 11 / Python 11）"，但矩阵实列：真实可部署 22 + 服务级 6 + 骨架 13 = **41 个**。标题 44 ≠ 实列 41 |

---

## 问题 1：AI 组件全 Mock（高严重度）

**问题描述**：vector-engine / llm-gateway / llmops / knowledge-engine / ml-platform / model-finetuning / registry 共 7 个 AI 组件全骨架 Mock，AI 链路全不可用。

### 修复方案（分阶段）

- **阶段 1：llm-gateway 接入真实 Provider**（1-2 周）
  - 配置真实 OpenAI / 千问 / 文心 / 智谱 API Key（`LLM_PROVIDER=openai` + `OPENAI_API_KEY`）
  - 验证五种 Provider 适配器真实输出，移除 Mock 兜底为可选降级
- **阶段 2：vector-engine 启用 Milvus 构建产物**（1 周）
  - 构建命令加 `-tags milvus_enabled`，产出含真实 Milvus SDK 的二进制
  - 部署 Milvus 实例，配置 `STORE_TYPE=milvus` + `MILVUS_ADDR`
- **阶段 3：llmops / ml-platform 接入真实 MLflow**（2-3 周）
  - 部署 MLflow Tracking Server，配置 `MLFLOW_TRACKING_URI`
  - ml-platform 设 `ML_BACKEND_TYPE=mlflow` + `MLFLOW_ENABLED=true`
  - llmops 设 `LLMOPS_STORE_TYPE=mlflow`
- **阶段 4：knowledge-engine 接入真实 NebulaGraph + LLM**（2-3 周）
  - 部署 NebulaGraph，配置 `KE_STORE_TYPE=nebula` + `NEBULA_GRAPH_ADDR`
  - 配置 `KE_EXTRACTOR_TYPE=llm` + 对接 llm-gateway
- **阶段 5：model-finetuning / registry 接入真实 GPU 节点池 + 容器运行时**（4-6 周）
  - model-finetuning 设 `FINETUNE_MOCK_MODE=false`，对接 Volcano 调度 + GPU 节点池
  - registry 设 `REGISTRY_MOCK_MODE=false`，DeploymentManager 真实启动容器
- **阶段 6：端到端联调 + 纳阵更新**（1 周）
  - 7 个组件端到端联调，更新 component-maturity.md 成熟度评级

### 五维评审

- **成本性**：1/5 — 7 个组件全链路真实化需 GPU 节点池、Milvus、NebulaGraph、MLflow、真实 LLM API 等外部基础设施，人力 >3 人月 + 基础设施成本，**触发成本性否决**
- **风险性**：2/5 — 涉及 5 类外部依赖（向量库/图库/MLflow/GPU/LLM API），联调风险高；Mock→真实切换易暴露隐藏缺陷
- **收益性**：5/5 — AI 链路全可用是平台核心卖点，直接决定产品竞争力
- **兼容性**：4/5 — 各组件已备 build tag / 环境变量开关，切换路径清晰，向后兼容好
- **可持续性**：4/5 — 真实实现后可持续运维，但外部依赖运维负担长期存在
- **五维总分**：16/25
- **评审结论**：❌ 不推荐做（**成本性否决**：≥1 人月，且需大量外部基础设施投入。建议拆分子问题分批推进，见优先级排序）

---

## 问题 2：多集群联邦全骨架（高严重度）

**问题描述**：karmada-api / karmada-federated-query / karmada-failover 三个组件全骨架，策略 CRUD 未接控制面。

### 修复方案（分阶段）

- **阶段 1：karmada-api 接入真实 Karmada 控制面**（2-3 周）
  - 配置 Karmada 控制面 kubeconfig（`KARMADA_KUBECONFIG`）
  - PropagationPolicy CRUD 从本地库改为对接 Karmada 控制面真实下发（karmadactl / K8s API）
  - 验证策略下发到成员集群
- **阶段 2：karmada-federated-query 接入真实多集群查询路由**（2-3 周）
  - 配置多集群连接信息（各成员集群 kubeconfig）
  - 跨集群查询路由与结果归并真实实现（当前 H2 文件持久层保留）
  - 验证跨集群 SQL 查询正确归并
- **阶段 3：karmada-failover 接入真实故障检测与切换**（3-4 周）
  - 故障检测对接真实多集群健康检查（Prometheus / K8s API）
  - 切换执行对接 Karmada 重调度 API
  - 故障转移策略从内存态改为持久化
- **阶段 4：端到端联调 + 纳阵更新**（1 周）
  - 多集群联邦全链路联调，更新 component-maturity.md

### 五维评审

- **成本性**：2/5 — 需真实 Karmada 控制面 + 多成员集群环境，代码骨架已备但联调量大，约 1-2 人月
- **风险性**：2/5 — 多集群故障转移涉及生产流量切换，误操作风险高；联邦查询结果归并正确性风险
- **收益性**：4/5 — 多集群联邦是平台核心差异化能力，补齐后形成完整多云故事
- **兼容性**：4/5 — 三组件骨架已备（Go/Java 各有 go.mod/pom.xml），接入路径清晰
- **可持续性**：4/5 — 真实接入后可持续，但多集群环境运维复杂度高
- **五维总分**：16/25
- **评审结论**：⚠️ 需讨论（成本偏高但未触发否决，收益显著。建议有真实多集群客户场景时推进）

---

## 问题 3：L3.2 数据标准无独立模块（中严重度）

**问题描述**：设计中列出 L3.2 数据标准（`sq-data-standard`，归属 sq-governance）但 `platform/` 下无对应自研模块。

### 修复方案（分阶段）

- **阶段 1：模块归属决策**（1 天）
  - 方案 A：独立成 `platform/data-standard` 模块（Java + Spring Boot，与 governance 同栈）
  - 方案 B：并入 `platform/governance` 作为子模块（减少模块数）
  - **推荐方案 A**：独立模块便于独立部署与演进，与设计文档一致
- **阶段 2：创建模块骨架 + 标准项 CRUD**（1-2 周）
  - 创建 `platform/data-standard`（pom.xml + Spring Boot 4.1.1 + JPA）
  - 实现标准项 CRUD（标准字典 / 码值 / 命名规范），API 路径 `/api/std/v1/*`（与设计文档一致）
  - 默认持久层 H2 文件，prod profile 切 PostgreSQL
- **阶段 3：落标校验引擎**（1-2 周）
  - 实现落标校验：字段命名 / 类型 / 码值校验，对接 rule-engine DSL
  - 落标率统计 API
- **阶段 4：纳入矩阵 + 设计对齐**（1 天）
  - 补入 component-maturity.md（真实可部署 / 服务级）
  - 验证与 `治理中台详细设计` / `部署清单详细设计` 一致

### 五维评审

- **成本性**：3/5 — 标准项 CRUD + 落标校验，Java 同栈复用 governance 模式，约 2-3 周
- **风险性**：4/5 — 独立新模块，不影响现有组件，风险低
- **收益性**：3/5 — 数据标准是治理中台子能力，补齐后治理层完整；但短期可由 governance 临时承载
- **兼容性**：4/5 — 与 governance 同栈同持久层策略，API 路径设计文档已定义
- **可持续性**：4/5 — 独立模块可持续演进，与设计文档对齐避免漂移
- **五维总分**：18/25
- **评审结论**：✅ 推荐做

---

## 问题 4：L3.6 主数据管理无独立模块（中严重度）

**问题描述**：设计中列出 L3.6 主数据管理但 `platform/` 下无对应自研模块。

### 修复方案（分阶段）

- **阶段 1：模块归属决策**（1 天）
  - 方案 A：独立成 `platform/master-data` 模块
  - 方案 B：并入 `platform/governance` 作为子模块
  - **推荐方案 A**：主数据管理涉及分发/订阅，独立模块更清晰
- **阶段 2：创建模块骨架 + 主数据 CRUD**（1-2 周）
  - 创建 `platform/master-data`（Java + Spring Boot 4.1.1 + JPA）
  - 实现主数据实体 CRUD（`StandardEntity`，见 `平台元数据库设计.md:71` 的 `standard` 表）
  - 默认持久层 H2 文件，prod profile 切 PostgreSQL
- **阶段 3：主数据分发 + 校验**（1-2 周）
  - 实现主数据分发到下游系统（API / 消息）
  - 主数据校验对接 L3.2 数据标准
- **阶段 4：纳入矩阵 + 设计对齐**（1 天）
  - 补入 component-maturity.md
  - 验证与 `主数据详细设计_v0.1.md` 一致

### 五维评审

- **成本性**：3/5 — 主数据 CRUD + 分发，Java 同栈，约 2-3 周
- **风险性**：4/5 — 独立新模块，不影响现有组件，风险低
- **收益性**：3/5 — 主数据管理是治理层子能力，补齐后完整；但优先级低于数据标准
- **兼容性**：4/5 — 与 governance 同栈，元数据库设计已定义 `standard` 表
- **可持续性**：4/5 — 独立模块可持续演进
- **五维总分**：18/25
- **评审结论**：✅ 推荐做

---

## 问题 5：前端 5 页面对应 Mock 后端（中严重度）

**问题描述**：Vector / Kb / Llmops / Gateway / DevMl 前端功能完整但后端返回 Mock 数据。

### 修复方案（分阶段）

- **阶段 1：梳理前端 5 页面 API 依赖**（2 天）
  - 核实 `api/vector.ts` / `knowledge.ts` / `llmops.ts` / `gateway.ts` / `dev-ml.ts` 调用的后端端点
  - 标记哪些端点后端已真实实现、哪些仍 Mock
- **阶段 2：后端组件真实化（依赖问题 1）**（视问题 1 推进）
  - vector-engine 启用 Milvus（问题 1 阶段 2）
  - knowledge-engine 接入真实 NebulaGraph + LLM（问题 1 阶段 4）
  - llm-gateway 接入真实 Provider（问题 1 阶段 1）
  - ml-platform / model-finetuning / registry 真实化（问题 1 阶段 3/5）
- **阶段 3：前端联调验证**（1-2 周）
  - 5 个页面对接真实后端 API，修复联调缺陷
  - E2E 测试覆盖（playwright）
- **阶段 4：Mock 兜底保留为开发模式**（2 天）
  - 前端保留 Mock 模式开关供本地开发，生产默认走真实后端

### 五维评审

- **成本性**：2/5 — 强依赖问题 1 的后端真实化，本身联调 1-2 周但前置成本高
- **风险性**：3/5 — 前端联调可能暴露后端 Mock→真实切换的隐藏缺陷
- **收益性**：4/5 — 前端 5 页面是用户直接交互面，Mock 后端严重影响用户体验与可信度
- **兼容性**：4/5 — 前端 API 层已封装，切换后端实现兼容性好
- **可持续性**：4/5 — 真实联调后可持续，Mock 兜底保留利于开发
- **五维总分**：17/25
- **评审结论**：⚠️ 需讨论（本身成本低但强依赖问题 1。建议随问题 1 各阶段推进同步联调）

---

## 问题 6：operations-api 未纳入矩阵（低严重度）

**问题描述**：`platform/operations-api` 下有实现但 `component-maturity.md` 未列入。

### 修复方案

- **阶段 1：核实 operations-api 成熟度**（半天）
  - 读 `operations_api/` 下 api/models/repositories/services，确认技术栈与持久层
  - 确认是真实可部署 / 服务级 / 骨架
- **阶段 2：补入 component-maturity.md**（半天）
  - 按矩阵格式新增一行（组件 / 技术栈 / 成熟度 / 默认持久层 / 关键缺口）
  - 更新标题组件计数（与问题 8 联动）

### 五维评审

- **成本性**：5/5 — 纯文档更新，1 小时内完成
- **风险性**：5/5 — 无代码变更，无风险
- **收益性**：4/5 — 矩阵完整性与准确性提升，避免漏列误导决策
- **兼容性**：5/5 — 仅新增矩阵行，不影响任何现有组件
- **可持续性**：5/5 — 矩阵动态更新机制已有，纳入后可持续维护
- **五维总分**：24/25
- **评审结论**：✅ 推荐做

---

## 问题 7：knowledge-engine 矩阵描述滞后（低严重度）

**问题描述**：Dockerfile 已从 mock 默认改为 fail-fast 但矩阵仍按旧描述。

### 修复方案

- **阶段 1：更新矩阵第 61 行描述**（30 分钟）
  - 旧描述："交付 Dockerfile 内置 `KE_STORE_TYPE=mock`、`KE_EXTRACTOR_TYPE=mock`"
  - 新描述："交付 Dockerfile 已改为 fail-fast（不烘焙 mock，未配置真实 NebulaGraph 时启动失败）；代码配置默认指向 nebula / llm，需部署环境显式提供真实图存储或显式声明降级"
  - 成熟度可从"骨架（镜像内 Mock 默认）"调整为"骨架（fail-fast，需真实 NebulaGraph）"

### 五维评审

- **成本性**：5/5 — 单行文档更新，30 分钟
- **风险性**：5/5 — 无代码变更，无风险
- **收益性**：4/5 — 矩阵描述与实际一致，避免"以为 Mock 兜底实际 fail-fast"的认知偏差
- **兼容性**：5/5 — 仅描述修正
- **可持续性**：5/5 — 描述准确后可持续维护
- **五维总分**：24/25
- **评审结论**：✅ 推荐做

---

## 问题 8：组件计数口径不一致（低严重度）

**问题描述**：标题 44 个但矩阵实列 41 个。

### 修复方案

- **阶段 1：核实真实组件数**（半天）
  - 重新按构建文件（pom.xml / go.mod / pyproject.toml）口径清点 `platform/` 下所有自研组件
  - 确认 41 还是 44：若 operations-api + 其他未列入组件补齐后达 44，则标题对、矩阵缺行；若确为 41，则标题错
- **阶段 2：统一标题与矩阵实列数**（半天）
  - 方案 A：补齐矩阵缺失行（如 operations-api）使实列 = 标题数
  - 方案 B：修正标题数为实列数
  - **推荐**：先补齐问题 6（operations-api），再重新计数，确保标题 = 实列

### 五维评审

- **成本性**：5/5 — 文档更新 + 简单计数，1 小时
- **风险性**：5/5 — 无代码变更，无风险
- **收益性**：4/5 — 计数口径一致是矩阵可信度基础，避免"44 vs 41"混淆
- **兼容性**：5/5 — 仅文档修正
- **可持续性**：5/5 — 口径统一后可持续维护
- **五维总分**：24/25
- **评审结论**：✅ 推荐做

---

## 汇总表

| # | 问题 | 严重度 | 成本性 | 风险性 | 收益性 | 兼容性 | 可持续性 | 五维总分 | 评审结论 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 6 | operations-api 未纳入矩阵 | 低 | 5 | 5 | 4 | 5 | 5 | **24** | ✅ 推荐做 |
| 7 | knowledge-engine 矩阵描述滞后 | 低 | 5 | 5 | 4 | 5 | 5 | **24** | ✅ 推荐做 |
| 8 | 组件计数口径不一致 | 低 | 5 | 5 | 4 | 5 | 5 | **24** | ✅ 推荐做 |
| 3 | L3.2 数据标准无独立模块 | 中 | 3 | 4 | 3 | 4 | 4 | **18** | ✅ 推荐做 |
| 4 | L3.6 主数据管理无独立模块 | 中 | 3 | 4 | 3 | 4 | 4 | **18** | ✅ 推荐做 |
| 5 | 前端 5 页面对应 Mock 后端 | 中 | 2 | 3 | 4 | 4 | 4 | **17** | ⚠️ 需讨论 |
| 2 | 多集群联邦全骨架 | 高 | 2 | 2 | 4 | 4 | 4 | **16** | ⚠️ 需讨论 |
| 1 | AI 组件全 Mock | 高 | 1 | 2 | 5 | 4 | 4 | **16** | ❌ 不推荐做（成本性否决） |

---

## 优先级排序与执行建议

### 第一梯队：立即执行（低风险高收益文档修复，24/25）

| 顺序 | 问题 | 预估工时 | 依赖 |
| --- | --- | --- | --- |
| 1 | 问题 6：operations-api 纳入矩阵 | 1h | 无 |
| 2 | 问题 7：knowledge-engine 矩阵描述修正 | 0.5h | 无 |
| 3 | 问题 8：组件计数口径统一 | 1h | 问题 6 先行 |

**执行策略**：三个文档修复可一次性提交，互不冲突。问题 6 先行（补 operations-api 行），问题 8 后行（重新计数确认标题数）。

### 第二梯队：近期执行（中风险中收益模块补齐，18/25）

| 顺序 | 问题 | 预估工时 | 依赖 |
| --- | --- | --- | --- |
| 4 | 问题 3：L3.2 数据标准独立模块 | 2-3 周 | 无（Java 同栈） |
| 5 | 问题 4：L3.6 主数据管理独立模块 | 2-3 周 | 问题 3（主数据校验对接数据标准） |

**执行策略**：问题 3 先行（数据标准是主数据校验的依赖），问题 4 后行。两者均 Java + Spring Boot 同栈，可复用 governance 模式，独立新模块不影响现有组件。

### 第三梯队：需讨论后决策（高成本高收益，16-17/25）

| 顺序 | 问题 | 预估工时 | 依赖 |
| --- | --- | --- | --- |
| 6 | 问题 5：前端 5 页面对应 Mock 后端 | 1-2 周（本身） | 强依赖问题 1 |
| 7 | 问题 2：多集群联邦全骨架 | 1-2 人月 | 需真实 Karmada 环境 |
| 8 | 问题 1：AI 组件全 Mock | >3 人月 + 基础设施 | 需 GPU/Milvus/NebulaGraph/MLflow |

**执行策略**：
- **问题 1**（成本性否决）：不建议一次性全链路真实化。建议**拆分子问题分批推进**：
  - 1a. llm-gateway 接入真实 Provider（阶段 1，1-2 周，成本性 3/5，可单独决策）
  - 1b. vector-engine 启用 Milvus 构建产物（阶段 2，1 周，成本性 4/5，可单独决策）
  - 1c. llmops/ml-platform 接入 MLflow（阶段 3，2-3 周，成本性 3/5，可单独决策）
  - 1d. knowledge-engine 接入 NebulaGraph（阶段 4，2-3 周，成本性 3/5，可单独决策）
  - 1e. model-finetuning/registry 真实化（阶段 5，4-6 周，成本性 1/5，需 GPU 节点池，建议有真实需求再推进）
- **问题 5**：随问题 1 各子阶段推进同步联调，不独立立项
- **问题 2**：有真实多集群客户场景时推进，否则保持骨架

---

## 五维评审方法论说明

> 本评审遵循经验库 `2026-09-12-product-strategy-five-dimension-review-methodology` 与 `2026-09-12-5d-review-pass-fail-cost-veto-decision-rule`：

1. **代码现状核实**（前置必做）：对每个问题读码核实，不依赖任务描述
2. **逐问题五维评估**：从风险性/收益性/成本性/兼容性/可持续性分别评分（1-5 分），每维附理由
3. **应用成本性否决规则**：成本性 = 1 分（❌ 不可行，≥1 人月）直接判定"不通过"，记录否决理由
4. **综合判定其余问题**：成本性 ≥2 分的问题按总分判定（>17 推荐做 / 13-17 需讨论 / <13 不推荐做）
5. **分阶段拆解**：每个问题拆解为 3-6 个阶段，每阶段独立可交付

### 评分锚点

| 维度 | 5 分（✅） | 3 分（⚠️） | 1 分（❌） |
| --- | --- | --- | --- |
| 成本性 | <1 人天 | 2-3 周 | ≥1 人月 + 基础设施 |
| 风险性 | 无风险 | 中等联调风险 | 高风险（生产流量/数据） |
| 收益性 | 核心卖点 | 子能力补齐 | 边际收益 |
| 兼容性 | 无影响 | 同栈兼容 | 跨栈/破坏性 |
| 可持续性 | 长期可持续 | 可维护 | 一次性/易漂移 |