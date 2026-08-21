# PRD：多租户资源配额管理

> 文档版本：v1.0
> 创建日期：2026-08-13
> 状态：Draft
> 负责人：待定

---

## 第1章 功能概述

### 1.1 功能目标

为多租户系统（MAOP，Multi Agents Orchestration Platform）提供完整的资源配额管理与超额告警能力，确保各租户在共享基础设施上公平、可控地使用 CPU、内存、Agent 实例、存储、API 调用次数、DAG 执行次数等资源，避免单一租户的资源滥用影响整体系统稳定性。

### 1.2 背景

MAOP 当前已具备多租户基础能力：

- `maop/tenant.py` 已实现租户的创建、查询、禁用等基础管理逻辑。
- 数据库已存在 `tenant_usage` 表，记录租户基础使用量。
- 现有调度器在分配 Agent、执行 DAG 时未对单租户资源进行限制，存在以下风险：
  - 单租户可无限制创建 Agent，耗尽集群资源。
  - 单租户可发起高频 API 调用，拖垮网关与后端服务。
  - 缺乏配额超限告警机制，运维无法及时感知资源争抢。
- 业务方提出明确诉求：按租户维度设置 CPU/内存/Agent 等配额，并支持超额告警与硬限制拒绝。

### 1.3 范围

本 PRD 覆盖以下功能模块：

| 模块 | 说明 |
| --- | --- |
| 配额设置 | 管理员为每个租户配置各类资源的上限值与限制模式（软/硬）。 |
| 使用量监控 | 实时采集并暴露各租户资源使用量，支持按天聚合的趋势查询。 |
| 超额告警 | 使用率达到阈值时自动触发告警，集成通知中心，支持告警解决流转。 |
| 配额调整 | 支持配额变更，记录审计日志，支持临时配额（有效期）。 |
| 使用统计 | 提供租户维度与全局维度的配额使用统计、热力图、趋势图。 |
| 配额检查 | 在关键请求路径（创建 Agent、执行 DAG、调用 API）前置配额检查。 |

不在本版本范围内：

- 跨租户资源借用（burst / borrow）。
- 配额计费与账单。
- 基于预测的弹性配额建议。

### 1.4 术语

| 术语 | 说明 |
| --- | --- |
| Quota | 配额，某资源在某租户上的上限值。 |
| Usage | 使用量，某资源在某租户上的当前占用值。 |
| 软限制（Soft Limit） | 超限后仅告警，不拒绝请求。 |
| 硬限制（Hard Limit） | 超限后拒绝请求并返回 429。 |
| Fail-open | 配额检查服务异常时默认放行请求，保障可用性。 |

---

## 第2章 用户故事

| 编号 | 角色 | 故事 | 优先级 |
| --- | --- | --- | --- |
| US-01 | 系统管理员 | 我想为每个租户设置资源配额（CPU/内存/Agent/用户/存储/API/DAG），以便控制资源使用上限。 | P0 |
| US-02 | 系统管理员 | 我想查看各租户资源使用情况与使用率，以便了解资源分配状况。 | P0 |
| US-03 | 系统管理员 | 我想配置超额告警阈值与通知渠道，以便租户超额时及时收到通知。 | P0 |
| US-04 | 租户管理员 | 我想查看本租户配额使用情况（只读），以便合理规划业务资源。 | P1 |
| US-05 | 系统 | 我想在租户超额时拒绝新请求（硬限制模式），以便保护系统稳定。 | P0 |
| US-06 | 系统管理员 | 我想查看配额使用趋势（按天聚合），以便优化资源分配策略。 | P1 |
| US-07 | 系统管理员 | 我想查看所有租户配额概览与热力图，以便快速发现超额风险租户。 | P1 |
| US-08 | 系统管理员 | 我想为某租户设置临时配额（带有效期），以便应对临时活动峰值。 | P2 |
| US-09 | 系统管理员 | 我想查看配额变更审计日志，以便追溯配额调整历史。 | P1 |
| US-10 | 系统 | 我想在配额检查服务异常时默认放行请求（fail-open），以便避免配额模块故障拖垮主链路。 | P0 |

---

## 第3章 数据模型设计

### 3.1 新增表：tenant_quotas

租户配额主表，记录每租户每类资源的上限与限制模式。

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO_INCREMENT | 主键。 |
| tenant_id | BIGINT | NOT NULL, FK→tenants.id, UNIQUE(tenant_id, quota_type) | 租户 ID。 |
| quota_type | VARCHAR(32) | NOT NULL | 配额类型，枚举见 3.4。 |
| hard_limit | BIGINT | NOT NULL | 硬上限，超过即拒绝。 |
| soft_limit | BIGINT | NULL | 软上限，超过即告警但不拒绝；为空表示不启用软限制。 |
| alert_threshold | DECIMAL(5,2) | DEFAULT 80.00 | 告警阈值百分比，使用率 ≥ 该值触发告警。 |
| mode | VARCHAR(16) | NOT NULL, DEFAULT 'hard' | 限制模式：`soft` / `hard`。 |
| temp_override | BIGINT | NULL | 临时配额值，优先于 hard_limit 生效。 |
| temp_expires_at | TIMESTAMP | NULL | 临时配额过期时间。 |
| created_at | TIMESTAMP | NOT NULL, DEFAULT CURRENT_TIMESTAMP | 创建时间。 |
| updated_at | TIMESTAMP | NOT NULL, DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP | 更新时间。 |
| created_by | BIGINT | NOT NULL | 创建人（管理员 ID）。 |

索引：

- `UNIQUE INDEX uk_tenant_quota (tenant_id, quota_type)`
- `INDEX idx_temp_expires (temp_expires_at)`

### 3.2 扩展现有表：tenant_usage

在现有 `tenant_usage` 表基础上新增字段，记录实时使用量与当日累计指标。

| 新增字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| cpu_usage | DECIMAL(10,3) | DEFAULT 0.000 | 当前 CPU 占用核数。 |
| memory_usage | BIGINT | DEFAULT 0 | 当前内存占用 MB。 |
| storage_usage | BIGINT | DEFAULT 0 | 当前存储占用 MB。 |
| api_calls_today | BIGINT | DEFAULT 0 | 当日 API 调用累计次数。 |
| dag_executions_today | BIGINT | DEFAULT 0 | 当日 DAG 执行累计次数。 |
| usage_reset_at | DATE | NOT NULL | 当日累计指标重置基准日期。 |

说明：

- `cpu_usage` / `memory_usage` / `storage_usage` 为实时值，由采集任务周期更新。
- `api_calls_today` / `dag_executions_today` 为当日累计值，跨日时由定时任务重置。
- 现有 `agent_count` / `user_count` 字段保留复用。

### 3.3 新增表：quota_alerts

配额告警记录表。

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| id | BIGINT | PK, AUTO_INCREMENT | 主键。 |
| tenant_id | BIGINT | NOT NULL, FK→tenants.id | 租户 ID。 |
| quota_type | VARCHAR(32) | NOT NULL | 配额类型。 |
| alert_level | VARCHAR(16) | NOT NULL | 告警级别：`warning` / `critical`。 |
| threshold | DECIMAL(5,2) | NOT NULL | 触发时的阈值百分比。 |
| current_value | BIGINT | NOT NULL | 触发时的当前使用量。 |
| limit_value | BIGINT | NOT NULL | 触发时的配额上限。 |
| message | TEXT | NULL | 告警描述。 |
| triggered_at | TIMESTAMP | NOT NULL, DEFAULT CURRENT_TIMESTAMP | 触发时间。 |
| resolved_at | TIMESTAMP | NULL | 解决时间，为空表示未解决。 |
| resolved_by | BIGINT | NULL | 解决人。 |
| resolve_note | TEXT | NULL | 解决备注。 |
| status | VARCHAR(16) | NOT NULL, DEFAULT 'open' | 状态：`open` / `resolved` / `ignored`。 |

索引：

- `INDEX idx_tenant_status (tenant_id, status)`
- `INDEX idx_triggered_at (triggered_at)`

### 3.4 配额类型枚举

| quota_type | 单位 | 说明 |
| --- | --- | --- |
| agents | 个 | Agent 实例数量上限。 |
| users | 个 | 租户内用户数量上限。 |
| cpu | 核 | CPU 核数上限。 |
| memory | MB | 内存上限。 |
| storage | MB | 存储上限。 |
| api_calls | 次/天 | 每日 API 调用次数上限。 |
| dag_executions | 次/天 | 每日 DAG 执行次数上限。 |

### 3.5 ER 关系示意

```text
tenants 1 ──── * tenant_quotas
tenants 1 ──── 1 tenant_usage
tenants 1 ──── * quota_alerts
```

---

## 第4章 API 设计

### 4.1 接口总览

| 方法 | 路径 | 说明 | 角色 |
| --- | --- | --- | --- |
| GET | /api/v1/tenants/{id}/quota | 查看租户配额 | 管理员/租户管理员 |
| PUT | /api/v1/tenants/{id}/quota | 设置/更新配额 | 管理员 |
| GET | /api/v1/tenants/{id}/usage | 查看租户使用量 | 管理员/租户管理员 |
| GET | /api/v1/tenants/{id}/usage/trend | 使用量趋势（按天聚合） | 管理员/租户管理员 |
| GET | /api/v1/quotas/overview | 所有租户配额概览 | 管理员 |
| GET | /api/v1/quotas/alerts | 配额告警列表 | 管理员 |
| PUT | /api/v1/quotas/alerts/{id}/resolve | 解决告警 | 管理员 |
| POST | /api/v1/quotas/check | 检查配额（内部接口） | 系统 |

### 4.2 查看租户配额

```text
GET /api/v1/tenants/{id}/quota
```

响应示例：

```json
{
  "tenant_id": 101,
  "quotas": [
    {
      "quota_type": "agents",
      "hard_limit": 50,
      "soft_limit": 40,
      "alert_threshold": 80.00,
      "mode": "hard",
      "temp_override": null,
      "temp_expires_at": null
    },
    {
      "quota_type": "cpu",
      "hard_limit": 32,
      "soft_limit": null,
      "alert_threshold": 85.00,
      "mode": "hard",
      "temp_override": 48,
      "temp_expires_at": "2026-08-20T23:59:59Z"
    }
  ]
}
```

### 4.3 设置/更新配额

```text
PUT /api/v1/tenants/{id}/quota
```

请求体示例：

```json
{
  "quotas": [
    {
      "quota_type": "agents",
      "hard_limit": 100,
      "soft_limit": 80,
      "alert_threshold": 80.0,
      "mode": "hard"
    },
    {
      "quota_type": "memory",
      "hard_limit": 65536,
      "mode": "soft"
    }
  ],
  "temp_override": {
    "quota_type": "cpu",
    "value": 48,
    "expires_at": "2026-08-20T23:59:59Z"
  },
  "reason": "应对 818 大促活动临时扩容"
}
```

响应：`200 OK`，返回更新后的完整配额。变更写入审计日志。

### 4.4 查看租户使用量

```text
GET /api/v1/tenants/{id}/usage
```

响应示例：

```json
{
  "tenant_id": 101,
  "usage": {
    "agents": { "used": 38, "limit": 50, "rate": 0.76 },
    "users": { "used": 12, "limit": 30, "rate": 0.40 },
    "cpu": { "used": 26.5, "limit": 32, "rate": 0.83 },
    "memory": { "used": 40960, "limit": 65536, "rate": 0.625 },
    "storage": { "used": 102400, "limit": 524288, "rate": 0.195 },
    "api_calls": { "used": 18500, "limit": 50000, "rate": 0.37 },
    "dag_executions": { "used": 320, "limit": 1000, "rate": 0.32 }
  },
  "collected_at": "2026-08-13T10:30:00Z"
}
```

### 4.5 使用量趋势

```text
GET /api/v1/tenants/{id}/usage/trend?quota_type=cpu&days=30
```

响应示例：

```json
{
  "tenant_id": 101,
  "quota_type": "cpu",
  "points": [
    { "date": "2026-07-15", "used": 18.2, "limit": 32, "rate": 0.569 },
    { "date": "2026-07-16", "used": 22.4, "limit": 32, "rate": 0.700 }
  ]
}
```

### 4.6 所有租户配额概览

```text
GET /api/v1/quotas/overview?quota_type=agents&sort=rate_desc
```

响应示例：

```json
{
  "summary": {
    "total_tenants": 25,
    "over_limit_tenants": 3,
    "near_limit_tenants": 7
  },
  "items": [
    {
      "tenant_id": 101,
      "tenant_name": "tenant-a",
      "quotas": [
        { "quota_type": "agents", "used": 38, "limit": 50, "rate": 0.76, "status": "normal" },
        { "quota_type": "cpu", "used": 28.5, "limit": 32, "rate": 0.89, "status": "warning" }
      ]
    }
  ]
}
```

`status` 枚举：`normal` / `warning`（≥ 阈值）/ `critical`（超限）。

### 4.7 配额告警列表

```text
GET /api/v1/quotas/alerts?status=open&tenant_id=101&page=1&page_size=20
```

响应示例：

```json
{
  "total": 5,
  "items": [
    {
      "id": 9001,
      "tenant_id": 101,
      "quota_type": "cpu",
      "alert_level": "warning",
      "threshold": 80.00,
      "current_value": 26,
      "limit_value": 32,
      "message": "租户 tenant-a CPU 使用率 81.25% 已超过阈值 80%",
      "triggered_at": "2026-08-13T09:15:00Z",
      "status": "open"
    }
  ]
}
```

### 4.8 解决告警

```text
PUT /api/v1/quotas/alerts/{id}/resolve
```

请求体：

```json
{ "note": "已为该租户扩容 CPU 配额至 48 核" }
```

响应：`200 OK`，返回更新后的告警记录。

### 4.9 检查配额（内部接口）

```text
POST /api/v1/quotas/check
```

请求体：

```json
{
  "tenant_id": 101,
  "quota_type": "agents",
  "requested": 1,
  "action": "create_agent"
}
```

响应（放行）：

```json
{ "allowed": true, "reason": null, "current_usage": 38, "limit": 50 }
```

响应（拒绝，HTTP 429）：

```json
{
  "allowed": false,
  "reason": "quota_exceeded",
  "message": "租户 101 的 agents 配额已达上限 50，当前已用 50",
  "current_usage": 50,
  "limit": 50
}
```

错误码：

| HTTP | code | 说明 |
| --- | --- | --- |
| 429 | quota_exceeded | 超过硬限制。 |
| 200 | quota_warning | 超过软限制但放行（响应中附带 warning 字段）。 |
| 200 | quota_check_skipped | 配额检查服务异常，fail-open 放行。 |

---

## 第5章 UI 设计

### 5.1 配额管理页面（/quotas）

布局采用项目既有 `ListPageLayout` 组件，保持左右宽度一致。

#### 5.1.1 页面结构

- 顶栏：标题「配额管理」+ 全局操作（刷新、导出）。
- 筛选区（`FilterBar`）：租户搜索、配额类型筛选、状态筛选（正常/告警/超限）。
- 主体区：
  - 左侧：租户列表（树或表格）。
  - 右侧：选中租户的配额卡片网格。

#### 5.1.2 配额卡片

每张卡片对应一个 `quota_type`，展示：

- 资源名称与图标（统一简约风格，不花哨）。
- 已用 / 上限（如 `38 / 50`）。
- 使用率进度条，三色阈值：
  - 绿色：rate < alert_threshold。
  - 黄色：alert_threshold ≤ rate < 100%。
  - 红色：rate ≥ 100%。
- 限制模式标签（软/硬）。
- 操作按钮：调整配额、查看详情。

#### 5.1.3 调整配额弹窗

- 表单字段：hard_limit、soft_limit、alert_threshold、mode、临时配额（值 + 过期时间）、变更原因。
- 提交后写审计日志并刷新卡片。

### 5.2 配额详情面板（DetailDrawer）

复用项目既有 `DetailDrawer` 组件，从右侧滑出。

- 顶部：租户基本信息 + 资源类型切换 Tab。
- 实时使用量区块：当前值、上限、使用率、限制模式、临时配额状态。
- 使用量趋势图：折线图（按天，默认 30 天），支持切换 7/30/90 天。横轴日期，纵轴使用量，叠加阈值参考线。
- 配额历史变更记录：时间线列表，展示变更前后值、操作人、原因。

### 5.3 告警通知

- 超额触发时，通过通知中心下发告警，渠道支持站内信、邮件、Webhook。
- 告警列表页（/quotas/alerts）：
  - 表格列：租户、配额类型、级别、触发时间、状态、操作。
  - 状态筛选：全部 / 未解决 / 已解决 / 已忽略。
  - 操作：查看详情、解决、忽略。
- 告警级别样式：warning 黄色、critical 红色，图标统一。

### 5.4 租户概览仪表板（/quotas/dashboard）

- 所有租户配额使用率热力图：行=租户，列=配额类型，色深=使用率。
- 资源分配饼图：按租户聚合某类资源的分配占比。
- 超额租户高亮卡片：列出 status=critical 的租户，置顶展示。
- 顶部统计条：租户总数、超限租户数、告警租户数、平均使用率。

### 5.5 视觉规范

- 采用浅色/白色主题，自然优雅风格，避免深色块。
- 顶栏仅一行，系统级功能居右对齐，其余内容移至正文区域首行。
- 提示消息采用非阻塞 toast，不遮挡主内容区。
- 图标统一简约小众风格，同一语义唯一图标。

---

## 第6章 验收标准

| 编号 | 验收项 | 验证方式 |
| --- | --- | --- |
| AC-01 | 管理员可为每个租户设置 7 类资源配额（agents/users/cpu/memory/storage/api_calls/dag_executions）。 | 调用 PUT /quota 后查询返回一致。 |
| AC-02 | 系统在创建 Agent、执行 DAG、调用 API 前检查配额，硬限制超额时拒绝并返回 HTTP 429。 | 模拟超额场景，请求被拒绝。 |
| AC-03 | 配额使用量实时更新，延迟 ≤ 10 秒。 | 创建 Agent 后查询 usage 反映新值。 |
| AC-04 | 使用率达到 alert_threshold 时触发告警，告警记录写入 quota_alerts 并通知。 | 配额设为 10，使用至 8 触发 warning。 |
| AC-05 | 提供使用量趋势图表，支持 7/30/90 天聚合。 | 调用 usage/trend 返回按天数据。 |
| AC-06 | 租户管理员可查看本租户配额（只读），无法修改。 | 租户管理员调用 PUT /quota 返回 403。 |
| AC-07 | 配额变更记录审计日志，含变更前后值、操作人、时间、原因。 | 查询审计日志可见变更记录。 |
| AC-08 | 配额检查服务异常时 fail-open，主链路请求正常放行。 | 断开配额服务，业务请求仍成功。 |
| AC-09 | 临时配额在过期后自动失效，恢复原 hard_limit。 | 临时配额过期后 check 使用原上限。 |
| AC-10 | 配额概览页展示所有租户使用率，超限租户高亮。 | 概览接口返回 status=critical 项。 |

---

## 第7章 非功能需求

### 7.1 性能

- 配额检查接口（POST /quotas/check）P99 < 5 ms，基于内存缓存（Redis 或进程内 LRU）。
- 使用量采集周期 ≤ 10 秒。
- 趋势查询 30 天数据 P95 < 200 ms，使用按天预聚合表。
- 配额概览接口 25 租户 P95 < 100 ms。

### 7.2 可靠性

- 配额检查失败（缓存不可用、DB 不可达）时 fail-open，记录 warn 日志，不阻断主链路。
- 使用量采集任务具备重试与死信队列，单次采集失败不影响整体。
- 告警去重：同一租户同一 quota_type 在告警未解决期间不重复触发。

### 7.3 公平性

- 支持软限制（告警）与硬限制（拒绝）两种模式，按 quota_type 独立配置。
- 临时配额支持有效期，过期自动恢复，避免长期占用。

### 7.4 安全

- 配额设置/调整仅系统管理员可操作，RBAC 校验。
- 租户管理员仅可查看本租户配额（只读）。
- 审计日志不可篡改，保留 ≥ 180 天。

### 7.5 可观测

- 暴露 Prometheus 指标：
  - `maop_quota_usage_ratio{tenant_id,quota_type}`
  - `maop_quota_check_total{tenant_id,quota_type,result}`
  - `maop_quota_alerts_active{tenant_id,quota_type}`
- 关键操作记录结构化日志。

---

## 第8章 实现计划

### 8.1 后端文件清单（Python / MAOP）

| 操作 | 文件路径 | 说明 |
| --- | --- | --- |
| 新建 | `maop/quota/__init__.py` | 配额模块包。 |
| 新建 | `maop/quota/models.py` | `TenantQuota`、`QuotaAlert` ORM 模型；扩展 `TenantUsage`。 |
| 新建 | `maop/quota/schemas.py` | Pydantic 请求/响应 schema。 |
| 新建 | `maop/quota/service.py` | 配额设置、查询、调整、临时配额业务逻辑。 |
| 新建 | `maop/quota/checker.py` | 配额检查核心（内存缓存 + fail-open）。 |
| 新建 | `maop/quota/collector.py` | 使用量采集任务（CPU/内存/存储实时指标）。 |
| 新建 | `maop/quota/alert.py` | 告警触发、去重、通知集成。 |
| 新建 | `maop/quota/audit.py` | 配额变更审计日志。 |
| 新建 | `maop/quota/api.py` | FastAPI 路由（8 个接口）。 |
| 新建 | `maop/quota/cache.py` | Redis/进程内缓存抽象。 |
| 新建 | `maop/quota/constants.py` | QuotaType 枚举、限制模式常量。 |
| 修改 | `maop/tenant.py` | 在租户创建时初始化默认配额。 |
| 修改 | `maop/main.py`（或 app 入口） | 注册 quota 路由、启动采集定时任务。 |
| 修改 | `maop/agent/service.py` | 创建 Agent 前调用 `checker.check`。 |
| 修改 | `maop/dag/executor.py` | 执行 DAG 前调用 `checker.check`。 |
| 修改 | `maop/middleware/ratelimit.py` | API 调用计数 + 配额检查。 |
| 新建 | `migrations/versions/xxxx_add_quota_tables.py` | Alembic 迁移：新增 tenant_quotas、quota_alerts，扩展 tenant_usage。 |
| 新建 | `tests/quota/test_service.py` | 配额服务单测。 |
| 新建 | `tests/quota/test_checker.py` | 检查器单测（含 fail-open）。 |
| 新建 | `tests/quota/test_api.py` | API 集成测试。 |
| 新建 | `tests/quota/test_alert.py` | 告警触发/去重单测。 |

### 8.2 前端文件清单（Vue3 + 组件化）

| 操作 | 文件路径 | 说明 |
| --- | --- | --- |
| 新建 | `src/views/quota/QuotaListPage.vue` | 配额管理主页面（ListPageLayout）。 |
| 新建 | `src/views/quota/QuotaDashboard.vue` | 概览仪表板（热力图 + 饼图）。 |
| 新建 | `src/views/quota/QuotaAlertsPage.vue` | 告警列表页。 |
| 新建 | `src/components/quota/QuotaCard.vue` | 单资源配额卡片 + 进度条。 |
| 新建 | `src/components/quota/QuotaDetailDrawer.vue` | 详情抽屉（趋势图 + 变更记录）。 |
| 新建 | `src/components/quota/QuotaEditDialog.vue` | 调整配额弹窗。 |
| 新建 | `src/components/quota/UsageTrendChart.vue` | 使用量趋势折线图。 |
| 新建 | `src/components/quota/QuotaHeatmap.vue` | 使用率热力图。 |
| 新建 | `src/api/quota.ts` | 配额相关 API 封装。 |
| 新建 | `src/types/quota.ts` | TypeScript 类型定义。 |
| 修改 | `src/router/index.ts` | 新增 /quotas、/quotas/dashboard、/quotas/alerts 路由。 |
| 修改 | `src/store/menu.ts`（或菜单配置） | 新增配额管理菜单项。 |

### 8.3 里程碑

| 阶段 | 内容 | 预估 |
| --- | --- | --- |
| M1 | 数据模型 + 迁移 + 配额 CRUD API + 单测 | 3 天 |
| M2 | 配额检查器（缓存 + fail-open）+ 接入 Agent/DAG/API 主链路 | 3 天 |
| M3 | 使用量采集 + 趋势聚合 + 告警触发/通知 | 3 天 |
| M4 | 前端配额管理页 + 详情抽屉 + 告警列表 | 4 天 |
| M5 | 概览仪表板 + 热力图 + 审计日志 + 联调验收 | 3 天 |

---

## 第9章 风险与依赖

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| 配额检查引入主链路延迟 | 创建 Agent/DAG 延迟增加 | 内存缓存 + P99<5ms 目标 + fail-open。 |
| 使用量采集不准 | 配额判定偏差 | 多源采集对账 + 定期全量校准任务。 |
| 告警风暴 | 通知渠道被打爆 | 同租户同类型去重 + 告警冷却时间 + 分级通知。 |
| 临时配额遗忘过期 | 长期占用超额资源 | 过期自动失效 + 临近过期提醒 + 仪表板高亮。 |

依赖：

- 通知中心（站内信/邮件/Webhook）已就绪。
- Redis 可用（用于配额缓存与计数）。
- Prometheus + Grafana 监控栈已部署。

---

## 第10章 附录

### 10.1 配额检查时序

```text
请求 → 主链路拦截器 → POST /quotas/check
  ├─ 缓存命中 → 返回判定
  ├─ 缓存未命中 → 查 DB → 回填缓存 → 返回判定
  └─ 检查异常 → fail-open 放行 + warn 日志
判定通过 → 继续主链路
判定拒绝 → 返回 429
```

### 10.2 默认配额模板

| quota_type | 默认 hard_limit | 默认 alert_threshold |
| --- | --- | --- |
| agents | 50 | 80% |
| users | 30 | 80% |
| cpu | 32 | 80% |
| memory | 65536 (64GB) | 80% |
| storage | 524288 (512GB) | 80% |
| api_calls | 50000 | 80% |
| dag_executions | 1000 | 80% |

### 10.3 变更记录

| 版本 | 日期 | 变更 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-08-13 | 初稿创建 | PRD Agent |