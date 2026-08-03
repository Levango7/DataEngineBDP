# L2.7 统一 SQL 网关 · 详细设计（v0.1）

> 归属：多平台多租户大数据平台 · L2 大数据引擎层
> 对标：星环 Quark 统一查询入口 / 阿里 DataWorks 统一查询（联邦、跨引擎）
> 适用套餐：基础版、标准版、旗舰版（全档含，对齐 §11.5.1 套餐矩阵）
> 关联：L0.11 封装层（网关本身以逻辑服务形态运行，Pod 由 Operator 托管，客户无感知）；L2.1 统一存储（Iceberg catalog）；L2.2 Spark（重批路由）；L2.4 Trino（交互路由）；L2.5 Doris（OLAP 路由）；L2.8 Kafka（流路由）；L2.9 IoTDB（时序路由）；L2.10 多模型（方言路由）；L3.5 资产目录（元数据同源）；L3.7 数据安全（权限/脱敏下推）

## 1. 定位与价值

统一 SQL 网关是平台的**唯一查询入口**：客户写一条 SQL，即可跨 Spark（重批）/ Flink（Stream SQL）/ Trino（交互/联邦）/ Doris（仓·集）/ Iceberg（湖）/ Kafka（流）/ IoTDB（时序）/ 多模型（图/向量/搜索/键值）/ 外部 JDBC 做联邦查询，**无需关心底层是哪种引擎、数据落在哪**。

客户价值（销售话术）：
- "一个入口查全部"——屏蔽引擎差异，降低使用门槛。
- 引擎可热替换、可扩展，不绑死某一组件。
- 权限、脱敏、审计在网关层统一收敛，合规可证。

平台价值：
- 统一元数据发现 → 资产目录（L3.5）与网关共享同一元模型。
- 统一权限下推 → 复用 L3 安全脱敏的行级/列级策略。
- 计算可路由到最便宜/最快的引擎，成本可控。

## 2. 总体架构

```text
┌──────────────────────── 客户视角（控制台 / API） ────────────────────────┐
│  统一 SQL 工作台 / BI 看板 / OpenAPI  POST /api/sql/v1/query            │
└───────────────────────────────┬────────────────────────────────────────┘
                                 ▼
┌──────────────── 统一 SQL 网关（逻辑服务，Pod 由封装层 Operator 托管） ──┐
│  Gateway API ──→ Parser(ANTLR) ──→ Planner(Calcite 联邦优化)           │
│       │                      │                                        │
│       │               权限/脱敏下推(L3)                                  │
│       ▼                      ▼                                        │
│  Router(按 catalog/代价选引擎) ──→ Executor Adapter（方言翻译/下推）    │
│       ▲                      │                                        │
│       └──────── Result Merger（跨源归并/排序/分页）←──────┘            │
└────────────────────────────────────────────────────────────────────────┘
        │       │       │       │       │       │       │       │
        ▼       ▼       ▼       ▼       ▼       ▼       ▼       ▼
   [Spark]  [Flink]  [Trino]  [Doris]  [Iceberg] [Kafka] [IoTDB]  [多模型]
   (重批)   (Stream) (交互/联邦) (仓·集) (湖)    (流)    (时序)  (图/向量/搜索/键值)
```

网关是**无状态逻辑服务**，不持有数据；实际执行在各引擎。多副本 + 无状态，水平扩展由封装层 HPA 托管。

## 3. 组件职责

| 组件 | 职责 | 关键设计 |
| --- | --- | --- |
| Gateway API | 接收查询、鉴权、返回查询结果/游标 | 统一 REST；鉴权复用封装层令牌 |
| Parser | SQL 解析为 AST | ANTLR4；宽表方言兼容 |
| Planner | 联邦查询计划、代价估算、下推决策 | Apache Calcite；基于 catalog 统计 |
| Router | 选择执行引擎、决定本地/远程执行 | 规则 + 代价双策略 |
| Executor Adapter | 方言翻译、投影/过滤/聚合下推到引擎 | 每引擎一个 Adapter（可插拔） |
| Result Merger | 跨源结果归并、排序、分页、限制 | 流式归并，防大结果集 OOM |
| Catalog Service | 统一元数据目录、可见性过滤 | 与 L3.5 资产目录同源 |

## 4. 接口契约（REST）

### 4.1 提交查询
```
POST /api/sql/v1/query
{
  "workspaceId": "ws-hd-prod",
  "sql": "SELECT u.city, SUM(p.amount) gmv FROM iceberg.ods.orders o JOIN doris.dim.user u ON o.user_id=u.user_id GROUP BY u.city",
  "defaultCatalog": "iceberg",
  "timeoutMs": 120000,
  "maxRows": 1000,
  "format": "json"
}
→ 200 { "queryId":"q-8f2a", "status":"RUNNING" }   // 异步，返回游标
→ 同步模式（小查询）直接返回 { "queryId","status":"SUCCESS","columns":[...],"rows":[...],"costMs":1800,"engine":"trino","bytesScanned":14000000 }
```

### 4.2 查询状态 / 结果
```
GET /api/sql/v1/query/{queryId}/status  → { "status":"SUCCESS|FAILED|RUNNING", "error?":{...} }
GET /api/sql/v1/query/{queryId}/result?offset=0&limit=100 → { "columns":[...], "rows":[...], "truncated":false }
```

### 4.3 元数据发现（受权限过滤）
```
GET /api/sql/v1/catalogs                      → [{catalog, type, engine}]
GET /api/sql/v1/catalogs/{c}/tables?like=ord* → [{table, rows, owner, layer, sensitive}]
GET /api/sql/v1/sql/validate                  POST {sql} → {ok, hints[], risk[]}
```
> 客户只能看到其工作空间/项目下、且通过行级权限过滤后的表与列；敏感列依 L3 策略自动隐藏或脱敏。

## 5. 联邦查询流程

1. **提交**：控制台/API 提交 SQL + workspaceId。
2. **鉴权**：校验令牌与工作空间归属（封装层颁发）。
3. **解析**：Parser 产出 AST。
4. **权限/脱敏下推**：向 L3 安全服务请求该 workspace 对涉及表/列的行级策略与脱敏规则，注入查询计划（如 WHERE tenant_id=:ctx 自动追加、敏感列套脱敏函数）。
5. **规划**：Planner 识别各段所属 catalog/引擎；能下推的投影/过滤/局部聚合下推到对应引擎；跨源 JOIN/全局排序留在网关侧（或由 Router 选 Trino 做 shuffle）。
6. **路由执行**：Router 按代价 + 引擎健康度派发；Executor Adapter 做方言翻译（见 §6）。
7. **归并**：Result Merger 流式归并、排序、分页，限制 maxRows，防 OOM。
8. **返回**：回填 columns/rows/costMs/engine/bytesScanned；写审计日志（谁、查了什么、命中哪些表）。

## 6. 方言适配（Adapter 可插拔）

| 差异点 | Iceberg/Spark SQL | Doris SQL | Trino SQL | 网关处理 |
| --- | --- | --- | --- | --- |
| 分页 | `LIMIT n OFFSET m` | `LIMIT n, m` | `LIMIT m, n`（同 Spark） | Adapter 归一化为标准 `LIMIT/OFFSET` |
| 日期函数 | `to_date()` | `to_date()` | `date()` | 映射表转换 |
| 字符串 | 基本一致 | `substring` 同 | 同 | 直传 |
|  hint | `/*+ ... */` | `/+ ... /` | 不支持 | 剥离或转写 |
| 数组/JSON | 支持 | 部分 | 支持 | 下推前判定引擎能力，不支持则在网关侧算 |

新增引擎 = 新增一个 Executor Adapter 实现，网关主体不变（与 L0 环境适配框架同思路）。

表：L2.7 网关 Executor Adapter 覆盖清单

| Adapter | 目标引擎 | 方言/协议 | 路由场景 |
| --- | --- | --- | --- |
| SparkAdapter | L2.2 Spark 3.5 | Spark SQL | 重批 SQL、大表 ETL 查询 |
| FlinkAdapter | L2.3 Flink 1.18 | Stream SQL / Table API | 流式 SQL、流表联邦 |
| TrinoAdapter | L2.4 Trino 438 | Trino SQL | 即席/联邦查询、跨源 JOIN |
| DorisAdapter | L2.5 Doris 2.1 | Doris SQL | OLAP 加速、在线点查 |
| IcebergAdapter | L2.1 Iceberg REST Catalog | Iceberg API | 湖层直查、元数据访问 |
| KafkaAdapter | L2.8 Kafka | KSQL / Consumer | 流数据探查、源表查询 |
| IoTDBAdapter | L2.9 IoTDB 2.0 | IoTDB SQL | 时序查询、降采样 |
| NebulaAdapter | L2.10 NebulaGraph | nGQL / Cypher | 图查询、多跳关系 |
| MilvusAdapter | L2.10 Milvus | Milvus SDK | 向量相似度查询 |
| ESAdapter | L2.10 Elasticsearch | DSL / SQL | 全文检索、标签筛选 |
| RedisAdapter | L2.10 Redis | Redis 命令 | 键值点查、缓存命中 |
| JdbcAdapter | 外部库 | JDBC | 外部维表联邦 |

## 7. 权限与脱敏下推（复用 L3）

- **行级**：查询计划自动追加上下文谓词（如 `tenant_id = :ws_ctx`），由封装层在提交时注入，客户不可篡改。
- **列级脱敏**：敏感列（PII/受限）调用 L3 脱敏函数（掩码/哈希/仅授权可见）；国密 SM3 在信创环境按 Profile 启用。
- **审计**：每次查询记录 workspace / 用户 / SQL 指纹 / 命中表 / 扫描量 / 耗时，供合规与计量。

## 8. 与封装层（L0.11）的关系

- 网关在客户视角是"一个查询框"；在平台内部是一个 **Deployment + Service**，其 Pod 由 Spark/Flink 之外的独立 Operator（或封装层通用 Deployment CR）托管。
- 客户**不持有 K8s 权限**、看不到 Pod/Service；扩容、故障自愈由封装层 HPA 处理。
- 网关的 CPU/内存计入工作空间 ResourceQuota，与"套餐"概念联动。

## 9. 多环境一致性

- 网关逻辑与环境无关；差异收敛到 Executor Adapter 与各环境的"引擎驱动"（L0 环境适配框架）。
- 四环境（信创/本地/云VM/私有云）同一套网关镜像 + 同一套 Profile，仅引擎端点与驱动不同。
- 联邦计划、权限下推、审计在四环境行为一致，便于交付与审计。

## 10. 关键流程（异常与边界）

- **超时**：超过 timeoutMs 取消下游引擎查询，返回 FAILED + 原因；客户端可轮询 status。
- **大结果集**：maxRows 截断 + `truncated=true` 提示；支持游标翻页，避免一次性拉全量。
- **跨源 JOIN 性能**：对超大表跨源 JOIN，Router 优先选 Trino 做分布式 shuffle；超阈值时返回风险 hint，建议预物化。
- **引擎故障**：某引擎不可达，Router 标记不健康并路由备选；无备选则返回 FAILED。

## 11. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 跨源 JOIN 性能差 | 查询慢、客户感知差 | Router 选 Trino shuffle；热点表预物化到 Doris；返回风险 hint |
| 方言差异导致结果不一致 | 数据可信度受损 | 统一类型系统 + 适配单测；validate 接口前置校验 |
| 大结果集 OOM | 网关不稳 | 流式归并 + maxRows 硬限 + 游标 |
| 权限下推遗漏 | 越权/合规事故 | 计划阶段强制注入行级谓词；审计全量留痕；L3 策略变更实时刷新 |
| 引擎端点随环境变化 | 多环境交付复杂 | Adapter + Profile 外置，网关主体不变 |

## 12. 与 UI 的对应

控制台「统一 SQL」页（v0.2/v0.3）已体现：单一查询框、跨 Iceberg+Doris 联邦示例、引擎自动路由、AI 辅助（自然语言→SQL，对标 DataWorks Copilot）。本文件是其后端契约依据；BI 看板的数据源即「统一 SQL 网关」，保证客户"只看到一个入口"。
