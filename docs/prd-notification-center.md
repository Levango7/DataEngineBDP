# PRD：通知中心

> 文档版本：v1.0
> 创建日期：2026-08-13
> 状态：Draft
> 负责人：Coding Engineer
> 关联模块：MAOP（Multi Agents Orchestration Platform）

---

## 第1章 功能概述

### 1.1 功能目标

为 MAOP 提供统一的通知中心（Notification Center），支持邮件（Email）、Webhook、站内信（In-App）三种通知渠道，覆盖任务完成、异常告警、配额超限、License 过期等系统事件的统一通知能力。通知中心作为平台级基础设施，向上承接各类业务事件，向下对接多渠道分发，实现"一次事件配置，多渠道触达"的闭环。

### 1.2 背景

MAOP 当前已具备以下能力：

- 任务编排与执行（DAG 执行、Agent 调度）。
- 多租户管理与配额管控（`maop/tenant.py`、`tenant_quotas` 表）。
- 审计日志记录（`audit_events` 表）。
- License 生命周期管理。

但当前缺少统一通知机制，存在以下缺口：

- 任务执行完成或失败后，用户无法及时获知结果，需主动刷新页面查看。
- Agent 异常、配额超限、License 即将过期等告警事件无法主动通知运维与管理员。
- 不同业务模块各自实现通知逻辑，缺乏统一渠道配置与模板管理，维护成本高。
- 缺少站内信实时推送，用户在系统内无法即时收到消息。
- 缺少通知偏好与免打扰时段配置，用户被无关通知打扰。

本 PRD 旨在交付一套完整的通知中心子系统，补齐上述缺口。

### 1.3 范围

表：功能范围说明表

| 范围维度 | 说明 |
| --- | --- |
| 通知渠道配置 | 管理员配置邮件（SMTP）、Webhook、站内信渠道，支持测试发送 |
| 通知规则 | 将业务事件类型绑定到一个或多个通知渠道，配置触发条件 |
| 通知模板 | 按事件类型管理通知标题与正文模板，支持变量替换 |
| 通知历史 | 记录每条通知的发送状态、渠道、收件人、错误信息，支持分页查询 |
| 站内信 | 站内通知的存储、未读计数、标记已读、实时推送 |
| 通知偏好 | 用户级偏好设置：订阅的事件类型、接收渠道、免打扰时段 |
| 实时推送 | 通过 WebSocket 将站内通知实时推送至前端铃铛面板 |
| 事件接入 | 平台各业务模块通过统一事件总线发布事件，通知中心订阅并分发 |

不在本期范围内：

- 短信（SMS）渠道（后续迭代，预留插件扩展点）。
- 移动端 Push 通知（APNs/FCM）。
- 通知发送的 SLA 监控与告警升级链路。
- 通知内容的富文本编辑器（本期支持纯文本 + 变量占位符）。

### 1.4 术语表

表：术语对照表

| 术语 | 含义 |
| --- | --- |
| Channel | 通知渠道，如 email / webhook / inapp |
| Rule | 通知规则，绑定事件类型到渠道与模板 |
| Template | 通知模板，含标题与正文模板字符串，支持变量占位符 |
| Event | 业务事件，由各模块发布，如 task_completed、agent_error |
| In-App | 站内信，在系统内展示的通知消息 |
| Quiet Hours | 免打扰时段，该时段内通知延迟发送或仅站内信 |
| WebSocket Stream | 实时通知推送通道，前端长连接接收新通知 |
| Dead Letter Queue | 死信队列，重试耗尽后转入，供人工排查 |

---

## 第2章 用户故事

### 2.1 用户故事列表

表：用户故事列表

| 编号 | 角色 | 故事 | 优先级 |
| --- | --- | --- | --- |
| US-01 | 系统管理员 | 我想配置邮件通知渠道（SMTP），以便通过邮件接收系统告警 | P0 |
| US-02 | 系统管理员 | 我想配置 Webhook 通知渠道，以便对接企业 IM（钉钉/飞书/Slack） | P0 |
| US-03 | 普通用户 | 我想接收站内通知，以便在系统内查看任务状态与告警 | P0 |
| US-04 | 普通用户 | 我想配置通知偏好（事件类型、渠道、免打扰时段），以便选择接收哪些通知 | P1 |
| US-05 | 系统管理员 | 我想配置通知模板，以便统一通知格式与品牌形象 | P1 |
| US-06 | 普通用户 | 我想实时收到通知推送（WebSocket），以便及时响应事件 | P0 |
| US-07 | 系统管理员 | 我想查看通知历史与发送状态，以便追踪通知发送情况与排查失败 | P0 |
| US-08 | 系统管理员 | 我想配置通知规则（事件 → 渠道），以便控制哪些事件通过哪些渠道通知 | P0 |
| US-09 | 系统管理员 | 我想测试通知渠道连通性，以便在配置后验证渠道可用 | P0 |
| US-10 | 普通用户 | 我想在通知铃铛看到未读数量，以便快速感知新通知 | P0 |
| US-11 | 普通用户 | 我想一键全部已读，以便快速清理未读通知 | P1 |
| US-12 | 系统 | 我想在任务完成/失败时自动通知相关人员，以便用户无需轮询查询 | P0 |
| US-13 | 系统 | 我想在 Agent 异常、配额超限时自动告警通知，以便运维及时介入 | P0 |

### 2.2 用户故事详述

#### 2.2.1 US-01 配置邮件渠道

- **前置条件**：管理员已登录，具备 `notification:channel:manage` 权限。
- **主流程**：进入通知中心 → 渠道管理 Tab → 新建渠道 → 选择类型 email → 填写 SMTP 服务器、端口、用户名、密码、发件人地址 → 保存 → 点击测试发送验证连通性。
- **验收**：渠道保存成功，测试邮件送达指定测试收件人，SMTP 密码加密存储。

#### 2.2.2 US-02 配置 Webhook 渠道

- **前置条件**：管理员已登录。
- **主流程**：新建渠道 → 选择类型 webhook → 填写 URL、HTTP 方法、自定义 Header、Body 模板、签名密钥 → 保存 → 测试发送。
- **验收**：测试请求送达目标 URL，返回 HTTP 状态码与响应体，签名校验通过。

#### 2.2.3 US-06 实时推送

- **前置条件**：用户已登录，浏览器与后端建立 WebSocket 连接。
- **主流程**：系统产生站内通知 → 后端通过 WebSocket 推送至前端 → 通知铃铛未读数 +1 → 铃铛面板自动出现新通知条目。
- **验收**：通知从产生到前端展示延迟 ≤ 2 秒，连接断开后自动重连（指数退避）。

#### 2.2.4 US-04 通知偏好

- **前置条件**：用户已登录。
- **主流程**：进入通知中心 → 偏好设置 Tab → 在事件类型 × 渠道矩阵中勾选订阅项 → 设置免打扰时段（如 22:00-08:00）→ 保存。
- **验收**：保存后，用户仅收到订阅的事件类型与渠道的通知，免打扰时段内邮件/Webhook 延迟至时段结束后发送，站内信正常存储但不实时推送。

---

## 第3章 数据模型设计

### 3.1 新增表：notification_channels

通知渠道配置表，存储各渠道的连接信息与启用状态。

表：notification_channels 参数说明表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT | 主键 |
| `name` | VARCHAR(128) | NOT NULL | 渠道名称，如「默认邮件渠道」 |
| `type` | VARCHAR(32) | NOT NULL, CHECK ∈ {`email`, `webhook`, `inapp`} | 渠道类型 |
| `config` | JSON | NOT NULL | 渠道配置，结构见 3.6 |
| `enabled` | BOOLEAN | NOT NULL, DEFAULT TRUE | 是否启用 |
| `tenant_id` | BIGINT | NOT NULL, FK → tenants.id | 所属租户 |
| `description` | VARCHAR(512) | NULL | 渠道描述 |
| `created_by` | BIGINT | NOT NULL | 创建人 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 创建时间 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 更新时间 |

索引：

- `UNIQUE INDEX uk_tenant_channel_name (tenant_id, name)`
- `INDEX idx_channel_type (type)`

### 3.2 新增表：notification_rules

通知规则表，将事件类型绑定到渠道与模板。

表：notification_rules 参数说明表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT | 主键 |
| `name` | VARCHAR(128) | NOT NULL | 规则名称 |
| `event_type` | VARCHAR(64) | NOT NULL | 事件类型，枚举见 3.5 |
| `condition` | JSON | NULL | 触发条件表达式，结构见 3.7，为空表示无条件触发 |
| `channel_ids` | JSON | NOT NULL | 目标渠道 ID 数组，如 `[1, 3]` |
| `template_id` | BIGINT | NULL, FK → notification_templates.id | 关联模板，为空使用事件默认模板 |
| `recipient_strategy` | VARCHAR(32) | NOT NULL, DEFAULT `event_actor` | 收件人策略，见 3.8 |
| `recipient_override` | JSON | NULL | 收件人覆盖列表（当策略为 override 时生效） |
| `enabled` | BOOLEAN | NOT NULL, DEFAULT TRUE | 是否启用 |
| `tenant_id` | BIGINT | NOT NULL, FK → tenants.id | 所属租户 |
| `description` | VARCHAR(512) | NULL | 规则描述 |
| `created_by` | BIGINT | NOT NULL | 创建人 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 创建时间 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 更新时间 |

索引：

- `INDEX idx_rule_event (tenant_id, event_type, enabled)`
- `INDEX idx_rule_tenant (tenant_id)`

### 3.3 新增表：notification_templates

通知模板表，按事件类型管理标题与正文模板。

表：notification_templates 参数说明表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT | 主键 |
| `name` | VARCHAR(128) | NOT NULL | 模板名称 |
| `event_type` | VARCHAR(64) | NOT NULL | 适用事件类型 |
| `subject_template` | VARCHAR(512) | NOT NULL | 标题模板，支持 `{{variable}}` 占位符 |
| `body_template` | TEXT | NOT NULL | 正文模板，支持 `{{variable}}` 占位符 |
| `variables` | JSON | NULL | 模板变量定义，结构见 3.9 |
| `is_default` | BOOLEAN | NOT NULL, DEFAULT FALSE | 是否为该事件类型的默认模板 |
| `tenant_id` | BIGINT | NOT NULL, FK → tenants.id | 所属租户 |
| `created_by` | BIGINT | NOT NULL | 创建人 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 创建时间 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 更新时间 |

索引：

- `INDEX idx_template_event (tenant_id, event_type)`
- `UNIQUE INDEX uk_default_template (tenant_id, event_type, is_default)` — 每租户每事件类型仅一个默认模板

### 3.4 新增表：notifications

通知发送记录表，记录每条通知的发送状态与详情。

表：notifications 参数说明表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT | 主键 |
| `rule_id` | BIGINT | NULL, FK → notification_rules.id | 触发规则 ID |
| `channel_id` | BIGINT | NOT NULL, FK → notification_channels.id | 发送渠道 |
| `event_type` | VARCHAR(64) | NOT NULL | 事件类型 |
| `recipient` | VARCHAR(512) | NOT NULL | 收件人（邮箱地址 / Webhook URL / 用户 ID） |
| `subject` | VARCHAR(512) | NOT NULL | 渲染后的标题 |
| `body` | TEXT | NOT NULL | 渲染后的正文 |
| `status` | VARCHAR(16) | NOT NULL, DEFAULT `pending` | 发送状态：`pending` / `sent` / `failed` / `retrying` |
| `retry_count` | INTEGER | NOT NULL, DEFAULT 0 | 已重试次数 |
| `sent_at` | TIMESTAMPTZ | NULL | 实际发送时间 |
| `error_message` | TEXT | NULL | 失败错误信息 |
| `is_read` | BOOLEAN | NOT NULL, DEFAULT FALSE | 是否已读（仅站内信适用） |
| `read_at` | TIMESTAMPTZ | NULL | 已读时间 |
| `user_id` | BIGINT | NULL | 目标用户 ID（站内信适用） |
| `tenant_id` | BIGINT | NOT NULL | 所属租户 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 创建时间 |

索引：

- `INDEX idx_notif_user_unread (user_id, is_read, created_at DESC)` — 未读查询
- `INDEX idx_notif_tenant_status (tenant_id, status, created_at DESC)` — 历史查询
- `INDEX idx_notif_retry (status, retry_count)` — 重试扫描
- `INDEX idx_notif_event (event_type, created_at DESC)`

### 3.5 新增表：notification_preferences

用户通知偏好表，存储个人订阅与免打扰配置。

表：notification_preferences 参数说明表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | BIGINT | PK, AUTO_INCREMENT | 主键 |
| `user_id` | BIGINT | NOT NULL, FK → users.id, UNIQUE | 用户 ID |
| `event_types` | JSON | NOT NULL | 订阅的事件类型数组，如 `["task_completed", "agent_error"]`，空数组表示全部订阅 |
| `channels` | JSON | NOT NULL | 启用的渠道数组，如 `["email", "inapp"]` |
| `quiet_hours` | JSON | NULL | 免打扰时段，结构见 3.10 |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 创建时间 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | 更新时间 |

索引：

- `UNIQUE INDEX uk_pref_user (user_id)`

### 3.6 事件类型枚举

表：事件类型枚举表

| event_type | 说明 | 默认收件人 | 来源模块 |
| --- | --- | --- | --- |
| `task_completed` | 任务执行完成 | 任务创建者 | 任务调度器 |
| `task_failed` | 任务执行失败 | 任务创建者 | 任务调度器 |
| `agent_error` | Agent 运行异常 | 租户管理员 | Agent 调度器 |
| `quota_exceeded` | 配额超限 | 租户管理员 | 配额管控模块 |
| `license_expiring` | License 即将过期 | 系统管理员 | License 管理模块 |
| `audit_alert` | 审计告警触发 | 安全官/管理员 | 审计模块 |
| `system_error` | 系统级错误 | 系统管理员 | 系统监控 |
| `dag_completed` | DAG 执行完成 | DAG 创建者 | DAG 引擎 |
| `dag_failed` | DAG 执行失败 | DAG 创建者 | DAG 引擎 |

### 3.7 渠道 config 结构

#### 3.7.1 Email 渠道配置

```json
{
  "smtp_host": "smtp.example.com",
  "smtp_port": 587,
  "use_tls": true,
  "username": "noreply@example.com",
  "password_encrypted": "<AES加密密文>",
  "from_address": "noreply@example.com",
  "from_name": "MAOP 通知"
}
```

#### 3.7.2 Webhook 渠道配置

```json
{
  "url": "https://oapi.dingtalk.com/robot/send?access_token=xxx",
  "method": "POST",
  "headers": {
    "Content-Type": "application/json"
  },
  "body_template": "{\"msgtype\":\"text\",\"text\":{\"content\":\"{{subject}}\\n{{body}}\"}}",
  "sign_secret_encrypted": "<AES加密密文>",
  "sign_type": "hmac_sha256"
}
```

#### 3.7.3 In-App 渠道配置

```json
{
  "max_retention_days": 90
}
```

站内信无需外部连接配置，`max_retention_days` 控制通知保留天数，超期自动清理。

### 3.8 规则 condition 结构

condition 为可选的 JSON 条件表达式，用于在事件 payload 上做附加过滤。采用简洁的 AND 条件数组形式：

```json
{
  "and": [
    { "field": "severity", "op": "in", "value": ["critical", "high"] },
    { "field": "tenant_id", "op": "eq", "value": 101 }
  ]
}
```

支持的运算符（op）：`eq` / `ne` / `gt` / `gte` / `lt` / `lte` / `in` / `contains`。condition 为空时表示无条件触发。

### 3.9 收件人策略枚举

表：收件人策略说明表

| recipient_strategy | 说明 |
| --- | --- |
| `event_actor` | 事件发起人（如任务创建者），从事件 payload 的 `actor_user_id` 取值 |
| `tenant_admins` | 事件所属租户的全部管理员 |
| `system_admins` | 全局系统管理员 |
| `override` | 使用 `recipient_override` 字段指定的固定收件人列表 |
| `event_actor_and_tenant_admins` | 事件发起人 + 租户管理员 |

### 3.10 模板 variables 结构

```json
[
  { "name": "task_name", "description": "任务名称", "required": true },
  { "name": "task_status", "description": "任务状态", "required": true },
  { "name": "duration", "description": "执行耗时（秒）", "required": false },
  { "name": "error_detail", "description": "错误详情", "required": false }
]
```

### 3.11 quiet_hours 结构

```json
{
  "enabled": true,
  "start": "22:00",
  "end": "08:00",
  "timezone": "Asia/Shanghai",
  "behavior": "delay",
  "weekdays": [1, 2, 3, 4, 5]
}
```

`behavior` 枚举：

- `delay`：延迟至免打扰结束后发送（邮件/Webhook）。
- `skip`：跳过不发送（邮件/Webhook），站内信仍存储。
- `inapp_only`：仅保留站内信，跳过邮件/Webhook。

`weekdays` 为 ISO 周几数组（1=周一 … 7=周日），为空表示每天生效。

### 3.12 ER 关系示意

```text
tenants 1 ──── * notification_channels
tenants 1 ──── * notification_rules ──── * notification_channels (via channel_ids)
tenants 1 ──── * notification_templates
tenants 1 ──── * notifications ──── 1 notification_channels
users   1 ──── 1 notification_preferences
notification_rules 1 ──── * notifications
```

---

## 第4章 API 设计

### 4.1 接口总览

表：API 接口总览表

| 方法 | 路径 | 说明 | 角色 |
| --- | --- | --- | --- |
| POST | /api/v1/notifications/channels | 创建通知渠道 | 管理员 |
| GET | /api/v1/notifications/channels | 列出渠道 | 管理员 |
| GET | /api/v1/notifications/channels/{id} | 查看渠道详情 | 管理员 |
| PUT | /api/v1/notifications/channels/{id} | 更新渠道 | 管理员 |
| DELETE | /api/v1/notifications/channels/{id} | 删除渠道 | 管理员 |
| POST | /api/v1/notifications/channels/{id}/test | 测试渠道连通性 | 管理员 |
| POST | /api/v1/notifications/rules | 创建通知规则 | 管理员 |
| GET | /api/v1/notifications/rules | 列出规则 | 管理员 |
| PUT | /api/v1/notifications/rules/{id} | 更新规则 | 管理员 |
| DELETE | /api/v1/notifications/rules/{id} | 删除规则 | 管理员 |
| POST | /api/v1/notifications/templates | 创建模板 | 管理员 |
| GET | /api/v1/notifications/templates | 列出模板 | 管理员 |
| PUT | /api/v1/notifications/templates/{id} | 更新模板 | 管理员 |
| DELETE | /api/v1/notifications/templates/{id} | 删除模板 | 管理员 |
| GET | /api/v1/notifications | 查看通知历史（分页） | 管理员/用户 |
| GET | /api/v1/notifications/unread | 未读通知（站内信） | 用户 |
| PUT | /api/v1/notifications/{id}/read | 标记单条已读 | 用户 |
| PUT | /api/v1/notifications/read-all | 全部标记已读 | 用户 |
| GET | /api/v1/notifications/preferences | 查看通知偏好 | 用户 |
| PUT | /api/v1/notifications/preferences | 更新通知偏好 | 用户 |
| POST | /api/v1/notifications/events | 发布事件（内部接口） | 系统 |
| WS | /api/v1/notifications/stream | 实时通知推送 | 用户 |

### 4.2 创建通知渠道

```text
POST /api/v1/notifications/channels
```

请求体示例（Email）：

```json
{
  "name": "默认邮件渠道",
  "type": "email",
  "config": {
    "smtp_host": "smtp.example.com",
    "smtp_port": 587,
    "use_tls": true,
    "username": "noreply@example.com",
    "password": "secret-password",
    "from_address": "noreply@example.com",
    "from_name": "MAOP 通知"
  },
  "description": "用于系统告警邮件发送"
}
```

响应示例：

```json
{
  "id": 1,
  "name": "默认邮件渠道",
  "type": "email",
  "config": {
    "smtp_host": "smtp.example.com",
    "smtp_port": 587,
    "use_tls": true,
    "username": "noreply@example.com",
    "from_address": "noreply@example.com",
    "from_name": "MAOP 通知"
  },
  "enabled": true,
  "tenant_id": 101,
  "created_at": "2026-08-13T10:00:00Z"
}
```

说明：请求中的 `password` 为明文，后端加密存储后响应中不再返回密码字段。

### 4.3 列出渠道

```text
GET /api/v1/notifications/channels?type=email&enabled=true&page=1&page_size=20
```

响应示例：

```json
{
  "total": 3,
  "items": [
    {
      "id": 1,
      "name": "默认邮件渠道",
      "type": "email",
      "enabled": true,
      "description": "用于系统告警邮件发送",
      "created_at": "2026-08-13T10:00:00Z"
    }
  ]
}
```

### 4.4 测试渠道

```text
POST /api/v1/notifications/channels/{id}/test
```

请求体：

```json
{
  "test_recipient": "admin@example.com"
}
```

响应示例（成功）：

```json
{
  "success": true,
  "message": "测试通知已发送至 admin@example.com",
  "latency_ms": 320
}
```

响应示例（失败）：

```json
{
  "success": false,
  "message": "SMTP 连接失败",
  "error_code": "smtp_connection_refused",
  "latency_ms": 5020
}
```

### 4.5 创建通知规则

```text
POST /api/v1/notifications/rules
```

请求体示例：

```json
{
  "name": "任务失败邮件告警",
  "event_type": "task_failed",
  "condition": {
    "and": [
      { "field": "severity", "op": "in", "value": ["critical", "high"] }
    ]
  },
  "channel_ids": [1, 3],
  "template_id": 5,
  "recipient_strategy": "event_actor",
  "description": "任务失败时通过邮件与站内信通知任务创建者"
}
```

响应示例：

```json
{
  "id": 10,
  "name": "任务失败邮件告警",
  "event_type": "task_failed",
  "condition": {
    "and": [
      { "field": "severity", "op": "in", "value": ["critical", "high"] }
    ]
  },
  "channel_ids": [1, 3],
  "template_id": 5,
  "recipient_strategy": "event_actor",
  "enabled": true,
  "tenant_id": 101,
  "created_at": "2026-08-13T10:05:00Z"
}
```

### 4.6 创建通知模板

```text
POST /api/v1/notifications/templates
```

请求体示例：

```json
{
  "name": "任务完成通知模板",
  "event_type": "task_completed",
  "subject_template": "【MAOP】任务 {{task_name}} 已完成",
  "body_template": "您的任务 {{task_name}} 已于 {{completed_at}} 执行完成。\n状态：{{task_status}}\n耗时：{{duration}} 秒\n详情请查看：{{task_url}}",
  "variables": [
    { "name": "task_name", "description": "任务名称", "required": true },
    { "name": "completed_at", "description": "完成时间", "required": true },
    { "name": "task_status", "description": "任务状态", "required": true },
    { "name": "duration", "description": "执行耗时（秒）", "required": false },
    { "name": "task_url", "description": "任务详情链接", "required": false }
  ],
  "is_default": true
}
```

### 4.7 查看通知历史

```text
GET /api/v1/notifications?event_type=task_failed&status=failed&page=1&page_size=20&start=2026-08-01&end=2026-08-13
```

响应示例：

```json
{
  "total": 42,
  "items": [
    {
      "id": 5001,
      "event_type": "task_failed",
      "channel_type": "email",
      "channel_name": "默认邮件渠道",
      "recipient": "user@example.com",
      "subject": "【MAOP】任务 data_pipeline 失败",
      "status": "failed",
      "retry_count": 3,
      "error_message": "SMTP connection timeout",
      "created_at": "2026-08-13T09:30:00Z",
      "sent_at": null
    }
  ]
}
```

### 4.8 未读通知

```text
GET /api/v1/notifications/unread?limit=10
```

响应示例：

```json
{
  "unread_count": 5,
  "items": [
    {
      "id": 5010,
      "event_type": "task_completed",
      "subject": "【MAOP】任务 data_pipeline 已完成",
      "body": "您的任务 data_pipeline 已于 2026-08-13 10:00 执行完成。",
      "created_at": "2026-08-13T10:00:00Z",
      "is_read": false
    }
  ]
}
```

### 4.9 标记已读

```text
PUT /api/v1/notifications/{id}/read
```

响应：`200 OK`，返回更新后的通知记录（`is_read: true`, `read_at` 填充）。

### 4.10 全部标记已读

```text
PUT /api/v1/notifications/read-all
```

响应示例：

```json
{
  "updated_count": 5
}
```

### 4.11 通知偏好

```text
GET /api/v1/notifications/preferences
```

响应示例：

```json
{
  "user_id": 200,
  "event_types": ["task_completed", "task_failed", "agent_error", "quota_exceeded"],
  "channels": ["email", "inapp"],
  "quiet_hours": {
    "enabled": true,
    "start": "22:00",
    "end": "08:00",
    "timezone": "Asia/Shanghai",
    "behavior": "delay",
    "weekdays": [1, 2, 3, 4, 5]
  }
}
```

```text
PUT /api/v1/notifications/preferences
```

请求体同响应结构（不含 `user_id`），响应返回更新后的完整偏好。

### 4.12 发布事件（内部接口）

业务模块通过此接口向通知中心发布事件，通知中心异步处理。

```text
POST /api/v1/notifications/events
```

请求体示例：

```json
{
  "event_type": "task_failed",
  "tenant_id": 101,
  "payload": {
    "task_name": "data_pipeline",
    "task_id": "task-abc-123",
    "task_status": "failed",
    "severity": "critical",
    "duration": 120,
    "error_detail": "Connection refused to database",
    "task_url": "https://maop.example.com/tasks/task-abc-123",
    "actor_user_id": 200,
    "completed_at": "2026-08-13T10:00:00Z"
  }
}
```

响应：`202 Accepted`，返回事件接收确认。

```json
{
  "event_id": "evt-xyz-789",
  "accepted": true,
  "message": "事件已接收，将异步处理"
}
```

### 4.13 WebSocket 实时推送

```text
WS /api/v1/notifications/stream
```

连接建立时通过 query 参数传递 JWT：

```text
WS /api/v1/notifications/stream?token=<jwt_token>
```

服务端推送消息格式：

```json
{
  "type": "notification",
  "data": {
    "id": 5010,
    "event_type": "task_completed",
    "subject": "【MAOP】任务 data_pipeline 已完成",
    "body": "您的任务 data_pipeline 已于 2026-08-13 10:00 执行完成。",
    "created_at": "2026-08-13T10:00:00Z"
  }
}
```

心跳消息（每 30 秒）：

```json
{ "type": "ping", "timestamp": "2026-08-13T10:00:30Z" }
```

客户端可发送 `pong` 响应。连接断开后客户端自动重连（指数退避，最大间隔 30 秒）。

### 4.14 错误码

表：错误码说明表

| HTTP | code | 说明 |
| --- | --- | --- |
| 400 | invalid_channel_config | 渠道配置格式错误 |
| 400 | invalid_template_syntax | 模板语法错误（未闭合变量） |
| 400 | invalid_condition | 规则条件表达式错误 |
| 404 | channel_not_found | 渠道不存在 |
| 404 | rule_not_found | 规则不存在 |
| 404 | template_not_found | 模板不存在 |
| 404 | notification_not_found | 通知不存在 |
| 409 | channel_name_conflict | 同租户下渠道名称冲突 |
| 409 | default_template_exists | 该事件类型的默认模板已存在 |
| 422 | smtp_auth_failed | SMTP 认证失败（测试发送时） |
| 422 | webhook_unreachable | Webhook 目标不可达（测试发送时） |

---

## 第5章 UI 设计

### 5.1 通知中心管理页面（/notifications）

布局采用项目既有 `ListPageLayout` 组件，保持左右宽度一致。页面顶部使用 Tab 切换不同管理视图。

#### 5.1.1 页面结构

```text
┌─────────────────────────────────────────────────────────┐
│  通知中心                              [刷新]          │
├─────────────────────────────────────────────────────────┤
│ [通知历史] [渠道管理] [规则管理] [模板管理] [偏好设置]  │
├─────────────────────────────────────────────────────────┤
│                                                          │
│              当前 Tab 内容区                             │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

#### 5.1.2 通知历史 Tab

- 筛选区（`FilterBar`）：事件类型下拉、渠道类型下拉、状态下拉（全部/待发送/已发送/失败/重试中）、时间范围选择器、收件人搜索。
- 数据表格（`DataTable`），列：时间、事件类型、渠道、收件人、标题、状态、操作。
- 状态列样式：`sent` 绿色、`failed` 红色、`pending` 灰色、`retrying` 橙色。
- 操作列：查看详情（`DetailDrawer` 展示完整正文与错误信息）、重试（仅 failed 状态可重试）。
- 分页：底部分页器，默认每页 20 条。

#### 5.1.3 渠道管理 Tab

- 渠道列表表格：名称、类型、状态（启用/禁用切换）、描述、操作。
- 操作：编辑、测试、启用/禁用、删除。
- 新建/编辑渠道对话框：
  - 通用字段：名称、类型（下拉选择 email/webhook/inapp）、描述。
  - 邮件类型表单：SMTP 服务器、端口、是否 TLS、用户名、密码、发件人地址、发件人名称。
  - Webhook 类型表单：URL、HTTP 方法（GET/POST/PUT）、Header（键值对编辑器）、Body 模板（多行文本）、签名密钥、签名类型。
  - 站内信类型表单：保留天数。
- 测试发送对话框：输入测试收件人（邮件）/展示测试结果（Webhook），展示发送结果与延迟。

#### 5.1.4 规则管理 Tab

- 规则列表表格：名称、事件类型、关联渠道（标签展示）、状态、操作。
- 操作：编辑、启用/禁用、删除。
- 新建/编辑规则对话框：
  - 名称、事件类型（下拉，枚举见 3.6）、描述。
  - 条件编辑器：可视化条件构建器，支持添加 AND 条件组，每条选择字段、运算符、值。
  - 目标渠道：多选下拉（关联已启用渠道）。
  - 模板：下拉选择（按事件类型过滤），可选「使用默认模板」。
  - 收件人策略：下拉选择（枚举见 3.9），当选择 `override` 时展示收件人输入框。

#### 5.1.5 模板管理 Tab

- 模板列表表格：名称、事件类型、是否默认、操作。
- 操作：编辑、删除、设为默认。
- 模板编辑器：
  - 通用字段：名称、事件类型、是否默认。
  - 标题模板：单行输入框，支持插入变量（点击变量标签插入 `{{variable}}`）。
  - 正文模板：多行文本域，支持插入变量。
  - 变量定义区：变量列表（名称、描述、是否必填），可增删。
  - 预览区：选择一组示例变量值，实时渲染标题与正文预览。

#### 5.1.6 偏好设置 Tab

- 事件类型 × 渠道矩阵：行为复选框表格，行=事件类型，列=渠道（email/webhook/inapp），勾选表示订阅该事件通过该渠道接收。
- 免打扰时段配置：
  - 启用开关。
  - 开始时间、结束时间（时间选择器）。
  - 时区（下拉，默认取用户 profile 时区）。
  - 生效星期（周一至周日复选）。
  - 行为（下拉：延迟发送/跳过/仅站内信）。
- 保存按钮。

### 5.2 通知铃铛（TopBar 右侧）

在全局顶栏右侧添加通知铃铛图标组件，与现有系统级功能居右对齐。

#### 5.2.1 铃铛图标

- 图标：统一简约风格的小铃铛图标。
- 未读 Badge：右上角红色圆形数字角标，显示未读数量；超过 99 显示 `99+`；为 0 时不显示角标。
- 点击行为：展开下拉通知面板。

#### 5.2.2 下拉通知面板

```text
┌──────────────────────────────┐
│  通知              [全部已读] │
├──────────────────────────────┤
│ ● 任务 data_pipeline 已完成  │
│   2026-08-13 10:00    [任务] │
├──────────────────────────────┤
│ ● Agent agent-01 运行异常    │
│   2026-08-13 09:45    [告警] │
├──────────────────────────────┤
│   ...最近 10 条...           │
├──────────────────────────────┤
│              [查看全部通知]  │
└──────────────────────────────┘
```

- 展示最近 10 条未读/已读通知，未读项左侧有圆点标记。
- 每条通知：标题（单行截断）、时间（相对时间如「5 分钟前」）、事件类型标签。
- 点击单条通知：跳转至对应详情页（如任务详情），同时标记已读。
- 顶部「全部已读」按钮：一键标记全部已读。
- 底部「查看全部通知」链接：跳转至 `/notifications` 通知历史 Tab。
- 面板宽度与顶栏其他下拉面板保持一致。

### 5.3 实时推送

- 前端在用户登录后自动建立 WebSocket 连接（`/api/v1/notifications/stream`）。
- 收到 `notification` 类型消息时：
  - 更新铃铛未读计数。
  - 若铃铛面板已展开，将新通知插入面板顶部。
  - 可选：显示非阻塞 toast 提示（右上角，3 秒自动消失，不遮挡主内容区）。
- 连接断开时自动重连，指数退避（1s / 2s / 4s / 8s / … / 最大 30s），重连成功后拉取断连期间错过的通知。
- 用户登出时主动关闭 WebSocket 连接。

### 5.4 视觉规范

- 采用浅色/白色主题，自然优雅风格，避免深色块。
- 顶栏仅一行，通知铃铛与其他系统级功能居右对齐。
- 提示消息采用非阻塞 toast，不遮挡主内容区。
- 图标统一简约小众风格，同一语义唯一图标（通知铃铛、邮件、Webhook、站内信各有唯一图标）。
- 状态色：成功绿色、失败红色、待处理灰色、重试橙色、告警黄色，全站统一。
- 所有页面左右宽度保持一致。

---

## 第6章 验收标准

表：验收标准表

| 编号 | 验收项 | 验证方式 |
| --- | --- | --- |
| AC-01 | 支持邮件、Webhook、站内信三种通知渠道的创建、编辑、删除、启用/禁用 | 调用渠道 CRUD 接口，数据持久化一致 |
| AC-02 | 通知规则可绑定事件类型与多个渠道，条件表达式正确过滤事件 | 创建规则后发布匹配/不匹配事件，验证通知是否触发 |
| AC-03 | 通知模板支持 `{{variable}}` 变量替换，缺失变量时保留占位符或使用默认值 | 渲染模板传入变量 payload，比对输出 |
| AC-04 | 站内信通过 WebSocket 实时推送至前端，延迟 ≤ 2 秒 | 发布事件后观察铃铛未读数变化时序 |
| AC-05 | 通知铃铛显示未读数量，超过 99 显示 99+，为 0 不显示角标 | 制造不同未读数量验证角标展示 |
| AC-06 | 用户可配置通知偏好（订阅事件类型、启用渠道、免打扰时段），偏好生效 | 配置偏好后发布事件，验证通知按偏好过滤/延迟 |
| AC-07 | 渠道可测试发送，返回发送结果与延迟 | 调用 test 接口，验证邮件送达/Webhook 响应 |
| AC-08 | 通知历史可查看发送状态、错误信息，支持分页与多条件筛选 | 调用列表接口，验证筛选与分页结果 |
| AC-09 | 支持免打扰时段，邮件/Webhook 在免打扰时段按 behavior 策略处理 | 配置免打扰时段后发布事件，验证发送时机 |
| AC-10 | 通知发送异步执行，不阻塞主业务流程 | 发布事件接口返回 202，主流程继续执行 |
| AC-11 | 通知发送失败自动重试 3 次，重试耗尽转入死信队列 | 模拟渠道不可用，观察重试次数与死信记录 |
| AC-12 | SMTP 密码与 Webhook 签名密钥加密存储，API 响应不返回明文 | 查询渠道详情，验证密码字段不返回 |
| AC-13 | 标记单条已读与全部已读功能正常，未读计数同步更新 | 调用 read 接口后查询 unread 接口 |
| AC-14 | WebSocket 断开后自动重连，重连后补拉错过通知 | 模拟网络断开恢复，验证通知不丢失 |
| AC-15 | 事件类型覆盖 task_completed/task_failed/agent_error/quota_exceeded/license_expiring/audit_alert/system_error/dag_completed/dag_failed | 逐一发布各事件类型，验证通知生成 |
| AC-16 | 前端通知中心页面 5 个 Tab 功能完整，与既有 ListPageLayout 风格一致 | 人工验证各 Tab 交互 |
| AC-17 | 多租户隔离：租户 A 的渠道/规则/通知对租户 B 不可见 | 跨租户查询验证返回空或 404 |

---

## 第7章 非功能需求

### 7.1 可靠性

- 通知发送异步执行，事件发布接口立即返回 `202 Accepted`，不阻塞主业务流程。
- 发送失败自动重试，最多 3 次，重试间隔指数退避（10s / 30s / 90s）。
- 重试耗尽的通知转入死信队列（Dead Letter Queue），供管理员手动排查与重发。
- WebSocket 连接断开后客户端自动重连，重连成功后补拉断连期间的未读通知。
- 通知中心服务异常时，业务模块发布事件应 fail-safe：事件写入持久化队列后即返回，不因通知中心不可用导致业务失败。

### 7.2 性能

- 事件发布接口响应时间 ≤ 50ms（仅做事件入队）。
- 通知发送从事件产生到渠道发出 ≤ 5 秒（非免打扰时段）。
- 支持批量发送：同一事件触发多渠道多收件人时，并行分发，单批次上限 100 条。
- 未读通知查询响应时间 ≤ 100ms（索引覆盖）。
- 通知历史分页查询响应时间 ≤ 200ms（复合索引覆盖）。
- WebSocket 单连接内存占用 ≤ 512KB，单实例支持 ≥ 5000 并发连接。

### 7.3 安全

- SMTP 密码、Webhook 签名密钥使用 AES-256 加密存储，密钥由 `crypto_utils.py` 统一管理。
- API 响应中永不返回密码/密钥明文，仅返回脱敏前缀（如 `nore***@exa***.com`）。
- 通知内容脱敏：对包含敏感字段（如 API Key、密码）的 payload 在渲染前脱敏。
- WebSocket 连接需携带有效 JWT，连接建立时校验 token，过期则拒绝并关闭连接。
- 通知偏好与站内信仅用户本人可查看与修改，渠道/规则/模板管理需 `notification:*:manage` 权限。
- 多租户隔离：所有查询自动附加 `tenant_id` 过滤，跨租户访问返回 404。
- 通知操作（渠道配置变更、规则变更、模板变更）写入审计日志（`audit_events` 表）。

### 7.4 扩展性

- 渠道采用插件机制：定义 `BaseChannel` 抽象类，各渠道实现 `send()` 方法，新增渠道仅需实现接口并注册。
- 事件类型可扩展：业务模块通过 `POST /api/v1/notifications/events` 发布自定义事件类型，无需修改通知中心代码。
- 模板引擎可替换：默认使用 `{{variable}}` 简单替换，预留 Jinja2 引擎接入点。
- 预留短信（SMS）渠道扩展点，后续迭代可实现 `SmsChannel`。

---

## 第8章 实现计划

### 8.1 技术栈

表：技术栈说明表

| 层 | 技术 | 说明 |
| --- | --- | --- |
| 后端 | Python + FastAPI | MAOP 既有技术栈 |
| ORM | SQLAlchemy | 既有数据模型层 |
| Schema | Pydantic | 请求/响应校验 |
| 异步任务 | Celery / asyncio | 通知异步发送与重试 |
| 实时推送 | WebSocket（FastAPI 原生支持） | 站内信实时推送 |
| 加密 | crypto_utils.py（AES-256） | 既有加密工具 |
| 数据库迁移 | Alembic | 既有迁移工具 |
| 前端 | Vue 3 + TypeScript（Strict + ESM） | 既有前端技术栈 |
| UI 组件 | ListPageLayout / FilterBar / DetailDrawer / DataTable | 既有可复用布局组件 |
| 状态管理 | Pinia | 既有状态管理 |
| i18n | vue-i18n | 既有国际化方案 |

### 8.2 后端文件清单

表：后端新建文件清单

| 文件路径 | 说明 |
| --- | --- |
| maop/models/notification.py | 5 张表的 SQLAlchemy 模型 |
| maop/schemas/notification.py | Pydantic 请求/响应 Schema |
| maop/api/v1/notifications.py | 通知中心 REST 路由（20 个接口） |
| maop/api/v1/notification_ws.py | WebSocket 实时推送路由 |
| maop/services/notification_service.py | 通知发送业务逻辑（事件处理、模板渲染、渠道分发） |
| maop/services/notification_channel_service.py | 渠道管理业务逻辑 |
| maop/services/notification_rule_service.py | 规则管理业务逻辑 |
| maop/services/notification_template_service.py | 模板管理业务逻辑与变量渲染 |
| maop/services/notification_preference_service.py | 偏好管理与免打扰判定 |
| maop/services/notification_event_bus.py | 事件总线：事件接收、规则匹配、异步分发 |
| maop/notifications/__init__.py | 通知渠道包 |
| maop/notifications/base_channel.py | BaseChannel 抽象基类 |
| maop/notifications/email_channel.py | Email 渠道实现（SMTP） |
| maop/notifications/webhook_channel.py | Webhook 渠道实现（HTTP + 签名） |
| maop/notifications/inapp_channel.py | 站内信渠道实现（DB 存储 + WS 推送） |
| maop/notifications/template_engine.py | 模板变量渲染引擎 |
| maop/notifications/condition_evaluator.py | 规则条件表达式求值器 |
| maop/notifications/ws_manager.py | WebSocket 连接管理器（连接池、广播、心跳） |
| maop/tasks/notification_tasks.py | Celery 异步任务：发送、重试、死信处理 |
| migrations/versions/xxxx_add_notifications.py | 数据库迁移：5 张通知表 |

表：后端修改文件清单

| 文件路径 | 修改内容 |
| --- | --- |
| maop/main.py | 注册 notifications 路由与 WebSocket 路由，启动事件总线 |
| maop/core/auth.py | 新增 `notification:*:manage` 权限校验 |
| maop/config.py | 新增通知相关配置项（重试次数、重试间隔、死信队列大小等） |
| maop/tasks/scheduler.py | 注册通知重试扫描定时任务、站内信清理定时任务 |

### 8.3 前端文件清单

表：前端新建文件清单

| 文件路径 | 说明 |
| --- | --- |
| src/views/notifications/NotificationCenter.vue | 通知中心主页面（Tab 容器） |
| src/views/notifications/tabs/NotificationHistory.vue | 通知历史 Tab |
| src/views/notifications/tabs/ChannelManagement.vue | 渠道管理 Tab |
| src/views/notifications/tabs/RuleManagement.vue | 规则管理 Tab |
| src/views/notifications/tabs/TemplateManagement.vue | 模板管理 Tab |
| src/views/notifications/tabs/PreferenceSettings.vue | 偏好设置 Tab |
| src/views/notifications/components/ChannelEditDialog.vue | 渠道新建/编辑对话框 |
| src/views/notifications/components/ChannelTestDialog.vue | 渠道测试发送对话框 |
| src/views/notifications/components/RuleEditDialog.vue | 规则新建/编辑对话框 |
| src/views/notifications/components/ConditionEditor.vue | 可视化条件构建器 |
| src/views/notifications/components/TemplateEditor.vue | 模板编辑器（含变量插入与预览） |
| src/views/notifications/components/PreferenceMatrix.vue | 事件类型 × 渠道矩阵组件 |
| src/views/notifications/components/QuietHoursEditor.vue | 免打扰时段编辑器 |
| src/views/notifications/components/NotificationDetailDrawer.vue | 通知详情面板（DetailDrawer） |
| src/components/notification/NotificationBell.vue | 通知铃铛组件（TopBar 右侧） |
| src/components/notification/NotificationPanel.vue | 铃铛下拉通知面板 |
| src/composables/useNotificationWebSocket.ts | WebSocket 连接 composable（连接、重连、消息处理） |
| src/composables/useNotifications.ts | 通知状态 composable（未读数、列表、已读操作） |
| src/stores/notification.ts | Pinia 通知 store（未读数、实时通知队列） |
| src/api/notifications.ts | 通知中心 API 封装 |

表：前端修改文件清单

| 文件路径 | 修改内容 |
| --- | --- |
| src/router/index.ts | 新增 /notifications 路由 |
| src/layouts/default/components/TopBar.vue | 在顶栏右侧添加 NotificationBell 组件 |
| src/locales/zh-CN.json | 新增通知中心中文文案 |
| src/locales/en-US.json | 新增通知中心英文文案 |

### 8.4 测试文件清单

表：测试文件清单

| 文件路径 | 说明 |
| --- | --- |
| tests/unit/test_notification_channel_service.py | 渠道管理单元测试 |
| tests/unit/test_notification_rule_service.py | 规则管理单元测试 |
| tests/unit/test_notification_template_service.py | 模板渲染单元测试 |
| tests/unit/test_notification_preference_service.py | 偏好管理与免打扰判定单元测试 |
| tests/unit/test_condition_evaluator.py | 条件表达式求值单元测试 |
| tests/unit/test_template_engine.py | 模板变量渲染单元测试 |
| tests/unit/test_email_channel.py | Email 渠道发送单元测试（mock SMTP） |
| tests/unit/test_webhook_channel.py | Webhook 渠道发送单元测试（mock HTTP） |
| tests/unit/test_inapp_channel.py | 站内信渠道单元测试 |
| tests/integration/test_notification_api.py | 通知中心 API 集成测试 |
| tests/integration/test_notification_event_flow.py | 事件发布到通知送达端到端测试 |
| tests/integration/test_notification_ws.py | WebSocket 推送集成测试 |
| frontend/tests/unit/NotificationBell.spec.ts | 铃铛组件单元测试 |
| frontend/tests/unit/NotificationCenter.spec.ts | 通知中心页面单元测试 |

### 8.5 实施阶段

表：实施阶段计划表

| 阶段 | 内容 | 交付物 |
| --- | --- | --- |
| Phase 1 | 数据模型 + 迁移 + 基础 Service | 5 张表建表迁移、CRUD Service |
| Phase 2 | 渠道抽象与实现（Email/Webhook/InApp） | 三种渠道可发送通知 |
| Phase 3 | 事件总线 + 规则匹配 + 模板渲染 | 事件发布后自动触发通知 |
| Phase 4 | REST API 全量接口 | 20 个接口可用 |
| Phase 5 | WebSocket 推送 + 连接管理 | 站内信实时推送 |
| Phase 6 | 异步任务 + 重试 + 死信队列 | 通知可靠发送 |
| Phase 7 | 前端通知中心页面（5 Tab） | 管理页面可用 |
| Phase 8 | 前端通知铃铛 + WebSocket 客户端 | 实时通知体验 |
| Phase 9 | 通知偏好 + 免打扰 | 用户级偏好生效 |
| Phase 10 | 单元测试 + 集成测试 | 测试覆盖率达标 |

### 8.6 配置项

maop 配置新增项（`config.py` / `application.yml`）：

```yaml
notification:
  max_retry: 3
  retry_intervals: [10, 30, 90]        # 重试间隔（秒）
  batch_size: 100                       # 单批次最大发送数
  dead_letter_retention_days: 30        # 死信保留天数
  inapp_retention_days: 90              # 站内信保留天数
  ws_heartbeat_interval: 30             # WebSocket 心跳间隔（秒）
  ws_max_connections: 5000              # 单实例最大 WS 连接数
  ws_reconnect_max_interval: 30         # 客户端重连最大间隔（秒）
  template_missing_var_behavior: keep   # 模板变量缺失行为：keep(保留占位符) / empty(置空) / default(使用默认值)
  event_queue_max_size: 10000           # 事件队列最大容量
```

---

## 第9章 风险与依赖

### 9.1 风险

表：风险与应对表

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| SMTP 服务器不可达导致邮件通知失败 | 告警无法送达 | 重试机制 + 死信队列 + 站内信兜底 |
| Webhook 目标服务限流 | 通知发送延迟 | 批量发送 + 限流退避 + 渠道级速率配置 |
| WebSocket 连接资源占用过高 | 服务端内存压力 | 连接数上限 + 心跳检测 + 空闲连接清理 |
| 事件洪峰（如批量任务同时完成） | 通知队列堆积 | 事件队列限流 + 批量合并 + 通知去重 |
| 模板变量注入风险 | 通知内容异常 | 变量白名单校验 + 模板沙箱渲染 |

### 9.2 依赖

- 依赖既有 `tenants` 表与多租户中间件（`tenant_id` 自动注入）。
- 依赖既有 `users` 表获取用户邮箱与角色信息。
- 依赖既有 `crypto_utils.py` 进行密码/密钥加密。
- 依赖既有 `audit_events` 表记录通知配置变更审计日志。
- 依赖既有 `maop/core/auth.py` 进行权限校验。
- 配额超限通知（`quota_exceeded`）依赖配额管控模块（`prd-tenant-quota.md`）发布事件。
- License 过期通知（`license_expiring`）依赖 License 管理模块（`prd-license-management.md`）发布事件。
- 审计告警通知（`audit_alert`）依赖审计增强模块（`prd-audit-enhancement.md`）发布事件。

---

## 第10章 附录

### 10.1 事件 payload 规范

各业务模块发布事件时，payload 应包含以下通用字段：

表：事件 payload 通用字段表

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `actor_user_id` | INTEGER | 事件发起人 ID（用于收件人策略） |
| `severity` | STRING | 严重级别：info / warning / high / critical |
| `tenant_id` | INTEGER | 事件所属租户 |

各事件类型特有字段由来源模块自行定义，通知模板通过 `{{field}}` 引用。

### 10.2 各事件类型 payload 示例

#### 10.2.1 task_completed / task_failed

```json
{
  "task_id": "task-abc-123",
  "task_name": "data_pipeline",
  "task_status": "completed",
  "severity": "info",
  "duration": 120,
  "completed_at": "2026-08-13T10:00:00Z",
  "task_url": "https://maop.example.com/tasks/task-abc-123",
  "actor_user_id": 200
}
```

#### 10.2.2 agent_error

```json
{
  "agent_id": "agent-01",
  "agent_name": "data-collector",
  "error_code": "OOM_KILLED",
  "error_detail": "Agent process killed due to out of memory",
  "severity": "high",
  "occurred_at": "2026-08-13T09:45:00Z",
  "actor_user_id": 200
}
```

#### 10.2.3 quota_exceeded

```json
{
  "tenant_id": 101,
  "quota_type": "cpu",
  "current_value": 33,
  "limit_value": 32,
  "usage_rate": 1.03,
  "severity": "critical",
  "message": "租户 CPU 使用率 103% 已超过硬限制"
}
```

#### 10.2.4 license_expiring

```json
{
  "license_key": "MAOP-XXXX-XXXX-XXXX-XXXX",
  "tenant_id": 101,
  "expires_at": "2026-08-20T23:59:59Z",
  "remaining_days": 7,
  "severity": "warning",
  "message": "License 将于 7 天后过期"
}
```

### 10.3 渠道插件扩展指南

新增自定义渠道（如 SMS）步骤：

1. 在 `maop/notifications/` 下新建 `sms_channel.py`，继承 `BaseChannel`。
2. 实现 `send(recipient, subject, body, config) -> SendResult` 方法。
3. 实现 `validate_config(config) -> bool` 方法。
4. 在 `maop/notifications/__init__.py` 中注册渠道类型。
5. 在前端渠道类型下拉中新增选项与对应配置表单。
6. 数据库 `notification_channels.type` 字段为 VARCHAR，无需迁移。

`BaseChannel` 抽象接口定义：

```python
from abc import ABC, abstractmethod
from dataclasses import dataclass

@dataclass
class SendResult:
    success: bool
    message: str
    latency_ms: int
    error_code: str | None = None

class BaseChannel(ABC):
    @abstractmethod
    async def send(
        self,
        recipient: str,
        subject: str,
        body: str,
        config: dict,
    ) -> SendResult:
        ...

    @abstractmethod
    def validate_config(self, config: dict) -> bool:
        ...

    @abstractmethod
    def mask_config(self, config: dict) -> dict:
        """返回脱敏后的配置，用于 API 响应。"""
        ...
```