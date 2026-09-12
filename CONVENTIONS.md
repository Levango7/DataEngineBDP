# 数据引擎大数据平台 · 统一命名与约定（CONVENTIONS）

> 版本：v0.1 ｜ 日期：2026-08-05 ｜ 状态：生效
> 适用范围：全仓库（设计文档 / values / 脚本 / 代码 / CI）
> 目标：消除评估报告指出的"套餐命名 base vs basic、工作空间命名两套、模块计数 41 vs 49、版本号 v0.4/v0.5、SKE v1.0/v0.1"等基础口径漂移，建立单一事实来源。

---

## 1. 套餐命名

| 规范 | 说明 |
| --- | --- |
| **base** | 基础版（不用 `basic`） |
| **standard** | 标准版 |
| **flagship** | 企业版（键名保留 `flagship` 兼容代码；中文显示"企业版"，不用"旗舰版"） |

- 与 `design/deploy/values/*/values.yaml` 的 `tierProfiles` 三档键名对齐。
- 运营后台 API（`operations/main.py`）返回的 `package` 字段取值范围为 `base` / `standard` / `flagship`。
- 产品文档 §11.5 中文表述"基础版 / 标准版 / 企业版"对应英文"Starter / Standard / Enterprise"，键名 `base` / `standard` / `flagship`。
- **禁止**：`basic`、`pro`、`premium`、"旗舰版"、"免费版"、"专业版"等同义别名。

## 2. 工作空间命名

| 规范 | 格式 | 示例 |
| --- | --- | --- |
| **ws-\<name>** | `ws-` 前缀 + 小写字母/数字/连字符，长度 ≤ 32 | `ws-demo`、`ws-acme-prod` |

- 对应 K8s Namespace 名称。
- **禁止**：`<tenant>-default`（如 `acme-default`）、`<tenant>-ws` 等其他约定。
- 租户 → 工作空间映射由封装层（L0.11）管理，Namespace 上打 `tenant=<tid>` 标签。

## 3. 模块计数

> 四种口径统一定义见 [模块数口径定义](docs/模块数口径定义.md)。本节记录设计模块数口径。

| 规范 | 说明 |
| --- | --- |
| **设计模块数 49** | §3.3 产品能力全景图逐行清点的真实模块数（含未实现规划模块） |
| **自研组件数 46** | platform/ 构建文件实测（含子模块拆分，含 operations-api） |
| **矩阵实列 43** | component-maturity.md 矩阵实列数（governance/finops 合并显示） |
| **独立部署单元 42** | ADR-001 定义的独立部署单元数（扣库形态组件） |
| **platform/ 目录数 38** | platform/ 下一级子目录数 |

- 分布：L0.1–L0.12（12）+ L2.1–L2.10（10）+ L3.1–L3.7（7）+ L4.1–L4.5.6（10）+ L5.1–L5.6（6）+ X1–X4（4）= **49**。
- 详细设计文档 52 份（design/详细设计/ 下实测，2026-09-12 glob 口径）。
- 部署清单 88 个 Chart 条目（含 14 个自研组件 Chart + 74 个开源组件 Chart）。
- **禁止**：混用口径（如"41 模块"既指设计模块又指部署单元）；引用模块数必须标注口径名称。

## 4. 版本号

| 对象 | 规范 | 说明 |
| --- | --- | --- |
| 产品设计文档 | **v0.5** | 文件名暂保留 `v0.4.md`，内容自称 v0.5 |
| SKE 发行版 | **v0.1** | `ske/` 目录，与 `ske/README.md` 一致 |
| 详细设计文档 | **v0.1** | `design/详细设计/*_v0.1.md` |
| 控制台原型 | **v0.3**（HTML）/ **v0.4**（Vue3，进行中） | `frontend/` |
| 运营后台 | **v0.1** | `design/deploy/services/operations/` |

- **禁止**：SKE v1.0（GA 未到）、产品文档 v0.4（内容已是 v0.5）。

## 5. 技术选型

以产品文档 **§5.5 选型决策表（v0.5 拍板）** 的 13 项为准，单一选型不留"或"：

| # | 能力 | 选型 |
| --- | --- | --- |
| 1 | K8s 发行版 | 自研 SKE（基于 kubeadm 二次封装，非 KubeSphere/RKE2/k3s/kind 原样） |
| 2 | 元数据管理 | 自研轻量 Catalog（Java/Go） |
| 3 | 数据质量 | 自研规则引擎（Java） |
| 4 | BI 可视化 | Apache Superset + 自研大屏 |
| 5 | 图表库 | Apache ECharts |
| 6 | 认证 | Keycloak |
| 7 | 消息 | Apache Kafka |
| 8 | 图数据库 | NebulaGraph |
| 9 | 向量数据库 | Milvus |
| 10 | 调度编排 | Apache DolphinScheduler |
| 11 | 数据集成 | Apache SeaTunnel |
| 12 | 开发 IDE | 基于 Eclipse Theia 二次开发 |
| 13 | API 网关 | Apache APISIX |

语言栈收敛：**Java（主）+ Python（BI/ML/控制面）+ Go（向量/Catalog）+ TS（前端/IDE）**。

## 6. 引擎版本（与部署清单对齐）

| 引擎 | 版本 | 说明 |
| --- | --- | --- |
| Trino | **460** | 向量化执行引擎 |
| Doris | **2.1** | 2.1.7 |
| Kafka | **3.8** | 3.8.1 KRaft |
| Spark | 3.5 | 3.5.3 |
| Flink | 1.20 | 1.20.0 |
| IoTDB | 2.0 | 2.0.2 |
| NebulaGraph | 3.6 | |
| Keycloak | 25.0 | 25.0.0 |

三处版本漂移（CI 矩阵 / values / 部署清单文档）以此表为准对齐。

## 7. CI 校验建议

建议在 CI 中加入对账脚本，本文件作为单一事实来源：

- 套餐键名扫描：`grep -r '"basic"' design/` 应为空。
- 工作空间命名扫描：`grep -rE '<tenant>-default' design/` 应为空。
- 模块计数扫描：`grep -r '41 模块\|41模块' design/ README.md docs/` 应为空。
- 版本号扫描：`grep -r 'SKE.*v1\.0' design/` 应为空。
- 引擎版本扫描：`grep -rE 'Trino 460|Doris 2\.1|Kafka 3\.7' design/` 应为空。

---

> 本文件由组A-文档一致性修复任务（task 51）创建，依据《数据引擎大数据平台_全面评估报告.md》P0/P1 问题清单。

## 8. MIRRORED FILE 同步规范

部分轻量公共代码以"镜像副本"形式存在于多个服务（各服务独立 pip 包/Go module，
引入共享包需发布基础设施，成本高于收益）：

| 文件 | 副本位置 |
| --- | --- |
| `jwt_auth.py` | `platform/llmops/llmops/api/`（canonical）、`platform/ml-platform/ml_platform/api/`、`platform/nl2sql/`、`platform/llm-gateway/evaluation/app/`、`platform/knowledge-engine/knowledge_engine/api/`、`platform/asset-exchange/asset_exchange/api/`、`platform/open-api-catalog/openapi_catalog/api/` |

规则：

1. 每个副本文件头必须带 `MIRRORED FILE` 标记，注明 canonical 位置与全部副本路径。
2. 修改任一副本必须同步其余副本**逐字节一致**。
3. CI 由 `scripts/check-mirrored-jwt-auth.sh` 强制校验（bash-check job），不一致即阻断。
4. 新增镜像文件时必须同步更新校验脚本与本表。

---

## 9. 接口规范基线

> 基线文档：`docs/user-guide/api-reference.md` V2.2（2026-08-25）。
> 新服务接口设计必须遵守本节；与本节不符的存量行为登记于 §9.8「现存偏差登记表」并标注"待迁移"。

### 9.1 URL

- 统一前缀 `/api/v1`（例外见 §9.7 豁免登记）。
- 资源名用复数名词：`/tenants`、`/tables`、`/collections`。
- 多词路径段用 kebab-case：`/batch-compute`、`/hybrid-search`；禁止 snake_case / camelCase 路径段。

### 9.2 成功响应

允许且仅允许两类封装，新服务二选一并在模块 README 声明：

| 类型 | 格式 | 现状 |
| --- | --- | --- |
| 包裹型 | `{code, message, data, traceId?, timestamp}`，`code=0` 表示成功 | Java 栈现状（encaps-layer ApiResponseAdvice 全量包装） |
| 资源直出 | 直接返回资源对象/数组；列表可用 `{list,total}` 或 `{data,total}` | Go / FastAPI 及其余 Java 服务现状 |

**禁止引入第三种封装。**

### 9.3 错误响应

统一 `{"error": "snake_case_code", "message": "人类可读信息"}`，并使用正确的 HTTP 语义：

| HTTP | 语义 |
| --- | --- |
| 401 | 未认证（缺 token / token 无效或过期） |
| 403 | 已认证但越权（含租户不一致） |
| 404 | 资源不存在 |
| 409 | 仅用于唯一性冲突（资源已存在） |
| 422 | 请求校验失败 |
| 500 | 内部错误 |

- 禁止用 200 + 业务状态位表达可预期的失败（存量特例见偏差表 #1）。
- 错误码用 snake_case；PascalCase 特例码见偏差表 #2。

### 9.4 分页

- 入参命名：`page`（从 1 起）+ `pageSize`。
- 出参命名：`{list, total}`。
- 所有列表端点必须有界：默认页大小 ≤ 100 且强制上限（如 catalog 全文检索 limit 上限 200）。

### 9.5 租户

- 租户上下文一律以 JWT claim（`tenantId`）为准，服务端不得信任请求体/请求头中的裸租户值。
- 请求显式携带租户（`X-Tenant-Id` 头或 body.tenantId）与 claim **不一致时必须返回 403**。
- 普通用户忽略请求中的租户值；admin 可指定他人租户（`effectiveTenant` 语义，见 nl2sql / ai-assistant 实现）。

### 9.6 鉴权

- Bearer JWT（HS256），issuer=`shuqing-bigdata`。
- `/health`（及 `/healthz`、`/readyz`、`/metrics`、actuator health）匿名豁免——K8s 探针不得被 401 拦截。
- FastAPI 栈统一 `AUTH_MODE` 开关（镜像 jwt_auth 模块）：`jwt`=强制校验；`none`=匿名放行且角色视为 admin（仅限本地/测试，进程告警一次）；生产必须显式 `AUTH_MODE=jwt`。
- 危险端点不得匿名：原生查询类端点（如 knowledge-engine nGQL 查询）在 AUTH_MODE=none 下仍须拒绝。

### 9.7 豁免登记（规范内的既定例外）

| 项 | 说明 |
| --- | --- |
| query-api Prometheus 透传端点 | `/platform/api/v1/*` 与 `/tenant/api/v1/*` 保持 Prometheus 原生路径风格（`query_range` 等下划线命名），不做 kebab-case 改造 |
| encaps-layer 包裹型数字码 | ApiResponseAdvice 全量包装 `{code,message,data,...}`（code=0 成功），属 §9.2 包裹型合法形态，非偏差 |

### 9.8 现存偏差登记表（待迁移）

| # | 偏差 | 位置 | 现状 | 处理 |
| --- | --- | --- | --- | --- |
| 1 | ~~跨源查询失败返回 200 + FAILED~~ | sql-gateway `SqlGatewayController#crossSourceExecute / crossSourceExplain` | ✅ 已迁移（2026-08-29）：失败按错误码映射 HTTP 语义（PARSE_ERROR/UNSUPPORTED→400、SOURCE_NOT_FOUND→404、RESULT_TOO_LARGE→413、QUERY_TIMEOUT→504、QUERY_FAILED/MERGE_ERROR→502、INTERNAL→500）；body 保留 `status=FAILED` 结构兼容旧 SDK，前端与 api-reference 已同步 | 已解决 |
| 2 | ~~特例错误码 PascalCase~~ | encaps-tenant `QuotaController`、rule-engine（QUOTA 类） | ✅ 已迁移（2026-08-29）：QuotaExceeded→`quota_exceeded`、Conflict→`conflict`、ROLE_NOT_FOUND→`role_not_found`、AGENT_NOT_FOUND→`agent_not_found`、TOOL_NOT_FOUND→`tool_not_found`、RULE_NOT_FOUND→`rule_not_found`、UNSUPPORTED_RULE_TYPE→`unsupported_rule_type`；api-reference 错误码表同步 | 已解决 |
| 3 | ~~无 CORS 中间件~~ | nl2sql / open-api-catalog / asset-exchange 各 app.py | ✅ 已修复（历史批次）：三服务均已加 CORSMiddleware 并有测试 | 已解决 |
| 4 | ~~管理/订阅端点未挂应用层鉴权~~ | open-api-catalog app.py include_router | ✅ 已修复（历史批次）：apis/generate/billing/subscriptions/metrics_docs 均挂 `Depends(getAuthContext)`；`invoke` 为对外 AK/SK 调用端点（设计如此，不走 JWT） | 已解决 |
| 5 | ~~schema 调试端点无鉴权~~ | nl2sql `GET /api/v1/nl2sql/schema` | ✅ 已修复（历史批次）：挂 `Depends(getAuthContext)` + `requireAdmin` | 已解决 |
| 6 | ~~全局检索为哈希占位向量~~ | vector-engine `GlobalSearch`（POST /api/v1/vector/search） | ✅ 已解决（2026-08-29）：实现可插拔 embedding（`VECTOR_EMBEDDING_API` 环境变量切换真实 OpenAI 兼容服务，`mode=semantic`）；未配置时降级 n-gram 特征向量并标注 `mode=hash-fallback`，调用方可显式提示"语义检索未启用" | 已解决 |
| 7 | 跨源查询租户 ID 取自请求体（未与 JWT claim 比对） | sql-gateway `SqlGatewayController#crossSourceExecute/Explain` | ✅ 已修复（2026-08-29）：JWT claim 优先，body 不一致返回 403（TenantMismatchException），无认证上下文时回退 body 兼容单测 | 已解决 |

> 登记流程：新发现偏差先在本表登记并标注「待迁移」，修复后移入 §9.7 或删除；禁止无登记偏差长期存在。
---

## 10. 产品命名规范（v0.2 新增）

> 目标：统一产品名称、模块命名、套餐命名的规则，消除文档间命名不一致（如"旗舰版 vs 企业版"、"Starter vs Basic"等）。

### 10.1 产品名称

| 规范 | 说明 |
| --- | --- |
| **中文全称** | 数据引擎大数据平台 |
| **英文全称** | DataEngineBDP |
| **品牌名** | 数擎（Shuqing） |
| **底座名称** | SKE（DataEngine Kubernetes Engine） |
| **简称** | DataEngineBDP 或 BDP（不用"数据引擎"单独使用） |

- **禁止**：DataEngineBDP 与 DataEngine BDP 混用（统一无空格）。
- **禁止**：Shuqian（正确为 Shuqing，拼音 shù qíng）。

### 10.2 模块命名

| 规范 | 说明 |
| --- | --- |
| **Java 模块** | 24 个（按 `pom.xml` 实测，见 `docs/component-maturity.md`） |
| **Go 模块** | 10 个（按 `go.mod` 实测） |
| **Python 模块** | 12 个（按 `pyproject.toml` 实测） |
| **总计** | 46 个自研组件（Java 24 + Go 10 + Python 12） |

- 模块计数以构建文件实测为准，不使用设计文档中的理论模块数。
- **禁止**：使用"21 个 Java 模块"旧口径（governance 已拆分为 3 个独立子模块）。

### 10.3 套餐命名

| 中文显示名 | 英文显示名 | 代码键名 | 说明 |
| --- | --- | --- | --- |
| 基础版 | Starter | `base` | 最小可行产品，湖仓集主链路 |
| 标准版 | Standard | `standard` | 治理 + 开发 + BI 全套数据中台 |
| 企业版 | Enterprise | `flagship` | 数据 + AI 一站式 + 商业化运营 |

- 代码键名 `flagship` 保留以兼容现有代码；中文显示名统一为"企业版"（不用"旗舰版"）。
- 英文显示名统一为"Starter / Standard / Enterprise"（不用"Basic / Pro / Flagship"）。
- 前端 i18n `planTiers` 键名与代码键名对齐。

### 10.4 租户类型命名

| 中文显示名 | 代码标签 | 说明 |
| --- | --- | --- |
| 内部租户 | `type=internal` | 平台运营方自有业务线，全功能 + 管理权限 |
| SaaS 租户 | `type=external` | 外部客户，按套餐限制 + 自助管理 |

- **禁止**：使用"外部租户"作为面向客户的术语（内部技术文档可用，面向客户统一称"SaaS 租户"）。

### 10.5 完成度口径

全仓文档统一使用三维度完成度口径（见 `docs/component-maturity.md` 开头说明）：

| 口径 | 数值 | 含义 |
| --- | --- | --- |
| 端到端可用 | 80% | 真实完成度，含端到端联调、真实环境部署、外部依赖对接 |
| 功能模块完成 | 74.1% | GA 检查清单通过率 40/54 项 |
| 本地基础功能 | 100% | 22 个核心组件本地可运行（H2/SQLite 默认持久层） |

- **禁止**：单一数字描述完成度（必须标注口径维度）。
## 11. 语言选型指南（v0.2 新增）

> 目标：为新增组件提供明确的语言选择决策依据，消除"同业务多语言混用"与"选型随意"问题。
> 依据：ADR-001 §5 三语言选型原则（`design/详细设计/_ADR-001_微服务框架与中间件选型.md`）。

### 11.1 三语言职责边界

| 语言 | 版本 | 职责边界 | 选型依据 |
| --- | --- | --- | --- |
| **Java** | 17 | 主服务 / 治理 / 封装层 | Spring Boot 3.2.x 生态成熟（JPA / Security / WebFlux），企业级治理完备 |
| **Go** | 1.26 | CLI 工具 / 网关 / 轻量服务 | 编译单二进制（无 JVM 开销），启动毫秒级，信创 ARM64 一键交叉编译 |
| **Python** | 3.11 | AI / 数据 / 模板 / 运营 | FastAPI + Pydantic 异步类型安全，ML 生态丰富（sklearn / MLflow / LLaMA-Factory） |

### 11.2 新组件语言选择决策树

```
新组件需要选择语言？
│
├─ 1. 是否为 ML / AI / 数据科学 / 模板资产业务？
│     └─ 是 → Python 3.11（FastAPI + Pydantic）
│
├─ 2. 是否为 CLI 工具 / 网关 / 极低延迟轻量服务？
│     └─ 是 → Go 1.22+（Gin / cobra，单二进制）
│
├─ 3. 是否需要 K8s 资源翻译 / JPA 事务 / Spring Security 鉴权 / 云 SDK？
│     └─ 是 → Java 17（Spring Boot 3.2.x）
│
└─ 4. 混合场景？
      ├─ 计算密集 + 低延迟 → Go
      ├─ 企业治理 + 事务 → Java
      └─ ML / 数据 → Python
```

### 11.3 选型约束

- **禁止**：引入 Java 17 / Go 1.22+ / Python 3.11 以外的后端语言（TypeScript 仅限前端/IDE）。
- **禁止**：同一组件混用多语言（governance/finops/karmada 子模块按职责拆分除外）。
- **禁止**：因个人偏好选型；必须依据 §11.2 决策树。
- 新组件选型须在详细设计文档中记录语言选型依据（引用 ADR-001 §5.2 选型决策表）。

### 11.4 现有组件语言分布

| 语言 | 组件数 | 代表组件 |
| --- | --- | --- |
| Java 17 | 24 | encaps-layer、sql-gateway、rule-engine、governance、tag-engine、infra-orchestrator、finops |
| Go 1.22+ | 10 | catalog、llm-gateway、vector-engine、dqctl、ai-assistant、observability、infra-provider-baremetal、karmada-api、karmada-failover-api、karmada-failover-engine |
| Python 3.11 | 12 | knowledge-engine、nl2sql、batch-pipeline、llmops、ml-platform、operations-api、industry-templates |

> 组件计数口径见 [模块数口径定义](docs/模块数口径定义.md)。
