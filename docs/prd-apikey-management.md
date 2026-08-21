# API Key 管理 PRD

> 文档版本：v1.0
> 创建日期：2026-08-13
> 状态：Draft
> 负责人：Coding Engineer
> 关联项目：MAOP（Multi Agents Orchestration Platform）

---

## 第1章 功能概述

### 1.1 功能目标

为 MAOP（Multi Agents Orchestration Platform）提供对外 API Key 管理能力，支持第三方系统通过 API Key 方式安全接入 MAOP，实现机器到机器（M2M）的受控调用。

核心交付能力：

1. API Key 的生成与吊销全生命周期管理
2. 细粒度权限范围（Scope）控制，限定可访问的 API 资源
3. 速率限制（Rate Limit），防止滥用与暴力枚举
4. 使用统计与趋势分析，提供可观测性
5. IP 白名单，限制调用来源

### 1.2 背景

MAOP 当前仅支持 JWT（JSON Web Token）认证方式，适用于前端用户登录态场景。但以下场景缺少合适的认证方式：

- 第三方系统（如 CI/CD 平台、监控系统、外部编排工具）需要长期调用 MAOP API
- 脚本化任务需要非交互式认证
- 跨组织的数据共享与集成

JWT 的痛点：

1. Token 有较短过期时间，不适合长期集成
2. 绑定用户会话，机器调用需要模拟登录
3. 无法针对单个集成方做细粒度权限与速率控制

引入 API Key 认证可补齐上述缺口，与 JWT 认证并存。

### 1.3 范围

#### 1.3.1 In Scope

- API Key 生成（一次性展示完整 Key）
- API Key 列表查询（Key 脱敏）
- API Key 详情查看
- API Key 更新（权限范围、速率限制、IP 白名单、有效期）
- API Key 吊销（软删除，立即失效）
- 权限范围（Scope）管理
- 速率限制（滑动窗口算法）
- IP 白名单校验
- 使用统计记录与查询
- 使用趋势分析
- API Key 认证中间件（与 JWT 并存，优先 API Key）

#### 1.3.2 Out of Scope

- API Key 的自动轮换策略（v2 规划）
- API Key 的分级签名（HMAC 签名请求体，v2 规划）
- 第三方开发者门户（自助申请 Key，v2 规划）
- API Key 调用审计日志的长期归档（依赖现有审计模块）

### 1.4 术语定义

表：术语对照表

| 术语 | 英文 | 含义 |
| --- | --- | --- |
| API Key | API Key | 用于认证的长期凭据，格式 `maop_{key_id}_{secret}` |
| Scope | Scope | 权限范围，限定 Key 可访问的 API 资源集合 |
| Rate Limit | Rate Limit | 速率限制，单位时间内的最大请求数 |
| Sliding Window | Sliding Window | 滑动窗口算法，用于速率限制计数 |
| Key Hash | Key Hash | Key 的 SHA-256 哈希值，数据库仅存储此值 |
| IP 白名单 | IP Whitelist | 允许调用 Key 的来源 IP 列表 |

---

## 第2章 用户故事

### 2.1 管理员视角

#### 2.1.1 生成 API Key

> 作为管理员，我想生成 API Key，以便外部系统接入 MAOP。

验收要点：

- 生成时必须填写名称（用于辨识）
- 可选填写权限范围、速率限制、IP 白名单、有效期
- 生成成功后完整 Key 仅展示一次，提示用户立即复制保存
- 数据库仅存储 Key 的 SHA-256 哈希

#### 2.1.2 设置权限范围

> 作为管理员，我想设置 API Key 权限范围，以便限制可访问的 API。

验收要点：

- 权限范围为预定义 Scope 的多选集合
- 支持在生成时设置和后续编辑时修改
- 调用时若请求的 API 不在 Scope 范围内，返回 403

#### 2.1.3 设置速率限制

> 作为管理员，我想设置速率限制，以便防止滥用。

验收要点：

- 支持按分钟、小时、天三个维度设置上限
- 采用滑动窗口算法，计数精确
- 超限返回 429，响应头携带 `Retry-After`

#### 2.1.4 查看使用统计

> 作为管理员，我想查看 API Key 使用统计，以便了解调用情况。

验收要点：

- 支持查看调用量趋势（时间线）
- 支持查看状态码分布
- 支持查看热点端点排行
- 支持查看平均响应时间趋势
- 支持查看最近调用记录

#### 2.1.5 设置 IP 白名单

> 作为管理员，我想设置 IP 白名单，以便限制调用来源。

验收要点：

- 支持 IPv4 / IPv4 CIDR
- 白名单为空时表示不限制
- 来源 IP 不在白名单时返回 403

#### 2.1.6 吊销 API Key

> 作为管理员，我想吊销 API Key，以便立即停止某系统的访问。

验收要点：

- 吊销为软删除（设置 `revoked_at`），保留历史记录
- 吊销后立即失效，Redis 缓存同步清除
- 吊销后使用统计仍可查询

### 2.2 开发者视角

#### 2.2.1 使用 API Key 调用

> 作为开发者，我想用 API Key 调用 MAOP API，以便集成到我的系统。

验收要点：

- 支持两种传递方式：`Authorization: Bearer maop_xxx` 或 `X-API-Key: maop_xxx`
- API Key 认证与 JWT 认证并存，优先校验 API Key
- Key 无效、已吊销、过期返回 401
- Key 权限不足返回 403

---

## 第3章 数据模型设计

### 3.1 api_keys 表

存储 API Key 的元信息与哈希，不存储明文 Key。

表：api_keys 字段说明表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO INCREMENT | 主键 |
| key_id | VARCHAR(16) | UNIQUE, NOT NULL | Key 前缀中的标识段，8 字节 URL-safe base64 |
| key_hash | VARCHAR(64) | NOT NULL | Key 完整字符串的 SHA-256 哈希（hex） |
| name | VARCHAR(128) | NOT NULL | Key 名称，用于辨识 |
| tenant_id | BIGINT | NOT NULL, INDEX | 租户 ID |
| user_id | BIGINT | NOT NULL | 创建者用户 ID |
| scopes | JSON | NOT NULL DEFAULT '[]' | 权限范围数组 |
| rate_limit | JSON | NULL | 速率限制配置 |
| ip_whitelist | JSON | NULL | IP 白名单数组 |
| expires_at | TIMESTAMP | NULL | 过期时间，NULL 表示永不过期 |
| last_used_at | TIMESTAMP | NULL | 最后使用时间 |
| created_at | TIMESTAMP | NOT NULL DEFAULT NOW() | 创建时间 |
| revoked_at | TIMESTAMP | NULL | 吊销时间，NULL 表示未吊销 |

索引设计：

- `UNIQUE INDEX idx_api_keys_key_id` ON `key_id`
- `INDEX idx_api_keys_tenant_id` ON `tenant_id`
- `INDEX idx_api_keys_key_hash` ON `key_hash`
- `INDEX idx_api_keys_status` ON `revoked_at` WHERE `revoked_at IS NULL`

### 3.2 api_key_usage 表

存储每次 API Key 调用的记录，用于使用统计。

表：api_key_usage 字段说明表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO INCREMENT | 主键 |
| key_id | VARCHAR(16) | NOT NULL, INDEX | 关联 api_keys.key_id |
| endpoint | VARCHAR(256) | NOT NULL | 请求路径 |
| method | VARCHAR(8) | NOT NULL | HTTP 方法 |
| status_code | SMALLINT | NOT NULL | 响应状态码 |
| response_time | INT | NOT NULL | 响应耗时（ms） |
| client_ip | VARCHAR(45) | NULL | 调用方 IP |
| timestamp | TIMESTAMP | NOT NULL DEFAULT NOW() | 调用时间 |

索引设计：

- `INDEX idx_usage_key_id_ts` ON `key_id, timestamp`
- `INDEX idx_usage_ts` ON `timestamp`

数据治理：

- 统计数据保留 90 天，超期按天分区清理
- 高写入场景可异步批量落库（先写 Redis Stream，再批量消费）

### 3.3 Key 格式设计

#### 3.3.1 格式定义

```
maop_{key_id}_{secret}
```

- 前缀 `maop_`：固定标识，便于辨识来源
- `key_id`：8 字节随机数，URL-safe base64 编码，约 11 字符，用于数据库索引与展示
- `secret`：32 字节随机数，URL-safe base64 编码，约 43 字符，仅生成时返回一次

#### 3.3.2 生成与校验流程

图：API Key 生成与校验流程图

```
[生成]
secrets.token_urlsafe(8)  -> key_id
secrets.token_urlsafe(32) -> secret
full_key = f"maop_{key_id}_{secret}"
key_hash = sha256(full_key).hexdigest()
DB.save(key_id, key_hash, ...)
Redis.cache(key_id -> meta)
return full_key  # 仅此一次

[校验]
full_key = extract_from_header(key_id, secret)
key_hash = sha256(full_key).hexdigest()
meta = Redis.get(key_id) or DB.get(key_id)
if meta.key_hash != key_hash: 401
if meta.revoked_at or expired: 401
if not scope_allowed: 403
if not ip_allowed: 403
if rate_limited: 429
```

### 3.4 权限范围（Scope）定义

表：Scope 定义对照表

| Scope | 说明 | 对应 API 前缀 |
| --- | --- | --- |
| agents:read | 读取 Agent 配置与状态 | GET /api/v1/agents/** |
| agents:write | 创建、更新、删除 Agent | POST/PUT/DELETE /api/v1/agents/** |
| data:read | 读取数据集 | GET /api/v1/datasets/** |
| data:write | 写入、更新数据集 | POST/PUT/DELETE /api/v1/datasets/** |
| dag:execute | 执行 DAG 工作流 | POST /api/v1/dags/*/execute |
| evolution:read | 读取进化记录 | GET /api/v1/evolution/** |
| apikeys:manage | 管理 API Key（慎用） | /api/v1/api-keys/** |

Scope 存储为 JSON 数组，示例：

```json
["agents:read", "data:read", "dag:execute"]
```

### 3.5 速率限制配置

rate_limit 字段 JSON 结构：

表：rate_limit 参数说明表

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| requests_per_minute | INT | 每分钟最大请求数，NULL 表示不限 |
| requests_per_hour | INT | 每小时最大请求数，NULL 表示不限 |
| requests_per_day | INT | 每天最大请求数，NULL 表示不限 |

示例：

```json
{
  "requests_per_minute": 60,
  "requests_per_hour": 3600,
  "requests_per_day": 86400
}
```

### 3.6 IP 白名单配置

ip_whitelist 字段存储为 JSON 数组，元素支持单 IP 与 CIDR：

```json
["10.0.0.1", "192.168.1.0/24", "172.16.0.0/12"]
```

---

## 第4章 API 设计

### 4.1 接口总览

表：API 接口清单对照表

| 方法 | 路径 | 说明 | 认证 |
| --- | --- | --- | --- |
| POST | /api/v1/api-keys | 生成 API Key | JWT |
| GET | /api/v1/api-keys | 列出所有 API Key | JWT |
| GET | /api/v1/api-keys/{id} | 查看 API Key 详情 | JWT |
| PUT | /api/v1/api-keys/{id} | 更新 API Key | JWT |
| DELETE | /api/v1/api-keys/{id} | 吊销 API Key | JWT |
| GET | /api/v1/api-keys/{id}/usage | 使用统计 | JWT |
| GET | /api/v1/api-keys/{id}/usage/trend | 使用趋势 | JWT |

> 管理类接口使用 JWT 认证（管理员登录态）；业务调用使用 API Key 认证。

### 4.2 POST /api/v1/api-keys — 生成 API Key

请求体：

```json
{
  "name": "CI/CD 集成 Key",
  "scopes": ["agents:read", "data:read", "dag:execute"],
  "rate_limit": {
    "requests_per_minute": 60,
    "requests_per_hour": 3600,
    "requests_per_day": 86400
  },
  "ip_whitelist": ["10.0.0.0/8"],
  "expires_at": "2027-08-13T00:00:00Z"
}
```

表：生成请求参数说明表

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| name | string | 是 | Key 名称，1-128 字符 |
| scopes | string[] | 否 | 权限范围，默认空数组 |
| rate_limit | object | 否 | 速率限制，默认不限 |
| ip_whitelist | string[] | 否 | IP 白名单，默认空（不限） |
| expires_at | string | 否 | ISO 8601 过期时间，默认永不过期 |

响应（201 Created）：

```json
{
  "id": 1,
  "key_id": "aBcDeFgH1Jk",
  "key": "maop_aBcDeFgH1Jk_x9Y2Z3w4v5u6t7s8r9q0p1o2n3m4l5k6j7i8h9g0f1e2d3c4b5a6",
  "name": "CI/CD 集成 Key",
  "scopes": ["agents:read", "data:read", "dag:execute"],
  "rate_limit": {
    "requests_per_minute": 60,
    "requests_per_hour": 3600,
    "requests_per_day": 86400
  },
  "ip_whitelist": ["10.0.0.0/8"],
  "expires_at": "2027-08-13T00:00:00Z",
  "created_at": "2026-08-13T10:00:00Z"
}
```

> `key` 字段仅在本次响应中返回，后续查询不再返回。前端需提示用户立即复制保存。

### 4.3 GET /api/v1/api-keys — 列出所有 API Key

查询参数：

表：列表查询参数说明表

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| page | int | 1 | 页码 |
| page_size | int | 20 | 每页条数，最大 100 |
| status | string | active | 过滤状态：active / revoked / all |
| keyword | string | - | 按名称模糊搜索 |

响应（200 OK）：

```json
{
  "total": 15,
  "page": 1,
  "page_size": 20,
  "items": [
    {
      "id": 1,
      "key_id": "aBcDeFgH1Jk",
      "key_prefix": "maop_aBcDeFgH1Jk_****",
      "name": "CI/CD 集成 Key",
      "scopes": ["agents:read", "data:read", "dag:execute"],
      "status": "active",
      "last_used_at": "2026-08-13T09:55:00Z",
      "expires_at": "2027-08-13T00:00:00Z",
      "created_at": "2026-08-13T10:00:00Z"
    }
  ]
}
```

> `key_prefix` 为脱敏展示，secret 部分以 `****` 替代。

### 4.4 GET /api/v1/api-keys/{id} — 查看详情

响应（200 OK）：

```json
{
  "id": 1,
  "key_id": "aBcDeFgH1Jk",
  "key_prefix": "maop_aBcDeFgH1Jk_****",
  "name": "CI/CD 集成 Key",
  "scopes": ["agents:read", "data:read", "dag:execute"],
  "rate_limit": {
    "requests_per_minute": 60,
    "requests_per_hour": 3600,
    "requests_per_day": 86400
  },
  "ip_whitelist": ["10.0.0.0/8"],
  "status": "active",
  "last_used_at": "2026-08-13T09:55:00Z",
  "expires_at": "2027-08-13T00:00:00Z",
  "created_at": "2026-08-13T10:00:00Z",
  "revoked_at": null
}
```

### 4.5 PUT /api/v1/api-keys/{id} — 更新 API Key

请求体（所有字段可选，仅传需更新字段）：

```json
{
  "name": "CI/CD 集成 Key（更新）",
  "scopes": ["agents:read", "data:read"],
  "rate_limit": {
    "requests_per_minute": 30,
    "requests_per_hour": 1800,
    "requests_per_day": 43200
  },
  "ip_whitelist": ["10.0.0.0/8", "172.16.0.0/12"]
}
```

> 不支持更新 `key_id`、`key_hash`；如需更换 Key 内容，请吊销后重新生成。

响应（200 OK）：返回更新后的完整对象（同 4.4 结构）。

### 4.6 DELETE /api/v1/api-keys/{id} — 吊销 API Key

无请求体。响应（200 OK）：

```json
{
  "id": 1,
  "status": "revoked",
  "revoked_at": "2026-08-13T11:00:00Z"
}
```

> 吊销后立即失效：同步清除 Redis 缓存，后续使用该 Key 的请求返回 401。

### 4.7 GET /api/v1/api-keys/{id}/usage — 使用统计

查询参数：

表：使用统计查询参数说明表

| 参数 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| start_date | string | 7 天前 | 起始日期（ISO 8601） |
| end_date | string | 今天 | 结束日期（ISO 8601） |
| granularity | string | day | 聚合粒度：hour / day |

响应（200 OK）：

```json
{
  "key_id": "aBcDeFgH1Jk",
  "summary": {
    "total_requests": 12580,
    "success_count": 12300,
    "error_count": 280,
    "success_rate": 0.9777,
    "avg_response_time_ms": 45,
    "p95_response_time_ms": 120,
    "p99_response_time_ms": 250
  },
  "status_code_distribution": {
    "200": 12300,
    "400": 150,
    "401": 30,
    "403": 50,
    "429": 20,
    "500": 30
  },
  "top_endpoints": [
    {"endpoint": "/api/v1/agents", "method": "GET", "count": 5800},
    {"endpoint": "/api/v1/dags/1/execute", "method": "POST", "count": 3200},
    {"endpoint": "/api/v1/datasets", "method": "GET", "count": 2100}
  ],
  "recent_calls": [
    {
      "endpoint": "/api/v1/agents",
      "method": "GET",
      "status_code": 200,
      "response_time": 38,
      "client_ip": "10.0.1.5",
      "timestamp": "2026-08-13T09:55:00Z"
    }
  ]
}
```

### 4.8 GET /api/v1/api-keys/{id}/usage/trend — 使用趋势

查询参数同 4.7。响应（200 OK）：

```json
{
  "key_id": "aBcDeFgH1Jk",
  "granularity": "day",
  "trend": [
    {
      "timestamp": "2026-08-07T00:00:00Z",
      "request_count": 1800,
      "avg_response_time_ms": 42,
      "error_count": 40
    },
    {
      "timestamp": "2026-08-08T00:00:00Z",
      "request_count": 1950,
      "avg_response_time_ms": 45,
      "error_count": 35
    }
  ]
}
```

### 4.9 错误响应

统一错误格式：

```json
{
  "error": {
    "code": "APIKEY_INVALID",
    "message": "API Key is invalid or revoked",
    "details": {}
  }
}
```

表：错误码对照表

| HTTP | error.code | 触发场景 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 请求参数校验失败 |
| 401 | APIKEY_INVALID | API Key 无效、已吊销或已过期 |
| 403 | APIKEY_SCOPE_DENIED | 权限范围不足 |
| 403 | APIKEY_IP_DENIED | 来源 IP 不在白名单 |
| 404 | APIKEY_NOT_FOUND | 指定 ID 的 Key 不存在 |
| 409 | APIKEY_NAME_DUPLICATE | 名称重复（若启用唯一约束） |
| 429 | APIKEY_RATE_LIMITED | 触发速率限制 |

429 响应附加头：

```
Retry-After: 12
X-RateLimit-Limit: 60
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1723536072
```

### 4.10 认证中间件

#### 4.10.1 提取顺序

图：API Key 认证中间件流程图

```
Request
  |
  +-- Authorization: Bearer maop_xxx ? -> extract
  |    (优先)
  +-- X-API-Key: maop_xxx ? -> extract
  |
  +-- 无 API Key -> 走 JWT 认证
  |
  +-- 有 API Key:
       1. 解析 key_id, secret
       2. Redis 查 key_id -> meta（未命中查 DB 并回填）
       3. 校验 key_hash == sha256(full_key)
       4. 校验未吊销、未过期
       5. 校验 IP 白名单
       6. 速率限制（滑动窗口）
       7. Scope 校验（按 endpoint 匹配）
       8. 通过 -> 注入 request.api_key = meta
       9. 异步记录 usage
```

#### 4.10.2 传递方式

代码示例：API Key 提取（Python）

```python
import re

APIKEY_PATTERN = re.compile(r"^maop_([A-Za-z0-9_-]{11})_([A-Za-z0-9_-]{43})$")

def extract_api_key(request) -> str | None:
    # 优先 Authorization: Bearer
    auth = request.headers.get("Authorization", "")
    if auth.startswith("Bearer maop_"):
        return auth[7:]
    # 其次 X-API-Key
    return request.headers.get("X-API-Key")

def parse_api_key(raw: str) -> tuple[str, str] | None:
    match = APIKEY_PATTERN.match(raw or "")
    if not match:
        return None
    return match.group(1), match.group(2)
```

---

## 第5章 UI 设计

### 5.1 页面总览

表：页面清单对照表

| 页面 | 路由 | 布局组件 | 说明 |
| --- | --- | --- | --- |
| API Key 管理列表 | /api-keys | ListPageLayout | Key 列表与操作入口 |
| 生成 API Key 对话框 | - | ElDialog | 表单 + 一次性 Key 展示 |
| API Key 详情面板 | /api-keys/{id} | DetailDrawer | 详情 + 统计图表 |

> 遵循项目既有可复用布局组件规范：ListPageLayout、FilterBar、DetailDrawer。所有页面左右宽度保持一致。

### 5.2 API Key 管理列表页

#### 5.2.1 布局结构

图：列表页布局示意图

```
+----------------------------------------------------------+
| [顶栏] API Key 管理                          [用户] [设置] |
+----------------------------------------------------------+
| [FilterBar]                                               |
|  状态: [全部 v]  关键词: [______]  [查询] [重置]          |
+----------------------------------------------------------+
| [操作栏]                                  [生成 API Key]  |
+----------------------------------------------------------+
| [DataTable]                                               |
|  名称 | Key 前缀 | 权限范围 | 状态 | 最后使用 | 创建时间 | 操作 |
|  ...  | ...     | ...     | ...  | ...      | ...      | ...  |
+----------------------------------------------------------+
| [Pagination]                              共 15 条  < 1 > |
+----------------------------------------------------------+
```

#### 5.2.2 DataTable 列定义

表：列表列定义对照表

| 列名 | 字段 | 宽度 | 渲染说明 |
| --- | --- | --- | --- |
| 名称 | name | 15% | 文本 |
| Key 前缀 | key_prefix | 18% | `maop_xxxx_****` 等宽字体 |
| 权限范围 | scopes | 22% | Tag 标签组，超过 3 个显示 +N |
| 状态 | status | 10% | active 绿色 / revoked 红色 / expired 灰色 |
| 最后使用 | last_used_at | 13% | 相对时间（如 5 分钟前） |
| 创建时间 | created_at | 13% | YYYY-MM-DD HH:mm |
| 操作 | - | 9% | 详情 / 编辑 / 吊销 |

#### 5.2.3 操作按钮

- 生成 API Key：右上角主按钮，打开生成对话框
- 详情：打开 DetailDrawer
- 编辑：打开编辑对话框（同生成表单，预填值，不含 Key 展示）
- 吊销：二次确认弹窗，确认后调用 DELETE

### 5.3 生成 API Key 对话框

#### 5.3.1 表单阶段

图：生成对话框表单示意图

```
+--------------------------------------------------+
| 生成 API Key                              [x]    |
+--------------------------------------------------+
| 名称 *      [____________________________]       |
|                                                   |
| 权限范围    [x] agents:read                      |
|            [ ] agents:write                      |
|            [x] data:read                         |
|            [ ] data:write                        |
|            [x] dag:execute                       |
|            [ ] evolution:read                    |
|                                                   |
| 速率限制                                          |
|   每分钟  [60  ]   每小时 [3600 ]   每天 [86400] |
|                                                   |
| IP 白名单  [10.0.0.0/8      ] [+]                |
|            [172.16.0.0/12   ] [x]                |
|                                                   |
| 有效期     [永不过期 v] / [自定义日期选择器]     |
|                                                   |
|                          [取消]  [生成]          |
+--------------------------------------------------+
```

#### 5.3.2 Key 展示阶段

生成成功后切换为 Key 展示视图：

图：生成成功 Key 展示示意图

```
+--------------------------------------------------+
| API Key 生成成功                          [x]    |
+--------------------------------------------------+
|                                                   |
|  ! 请立即复制保存，关闭后无法再次查看完整 Key。  |
|                                                   |
|  完整 API Key                                     |
|  +------------------------------------------+    |
|  | maop_aBcDeFgH1Jk_x9Y2Z3w4v5u6t7s8r9q... |    |
|  +------------------------------------------+    |
|                                   [复制]         |
|                                                   |
|  Key 前缀: maop_aBcDeFgH1Jk_****                 |
|  名称:    CI/CD 集成 Key                         |
|                                                   |
|                          [我已保存，关闭]        |
+--------------------------------------------------+
```

> 复制按钮使用 clipboard API，复制后显示成功提示。关闭对话框前若未点击"我已保存"，二次确认。

### 5.4 API Key 详情面板（DetailDrawer）

#### 5.4.1 结构

图：详情面板结构示意图

```
+--------------------------------------------------+
| API Key 详情: CI/CD 集成 Key             [x]    |
+--------------------------------------------------+
| [Tab] 基本信息 | 使用统计 | 最近调用             |
+--------------------------------------------------+
| 基本信息                                          |
|  Key 前缀:  maop_aBcDeFgH1Jk_****                |
|  状态:     [active]                              |
|  创建时间: 2026-08-13 10:00                      |
|  过期时间: 2027-08-13 00:00                      |
|  最后使用: 5 分钟前                              |
|                                                   |
|  权限范围                                         |
|  [agents:read] [data:read] [dag:execute]         |
|                                                   |
|  速率限制                                         |
|  每分钟 60 / 每小时 3600 / 每天 86400            |
|                                                   |
|  IP 白名单                                        |
|  10.0.0.0/8, 172.16.0.0/12                       |
+--------------------------------------------------+
```

#### 5.4.2 使用统计 Tab

图：使用统计仪表板示意图

```
+--------------------------------------------------+
| 使用统计                  [近 7 天 v]           |
+--------------------------------------------------+
| [调用量趋势]                                     |
|  折线图（X: 日期, Y: 请求数）                    |
|                                                   |
| [状态码分布]        [热点端点排行]               |
|  饼图               横向条形图 Top 10             |
|  200: 97.8%        /api/v1/agents  5800          |
|  4xx: 1.8%         /api/v1/dags   3200          |
|  5xx: 0.4%         /api/v1/data   2100          |
|                                                   |
| [平均响应时间趋势]                               |
|  折线图（X: 日期, Y: ms）                        |
+--------------------------------------------------+
```

图表组件选型：

- 折线图：调用量趋势、响应时间趋势
- 饼图：状态码分布
- 横向条形图：热点端点排行

> 图表使用项目既有图表组件，保持视觉风格统一；配色遵循浅色/白色主题。

### 5.5 交互细节

- 所有操作有 loading 态与错误提示
- 吊销操作二次确认，确认弹窗文案明确说明"立即生效，不可恢复"
- 表单校验实时反馈（名称必填、IP 格式校验、速率限制为正整数）
- 列表空状态展示引导文案与"生成 API Key"入口
- 提示消息采用顶部 toast，不遮挡主内容区

---

## 第6章 验收标准

### 6.1 功能验收

| 编号 | 验收点 | 验证方式 |
| --- | --- | --- |
| AC-01 | API Key 可用于认证所有业务 API 请求 | 使用生成的 Key 调用 agents/datasets/dags 等接口，返回 200 |
| AC-02 | API Key 生成后仅显示一次完整 Key | 生成响应含 `key` 字段；再次 GET 详情无 `key` 字段，仅 `key_prefix` |
| AC-03 | 支持细粒度权限范围控制 | 仅授予 `agents:read` 的 Key 调用 `POST /agents` 返回 403 |
| AC-04 | 速率限制准确生效（滑动窗口） | 设置 60/min，连续 61 次请求第 61 次返回 429，60 秒后恢复 |
| AC-05 | IP 白名单正确拦截 | 白名单 `10.0.0.0/8`，来源 `192.168.1.1` 请求返回 403 |
| AC-06 | API Key 吊销后立即失效 | 吊销后立即用该 Key 请求返回 401，Redis 缓存已清除 |
| AC-07 | 使用统计实时记录 | 调用后查询 usage 接口，`total_requests` 与 `recent_calls` 包含本次 |
| AC-08 | API Key 认证与 JWT 认证并存（优先 API Key） | 同时携带 API Key 与 JWT，按 API Key 身份鉴权 |

### 6.2 安全验收

| 编号 | 验收点 | 验证方式 |
| --- | --- | --- |
| AC-09 | 数据库不存储明文 Key | 查询 api_keys 表，仅存在 key_hash，无明文 |
| AC-10 | Key 校验使用 SHA-256 比对 | 用错误 secret 的 Key 请求返回 401 |
| AC-11 | 过期 Key 自动失效 | 设置过期时间为过去，请求返回 401 |
| AC-12 | 速率限制防暴力枚举 | 高频随机 Key 尝试，触发 429 限流 |

### 6.3 兼容性验收

| 编号 | 验收点 | 验证方式 |
| --- | --- | --- |
| AC-13 | 不影响现有 JWT 登录 | 不携带 API Key 的请求正常走 JWT 认证 |
| AC-14 | 前端现有页面不受影响 | 启用 API Key 中间件后，前端登录态访问正常 |

---

## 第7章 非功能需求

### 7.1 安全

- Key 存储：数据库仅存 SHA-256 hash，不可逆向
- Key 传输：强制 HTTPS，禁止 HTTP 明文
- Key 展示：前端仅在生成时展示一次，后续仅展示脱敏前缀
- 速率限制：滑动窗口算法，防暴力枚举与滥用
- IP 白名单：支持 CIDR，校验在认证中间件最前置
- 日志脱敏：日志中禁止打印完整 Key，仅记录 key_id
- 吊销即时性：吊销同步清除 Redis 缓存，TTL 兜底 5 秒

### 7.2 性能

表：性能指标说明表

| 指标 | 目标 | 说明 |
| --- | --- | --- |
| Key 验证延迟 | < 2ms | Redis 缓存命中场景（含 hash 比对） |
| 速率限制计数 | < 1ms | Redis 滑动窗口（INCR + EXPIRE） |
| 使用统计写入 | 异步 | 不阻塞主请求，写入 Redis Stream 后批量落库 |
| 统计查询 | < 500ms | 7 天范围，按天聚合 |
| 列表查询 | < 100ms | 单租户 Key 数量 < 1000 |

### 7.3 可靠性

- Redis 不可用时降级：Key 校验回退到数据库查询（性能下降但可用）
- 使用统计写入失败不阻塞主请求（catch 并记录 warn 日志）
- 数据库迁移支持 SQLite（测试环境）与 PostgreSQL（生产环境）

### 7.4 兼容性

- 与现有 JWT 认证并存，认证中间件按 API Key 优先顺序处理
- 不修改现有用户登录流程与 Token 签发逻辑
- 前端新增路由与页面，不改动既有路由结构

### 7.5 可维护性

- Scope 定义集中维护，支持配置化扩展
- 速率限制策略可配置化，便于调整算法参数
- 使用统计聚合 SQL 可维护，避免复杂嵌套

---

## 第8章 实现计划

### 8.1 技术栈确认

基于项目 Engineering Context 与既有规范：

- 后端：Python + FastAPI（MAOP 既有技术栈）
- 前端：Vue 3 + TypeScript（Strict + ESM）
- 数据库：PostgreSQL（生产）/ SQLite（测试）
- 缓存：Redis（Key 校验缓存 + 速率限制滑动窗口）
- ORM：SQLAlchemy（既有）
- 认证：与既有 JWT 认证中间件并存

### 8.2 后端文件清单

#### 8.2.1 新建文件

表：后端新建文件清单

| 文件路径 | 职责 |
| --- | --- |
| maop/models/api_key.py | API Key 与 Usage 的 SQLAlchemy 模型 |
| maop/schemas/api_key.py | Pydantic 请求/响应 Schema |
| maop/api/v1/api_keys.py | API Key 管理路由（7 个接口） |
| maop/services/api_key_service.py | Key 生成、吊销、更新、查询业务逻辑 |
| maop/services/api_key_usage_service.py | 使用统计记录与聚合查询 |
| maop/middleware/api_key_auth.py | API Key 认证中间件 |
| maop/core/scope.py | Scope 定义与 endpoint 匹配 |
| maop/core/rate_limit.py | 滑动窗口速率限制器（Redis） |
| maop/core/ip_whitelist.py | IP/CIDR 白名单校验 |
| migrations/versions/xxxx_add_api_keys.py | 数据库迁移：api_keys + api_key_usage 表 |

#### 8.2.2 修改文件

表：后端修改文件清单

| 文件路径 | 修改内容 |
| --- | --- |
| maop/main.py | 注册 api_keys 路由，挂载 API Key 认证中间件 |
| maop/core/auth.py | 认证链调整为 API Key 优先，JWT 兜底 |
| maop/config.py | 新增 API Key 相关配置项（默认速率、缓存 TTL 等） |

### 8.3 前端文件清单

#### 8.3.1 新建文件

表：前端新建文件清单

| 文件路径 | 职责 |
| --- | --- |
| src/views/api-keys/ApiKeyList.vue | 列表页（ListPageLayout + DataTable） |
| src/views/api-keys/components/ApiKeyGenerateDialog.vue | 生成/编辑对话框 |
| src/views/api-keys/components/ApiKeyDetailDrawer.vue | 详情面板（DetailDrawer） |
| src/views/api-keys/components/ApiKeyUsageDashboard.vue | 使用统计仪表板 |
| src/views/api-keys/components/ApiKeyDisplay.vue | 一次性 Key 展示组件 |
| src/api/apiKeys.ts | API Key 接口封装 |
| src/types/apiKey.ts | TypeScript 类型定义 |

#### 8.3.2 修改文件

表：前端修改文件清单

| 文件路径 | 修改内容 |
| --- | --- |
| src/router/index.ts | 新增 /api-keys 路由 |
| src/layout/menu.ts | 菜单新增"API Key 管理"入口 |

### 8.4 实现阶段

表：实现阶段划分对照表

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| Phase 1 | 数据模型 + 迁移 + 基础 Service | api_keys / api_key_usage 表，Key 生成与哈希 |
| Phase 2 | 认证中间件 + Scope + 速率限制 + IP 白名单 | API Key 可用于业务 API 认证 |
| Phase 3 | 管理接口（7 个 REST API） | CRUD + 使用统计查询 |
| Phase 4 | 前端列表页 + 生成对话框 | 管理员可生成与管理 Key |
| Phase 5 | 前端详情面板 + 统计仪表板 | 使用统计可视化 |
| Phase 6 | 联调 + 压测 + 验收 | 满足全部 AC |

### 8.5 测试计划

表：测试项对照表

| 测试类型 | 范围 | 工具 |
| --- | --- | --- |
| 单元测试 | Scope 匹配、IP/CIDR 校验、Key 生成与哈希、速率限制计数 | pytest |
| 集成测试 | 7 个管理接口、认证中间件全链路 | pytest + httpx |
| 前端测试 | 列表渲染、对话框交互、Key 复制 | Vitest |
| 压测 | Key 验证延迟 < 2ms、速率限制准确性 | k6（JavaScript） |
| 安全测试 | 明文不落库、吊销即时失效、过期失效 | 手工 + 自动化 |

> 压测脚本使用 JavaScript 编写（k6），自定义指标采用 snake_case（如 `apikey_verify_latency_ms`、`rate_limit_triggered_total`）。

### 8.6 配置项

maop 配置新增项（application.yml / config.py）：

```yaml
api_key:
  key_prefix: "maop"
  key_id_bytes: 8
  secret_bytes: 32
  cache_ttl_seconds: 300
  revoke_cache_purge_timeout: 5
  usage_retention_days: 90
  default_rate_limit:
    requests_per_minute: 60
    requests_per_hour: 3600
    requests_per_day: 86400
  rate_limit:
    algorithm: "sliding_window"
    redis_key_prefix: "rl:apikey:"
```

### 8.7 风险与对策

表：风险对照表

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| Redis 故障导致 Key 校验不可用 | 所有 API Key 调用失败 | 降级到数据库查询，告警通知 |
| 使用统计写入量过大 | 数据库压力 | 异步批量写入，Redis Stream 缓冲 |
| Key 泄露（用户侧） | 越权调用 | 支持立即吊销 + IP 白名单兜底 |
| Scope 与 endpoint 映射维护遗漏 | 权限绕过 | 集中映射表 + 单元测试覆盖 |

---

## 附录

### 附录 A：Scope 与 Endpoint 映射

表：Scope 与 Endpoint 映射对照表

| Scope | 允许的方法 | Endpoint 模式 |
| --- | --- | --- |
| agents:read | GET | /api/v1/agents, /api/v1/agents/{id} |
| agents:write | POST, PUT, DELETE | /api/v1/agents, /api/v1/agents/{id} |
| data:read | GET | /api/v1/datasets, /api/v1/datasets/{id} |
| data:write | POST, PUT, DELETE | /api/v1/datasets, /api/v1/datasets/{id} |
| dag:execute | POST | /api/v1/dags/{id}/execute |
| evolution:read | GET | /api/v1/evolution, /api/v1/evolution/{id} |
| apikeys:manage | ALL | /api/v1/api-keys/** |

### 附录 B：速率限制滑动窗口实现

代码示例：滑动窗口速率限制（Python）

```python
import time
import redis

def rate_limit_check(
    r: redis.Redis,
    key: str,
    limit: int,
    window_seconds: int,
) -> tuple[bool, int]:
    """
    滑动窗口速率限制。
    返回 (allowed, remaining)。
    """
    now = int(time.time())
    window_start = now - window_seconds
    pipe = r.pipeline()
    pipe.zremrangebyscore(key, 0, window_start)
    pipe.zadd(key, {str(now): now})
    pipe.zcard(key)
    pipe.expire(key, window_seconds + 1)
    _, _, count, _ = pipe.execute()
    if count > limit:
        return False, 0
    return True, limit - count
```

### 附录 C：IP 白名单校验实现

代码示例：IP/CIDR 白名单校验（Python）

```python
import ipaddress

def is_ip_allowed(client_ip: str, whitelist: list[str]) -> bool:
    if not whitelist:
        return True
    addr = ipaddress.ip_address(client_ip)
    for entry in whitelist:
        if "/" in entry:
            network = ipaddress.ip_network(entry, strict=False)
            if addr in network:
                return True
        else:
            if addr == ipaddress.ip_address(entry):
                return True
    return False
```

---

> 本文档为 API Key 管理功能的完整 PRD，后续设计文档（design.md）与任务拆解（tasks.md）将基于此文档展开。