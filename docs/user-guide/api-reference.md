# 数擎大数据平台 API 参考文档

> 版本：V2.0 | API 前缀：`/api/v1` | 认证方式：JWT Bearer Token | 更新日期：2026-08-08

## 第1章 概述

数擎大数据平台提供统一的 RESTful API，覆盖封装层、SQL 网关、Catalog、规则引擎、数据虚拟化五大自研组件。所有 API 使用 JSON 作为请求与响应格式，统一前缀 `/api/v1`，统一错误码体系。

### 1.1 基础 URL

| 环境 | 基础 URL |
|------|----------|
| 生产 | `https://<platform-domain>/api/v1` |
| 预发 | `https://<platform-domain>-staging/api/v1` |
| 本地开发 | `http://localhost:8080/api/v1`（封装层）/ `8081`（SQL 网关）/ `8082`（Catalog）/ `8083`（规则引擎） |

### 1.2 通用响应格式

成功响应：

```json
{
  "data": { },
  "message": "success"
}
```

错误响应：

```json
{
  "error": "error_code",
  "message": "human readable message",
  "timestamp": "2026-08-08T10:00:00Z"
}
```

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

```json
{"error": "tenant_not_found", "message": "Tenant 9999 not found"}
```

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

#### 3.2.1 PUT /api/v1/quotas/{namespace} — 更新租户配额

**请求示例**

```bash
curl -X PUT https://<platform-domain>/api/v1/quotas/ns-finance \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"cpu":"80","memory":"160Gi","storage":"2Ti","pvcCount":"30"}'
```

### 3.3 工作空间管理

#### 3.3.1 POST /api/v1/workspaces — 创建工作空间

```bash
curl -X POST https://<platform-domain>/api/v1/workspaces \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{"name":"ws-risk","tenantId":1001,"description":"风控工作空间"}'
```

## 第4章 SQL 网关 API

SQL 网关（sql-gateway）提供统一 SQL 执行、路由管理、解析、优化、跨源查询能力，统一前缀 `/api/v1/sql`。

### 4.1 POST /api/v1/sql/execute — 执行 SQL

**请求参数**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| sql | string | 是 | SQL 语句 |
| tenantId | string | 是 | 租户 ID |
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
    "dialect": "trino"
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
  "durationMs": 120
}
```

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

### 6.7 GET /api/v1/rules/types — 规则类型列表

**响应**：`["DQ", "MASK", "ALERT"]`

- `DQ`：数据质量规则
- `MASK`：脱敏规则（支持掩码/哈希/仅授权/假名四种脱敏函数）
- `ALERT`：告警规则

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
| 封装层 | GET /api/v1/health | `{"status":"UP","version":"2.0.0"}` |
| SQL 网关 | GET /api/v1/health | `{"status":"UP","version":"2.0.0"}` |
| Catalog | GET /api/v1/health | `{"status":"UP","version":"0.1.0"}` |
| 规则引擎 | GET /api/v1/health | `{"status":"UP","version":"2.0.0"}` |
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
| cross_source_error | 500 | 跨源查询失败 |
| CROSS_SOURCE_UNSUPPORTED | 500 | 不支持的跨源操作 |
| sql_parse_error | 400 | SQL 解析失败 |
| sql_validate_failed | 400 | SQL 校验失败 |
| query_timeout | 503 | 查询超时 |
| quota_exceeded | 429 | 资源配额超限 |
| INTERNAL_ERROR | 500 | 内部错误 |

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

```bash
# 配置认证
dqctl config set-server https://platform.shuqing.com
dqctl config set-token ${TOKEN}

# 租户管理
dqctl tenant create --name tenant-finance --display-name "金融租户"
dqctl tenant list
dqctl tenant get 1001

# SQL 执行
dqctl sql execute --sql "SELECT * FROM transaction LIMIT 10" --tenant tenant-finance

# 虚拟表
dqctl virtual-table list
dqctl virtual-table register --file vt-def.yaml
```

## 第12章 限流与配额

- 默认限流：每租户 100 QPS，可通过 Quota API 调整
- SQL 查询并发：每租户最大 20 并发查询
- Token 有效期：access_token 30 分钟，refresh_token 24 小时
- 请求体大小：最大 10MB（SQL 执行最大 1MB）

## 附录：OpenAPI 规范

完整 OpenAPI 3.0 规范可在运行时通过以下端点获取：

- 封装层：`GET /v3/api-docs`（SpringDoc 自动生成）
- SQL 网关：`GET /v3/api-docs`
- 规则引擎：`GET /v3/api-docs`
- Catalog：`GET /openapi.json`（Gin-swagger 生成）
- Swagger UI：`/swagger-ui.html`