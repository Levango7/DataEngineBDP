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
| **flagship** | 旗舰版 |

- 与 `design/deploy/values/*/values.yaml` 的 `tierProfiles` 三档键名对齐。
- 运营后台 API（`operations/main.py`）返回的 `package` 字段取值范围为 `base` / `standard` / `flagship`。
- 产品文档 §11.5 中文表述"基础版 / 标准版 / 旗舰版"对应英文键 `base` / `standard` / `flagship`。
- **禁止**：`basic`、`enterprise`、`pro`、`premium` 等同义别名。

## 2. 工作空间命名

| 规范 | 格式 | 示例 |
| --- | --- | --- |
| **ws-\<name>** | `ws-` 前缀 + 小写字母/数字/连字符，长度 ≤ 32 | `ws-demo`、`ws-acme-prod` |

- 对应 K8s Namespace 名称。
- **禁止**：`<tenant>-default`（如 `acme-default`）、`<tenant>-ws` 等其他约定。
- 租户 → 工作空间映射由封装层（L0.11）管理，Namespace 上打 `tenant=<tid>` 标签。

## 3. 模块计数

| 规范 | 说明 |
| --- | --- |
| **49 模块** | §3.3 产品能力全景图逐行清点的真实模块数 |

- 分布：L0.1–L0.12（12）+ L2.1–L2.10（10）+ L3.1–L3.7（7）+ L4.1–L4.5.6（10）+ L5.1–L5.6（6）+ X1–X4（4）= **49**。
- 详细设计文档 51 份（49 模块 - 6 合并详设 + 部署清单 + 端到端 PoC + 控制台信息架构 + 运营后台实现落地 + 2 补充文档）。
- 部署清单 87 个 Chart 条目（含 13 个自研组件 Chart + 74 个开源组件 Chart）。
- **禁止**：沿用"41 模块"旧口径。

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
- 引擎版本扫描：`grep -rE 'Trino 438|Doris 2\.1|Kafka 3\.7' design/` 应为空。

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
| 1 | 跨源查询失败返回 200 + FAILED | sql-gateway `SqlGatewayController#crossSourceExecute / crossSourceExplain` | 部分结果语义，调用方以 status 字段判别（api-reference 4.11 已如实描述） | 待迁移：评估改 5xx，或固化写入 SDK 契约 |
| 2 | 特例错误码 PascalCase | encaps-tenant `QuotaController`（QuotaExceeded=422 / Conflict=409）、rule-engine 部分大写码（RULE_NOT_FOUND 等） | 违反 §9.3 snake_case | 待迁移 |
| 3 | 无 CORS 中间件 | nl2sql / open-api-catalog / asset-exchange 各 app.py | 浏览器直连受同源限制（Go 栈均有 CorsMiddleware） | 待迁移：补 CORSMiddleware |
| 4 | 管理/订阅端点未挂应用层鉴权 | open-api-catalog app.py include_router | 依赖部署侧网关策略 | 待迁移：应用层 JWT 中间件 |
| 5 | schema 调试端点无鉴权 | nl2sql `GET /api/v1/nl2sql/schema` | 匿名可达 | 待迁移：挂 getAuthContext |
| 6 | 全局检索为哈希占位向量 | vector-engine `GlobalSearch`（POST /api/v1/vector/search） | 文本哈希向量，无语义检索能力 | 待迁移：接入 embedding 服务 |

> 登记流程：新发现偏差先在本表登记并标注「待迁移」，修复后移入 §9.7 或删除；禁止无登记偏差长期存在。
