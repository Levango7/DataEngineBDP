# 数据引擎大数据平台 API 参考文档

> 版本：V2.2 | API 前缀：`/api/v1` | 认证方式：JWT Bearer Token | 更新日期：2026-08-25

## 第1章 概述

数据引擎大数据平台提供统一的 RESTful API，覆盖封装层、SQL 网关、Catalog、规则引擎、数据虚拟化、治理中台、标签引擎、向量引擎、大模型网关、自然语言查询（NL2SQL）、可观测查询、LLMOps、知识图谱、ML 平台、业务门户、开放 API 目录、资产流通等组件。所有 API 使用 JSON 作为请求与响应格式，统一前缀 `/api/v1`，统一错误码体系。

### 1.1 基础 URL

| 环境 | 基础 URL |
|------|----------|
| 生产 | `https://<platform-domain>/api/v1` |
| 预发 | `https://<platform-domain>-staging/api/v1` |
| 本地开发 | `http://localhost:8080/api/v1`（封装层）/ `8081`（SQL 网关）/ `8082`（Catalog）/ `8083`（规则引擎）/ `8084`（大模型网关）/ `8086`（向量引擎）/ `8090`（可观测查询）/ `8091`（行业模板）/ `8093`（NL2SQL） |

### 1.2 通用响应格式

平台存在两类成功响应封装（新服务二选一并在模块 README 声明，见 CONVENTIONS.md §9）：

**包裹型**（encaps-layer 等 Java 栈现状，`code=0` 表示成功）：

```json
{
  "code": 0,
  "message": "OK",
  "data": { },
  "traceId": "xxx",
  "timestamp": 1700000000000
}
```

**资源直出**（Go/FastAPI 及其余 Java 服务现状）：直接返回资源对象或数组；列表类端点常用 `{"data": [...], "total": n}` 包裹。

错误响应统一为：

```json
{
  "error": "error_code",
  "message": "human readable message"
}
```

> 部分服务会附加 `timestamp` 字段；Go 服务可能仅返回 `{"error": "..."}`。个别特例（如 sql-gateway 跨源查询失败返回 HTTP 200 + `status=FAILED`，见 4.11）在各章单独说明。

## 第2章 API 认证

### 2.1 JWT Token 获取

通过 Keycloak OAuth2 端点获取 JWT：

```bash
curl -X POST https://<platform-domain>/realms/<tenant>/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=shuqing-cli" \
  -d "username=<user>" \
  -d "password=<password>"
```

响应：

```json
{
  "access_token": "eyJhbGciOi...",
  "refresh_token": "eyJhbGciOi...",
  "expires_in": 1800,
  "refresh_expires_in": 86400,
  "token_type": "Bearer"
}
```

### 2.2 Token 刷新

```bash
curl -X POST https://<platform-domain>/realms/<tenant>/protocol/openid-connect/token \
  -d "grant_type=refresh_token" \
  -d "client_id=shuqing-cli" \
  -d "refresh_token=<refresh_token>"
```

### 2.3 请求携带 Token

所有需认证的 API 请求须在 Header 中携带：

```
Authorization: Bearer <access_token>
```

JWT 中包含 `tenantId` claim，用于租户上下文隔离。

### 2.4 各服务鉴权矩阵（Phase A 安全加固后，V2.2 逐仓核实）

| 服务 | 鉴权要求 | 匿名豁免端点 |
|------|----------|--------------|
| catalog | 业务端点 Bearer JWT（租户身份强制取自 claim） | GET /api/v1/health、GET /metrics |
| vector-engine | 业务端点默认强制 Bearer JWT（secure-by-default；`VECTOR_AUTH_REQUIRED=false` 显式关闭、`JWT_DEV_MODE=true` 开发旁路），issuer 默认 `shuqing-bigdata` | GET /api/v1/health |
| query-api | /tenant/** 需 JWT 并强制注入 tenant_id 过滤；/platform/** 与 /api/v1/ops/** 需 platform-ops 角色 | GET /api/v1/health |
| knowledge-engine | 全部业务端点 Bearer JWT（FastAPI 路由级依赖注入）；原生 nGQL 查询端点在 AUTH_MODE=none 时直接 403 拒绝 | GET /health |
| asset-exchange | assets / subscriptions / audit 全部业务端点 Bearer JWT | GET /api/v1/health |
| open-api-catalog | 目录管理/订阅端点暂未挂载应用层鉴权中间件（以部署侧网关策略为准）；API 调用需 X-API-Key + X-API-Secret（见 22.4） | GET /api/v1/health |
| nl2sql | generate / execute / dialogue / validate 走 getAuthContext：AUTH_MODE=jwt 时 Bearer HS256；AUTH_MODE=none（缺省）匿名放行且角色视为 admin（见第24章） | GET /api/v1/health、GET /api/v1/nl2sql/schema |
| ai-assistant | /api/v1/ai-assistant/** 全部 Bearer JWT；请求体 tenantId 与 claim 不一致返回 403 | GET /api/v1/health |
| infra-provider-baremetal | POST /api/v1/auth/login 匿名可达（认证引导入口）；POST /api/v1/auth/refresh 及 clusters 业务端点需 Bearer JWT | GET /healthz、GET /readyz、GET /version |

> **AUTH_MODE 说明**：llmops / ml-platform / nl2sql / knowledge-engine / asset-exchange 共用的镜像 `jwt_auth` 模块——`AUTH_MODE=jwt` 强制校验 HS256 Bearer token（可配 `JWT_EXPECTED_ISSUER` 校验 iss）；`AUTH_MODE=none`（缺省）返回匿名上下文（role=admin），仅限本地/测试，进程生命周期内告警一次；生产必须显式设置 `AUTH_MODE=jwt`。

## 第3章 封装层 API（REST）

封装层（encaps-layer）提供租户、配额、工作空间、安全等管理能力，统一前缀 `/api/v1`。

### 3.1 租户管理

#### 3.1.1 POST /api/v1/tenants — 创建租户

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | string | 是 | 租户标识（小写字母+数字+短横线） |
| displayName | string | 是 | 租户显示名 |
| namespace | string | 否 | K8s Namespace，默认 `ns-<name>` |
| quota | object | 否 | 资源配额 |

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/tenants \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "tenant-finance",
    "displayName": "金融业务租户",
    "namespace": "ns-finance",
    "quota": {"cpu": "20", "memory": "40Gi", "storage": "500Gi"}
  }'
```

**响应示例（201 Created）**

```json
{
  "id": 1001,
  "name": "tenant-finance",
  "displayName": "金融业务租户",
  "namespace": "ns-finance",
  "status": "ACTIVE",
  "createdAt": "2026-08-08T10:00:00Z"
}
```

#### 3.1.2 GET /api/v1/tenants — 租户列表

**请求示例**

```bash
curl https://<platform-domain>/api/v1/tenants \
  -H "Authorization: Bearer ${TOKEN}"
```

**响应示例（200 OK）**

```json
[
  {"id": 1001, "name": "tenant-finance", "displayName": "金融业务租户", "status": "ACTIVE"},
  {"id": 1002, "name": "tenant-energy", "displayName": "能源租户", "status": "ACTIVE"}
]
```

> 另有 `GET /api/v1/tenants/all`：列出全部租户（不分页，供前端下拉选择），返回内容与列表端点一致。

#### 3.1.3 GET /api/v1/tenants/{id} — 查询租户

**路径参数**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | integer | 租户 ID |

**响应示例（200 OK）**

```json
{
  "id": 1001,
  "name": "tenant-finance",
  "displayName": "金融业务租户",
  "namespace": "ns-finance",
  "status": "ACTIVE",
  "createdAt": "2026-08-08T10:00:00Z",
  "updatedAt": "2026-08-08T10:00:00Z"
}
```

**错误响应（404 Not Found）**

不存在时返回 **404 且响应体为空**（实现为 `ResponseEntity.notFound().build()`；encaps-tenant 服务无全局异常处理器包装）。早期文档版本示例中的 `{"error": "tenant_not_found", ...}` JSON 体与实际行为不符。

#### 3.1.4 PUT /api/v1/tenants/{id} — 更新租户

**请求示例**

```bash
curl -X PUT https://<platform-domain>/api/v1/tenants/1001 \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"displayName": "金融业务租户-V2", "quota": {"cpu": "40", "memory": "80Gi"}}'
```

**响应示例（200 OK）**

```json
{
  "id": 1001,
  "name": "tenant-finance",
  "displayName": "金融业务租户-V2",
  "status": "ACTIVE",
  "updatedAt": "2026-08-08T11:00:00Z"
}
```

#### 3.1.5 DELETE /api/v1/tenants/{id} — 删除租户

**响应**：204 No Content（成功） / 404 Not Found（不存在）

### 3.2 配额管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/quotas | 设置 Quota，返回 201 |
| GET | /api/v1/quotas | 列表（支持 tenantId / workspaceId 过滤） |
| GET | /api/v1/quotas/{id} | 配额详情 |
| PUT | /api/v1/quotas/{id} | 更新配额（仅可变字段） |
| DELETE | /api/v1/quotas/{id} | 删除配额（级联删除 K8s ResourceQuota + LimitRange） |
| GET | /api/v1/quotas/workspace/{workspaceId}/usage | 查询 Workspace 当前资源用量 |

#### 3.2.1 PUT /api/v1/quotas/{id} — 更新配额

**路径参数**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | integer | 配额 ID |

**请求示例**

```bash
curl -X PUT https://<platform-domain>/api/v1/quotas/1001 \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"cpu":"80","memory":"160Gi","storage":"2Ti","pvcCount":"30"}'
```

**错误响应（422 Unprocessable Entity）**

```json
{"error":"QuotaExceeded","message":"cpu 超限","resourceKey":"cpu","used":"60","hard":"80","requested":"100"}
```

### 3.3 工作空间管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/workspaces | 创建 Workspace，返回 201 |
| GET | /api/v1/workspaces | 列表（支持 tenantId 过滤） |
| GET | /api/v1/workspaces/{id} | 详情 |
| PUT | /api/v1/workspaces/{id} | 更新 |
| DELETE | /api/v1/workspaces/{id} | 删除（级联删除 K8s Namespace） |
| GET | /api/v1/workspaces/{id}/status | K8s Namespace 实时状态 |

#### 3.3.1 POST /api/v1/workspaces — 创建工作空间

```bash
curl -X POST https://<platform-domain>/api/v1/workspaces \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"name":"ws-risk","tenantId":1001,"description":"风控工作空间"}'
```

### 3.4 安全门面 API

SecurityFacadeController 提供安全能力统一入口，前缀 `/api/v1/security`，全部端点需 JWT 认证。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/security/status | 查询 SecurityFacade 状态（crypto/mask/audit/auth） |
| POST | /api/v1/security/mask | 执行脱敏（请求体：`{"input":"13812345678","type":"PHONE"}`） |
| GET | /api/v1/security/audit/events | 查询审计事件（可选 level 过滤） |
| GET | /api/v1/security/auth/check | 鉴权检查（返回当前 principal 与权限） |
| POST | /api/v1/security/evidence/collect | 收集并归档证据 |
| POST | /api/v1/security/assessment/export | 导出测评报告（请求体：`{"type":"dengbao-2.0","systemName":"数据引擎大数据平台"}`） |

## 第4章 SQL 网关 API

SQL 网关（sql-gateway）提供统一 SQL 执行、路由管理、解析、优化、跨源查询能力，统一前缀 `/api/v1/sql`。SQL 网关基于手写 SQL 解析 + 跨源归并引擎实现跨源联邦查询（Apache Calcite 集成规划中）。

### 4.1 POST /api/v1/sql/execute — 执行 SQL

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sql | string | 是 | SQL 语句（网关默认开启只读模式，仅放行 SELECT/SHOW/DESC/WITH/EXPLAIN） |
| tenantId | string | 是 | 租户 ID；已认证请求以 JWT claim 中的 tenantId 为准，body 值仅作无鉴权调用回退 |
| engine | string | 否 | 目标引擎：trino / doris，默认由路由规则决定 |
| limit | integer | 否 | 返回行数上限；与网关 default-limit/max-rows 取小后生效，超出标记 truncated=true |
| dialect | string | 否 | SQL 方言：trino / doris / hive / spark，默认 trino |
| timeout | integer | 否 | 超时秒数，默认 60 |

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/sql/execute \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT customer_id, COUNT(*) AS cnt FROM transaction GROUP BY customer_id LIMIT 10",
    "tenantId": "tenant-finance",
    "limit": 100,
    "engine": "trino"
  }'
```

**响应示例（200 OK）**

```json
{
  "queryId": "q-20260808-001",
  "status": "SUCCESS",
  "columns": ["customer_id", "cnt"],
  "rows": [["C001", 42], ["C002", 35]],
  "rowCount": 2,
  "durationMs": 120,
  "truncated": false,
  "message": null
}
```

> 字段说明：`truncated=true` 表示结果集不完整（触发行数上限，或 Trino 仅返回分页首页）；
> `message` 承载截断说明、只读模式拒绝原因、降级原因等附加信息；
> `status=FAILED` 表示被网关门禁拒绝或后端执行失败（此时 `message` 含具体原因）。

### 4.2 GET /api/v1/sql/routes — 查询路由规则

**响应示例**

```json
[
  {"id": 1, "pattern": "SELECT.*FROM doris\\..*", "engine": "doris", "priority": 10},
  {"id": 2, "pattern": ".*", "engine": "trino", "priority": 1}
]
```

### 4.3 POST /api/v1/sql/routes — 添加路由规则

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/sql/routes \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"pattern":"SELECT.*FROM iceberg\\..*","engine":"trino","priority":5}'
```

**响应示例（200 OK）**

```json
{"id": 3, "pattern": "SELECT.*FROM iceberg\\..*", "engine": "trino", "priority": 5}
```

### 4.4 GET /api/v1/sql/engines — 列出可用引擎

**响应**：`["trino", "doris"]`

### 4.5 POST /api/v1/sql/parse — 解析 SQL

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/sql/parse \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"sql":"SELECT a, b FROM t1 JOIN t2 ON t1.id=t2.id","dialect":"trino"}'
```

**响应示例**

```json
{
  "dialect": "TRINO",
  "statementType": "SELECT",
  "tables": ["t1", "t2"],
  "columns": ["a", "b"],
  "children": [{"type": "JOIN", "properties": {}, "children": []}]
}
```

### 4.6 POST /api/v1/sql/validate — 校验 SQL

**响应示例**

```json
{"valid": true, "dialect": "TRINO"}
```

### 4.7 POST /api/v1/sql/convert — 方言转换

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/sql/convert \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"sql":"SELECT DATE_FORMAT(t, \"%Y-%m-%d\") FROM t1","fromDialect":"hive","toDialect":"trino"}'
```

**响应示例**

```json
{"fromDialect": "HIVE", "toDialect": "TRINO", "convertedSql": "SELECT format_datetime(t, 'yyyy-MM-dd') FROM t1"}
```

### 4.8 POST /api/v1/sql/optimize — 优化 SQL

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sql | string | 是 | SQL 语句 |
| dialect | string | 否 | 方言 |
| enableAllRules | boolean | 否 | 是否启用全部优化规则 |

**响应示例**

```json
{
  "originalSql": "SELECT * FROM t1 WHERE id=1",
  "optimizedSql": "SELECT id, name FROM t1 WHERE id=1",
  "executionPlan": "...",
  "rulesApplied": ["PUSH_DOWN_FILTER", "COLUMN_PRUNE"],
  "estimatedCost": 12.5,
  "estimatedRows": 1,
  "tableAccesses": ["t1"],
  "suggestions": ["建议为 t1.id 建立索引"],
  "success": true,
  "dialect": "TRINO"
}
```

### 4.9 POST /api/v1/sql/explain — 生成执行计划

**响应示例**

```json
{
  "originalSql": "SELECT * FROM t1",
  "executionPlan": "TableScan[t1] -> ...",
  "rulesApplied": [],
  "estimatedCost": 100.0,
  "estimatedRows": 10000,
  "tableAccesses": ["t1"],
  "success": true,
  "dialect": "TRINO"
}
```

### 4.10 GET /api/v1/sql/optimize/rules — 列出优化规则

**响应**：`["PUSH_DOWN_FILTER", "COLUMN_PRUNE", "JOIN_REORDER", "PREDICATE_PUSH_DOWN"]`

### 4.11 POST /api/v1/sql/cross-source — 跨源查询

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/sql/cross-source \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT c.name, v.amount FROM doris.customer c JOIN v_mysql_orders v ON c.id=v.customer_id",
    "tenantId": "tenant-finance",
    "dialect": "trino"
  }'
```

**响应示例**

```json
{
  "queryId": "cs-uuid-xxx",
  "status": "SUCCESS",
  "columns": ["name", "amount"],
  "rows": [["张三", 100.0], ["李四", 200.0]],
  "rowCount": 2,
  "source": "MULTIPLE",
  "crossSource": true,
  "sources": ["DORIS", "MYSQL"],
  "tableToSource": {"customer": "DORIS", "v_mysql_orders": "MYSQL"},
  "durationMs": 350
}
```

**失败响应（部分结果语义，HTTP 200）**

跨源执行失败**不返回 HTTP 5xx**：Controller 捕获 `CrossSourceException` 后统一返回 **HTTP 200**，响应体携带 `status=FAILED`、空 `columns`/`rows`、`rowCount=0` 与 `error="错误码: 原因"`：

```json
{
  "queryId": "cs-uuid-xxx",
  "status": "FAILED",
  "columns": [],
  "rows": [],
  "rowCount": 0,
  "durationMs": 12,
  "error": "SOURCE_NOT_FOUND: table v_mysql_orders has no source mapping"
}
```

> 调用方必须以 `status` 字段（SUCCESS / FAILED / DEGRADED）而非 HTTP 状态码判断执行结果。
> 错误码全集：`PARSE_ERROR` / `SOURCE_NOT_FOUND` / `QUERY_TIMEOUT` / `QUERY_FAILED` / `MERGE_ERROR` / `RESULT_TOO_LARGE` / `UNSUPPORTED`。

### 4.12 POST /api/v1/sql/cross-source/explain — 跨源执行计划

**响应示例**

```json
{
  "sql": "SELECT ...",
  "statementType": "SELECT",
  "tables": ["customer", "v_mysql_orders"],
  "tableToSource": {"customer": "DORIS", "v_mysql_orders": "MYSQL"},
  "sources": ["DORIS", "MYSQL"],
  "crossSource": true,
  "strategy": "PARALLEL_MERGE",
  "durationMs": 5
}
```

> 执行计划生成失败同样返回 HTTP 200，响应体仅含 `sql`、`durationMs` 与 `error="错误码: 原因"` 字段（无 status 列）。

### 4.13 查询改写 API（RewriteController）

查询改写与物化视图自动路由，统一前缀 `/api/v1/rewrite`。

#### 4.13.1 改写与路由

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/rewrite/execute | 对 SQL 执行自动改写，返回改写后 SQL |
| POST | /api/v1/rewrite/route | 仅返回路由决策（不改写），用于调试 |
| POST | /api/v1/rewrite/candidates | 列出所有候选匹配结果（按评分降序） |

#### 4.13.2 物化视图定义管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/rewrite/views | 列出所有视图定义 |
| GET | /api/v1/rewrite/views/{viewName} | 获取单个视图定义 |
| POST | /api/v1/rewrite/views | 新增视图定义（已存在返回 409） |
| PUT | /api/v1/rewrite/views/{viewName} | 更新视图定义 |
| DELETE | /api/v1/rewrite/views/{viewName} | 删除视图定义 |
| POST | /api/v1/rewrite/views/{viewName}/refresh | 刷新视图最近刷新时间 |

#### 4.13.3 改写规则管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/rewrite/rules | 列出所有改写规则 |
| GET | /api/v1/rewrite/rules/{ruleName} | 获取单个改写规则 |
| POST | /api/v1/rewrite/rules | 新增改写规则（已存在返回 409） |
| DELETE | /api/v1/rewrite/rules/{ruleName} | 删除改写规则 |

## 第5章 Catalog API

Catalog（Go 实现）提供数据库与表的元数据管理，统一前缀 `/api/v1/catalog`，需 JWT 认证。

### 5.1 数据库管理

#### 5.1.1 GET /api/v1/catalog/databases — 数据库列表

**响应示例**

```json
{"data": [{"id": "db-001", "name": "finance_db", "type": "DORIS"}], "total": 1}
```

#### 5.1.2 POST /api/v1/catalog/databases — 创建数据库

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/catalog/databases \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"name":"finance_db","type":"DORIS","description":"金融业务库"}'
```

**响应示例（201 Created）**

```json
{"id": "db-uuid-xxx", "name": "finance_db", "type": "DORIS", "createdAt": "2026-08-08T10:00:00Z"}
```

#### 5.1.3 GET /api/v1/catalog/databases/{id} — 数据库详情

#### 5.1.4 DELETE /api/v1/catalog/databases/{id} — 删除数据库

### 5.2 表管理

#### 5.2.1 GET /api/v1/catalog/tables — 表列表

**响应示例**

```json
{"data": [{"id": "tbl-001", "name": "transaction", "databaseId": "db-001", "columns": [...]}], "total": 1}
```

#### 5.2.2 POST /api/v1/catalog/tables — 注册表

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/catalog/tables \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "transaction",
    "databaseId": "db-001",
    "columns": [
      {"name": "transaction_id", "type": "BIGINT"},
      {"name": "customer_id", "type": "VARCHAR(32)"},
      {"name": "amount", "type": "DECIMAL(18,2)"}
    ],
    "partitionKeys": ["dt"]
  }'
```

**响应示例（201 Created）**

```json
{"id": "tbl-uuid-xxx", "name": "transaction", "databaseId": "db-001", "createdAt": "2026-08-08T10:00:00Z"}
```

#### 5.2.3 GET /api/v1/catalog/tables/{id} — 表详情

#### 5.2.4 PUT /api/v1/catalog/tables/{id} — 更新表

#### 5.2.5 DELETE /api/v1/catalog/tables/{id} — 删除表

### 5.3 表格全文检索

#### 5.3.1 GET /api/v1/catalog/search/tables — 检索表

对当前租户范围内的表名与描述执行中文分词全文检索，按相关性分数降序返回。

**查询参数**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| q | string | 是 | 检索关键字；缺失或为空返回 400 |
| limit | integer | 否 | 结果上限，默认 50；超过 200 按 200 截断 |

**请求示例**

```bash
curl "https://<platform-domain>/api/v1/catalog/search/tables?q=交易&limit=20" \
  -H "Authorization: Bearer ${TOKEN}"
```

**响应示例（200 OK）**

```json
{"data": [{"table": {"id": "tbl-001", "name": "transaction"}, "score": 3.2}], "total": 1, "query": "交易"}
```

**错误响应**：400（`q` 缺失或 `limit` 非法）、401（缺租户身份）、500（存储错误）。

## 第6章 规则引擎 API

规则引擎（rule-engine）提供数据质量、脱敏、告警规则管理与执行，统一前缀 `/api/v1/rules`。

### 6.1 POST /api/v1/rules — 创建规则

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/rules \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "not_null_check",
    "type": "DQ",
    "expression": "column:customer_id NOT NULL",
    "target": {"table": "transaction", "database": "finance_db"}
  }'
```

**响应示例（201 Created）**

```json
{"id": 1, "name": "not_null_check", "type": "DQ", "createdAt": "2026-08-08T10:00:00Z"}
```

### 6.2 GET /api/v1/rules — 规则列表

**响应示例**

```json
[
  {"id": 1, "name": "not_null_check", "type": "DQ"},
  {"id": 2, "name": "id_card_mask", "type": "MASK"}
]
```

### 6.3 GET /api/v1/rules/{id} — 规则详情

### 6.4 PUT /api/v1/rules/{id} — 更新规则

### 6.5 DELETE /api/v1/rules/{id} — 删除规则

### 6.6 POST /api/v1/rules/execute — 执行规则

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/rules/execute \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"ruleId": 1, "params": {"table": "transaction", "dt": "2026-08-08"}}'
```

**响应示例**

```json
{
  "ruleId": 1,
  "status": "PASS",
  "totalRows": 10000,
  "violatedRows": 0,
  "message": "All rows passed",
  "durationMs": 250
}
```

#### 6.6.1 POST /api/v1/rules/execute/batch — 批量执行规则

并行执行多条规则，单条失败隔离（每条规则独立 status），批次汇总成功/失败计数。始终返回 200。

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| ruleIds | array[integer] | 是 | 规则 ID 列表 |
| context | object | 否 | 执行上下文（透传给每条规则） |
| tenantId | string | 否 | 租户 ID |

**响应示例（200 OK）**

```json
{
  "results": [
    {"ruleId": 1, "status": "PASS", "totalRows": 10000, "violatedRows": 0},
    {"ruleId": 2, "status": "ERROR", "message": "RULE_NOT_FOUND"}
  ],
  "successCount": 1,
  "failedCount": 1,
  "totalDurationMs": 480,
  "executedAt": "2026-08-25T10:00:00"
}
```

### 6.7 GET /api/v1/rules/types — 规则类型列表

**响应**：`["DQ", "MASK", "ALERT"]`

- `DQ`：数据质量规则
- `MASK`：脱敏规则（支持掩码/哈希/仅授权/假名四种脱敏函数）
- `ALERT`：告警规则

### 6.8 调度引擎 API（SchedulerController）

调度引擎提供任务提交/查询/取消、调度器状态、租户管理与资源配额管理，前缀 `/api/v1/scheduler`。

#### 6.8.1 任务 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/scheduler/tasks | 提交调度任务（返回 202；被拒绝返回 429） |
| GET | /api/v1/scheduler/tasks | 列出全部任务 |
| GET | /api/v1/scheduler/tasks/{taskId} | 查询单个任务 |
| DELETE | /api/v1/scheduler/tasks/{taskId} | 取消任务 |

#### 6.8.2 调度器状态

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/scheduler/status | 查询调度引擎运行状态（队列/worker/负载/利用率） |

#### 6.8.3 租户管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/scheduler/tenants | 注册/更新租户 |
| GET | /api/v1/scheduler/tenants | 列出全部租户 |
| PUT | /api/v1/scheduler/tenants/{tenantId}/enabled | 启用/禁用租户（query 参数 enabled） |

#### 6.8.4 资源配额管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/scheduler/quotas | 列出全部资源配额 |
| PUT | /api/v1/scheduler/quotas/{tenantId} | 设置租户资源配额（maxCpuCores / maxMemoryMb） |

### 6.9 Agent 编排 API（AgentController）

提供 8 种内置 Agent 角色（PLANNING/SQL/VISUALIZATION/QUALITY/LINEAGE/DOCUMENTATION/CODE/AUDIT）的统一入口，前缀 `/api/v1/agents`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/agents/{role}/execute | 执行指定角色 Agent |
| GET | /api/v1/agents | 列出所有可用角色 |
| GET | /api/v1/agents/describe | 描述所有角色元数据（含配额、白名单） |
| GET | /api/v1/agents/{role}/describe | 描述单个角色元数据 |

### 6.10 编排引擎 API（OrchestratorController）

提供 DAG 提交、查询、执行、停止、可视化与删除端点，前缀 `/api/v1/orchestrator/dags`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/orchestrator/dags | 提交 DAG（返回 201） |
| GET | /api/v1/orchestrator/dags | 列出所有 DAG |
| GET | /api/v1/orchestrator/dags/{id} | 查询 DAG 详情 |
| POST | /api/v1/orchestrator/dags/{id}/run | 执行 DAG |
| POST | /api/v1/orchestrator/dags/{id}/stop | 停止 DAG |
| GET | /api/v1/orchestrator/dags/{id}/results | 查询执行结果 |
| GET | /api/v1/orchestrator/dags/{id}/mermaid | 生成 Mermaid 可视化文本 |
| GET | /api/v1/orchestrator/dags/{id}/json | 导出 JSON 结构 |
| DELETE | /api/v1/orchestrator/dags/{id} | 删除 DAG |

### 6.11 数据质量规则 API（QualityRuleController）

面向前端质量规则契约（targetTable / checkType / threshold / actionOnFail）的端点，内部映射到通用 Rule 模型存储，前缀 `/api/v1/quality/rules`。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/quality/rules | 分页列表（query 参数 page / size，默认 1 / 20），返回 `{list, total, page, size}` |
| GET | /api/v1/quality/rules/{id} | 详情（404 空 body） |
| POST | /api/v1/quality/rules | 创建（映射到 Rule 模型；返回 200，非 201） |
| PUT | /api/v1/quality/rules/{id} | 更新（404 空 body） |
| DELETE | /api/v1/quality/rules/{id} | 删除：成功返回 `{"deleted": true}`，不存在 404 |
| POST | /api/v1/quality/rules/{id}/check | 立即触发规则校验，返回视图含 `lastCheckAt` 与 `lastResult{passed, message}` |
| GET | /api/v1/quality/rules/summary | 对全部规则执行校验后的通过率统计 `{total, passed, passRate}` |

**创建请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/quality/rules \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"name":"not_null_customer_id","targetTable":"ods.orders","targetField":"customer_id","checkType":"not_null","threshold":"0","actionOnFail":"WARN"}'
```

**视图字段**：id / name / targetTable / targetField / checkType / threshold / actionOnFail / status（enabled \| disabled）/ createdAt / updatedAt。

## 第7章 数据虚拟化 API

数据虚拟化（VirtualTableController）提供外部源虚拟表管理，统一前缀 `/api/v1/virtual-tables`，全部端点需认证，租户 ID 从 JWT 的 `tenantId` claim 提取。

### 7.1 POST /api/v1/virtual-tables — 注册虚拟表

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| tableName | string | 是 | 虚拟表名 |
| dataSourceType | string | 是 | 数据源类型：MYSQL/ORACLE/JDBC/KAFKA/REST |
| connection | object | 是 | 连接配置 |
| schema | array | 是 | 列定义 |
| tenantId | string | 否 | 租户 ID（默认从 JWT 提取） |

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/virtual-tables \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "tableName": "v_mysql_orders",
    "dataSourceType": "MYSQL",
    "connection": {
      "host": "10.0.0.20", "port": 3306,
      "username": "ro_user", "password": "${SECRET}",
      "database": "order_db", "table": "orders"
    },
    "schema": [{"name": "order_id", "type": "BIGINT"}, {"name": "amount", "type": "DECIMAL(18,2)"}]
  }'
```

**响应示例（201 Created）**

```json
{"tableName": "v_mysql_orders", "dataSourceType": "MYSQL", "tenantId": "tenant-finance", "createdAt": "2026-08-08T10:00:00Z"}
```

**错误响应（409 Conflict）**

```json
{"error": "Virtual table v_mysql_orders already exists"}
```

### 7.2 GET /api/v1/virtual-tables — 虚拟表列表

**查询参数**：`dataSourceType`（可选，按类型过滤）

**响应示例**

```json
[
  {"tableName": "v_mysql_orders", "dataSourceType": "MYSQL"},
  {"tableName": "v_kafka_events", "dataSourceType": "KAFKA"}
]
```

### 7.3 GET /api/v1/virtual-tables/{tableName} — 虚拟表详情

### 7.4 PUT /api/v1/virtual-tables/{tableName} — 更新虚拟表

### 7.5 DELETE /api/v1/virtual-tables/{tableName} — 删除虚拟表

### 7.6 POST /api/v1/virtual-tables/{tableName}/query — 查询虚拟表数据

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/virtual-tables/v_mysql_orders/query \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"predicate": "amount > 1000", "limit": 100}'
```

**响应示例**

```json
{"columns": ["order_id", "amount"], "rows": [[1, 1500.0], [2, 2000.0]], "rowCount": 2}
```

### 7.7 GET /api/v1/virtual-tables/{tableName}/schema — 获取 schema

**响应示例**

```json
[{"name": "order_id", "type": "BIGINT"}, {"name": "amount", "type": "DECIMAL(18,2)"}]
```

### 7.8 POST /api/v1/virtual-tables/{tableName}/test-connection — 测试连接

**响应**：`{"connected": true}`

### 7.9 POST /api/v1/virtual-tables/{tableName}/refresh — 手动刷新物化表

**响应**：`{"refreshed": true, "rows": 10000}`

### 7.10 GET /api/v1/virtual-tables/cache/stats — 缓存统计

**响应示例**

```json
{"hits": 1500, "misses": 50, "hitRate": 0.967, "size": 200}
```

### 7.11 GET /api/v1/virtual-tables/types — 支持的数据源类型

**响应**：`["MYSQL", "ORACLE", "JDBC", "KAFKA", "REST"]`

## 第8章 行业模板 API

行业模板服务（Python/FastAPI 实现）提供模板管理与部署能力，统一前缀 `/api/v1/templates`。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/templates | 列出所有模板（支持过滤） |
| GET | /api/v1/templates/{id} | 模板详情 |
| POST | /api/v1/templates/{id}/deploy | 部署模板 |
| GET | /api/v1/templates/{id}/preview | 预览模板架构 |
| GET | /api/v1/templates/categories | 模板分类 |
| GET | /api/v1/templates/{id}/deployments | 列出部署记录 |

### 8.1 GET /api/v1/templates — 列出模板

**响应示例**

```json
[
  {"id": "fin-risk-scorecard", "name": "风控评分卡", "category": "finance", "version": "1.0.0"},
  {"id": "energy-template", "name": "能源行业模板", "category": "energy", "version": "1.0.0"},
  {"id": "government-template", "name": "政务行业模板", "category": "government", "version": "1.0.0"}
]
```

### 8.2 POST /api/v1/templates/{id}/deploy — 部署模板

**请求示例**

```bash
curl -X POST https://<platform-domain>/api/v1/templates/fin-risk-scorecard/deploy \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"tenantId": "tenant-finance", "releaseName": "finance-prod", "values": {"replicas": 3}}'
```

## 第9章 健康检查 API

各组件提供健康检查端点（无需认证）：

| 组件 | 端点 | 响应 |
|------|------|------|
| 封装层 | GET /api/v1/health | `{"status":"UP","component":"encaps-layer","version":"0.1.0"}` |
| SQL 网关 | GET /api/v1/health | `{"status":"UP","component":"sql-gateway","version":"0.1.0"}` |
| Catalog | GET /api/v1/health | `{"status":"UP","component":"catalog","version":"0.1.0"}` |
| 规则引擎 | GET /api/v1/health | `{"status":"UP","component":"rule-engine","version":"0.1.0"}` |
| 标签引擎 | GET /api/v1/health | `{"status":"UP","component":"tag-engine","version":"0.1.0"}` |
| 向量引擎 | GET /api/v1/health | `{"status":"UP","component":"vector-engine","version":"0.1.0"}` |
| 大模型网关 | GET /health | `{"status":"UP","component":"llm-gateway","version":"0.2.0"}` |
| 可观测查询 | GET /api/v1/health | `{"status":"UP","component":"query-api","version":"0.1.0"}` |
| 行业模板 | GET /api/v1/health | `{"status":"UP","component":"industry-templates","version":"0.1.0"}` |
| LLMOps | GET /health | `{"status":"UP"}` |
| 知识引擎 | GET /health | `{"status":"ok","store":"mock|nebula","extractor":"mock|llm","version":"0.1.0"}` |
| ML 平台 | GET /health | `{"status":"UP"}` |
| 业务门户 | GET /api/v1/health | `{"status":"UP"}` |
| NL2SQL | GET /api/v1/health | `{"status":"UP","component":"nl2sql","version":"0.1.0","llmMode":"mock","catalogUrl":"http://localhost:8082","sqlGatewayUrl":"http://localhost:8081"}` |
| AI 助手 | GET /api/v1/health | `{"status":"UP","service":"ai-assistant"}` |
| 开放 API 目录 | GET /api/v1/health | `{"status":"UP","store":"mock","version":"0.1.0","module":"open-api-catalog","layer":"L5.5"}` |
| 资产流通 | GET /api/v1/health | `{"status":"UP","store":"mock","version":"0.1.0"}` |
| Catalog Metrics | GET /metrics | Prometheus 格式 |

## 第10章 统一错误码定义

### 10.1 HTTP 状态码

| 状态码 | 含义 | 说明 |
|--------|------|------|
| 200 | OK | 请求成功 |
| 201 | Created | 资源创建成功 |
| 204 | No Content | 请求成功无返回体 |
| 400 | Bad Request | 请求参数错误 |
| 401 | Unauthorized | 未认证或 Token 失效 |
| 403 | Forbidden | 无权限 |
| 404 | Not Found | 资源不存在 |
| 409 | Conflict | 资源冲突（如已存在） |
| 429 | Too Many Requests | 请求限流 |
| 500 | Internal Server Error | 服务器内部错误 |
| 503 | Service Unavailable | 服务不可用 |

### 10.2 业务错误码

| 错误码 | HTTP | 说明 |
|--------|------|------|
| tenant_not_found | 404 | 租户不存在 |
| tenant_already_exists | 409 | 租户已存在 |
| rule_not_found | 404 | 规则不存在 |
| RULE_NOT_FOUND | 404 | 规则执行时未找到 |
| virtual_table_not_found | 404 | 虚拟表不存在 |
| PARSE_ERROR | 200* | 跨源 SQL 解析失败 |
| SOURCE_NOT_FOUND | 200* | 表无源映射 |
| QUERY_TIMEOUT | 200* | 单源查询超时 |
| QUERY_FAILED | 200* | 单源查询执行失败 |
| MERGE_ERROR | 200* | 内存归并（JOIN/UNION）失败 |
| RESULT_TOO_LARGE | 200* | 归并结果超行数上限 |
| UNSUPPORTED | 200* | 不支持的跨源 SQL 形态或操作符 |
| sql_parse_error | 400 | SQL 解析失败 |
| sql_validate_failed | 400 | SQL 校验失败 |
| query_timeout | 503 | 查询超时 |
| quota_exceeded | 429 | 资源配额超限 |
| QuotaExceeded | 422 | 配额超限（封装层 QuotaController） |
| Conflict | 409 | 重复设置（封装层 QuotaController） |
| task_not_found | 404 | 调度任务不存在 |
| task_not_cancellable | 404 | 任务不可取消（不存在或已终态） |
| invalid_dag | 422 | DAG 非法（如存在环） |
| bad_request | 400 | 通用参数错误 |
| service_unavailable | 503 | 能力禁用 |
| internal_error | 500 | 内部错误（IO 等） |
| INVALID_ROLE | 400 | 未知 Agent 角色 |
| ROLE_NOT_FOUND | 404 | Agent 角色未注册 |
| lineage_analysis_failed | 400 | 血缘分析失败 |
| INTERNAL_ERROR | 500 | 内部错误 |

> \* 跨源查询错误不走 HTTP 5xx：统一返回 200 + `status=FAILED` + `error` 字段（部分结果语义，见 4.11）。

### 10.3 错误响应示例

```json
{
  "error": "tenant_not_found",
  "message": "Tenant 9999 not found",
  "timestamp": "2026-08-08T10:00:00Z"
}
```

## 第11章 SDK 与客户端

### 11.1 Python SDK

```python
from shuqing_sdk import Client

client = Client(base_url="https://platform.shuqing.com", token="eyJ...")
# 创建租户
tenant = client.tenants.create(name="tenant-finance", displayName="金融租户")
# 执行 SQL
result = client.sql.execute(sql="SELECT * FROM transaction LIMIT 10", tenantId="tenant-finance")
# 注册虚拟表
vt = client.virtual_tables.register(tableName="v_orders", dataSourceType="MYSQL", connection={...})
```

### 11.2 dqctl CLI（Go 实现）

dqctl 当前提供以下子命令（基于 cobra）：

```bash
# 初始化配置
dqctl init

# 查询平台状态
dqctl status

# 执行 SQL 查询
dqctl query "SELECT * FROM transaction LIMIT 10"

# 应用配置文件
dqctl apply -f config.yaml

# 查看版本
dqctl version
```

## 第12章 限流与配额

- 默认限流：每租户 100 QPS，可通过 Quota API 调整
- SQL 查询并发：每租户最大 20 并发查询
- Token 有效期：access_token 30 分钟，refresh_token 24 小时
- 请求体大小：最大 10MB（SQL 执行最大 1MB）

## 第13章 治理中台 API

治理中台（governance）提供实时治理管道、元数据采集与血缘分析能力。

### 13.1 实时治理管道（GovernanceController）

前缀 `/api/v1/governance`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/governance/metadata/collect | 手动触发元数据采集 |
| GET | /api/v1/governance/metadata/{tableIdentifier} | 查询缓存的表元数据 |
| POST | /api/v1/governance/lineage/parse | 解析 Flink CDC SQL 并更新血缘图 |
| GET | /api/v1/governance/lineage/{targetTable} | 查询指定目标表的血缘 |
| GET | /api/v1/governance/lineage | 查询所有血缘 |
| POST | /api/v1/governance/quality/rules | 注册质量规则 |
| DELETE | /api/v1/governance/quality/rules/{ruleId} | 注销质量规则 |
| GET | /api/v1/governance/quality/rules | 查询所有质量规则 |
| POST | /api/v1/governance/quality/evaluate | 评估单条记录（同步模式） |
| GET | /api/v1/governance/alerts | 查询所有告警 |
| GET | /api/v1/governance/alerts/{tableIdentifier} | 查询指定表的告警（query 参数 limit，默认 100） |
| GET | /api/v1/governance/pipeline/metrics | 查询治理闭环指标（P95 延迟等） |
| GET | /api/v1/governance/pipeline/history | 查询治理闭环执行历史 |

### 13.2 元数据采集（CollectorController）

前缀 `/api/v1/metadata`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/metadata/sources | 添加数据源（若提供 cron 自动注册调度） |
| GET | /api/v1/metadata/sources | 列出全部数据源 |
| GET | /api/v1/metadata/sources/{id} | 获取单个数据源 |
| PUT | /api/v1/metadata/sources/{id} | 更新数据源（cron 变更时重新调度） |
| DELETE | /api/v1/metadata/sources/{id} | 删除数据源 |
| POST | /api/v1/metadata/collect/{sourceId} | 手动触发采集 |
| GET | /api/v1/metadata/collect/status/{sourceId} | 查询采集状态 |
| POST | /api/v1/metadata/collect/test/{sourceId} | 测试数据源连接 |
| POST | /api/v1/metadata/collect/schedule/{sourceId} | 注册定时采集（cron） |
| DELETE | /api/v1/metadata/collect/schedule/{sourceId} | 取消定时采集 |
| GET | /api/v1/metadata/collectors | 列出已注册的 Collector 类型 |

### 13.3 血缘分析（LineageController）

前缀 `/api/v1/lineage`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/lineage/analyze | 分析 SQL 血缘（返回 ECharts 友好格式） |
| GET | /api/v1/lineage/upstream/{table} | 查询上游依赖表（query 参数 depth，默认 5） |
| GET | /api/v1/lineage/downstream/{table} | 查询下游依赖表 |
| GET | /api/v1/lineage/impact/{table} | 影响分析 |

## 第14章 标签引擎 API

标签引擎（tag-engine）提供标签定义、计算、用户画像与人群圈选能力。

### 14.1 标签管理（TagController）

前缀 `/api/v1/tags`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/tags | 创建标签定义（返回 201） |
| GET | /api/v1/tags?tenantId=xxx | 列出指定租户的标签 |
| GET | /api/v1/tags/{id} | 标签详情 |
| DELETE | /api/v1/tags/{id} | 删除标签 |
| POST | /api/v1/tags/{id}/rules | 添加标签规则 |
| GET | /api/v1/tags/{id}/rules | 标签规则列表 |
| POST | /api/v1/tags/{id}/compute | 计算单个标签 |
| POST | /api/v1/tags/batch-compute | 批量计算标签 |

### 14.2 用户画像（ProfileController）

前缀 `/api/v1/profiles`。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/profiles/{userId} | 获取单用户画像 |
| POST | /api/v1/profiles/query | 按标签条件查询用户列表 |
| POST | /api/v1/profiles/count | 按标签条件统计人数 |

### 14.3 人群圈选（AudienceController）

前缀 `/api/v1/audiences`。

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/audiences/select | 人群圈选（返回 count 与可选 user_id 列表） |

## 第15章 向量引擎 API

向量引擎（vector-engine，Go 实现）提供向量集合管理、向量 CRUD、ANN 近似检索与混合检索能力，前缀 `/api/v1`。

### 15.1 集合级 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/collections | 创建向量集合 |
| DELETE | /api/v1/collections/{name} | 删除向量集合 |
| POST | /api/v1/collections/{name}/vectors | 插入向量 |
| DELETE | /api/v1/collections/{name}/vectors | 删除向量 |
| POST | /api/v1/collections/{name}/search | 向量检索（ANN） |
| POST | /api/v1/collections/{name}/hybrid-search | 混合检索（向量 + 标量） |
| GET | /api/v1/collections/{name}/stats | 集合统计 |

### 15.2 历史契约端点（legacy 别名）

为兼容前端 `frontend/src/api/vector.ts` 的历史契约保留的别名端点，与集合级 API 同样要求 Bearer JWT：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/vector | 列出全部集合 |
| POST | /api/v1/vector/search | 全局检索：body `{"query": "文本", "topK": 5}`（topK 缺省 5），遍历全部集合并取 topK 条 |

**响应示例（POST /api/v1/vector/search，200 OK）**

```json
[{"id": "vec-1", "score": 0.87, "payload": {}, "collection": "docs"}]
```

> ⚠️ 当前全局检索的查询向量由文本确定性哈希占位生成（不具备语义检索能力），生产环境应通过 embedding 服务向量化。新集成建议优先使用 15.1 的集合级检索端点。

## 第16章 大模型网关 API

大模型网关（llm-gateway，Go 实现）提供统一 API 入口、多模型路由、限流、计费、审计，OpenAI 兼容协议。

### 16.1 网关管理 API（前缀 `/api/v1`）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/chat/completions | 对话补全（OpenAI 兼容） |
| POST | /api/v1/embeddings | 向量嵌入 |
| GET | /api/v1/models | 列出可用模型 |
| GET | /api/v1/providers | 列出 Provider |
| POST | /api/v1/providers | 注册 Provider |
| DELETE | /api/v1/providers/{name} | 注销 Provider |
| GET | /api/v1/metrics/tokens | Token 用量指标 |
| GET | /api/v1/metrics/latency | 延迟指标 |

### 16.2 多模态 OpenAI 兼容 API（前缀 `/v1`）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /v1/chat/completions | 多模态对话补全（支持文本/图像/语音/视频 + SSE 流式） |
| POST | /v1/batch/jobs | 提交异步批处理任务 |
| GET | /v1/batch/jobs | 列出批处理任务 |
| GET | /v1/batch/jobs/{id} | 查询批处理任务详情 |
| GET | /v1/routing/rules | 列出路由规则 |
| POST | /v1/routing/rules | 添加路由规则 |
| GET | /v1/routing/decision | 查询路由决策 |
| POST | /v1/token/estimate | 估算 Token 数 |

## 第17章 可观测查询 API

可观测查询 API（query-api，Go 实现）封装 Prometheus 查询，按租户隔离，提供平台方与客户方双视图。

### 17.1 平台方视图（前缀 `/platform`，要求 platform-ops 角色）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /platform/api/v1/query | 即时查询 |
| GET | /platform/api/v1/query_range | 范围查询 |
| GET | /platform/api/v1/labels | 标签名列表 |
| GET | /platform/api/v1/label/{name}/values | 标签值列表 |
| GET | /platform/api/v1/series | 序列列表 |

### 17.2 客户方视图（前缀 `/tenant`，强制注入 tenant_id 过滤）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /tenant/api/v1/query | 即时查询（仅本租户指标） |
| GET | /tenant/api/v1/query_range | 范围查询 |
| GET | /tenant/api/v1/labels | 标签名列表 |
| GET | /tenant/api/v1/label/{name}/values | 标签值列表 |
| GET | /tenant/api/v1/series | 序列列表 |

## 第18章 LLMOps API

LLMOps（Python/FastAPI 实现）提供大模型注册、训练、部署与监控能力，前缀 `/api/v1`。

### 18.1 模型管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/models | 注册模型（基座或微调，返回 201） |
| GET | /api/v1/models | 列出模型（支持 name/type/status/tag 过滤） |
| GET | /api/v1/models/{model_id} | 获取模型详情 |
| DELETE | /api/v1/models/{model_id} | 删除模型（已部署不允许删除） |
| GET | /api/v1/models/{model_id}/versions | 模型版本列表 |
| PUT | /api/v1/models/{model_id}/production-version | 设置生产版本 |

### 18.2 训练任务

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/training/jobs | 创建训练/微调任务（返回 201） |
| GET | /api/v1/training/jobs | 列出训练任务 |
| GET | /api/v1/training/jobs/{job_id} | 训练状态（含进度） |
| DELETE | /api/v1/training/jobs/{job_id} | 取消训练 |
| GET | /api/v1/training/jobs/{job_id}/eval | 评估训练产出模型（返回准确率/幻觉率/提升） |

### 18.3 部署管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/deployments | 部署模型到推理端点（返回 201） |
| GET | /api/v1/deployments | 列出部署 |
| GET | /api/v1/deployments/{deployment_id} | 部署状态（含端点 URL） |
| DELETE | /api/v1/deployments/{deployment_id} | 卸载部署 |

### 18.4 监控

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/deployments/{deployment_id}/metrics | 模型综合指标（准确率/幻觉率/提升/QPS/错误率） |
| GET | /api/v1/deployments/{deployment_id}/latency | 延迟统计（avg/P50/P95/P99/min/max） |
| GET | /api/v1/deployments/{deployment_id}/throughput | 吞吐量统计（rps/tps/totalRequests/totalTokens） |
| GET | /api/v1/deployments/{deployment_id}/error-rate | 错误率统计 |

## 第19章 知识图谱 API

知识引擎（knowledge-engine，Python/FastAPI 实现）提供知识空间、实体/关系管理、抽取、构建与图查询能力，前缀 `/api/v1`。

### 19.1 知识空间管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/spaces | 创建知识空间（返回 201） |
| GET | /api/v1/spaces | 列出知识空间 |
| DELETE | /api/v1/spaces/{name} | 删除知识空间 |

### 19.2 实体与关系

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/spaces/{name}/entities | 插入实体（跳过抽取） |
| POST | /api/v1/spaces/{name}/edges | 插入关系（跳过抽取） |
| POST | /api/v1/spaces/{name}/extract | 从文本抽取知识（不写入图存储） |
| POST | /api/v1/spaces/{name}/build | 构建知识图谱（抽取 + 写入） |

### 19.3 图查询

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/spaces/{name}/vertices/{vid} | 查询顶点 |
| GET | /api/v1/spaces/{name}/vertices/{vid}/neighbors | 查询邻居（可选 edgeType 过滤） |
| POST | /api/v1/spaces/{name}/query | 原生图查询（nGQL/GQL） |
| POST | /api/v1/spaces/{name}/shortest-path | 最短路径查询（BFS） |

## 第20章 ML 平台 API

ML 平台（ml-platform，Python/FastAPI 实现）提供实验管理、训练任务、模型管理与特征工程能力，前缀 `/api/v1`。

### 20.1 实验管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/experiments | 创建实验（返回 201） |
| GET | /api/v1/experiments | 列出实验 |
| GET | /api/v1/experiments/{experimentId} | 实验详情 |
| DELETE | /api/v1/experiments/{experimentId} | 删除实验 |
| POST | /api/v1/experiments/{experimentId}/metrics | 记录指标 |
| POST | /api/v1/experiments/{experimentId}/params | 记录参数 |

### 20.2 训练任务

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/training/jobs | 创建训练任务（返回 201） |
| GET | /api/v1/training/jobs | 列出训练任务 |
| GET | /api/v1/training/jobs/{jobId} | 训练状态 |
| DELETE | /api/v1/training/jobs/{jobId} | 取消训练任务 |

### 20.3 模型管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/models | 列出模型 |
| GET | /api/v1/models/{modelId} | 模型详情 |
| DELETE | /api/v1/models/{modelId} | 删除模型 |
| POST | /api/v1/models/{modelId}/predict | 模型预测 |
| POST | /api/v1/models/{modelId}/evaluate | 模型评估 |

### 20.4 特征工程

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/feature-groups | 创建特征组（返回 201） |
| GET | /api/v1/feature-groups | 列出特征组 |
| GET | /api/v1/feature-groups/{groupName} | 特征组详情 |
| GET | /api/v1/feature-groups/{groupName}/features/{entityId} | 获取特征 |
| PUT | /api/v1/feature-groups/{groupName}/features/{entityId} | 写入特征 |
| DELETE | /api/v1/feature-groups/{groupName}/features/{entityId} | 删除特征 |

## 第21章 业务门户 API

业务门户（business-portal，Python/FastAPI 实现）提供业务线、仪表盘、工作台、数据目录与 BI 报表能力，前缀 `/api/v1`。

### 21.1 业务线管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/business-lines | 创建业务线（返回 201） |
| GET | /api/v1/business-lines | 列出业务线（支持 tenantId/status/name/memberId 过滤） |
| GET | /api/v1/business-lines/{bl_id} | 业务线详情（带权限校验） |
| PUT | /api/v1/business-lines/{bl_id} | 更新业务线（仅管理员） |
| DELETE | /api/v1/business-lines/{bl_id} | 删除业务线（仅管理员） |

### 21.2 仪表盘与工作台

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/business-lines/{bl_id}/dashboard | 业务线数据概览（KPI + 趋势 + 实时监控 + TopN） |
| GET | /api/v1/business-lines/{bl_id}/workbench | 业务线工作台（待办 + 常用工具 + 最近任务） |

### 21.3 数据目录

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/business-lines/{bl_id}/catalog | 获取数据目录树 |
| POST | /api/v1/business-lines/{bl_id}/catalog | 添加目录节点（返回 201） |
| DELETE | /api/v1/business-lines/{bl_id}/catalog/{node_id} | 删除目录节点（递归删除子节点） |

### 21.4 BI 报表

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/business-lines/{bl_id}/reports | BI 报表列表 |
| POST | /api/v1/business-lines/{bl_id}/reports | 创建 BI 报表（返回 201） |
| GET | /api/v1/business-lines/{bl_id}/reports/{report_id} | 报表详情 |
| PUT | /api/v1/business-lines/{bl_id}/reports/{report_id} | 更新报表 |
| DELETE | /api/v1/business-lines/{bl_id}/reports/{report_id} | 删除报表 |

## 第22章 开放 API 目录

开放 API 目录（open-api-catalog，Python/FastAPI 实现）提供 API 注册、审核、发布、订阅与调用能力，前缀 `/api/v1`。

### 22.1 API 注册与管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/apis | 注册 API（返回 201） |
| GET | /api/v1/apis | 列出 API（支持 name/category/tag/status/keyword 过滤） |
| GET | /api/v1/apis/{api_id} | 获取 API 详情 |
| PUT | /api/v1/apis/{api_id} | 更新 API |
| DELETE | /api/v1/apis/{api_id} | 注销 API（仅 DRAFT/REJECTED/ARCHIVED 状态） |

### 22.2 状态转换

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/apis/{api_id}/submit-review | 提交安全审核 |
| POST | /api/v1/apis/{api_id}/approve | 审核通过 |
| POST | /api/v1/apis/{api_id}/reject | 审核驳回 |
| POST | /api/v1/apis/{api_id}/publish | 发布 API 到网关 |
| POST | /api/v1/apis/{api_id}/deprecate | 废弃 API（进入宽限期） |
| POST | /api/v1/apis/{api_id}/archive | 归档下线 API |

### 22.3 订阅管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/apis/{api_id}/subscribe | 申请订阅（返回 201，含用途与配额期望；审批通过后经 Keycloak 发放 AK/SK） |
| GET | /api/v1/apis/{api_id}/subscribers | 某 API 的订阅者列表（可选 status 过滤） |
| GET | /api/v1/subscriptions | 列出订阅（apiId / subscriberId / subscriberTenantId / status 过滤，limit ≤ 1000） |
| GET | /api/v1/subscriptions/{subscription_id} | 订阅详情 |
| POST | /api/v1/subscriptions/{subscription_id}/approve | 审批订阅（通过则发放 AK/SK 并配置订阅级限流） |
| POST | /api/v1/subscriptions/{subscription_id}/suspend | 暂停订阅 |
| POST | /api/v1/subscriptions/{subscription_id}/resume | 恢复订阅 |
| POST | /api/v1/subscriptions/{subscription_id}/revoke | 吊销订阅（清空 AK/SK） |

### 22.4 API 调用（AK/SK）

`POST /api/v1/apis/{api_id}/call` — 经网关调用已发布 API（鉴权 → 限流 → 计量 → 转发）。

**请求头（二选一组合）**

| 组合 | 请求头 |
|------|--------|
| AK/SK 成对认证 | `X-API-Key` + `X-API-Secret` |
| Bearer 形式 | `Authorization: Bearer <access_key>` + `X-API-Secret` |

**请求体**

```json
{"payload": {}, "headers": {}}
```

**响应**：CallResult（callId / statusCode / latencyMs / result 或 error）。凭证缺失时不返回 HTTP 401，而是返回 HTTP 200 且 CallResult 内 `statusCode=401`、`error` 字段说明缺失项。

## 第23章 资产流通 API

资产流通（asset-exchange，Python/FastAPI 实现）提供数据资产、订阅与审计日志能力，前缀 `/api/v1`。

### 23.1 资产管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/assets | 创建资产 |
| GET | /api/v1/assets | 列出资产 |
| GET | /api/v1/assets/{asset_id} | 资产详情 |
| PUT | /api/v1/assets/{asset_id} | 更新资产 |
| DELETE | /api/v1/assets/{asset_id} | 删除资产 |

### 23.2 订阅管理

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/v1/subscriptions | 创建订阅 |
| GET | /api/v1/subscriptions | 列出订阅 |

### 23.3 审计日志

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | /api/v1/audit-logs | 查询审计日志 |

## 第24章 NL2SQL API

NL2SQL 引擎（nl2sql，Python/FastAPI 实现，端口 8093）将自然语言转换为 SQL，对接 Catalog 元数据与 SQL 网关，支持意图识别、Schema 上下文构建、语法校验与多轮澄清，前缀 `/api/v1`。交互式文档见 `/docs`（Swagger UI）、`/redoc`（ReDoc）与 `/openapi.json`。

> 本章内容依据 `platform/nl2sql/app.py` 逐端点核实补录（V2.2 前本文档缺失该服务整章）。

### 24.1 鉴权

业务端点通过镜像 `jwt_auth` 模块（`getAuthContext` 依赖）校验：

- `AUTH_MODE=jwt`（生产）：要求 `Authorization: Bearer <HS256 token>`；缺失、格式非法、签名不符或过期返回 **401**；可配 `JWT_EXPECTED_ISSUER` 校验 iss；
- `AUTH_MODE=none`（缺省，本地/测试）：**匿名放行**，进程生命周期内告警一次；此时身份视为 `role=admin`、`tenantId=""`——即任何调用者都按管理员对待，可经请求体指定任意租户执行查询。

租户裁决（execute）：admin 可通过 body 的 `tenantId` 指定目标租户；普通用户强制使用 token 声明的租户（`effectiveTenant`），防止越权触发跨租户查询。

### 24.2 端点总览

| 方法 | 路径 | 鉴权 | 说明 |
|------|------|------|------|
| GET | /api/v1/health | 匿名 | 健康检查 |
| POST | /api/v1/nl2sql/generate | Bearer / AUTH_MODE=none 匿名 | 单轮 NL → SQL（不执行） |
| POST | /api/v1/nl2sql/execute | 同上 | NL → SQL → 经 SQL 网关执行 |
| POST | /api/v1/nl2sql/dialogue/start | 同上 | 开启多轮澄清对话 |
| POST | /api/v1/nl2sql/dialogue/answer | 同上 | 提交澄清回答 |
| POST | /api/v1/nl2sql/validate | 同上 | 校验 SQL 语法 |
| GET | /api/v1/nl2sql/schema | 匿名 | 获取 schema 上下文（调试用，暂未挂鉴权依赖，待迁移） |

### 24.3 NL → SQL 生成与执行

#### POST /api/v1/nl2sql/generate

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| query | string | 是 | 自然语言查询 |
| database | string | 否 | 目标数据库 |
| tableHints | array[string] | 否 | 表名提示 |
| useMockSchema | boolean | 否 | 强制使用 Mock schema |
| tenantId | string | 否 | 租户 ID（仅 admin 生效） |

**响应**：SqlGenerationResult（sql / intent / validation / needsClarification / clarificationQuestions / elapsedMs 等）。必需槽位缺失时置 `needsClarification=true` 并给出澄清问题列表。

#### POST /api/v1/nl2sql/execute

在 generate 参数基础上增加：

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| engine | string | 否 | trino / doris |
| limit | integer | 否 | 行数上限（透传网关） |

生成结果校验失败时直接返回（gateway=null 且 validation.valid=false）；否则调用 SQL 网关执行。

**响应示例（200 OK）**

```json
{
  "sql": "SELECT customer_id, COUNT(*) FROM transaction GROUP BY customer_id",
  "intent": {"type": "AGGREGATE"},
  "validation": {"valid": true},
  "gateway": {"queryId": "q-xxx", "status": "SUCCESS", "columns": [], "rows": []},
  "elapsedMs": 356.4
}
```

### 24.4 多轮澄清对话

| 端点 | 请求体 | 说明 |
|------|--------|------|
| POST /api/v1/nl2sql/dialogue/start | `{"query":"...","database":null,"tableHints":[],"useMockSchema":false}` | 开启对话，返回 sessionId / clarified / nextQuestion / sql / intent / slots / turnCount；无需澄清时直接给出 sql |
| POST /api/v1/nl2sql/dialogue/answer | `{"sessionId":"...","answer":"...","useMockSchema":false}` | 提交回答推进澄清；会话不存在返回 **404** |

会话保存在服务内存中（生产可换 Redis），服务重启即失效。

### 24.5 校验与 Schema 调试

- `POST /api/v1/nl2sql/validate`：body `{"sql":"...","database":null,"useMockSchema":false}`，返回 ValidationResult（valid 与错误明细）。
- `GET /api/v1/nl2sql/schema?database=&useMock=`：返回 `{"database":..., "tables":[...]}`（调试用）。

## 附录：OpenAPI 规范

各服务运行时文档端点的实际支持情况（V2.2 逐仓核实，替代早期版本的大面积失实声明）：

**Java 栈**

| 服务 | 运行时文档端点 | 说明 |
|------|----------------|------|
| 封装层（encaps-layer） | `GET /v3/api-docs`、`GET /v3/api-docs/{group}`、`/swagger-ui.html` | springdoc-openapi-starter-webmvc-ui 自动生成 |
| SQL 网关 / 规则引擎 / 治理中台（governance）/ 标签引擎 | **无** | 仅代码内 swagger-annotations 注解，未引入 springdoc 依赖，`/v3/api-docs` 不可用 |

**Go 栈**

| 服务 | 运行时文档端点 | 说明 |
|------|----------------|------|
| Catalog / 向量引擎 / 大模型网关 / 可观测查询（query-api） | **无** | 未集成任何 OpenAPI/swagger 库；历史版本声称的 `/openapi.json` 均不存在 |

**Python/FastAPI 栈** — 以下服务均由 FastAPI 自动生成 `/docs`（Swagger UI）、`/redoc`（ReDoc）与 `/openapi.json`：

- 行业模板（industry-templates）
- LLMOps（llmops）
- 知识引擎（knowledge-engine）
- ML 平台（ml-platform）
- 业务门户（business-portal）
- 开放 API 目录（open-api-catalog）
- 资产流通（asset-exchange）
- NL2SQL（nl2sql）

> 新增 Java 服务如需运行时文档应引入 springdoc 并更新本附录；Go 服务暂无统一 OpenAPI 方案（待迁移）。

---

## 变更记录

### V2.2（2026-08-25）

本次为文档-代码一致性勘误（每条修订均已读对应源码核实），不涉及服务行为变更：

1. 【1.1/1.2】本地端口补 NL2SQL 8093；通用响应格式改为如实描述"包裹型 / 资源直出"两类封装。
2. 【新增 2.4】Phase A 安全加固后各服务鉴权矩阵（catalog、vector-engine、query-api、knowledge-engine、asset-exchange、open-api-catalog、nl2sql、ai-assistant、infra-provider-baremetal）。
3. 【3.1.2】补录 `GET /api/v1/tenants/all`；【3.1.3】租户详情 404 实况修正为空响应体（原 error JSON 示例失实）。
4. 【4.11/4.12】跨源查询/执行计划失败实况修正：HTTP 200 + `status=FAILED` + `error`（部分结果语义）；【10.2】删除 cross_source_error/CROSS_SOURCE_UNSUPPORTED→500 失实行，改为 7 个实际错误码（200*）。
5. 【新增 5.3】补录 Catalog 表格全文检索 `GET /api/v1/catalog/search/tables`。
6. 【6.6.1】补录批量执行 `POST /api/v1/rules/execute/batch`；【新增 6.11】QualityRuleController 数据质量规则端点整节。
7. 【第9章】开放 API 目录健康响应字段补全；资产流通健康路径 `/health` → `/api/v1/health`；知识引擎响应字段补全；新增 NL2SQL、AI 助手行。
8. 【15.1/15.2】向量引擎章节结构化，并补录前端契约端点 `GET /api/v1/vector`、`POST /api/v1/vector/search`（标注 legacy 别名与哈希占位实现）。
9. 【22.3/22.4】开放 API 目录补录订阅管理端点与 AK/SK 调用端点 `POST /api/v1/apis/{api_id}/call`。
10. 【新增第24章】NL2SQL API 整章补录（含 AUTH_MODE 鉴权语义、admin 租户裁决、多轮澄清对话）。
11. 【附录】OpenAPI 自描述声明按实况重写：仅封装层有 springdoc `/v3/api-docs`；SQL 网关/规则引擎/治理中台/标签引擎仅有注解；Go 服务无任何 OpenAPI 端点；8 个 FastAPI 服务有 `/docs`。