# 审计日志增强 PRD

> 文档版本：v1.0
> 创建日期：2026-08-13
> 状态：Draft
> 负责人：Coding Engineer

---

## 第1章 功能概述

### 1.1 功能目标

增强 MAOP（Multi Agents Orchestration Platform）现有审计日志系统，在原有日志记录能力之上，提供可视化分析、高级过滤、批量导出、告警规则配置与实时监控能力，使平台满足等保 2.0 三级审计要求并具备安全运营闭环能力。

### 1.2 背景

MAOP 已有后端 `audit.py` 与前端 `Audit.vue` 两个审计相关模块，但当前功能较为基础，仅支持简单的日志写入与列表查看，缺少以下高级能力：

- 缺少可视化图表，无法直观了解系统活动趋势与异常分布；
- 缺少多条件组合过滤，难以快速定位特定操作；
- 缺少批量导出能力，无法满足合规审计交付需求；
- 缺少告警规则机制，异常操作无法自动通知；
- 缺少实时监控，关键操作无法及时感知。

本次增强在保留现有审计日志追加写入、不可篡改特性的前提下，补齐上述能力。

### 1.3 范围

| 范围维度 | 说明 |
|---------|------|
| 日志可视化图表 | 时间线趋势图、用户×操作热力图、操作分布饼图、顶部统计卡片 |
| 高级过滤 | 时间范围、用户、操作类型、资源、风险等级、关键词多条件组合过滤 |
| 批量导出 | 支持 CSV、JSON 两种格式导出，受 admin 权限控制 |
| 告警规则 | 条件配置（操作类型/用户/资源/风险等级/频率阈值）+ 动作配置（邮件/Webhook/站内信） |
| 实时监控 | 通过 WebSocket 推送关键操作事件至监控面板 |

### 1.4 非目标

- 不重构现有 `audit_events` 表的写入链路，保持追加写入语义不变；
- 不引入新的日志采集 Agent，仅增强平台自身审计日志；
- 不涉及日志的长期归档与冷存储迁移（后续迭代）。

---

## 第2章 用户故事

### 2.1 用户故事列表

| 编号 | 角色 | 故事 | 价值 |
|------|------|------|------|
| US-01 | 管理员 | 我想查看操作审计日志的可视化图表 | 以便快速了解系统活动趋势 |
| US-02 | 管理员 | 我想按多条件过滤审计日志 | 以便查找特定操作 |
| US-03 | 管理员 | 我想导出审计日志 | 以便合规审计 |
| US-04 | 管理员 | 我想配置告警规则 | 以便异常操作时自动通知 |
| US-05 | 安全官 | 我想查看高风险操作记录 | 以便安全审计 |
| US-06 | 管理员 | 我想实时监控关键操作 | 以便及时发现异常 |

### 2.2 用户故事详述

#### 2.2.1 US-01 查看可视化图表

- **前置条件**：管理员已登录，系统已产生审计事件。
- **主流程**：进入 `/audit` 页面，查看顶部统计卡片、时间线图表、热力图、操作分布饼图。
- **验收**：图表数据与底层审计事件一致，支持按时间范围切换（今日/近 7 天/近 30 天）。

#### 2.2.2 US-02 多条件过滤

- **前置条件**：管理员已登录。
- **主流程**：在过滤栏选择时间范围、用户、操作类型、资源、风险等级，输入关键词，点击查询。
- **验收**：表格仅展示满足全部条件的事件，支持分页。

#### 2.2.3 US-03 导出审计日志

- **前置条件**：管理员具备 admin 权限。
- **主流程**：设置过滤条件，点击导出按钮，选择 CSV 或 JSON 格式。
- **验收**：下载文件内容与当前过滤结果一致，文件包含完整字段。

#### 2.2.4 US-04 配置告警规则

- **前置条件**：管理员已登录。
- **主流程**：进入 `/audit/alerts`，创建规则，配置条件与动作，启用规则。
- **验收**：当审计事件命中规则条件时，执行配置的动作并记录告警历史。

#### 2.2.5 US-05 查看高风险操作

- **前置条件**：安全官已登录。
- **主流程**：在过滤栏将风险等级设为"高"，查看高风险操作列表。
- **验收**：列表仅展示 risk_level 为 high 的事件，支持进一步过滤。

#### 2.2.6 US-06 实时监控

- **前置条件**：管理员已登录，浏览器建立 WebSocket 连接。
- **主流程**：打开实时监控面板，关键操作事件实时推送至面板。
- **验收**：事件从产生到展示延迟低于 2 秒，连接断开后自动重连。

---

## 第3章 数据模型设计

### 3.1 现有表扩展：audit_events

在现有 `audit_events` 表基础上新增三个字段，不改动原有字段与写入语义。

| 字段名 | 类型 | 说明 | 是否索引 |
|--------|------|------|----------|
| risk_level | VARCHAR(16) | 风险等级，枚举值：low / medium / high / critical，默认 low | 是 |
| category | VARCHAR(64) | 操作分类，如 auth / config / data / system，默认 system | 是 |
| tags | JSON | 自定义标签数组，用于灵活标注，如 ["sensitive", "batch_delete"] | 否 |

SQL 示例：audit_events 表扩展字段

```sql
ALTER TABLE audit_events
    ADD COLUMN risk_level VARCHAR(16) NOT NULL DEFAULT 'low',
    ADD COLUMN category   VARCHAR(64) NOT NULL DEFAULT 'system',
    ADD COLUMN tags       JSON NULL;

CREATE INDEX idx_audit_events_risk_level ON audit_events (risk_level);
CREATE INDEX idx_audit_events_category   ON audit_events (category);
CREATE INDEX idx_audit_events_created_at ON audit_events (created_at);
```

### 3.2 新增表：audit_alert_rules

告警规则定义表，存储条件与动作配置。

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | BIGINT PK | 主键，自增 |
| name | VARCHAR(128) | 规则名称，唯一 |
| description | VARCHAR(512) | 规则描述 |
| condition | JSON | 告警条件，结构见 3.5 |
| action | JSON | 告警动作，结构见 3.6 |
| enabled | BOOLEAN | 是否启用，默认 true |
| created_by | VARCHAR(64) | 创建人 |
| created_at | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |

SQL 示例：audit_alert_rules 建表

```sql
CREATE TABLE audit_alert_rules (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(128) NOT NULL UNIQUE,
    description VARCHAR(512) NULL,
    condition   JSON NOT NULL,
    action      JSON NOT NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    created_by  VARCHAR(64) NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
```

### 3.3 新增表：audit_alert_history

告警触发历史表，记录每次告警的触发上下文与动作执行结果。

| 字段名 | 类型 | 说明 |
|--------|------|------|
| id | BIGINT PK | 主键，自增 |
| rule_id | BIGINT FK | 关联 audit_alert_rules.id |
| triggered_at | DATETIME | 触发时间 |
| event_id | BIGINT FK | 触发该告警的审计事件 id |
| action_result | JSON | 动作执行结果，如 {"email": "success", "webhook": "timeout"} |

SQL 示例：audit_alert_history 建表

```sql
CREATE TABLE audit_alert_history (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_id       BIGINT NOT NULL,
    triggered_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    event_id      BIGINT NOT NULL,
    action_result JSON NULL,
    FOREIGN KEY (rule_id)  REFERENCES audit_alert_rules (id) ON DELETE CASCADE,
    FOREIGN KEY (event_id) REFERENCES audit_events (id)    ON DELETE CASCADE
);

CREATE INDEX idx_alert_history_rule_id      ON audit_alert_history (rule_id);
CREATE INDEX idx_alert_history_triggered_at ON audit_alert_history (triggered_at);
```

### 3.4 索引策略

为支持百万级数据下的分页与过滤查询，在 `audit_events` 上建立以下复合索引：

| 索引名 | 字段组合 | 适用场景 |
|--------|----------|----------|
| idx_audit_created_user | (created_at, user_id) | 按时间+用户过滤 |
| idx_audit_created_op | (created_at, operation) | 按时间+操作类型过滤 |
| idx_audit_created_risk | (created_at, risk_level) | 按时间+风险等级过滤 |
| idx_audit_created_category | (created_at, category) | 按时间+分类过滤 |

### 3.5 告警条件结构（condition JSON）

```json
{
  "operation": ["delete", "config_update"],
  "user_id": ["u_001", "u_002"],
  "resource": ["system_setting", "user_role"],
  "risk_level": ["high", "critical"],
  "frequency": {
    "threshold": 10,
    "window_seconds": 300
  }
}
```

| 条件字段 | 类型 | 说明 |
|----------|------|------|
| operation | string[] | 命中的操作类型列表，空表示不限 |
| user_id | string[] | 命中的用户列表，空表示不限 |
| resource | string[] | 命中的资源列表，空表示不限 |
| risk_level | string[] | 命中的风险等级列表，空表示不限 |
| frequency.threshold | int | 频率阈值，窗口期内达到该值触发 |
| frequency.window_seconds | int | 频率统计窗口（秒） |

### 3.6 告警动作结构（action JSON）

```json
{
  "notify_email": {
    "enabled": true,
    "recipients": ["admin@example.com", "sec@example.com"]
  },
  "notify_webhook": {
    "enabled": true,
    "url": "https://hooks.example.com/audit-alert",
    "timeout_seconds": 10
  },
  "notify_inapp": {
    "enabled": true,
    "recipients": ["u_admin", "u_sec_officer"]
  }
}
```

| 动作字段 | 说明 |
|----------|------|
| notify_email | 邮件通知，指定收件人列表 |
| notify_webhook | Webhook 回调，POST 触发事件详情至指定 URL |
| notify_inapp | 站内信，向指定用户发送平台内通知 |

---

## 第4章 API 设计

### 4.1 接口总览

所有接口前缀 `/api/v1/audit`，需认证且具备 admin 权限（导出接口）或审计查看权限。

| 方法 | 路径 | 功能 | 章节 |
|------|------|------|------|
| GET | /events | 查询审计事件（高级过滤+分页） | 4.2 |
| GET | /events/export | 导出审计日志（CSV/JSON） | 4.3 |
| GET | /stats | 审计统计（按时间/用户/操作/资源分组） | 4.4 |
| GET | /stats/timeline | 时间线统计（按小时/天/周聚合） | 4.5 |
| GET | /stats/heatmap | 热力图数据（用户×操作矩阵） | 4.6 |
| POST | /alert-rules | 创建告警规则 | 4.7 |
| GET | /alert-rules | 列出告警规则 | 4.8 |
| PUT | /alert-rules/{id} | 更新告警规则 | 4.9 |
| DELETE | /alert-rules/{id} | 删除告警规则 | 4.10 |
| GET | /alert-history | 告警历史 | 4.11 |

### 4.2 GET /events — 查询审计事件

**查询参数**

| 参数 | 类型 | 说明 |
|------|------|------|
| start_time | datetime | 起始时间（ISO 8601） |
| end_time | datetime | 结束时间（ISO 8601） |
| user_id | string | 用户 ID，逗号分隔多值 |
| operation | string | 操作类型，逗号分隔多值 |
| resource | string | 资源，逗号分隔多值 |
| risk_level | string | 风险等级，逗号分隔多值 |
| category | string | 分类，逗号分隔多值 |
| keyword | string | 关键词，模糊匹配详情字段 |
| page | int | 页码，默认 1 |
| page_size | int | 每页条数，默认 20，最大 200 |
| sort | string | 排序字段，默认 -created_at |

**响应示例**

```json
{
  "total": 15234,
  "page": 1,
  "page_size": 20,
  "items": [
    {
      "id": 90001,
      "user_id": "u_001",
      "user_name": "alice",
      "operation": "config_update",
      "resource": "system_setting",
      "risk_level": "high",
      "category": "config",
      "tags": ["sensitive"],
      "detail": "更新了 SMTP 配置",
      "ip": "10.0.0.12",
      "created_at": "2026-08-13T10:23:45Z"
    }
  ]
}
```

### 4.3 GET /events/export — 导出审计日志

**查询参数**：与 4.2 过滤参数一致，另增：

| 参数 | 类型 | 说明 |
|------|------|------|
| format | string | 导出格式，csv 或 json，默认 csv |

**响应**

- `format=csv`：返回 `Content-Type: text/csv`，`Content-Disposition: attachment; filename=audit_events_YYYYMMDD.csv`，首行为字段标题。
- `format=json`：返回 `Content-Type: application/json`，同 4.2 items 结构，不分页，返回全部过滤结果。

**权限**：需 admin 权限，否则返回 403。

### 4.4 GET /stats — 审计统计

**查询参数**

| 参数 | 类型 | 说明 |
|------|------|------|
| start_time | datetime | 起始时间 |
| end_time | datetime | 结束时间 |
| group_by | string | 分组维度：time / user / operation / resource |

**响应示例**

```json
{
  "group_by": "operation",
  "groups": [
    {"key": "login", "count": 3200},
    {"key": "config_update", "count": 450},
    {"key": "delete", "count": 88}
  ]
}
```

### 4.5 GET /stats/timeline — 时间线统计

**查询参数**

| 参数 | 类型 | 说明 |
|------|------|------|
| start_time | datetime | 起始时间 |
| end_time | datetime | 结束时间 |
| granularity | string | 聚合粒度：hour / day / week，默认 day |

**响应示例**

```json
{
  "granularity": "day",
  "points": [
    {"timestamp": "2026-08-07T00:00:00Z", "count": 1200},
    {"timestamp": "2026-08-08T00:00:00Z", "count": 1350},
    {"timestamp": "2026-08-09T00:00:00Z", "count": 980}
  ]
}
```

### 4.6 GET /stats/heatmap — 热力图数据

**查询参数**

| 参数 | 类型 | 说明 |
|------|------|------|
| start_time | datetime | 起始时间 |
| end_time | datetime | 结束时间 |
| top_users | int | 取活跃用户数，默认 20 |

**响应示例**

```json
{
  "users": ["alice", "bob", "carol"],
  "operations": ["login", "config_update", "delete"],
  "matrix": [
    [120, 5, 0],
    [98, 12, 2],
    [76, 0, 0]
  ]
}
```

`matrix[i][j]` 表示第 i 个用户执行第 j 个操作的次数。

### 4.7 POST /alert-rules — 创建告警规则

**请求体**

```json
{
  "name": "高频删除告警",
  "description": "5 分钟内删除操作超过 10 次告警",
  "condition": {
    "operation": ["delete"],
    "risk_level": ["high", "critical"],
    "frequency": {"threshold": 10, "window_seconds": 300}
  },
  "action": {
    "notify_email": {"enabled": true, "recipients": ["admin@example.com"]},
    "notify_inapp": {"enabled": true, "recipients": ["u_admin"]}
  },
  "enabled": true
}
```

**响应**：返回创建后的完整规则对象（含 id、created_at）。

### 4.8 GET /alert-rules — 列出告警规则

**查询参数**

| 参数 | 类型 | 说明 |
|------|------|------|
| enabled | boolean | 按启用状态过滤 |
| page | int | 页码 |
| page_size | int | 每页条数 |

**响应**：分页规则列表。

### 4.9 PUT /alert-rules/{id} — 更新告警规则

**请求体**：与 4.7 创建体一致，所有字段可选更新。返回更新后的完整规则对象。

### 4.10 DELETE /alert-rules/{id} — 删除告警规则

**响应**：204 No Content。删除规则会级联删除其告警历史。

### 4.11 GET /alert-history — 告警历史

**查询参数**

| 参数 | 类型 | 说明 |
|------|------|------|
| rule_id | int | 按规则 ID 过滤 |
| start_time | datetime | 起始时间 |
| end_time | datetime | 结束时间 |
| page | int | 页码 |
| page_size | int | 每页条数 |

**响应示例**

```json
{
  "total": 42,
  "page": 1,
  "page_size": 20,
  "items": [
    {
      "id": 5001,
      "rule_id": 12,
      "rule_name": "高频删除告警",
      "triggered_at": "2026-08-13T09:15:00Z",
      "event_id": 90001,
      "action_result": {
        "notify_email": "success",
        "notify_inapp": "success"
      }
    }
  ]
}
```

### 4.12 WebSocket 实时推送

**连接**：`ws://host/api/v1/audit/ws/monitor`

**认证**：连接时通过 query 参数 `?token=<jwt>` 携带认证令牌。

**推送消息示例**

```json
{
  "type": "audit_event",
  "event": {
    "id": 90002,
    "user_name": "bob",
    "operation": "delete",
    "resource": "user_role",
    "risk_level": "critical",
    "created_at": "2026-08-13T10:30:00Z"
  }
}
```

仅推送 risk_level 为 high 或 critical 的关键操作事件。

---

## 第5章 UI 设计

### 5.1 审计日志页面增强（/audit）

页面整体采用上下分区布局，顶部为统计概览区，中部为图表区，下部为过滤+表格区，左右宽度与平台其他页面保持一致。

#### 5.1.1 顶部统计卡片

横向排列四张统计卡片，展示当前时间范围（默认今日）的概览指标。

| 卡片 | 指标 | 说明 |
|------|------|------|
| 今日操作数 | 当日审计事件总数 | 支持点击下钻至当日列表 |
| 高风险操作数 | risk_level 为 high/critical 的事件数 | 红色高亮 |
| 活跃用户数 | 当日产生审计事件的不同用户数 | — |
| 异常事件数 | 命中告警规则的事件数 | 橙色高亮 |

#### 5.1.2 时间线图表

- 图表类型：折线图 / 柱状图可切换。
- 数据来源：`GET /stats/timeline`。
- 交互：支持粒度切换（小时/天/周），鼠标悬停显示具体数值。
- 用途：展示操作趋势，快速识别异常峰值。

#### 5.1.3 热力图

- 图表类型：用户×操作类型矩阵热力图。
- 数据来源：`GET /stats/heatmap`。
- 交互：颜色深浅表示频次，单元格悬停显示用户、操作、次数。
- 用途：识别高频操作用户与异常操作组合。

#### 5.1.4 操作分布饼图

- 图表类型：饼图。
- 数据来源：`GET /stats?group_by=operation`。
- 交互：图例可点击筛选对应操作类型。
- 用途：展示按操作类型分组的占比。

#### 5.1.5 高级过滤栏

过滤栏位于表格上方，支持以下条件组合：

| 过滤项 | 控件类型 | 说明 |
|--------|----------|------|
| 时间范围 | 日期范围选择器 | 支持快捷选项：今日/近 7 天/近 30 天/自定义 |
| 用户 | 多选下拉 | 搜索+多选 |
| 操作类型 | 多选下拉 | 预置操作类型枚举 |
| 资源 | 多选下拉 | 搜索+多选 |
| 风险等级 | 多选下拉 | low/medium/high/critical |
| 关键词 | 文本输入 | 模糊匹配详情字段 |

点击"查询"触发 `GET /events`，点击"重置"清空所有条件。

#### 5.1.6 数据表格

- 列：时间、用户、操作类型、资源、风险等级、分类、详情、IP。
- 风险等级列以标签颜色区分（low 灰、medium 黄、high 橙、critical 红）。
- 支持服务端分页、排序。
- 行点击展开详情抽屉，展示完整字段与 tags。

#### 5.1.7 导出按钮

- 位于过滤栏右侧，提供 CSV、JSON 两个下拉选项。
- 点击后携带当前过滤条件调用 `GET /events/export`。
- 导出过程展示 loading 状态，完成后触发文件下载。

### 5.2 告警规则管理（/audit/alerts）

#### 5.2.1 规则列表

- 表格列：规则名称、描述、启用状态（开关）、创建人、创建时间、操作（编辑/删除）。
- 顶部"新建规则"按钮。
- 启用状态开关直接调用 `PUT /alert-rules/{id}` 更新 enabled 字段。

#### 5.2.2 创建/编辑规则对话框

- 基本信息：规则名称、描述。
- 条件配置：操作类型多选、用户多选、资源多选、风险等级多选、频率阈值+窗口。
- 动作配置：邮件通知（开关+收件人）、Webhook（开关+URL+超时）、站内信（开关+收件人）。
- 表单校验：规则名称必填且唯一，频率阈值大于 0，窗口大于 0。
- 保存调用 `POST /alert-rules` 或 `PUT /alert-rules/{id}`。

#### 5.2.3 告警历史查看

- 规则列表页提供"告警历史"入口（Tab 切换）。
- 历史表格列：触发时间、规则名称、触发事件 ID、动作执行结果。
- 支持按规则 ID、时间范围过滤，分页展示。

### 5.3 实时监控面板

- 入口：`/audit` 页面右上角"实时监控"按钮，点击弹出抽屉面板。
- 面板建立 WebSocket 连接，实时展示推送的关键操作事件。
- 事件流式追加，最新事件置顶，单条展示用户、操作、资源、风险等级、时间。
- 连接状态指示：已连接（绿点）/重连中（黄点）/已断开（红点）。
- 断开后自动重连，指数退避（1s/2s/4s/8s，上限 30s）。

---

## 第6章 验收标准

| 编号 | 验收项 | 验证方式 |
|------|--------|----------|
| AC-01 | 支持多条件组合过滤审计日志 | 设置时间+用户+操作+风险等级+关键词，验证结果仅含匹配事件 |
| AC-02 | 支持按 CSV/JSON 格式导出 | 分别导出两种格式，验证文件内容与过滤结果一致、字段完整 |
| AC-03 | 提供可视化图表（时间线、热力图、饼图） | 三类图表正常渲染，数据与统计接口一致，交互响应正确 |
| AC-04 | 告警规则可配置条件和动作 | 创建含频率阈值的规则，验证 condition 与 action 持久化正确 |
| AC-05 | 告警触发时执行配置的动作 | 制造命中条件的事件，验证邮件/Webhook/站内信动作执行且写入 audit_alert_history |
| AC-06 | 实时监控通过 WebSocket 推送 | 产生 high/critical 事件，验证面板 2 秒内收到推送 |
| AC-07 | 审计日志不可篡改（追加写入） | 尝试 UPDATE/DELETE audit_events 行，验证被数据库层拒绝或记录防篡改日志 |
| AC-08 | 导出需 admin 权限 | 非 admin 用户调用导出接口，验证返回 403 |
| AC-09 | 顶部统计卡片数据准确 | 卡片数值与对应统计查询结果一致 |
| AC-10 | 告警历史可追溯 | 查看告警历史，验证每条记录关联规则与触发事件正确 |

---

## 第7章 非功能需求

### 7.1 性能

| 指标 | 要求 |
|------|------|
| 查询响应时间 | 单次过滤查询（百万级数据）P95 < 500ms，依赖复合索引与分页 |
| 统计接口响应 | 时间线/热力图/分组统计 P95 < 1s，按粒度预聚合或缓存 |
| 导出性能 | 10 万条以内导出 < 5s，采用流式写入避免内存溢出 |
| WebSocket 推送延迟 | 事件产生到客户端展示 < 2s |
| 告警规则匹配 | 单事件匹配全部启用规则 < 50ms |

### 7.2 安全

| 要求 | 说明 |
|------|------|
| 权限控制 | 审计查看需 audit:read 权限，导出需 admin 权限，告警规则管理需 audit:rule:manage 权限 |
| 日志只读 | audit_events 表仅允许 INSERT，禁止 UPDATE/DELETE（数据库层 GRANT 限制） |
| 导出审计 | 导出操作本身产生审计事件，记录导出人、范围、格式 |
| 传输加密 | WebSocket 使用 wss，API 使用 HTTPS |
| 令牌校验 | WebSocket 连接校验 JWT 有效性与权限 |

### 7.3 合规

- 满足等保 2.0 三级审计要求：
  - 审计记录覆盖重要用户行为与重要系统操作；
  - 审计记录包含日期、时间、用户、事件类型、事件是否成功等信息；
  - 审计记录保留期限不少于 6 个月（由运维配置，本 PRD 不限定存储策略）；
  - 审计记录不可篡改、不可删除（追加写入 + 权限限制）；
  - 审计进程异常时告警（通过告警规则覆盖）。

### 7.4 可用性

| 要求 | 说明 |
|------|------|
| WebSocket 重连 | 断开后自动重连，指数退避，上限 30s |
| 图表降级 | 数据量过大时图表支持采样展示，避免前端卡顿 |
| 导出限流 | 单用户导出频率限制（默认 1 次/分钟），防止滥用 |

---

## 第8章 实现计划

### 8.1 文件清单总览

基于 MAOP 现有结构（后端 Python `audit.py` + 前端 Vue `Audit.vue`），本次增强涉及的文件分为后端、前端、数据库迁移三类。

#### 8.1.1 后端文件（Python / maop 包）

| 操作 | 文件路径 | 说明 |
|------|----------|------|
| 修改 | maop/audit/audit.py | 扩展事件写入，补充 risk_level/category/tags 字段；新增查询、统计、导出逻辑 |
| 新建 | maop/audit/routes.py | 审计 API 路由（events/stats/export），从 audit.py 拆分 |
| 新建 | maop/audit/stats.py | 统计聚合逻辑（timeline/heatmap/group_by） |
| 新建 | maop/audit/exporter.py | CSV/JSON 流式导出 |
| 新建 | maop/alert/__init__.py | 告警模块包 |
| 新建 | maop/alert/models.py | audit_alert_rules / audit_alert_history ORM 模型 |
| 新建 | maop/alert/rules.py | 告警规则 CRUD |
| 新建 | maop/alert/engine.py | 告警匹配引擎，事件写入后异步匹配规则并执行动作 |
| 新建 | maop/alert/actions.py | 动作执行器（email/webhook/inapp） |
| 新建 | maop/audit/ws.py | WebSocket 实时推送端点 |
| 修改 | maop/main.py | 注册新路由、WebSocket 端点、告警引擎启动 |
| 修改 | maop/permissions.py | 新增 audit:read / audit:rule:manage 权限定义 |

#### 8.1.2 前端文件（Vue）

| 操作 | 文件路径 | 说明 |
|------|----------|------|
| 修改 | src/views/audit/Audit.vue | 增强审计日志页面：统计卡片、图表、高级过滤、导出 |
| 新建 | src/views/audit/AlertRules.vue | 告警规则管理页面（/audit/alerts） |
| 新建 | src/views/audit/components/StatCards.vue | 顶部统计卡片组件 |
| 新建 | src/views/audit/components/TimelineChart.vue | 时间线图表组件 |
| 新建 | src/views/audit/components/HeatmapChart.vue | 热力图组件 |
| 新建 | src/views/audit/components/OperationPie.vue | 操作分布饼图组件 |
| 新建 | src/views/audit/components/FilterBar.vue | 高级过滤栏组件 |
| 新建 | src/views/audit/components/ExportButton.vue | 导出按钮组件 |
| 新建 | src/views/audit/components/RuleDialog.vue | 创建/编辑规则对话框 |
| 新建 | src/views/audit/components/AlertHistory.vue | 告警历史组件 |
| 新建 | src/views/audit/components/RealtimeMonitor.vue | 实时监控抽屉面板（WebSocket） |
| 新建 | src/api/audit.js | 审计相关 API 封装（events/stats/export/alert-rules/alert-history） |
| 修改 | src/router/index.js | 新增 /audit/alerts 路由 |

#### 8.1.3 数据库迁移

| 操作 | 文件路径 | 说明 |
|------|----------|------|
| 新建 | migrations/xxxx_add_audit_enhancement_fields.sql | audit_events 扩展字段 + 索引 |
| 新建 | migrations/xxxx_create_audit_alert_tables.sql | audit_alert_rules / audit_alert_history 建表 |

### 8.2 实现阶段划分

| 阶段 | 内容 | 依赖 |
|------|------|------|
| Phase 1 | 数据库迁移：扩展字段、建表、索引 | 无 |
| Phase 2 | 后端查询与统计 API（events/stats/timeline/heatmap） | Phase 1 |
| Phase 3 | 后端导出 API（events/export） | Phase 2 |
| Phase 4 | 后端告警规则 CRUD + 匹配引擎 + 动作执行 | Phase 1 |
| Phase 5 | 后端 WebSocket 实时推送 | Phase 2 |
| Phase 6 | 前端审计页面增强（卡片+图表+过滤+表格+导出） | Phase 2、Phase 3 |
| Phase 7 | 前端告警规则管理 + 告警历史 | Phase 4 |
| Phase 8 | 前端实时监控面板 | Phase 5 |
| Phase 9 | 集成测试 + 验收标准逐项验证 | Phase 6-8 |

### 8.3 依赖与风险

| 项 | 说明 |
|----|------|
| 图表库 | 前端需引入图表库（如 ECharts），支持折线/柱状/饼图/热力图 |
| WebSocket | 后端需支持 WebSocket（如 FastAPI 的 WebSocket 或 Starlette），确认现有框架兼容 |
| 邮件发送 | 告警邮件动作依赖平台邮件服务配置，需确认 SMTP 可用 |
| Webhook 超时 | Webhook 回调需设置超时与重试策略，避免阻塞告警引擎 |
| 百万级查询 | 需确认数据库支持复合索引与 JSON 字段查询（PostgreSQL 原生支持，MySQL 5.7+ 支持） |
| 防篡改 | audit_events 表权限限制需 DBA 配合执行 GRANT，仅应用账号仅授予 INSERT/SELECT |

---

## 附录 A 术语表

| 术语 | 说明 |
|------|------|
| MAOP | Multi Agents Orchestration Platform，多智能体编排平台 |
| audit event | 审计事件，一次用户或系统操作的记录 |
| risk_level | 风险等级，low/medium/high/critical |
| 告警规则 | 定义条件与动作的配置，事件命中条件时执行动作 |
| 热力图 | 用户×操作类型矩阵，颜色深浅表示频次 |
| 等保 2.0 | 网络安全等级保护 2.0 制度 |