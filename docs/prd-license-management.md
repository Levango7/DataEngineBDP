# PRD：License 管理系统

> 文档版本：v1.0
> 状态：Draft
> 负责人：Platform Team
> 最后更新：2026-08-13
> 关联模块：MAOP（Multi Agents Orchestration Platform）Enterprise Edition

---

## 第1章 功能概述

### 1.1 功能目标

为 MAOP 企业版（Enterprise Edition）提供完整的 License 生命周期管理能力，覆盖 License 的生成、分发、验证、过期管理与状态流转，支持试用（trial）、正式（enterprise）、到期（expired）三种状态的端到端管理。系统同时提供 CLI 工具与 Web UI 两条入口，满足运维批量操作与管理员可视化运营两类场景。

### 1.2 背景

MAOP 平台分为两个发行版本：

- **Personal Edition**：面向个人开发者，无需 License 即可使用全部基础能力。
- **Enterprise Edition**：面向企业客户，必须持有有效 License 才能启用多租户、多 Agent 编排、配额管控等高级能力。

当前代码库中已存在 `maop/core/license.py`，提供了基础的 License Key 解析与本地校验逻辑，但存在以下缺口：

1. 缺少 License 生成能力（无 CLI、无 UI、无签名机制）。
2. 缺少 License 持久化模型与审计记录，无法追溯发放与变更历史。
3. 缺少管理 UI，管理员无法在系统内查看、续期、吊销 License。
4. 缺少过期自动失效与只读模式降级策略。
5. 缺少试用转正式的转化流程。

本 PRD 旨在补齐上述缺口，交付一套完整的 License 管理子系统。

### 1.3 范围

**本期范围内（In Scope）：**

- License 数据模型与数据库迁移。
- License Key 生成算法（base32 编码 + 校验位 + RSA 签名）。
- License 管理 REST API（生成、列表、详情、更新、吊销、续期、验证、当前租户查询）。
- License 生成 CLI 命令（`maop license generate`）。
- License 管理 Web UI（列表页、详情抽屉、生成对话框、当前状态卡片）。
- License 状态机：active / expired / revoked 三态流转。
- 过期检测定时任务与只读模式降级。
- 试用转正式转化流程。
- License 变更审计日志。
- i18n 中英文支持。

**本期范围外（Out of Scope）：**

- License 在线销售与支付集成（后续迭代）。
- License 漂移检测与硬件指纹绑定（后续迭代）。
- License 按功能模块细粒度授权的运行时拦截（本期仅记录 `features` 字段，不做运行时强制）。
- Personal Edition 的 License 兼容（Personal 版不依赖 License）。

### 1.4 术语表

表：术语对照表

| 术语 | 含义 |
| --- | --- |
| License Key | 授权码，格式 `MAOP-XXXX-XXXX-XXXX-XXXX`，base32 编码 + 校验位 |
| Edition | 发行版本，取值 `trial` / `enterprise` |
| Status | License 状态，取值 `active` / `expired` / `revoked` |
| Trial | 试用版 License，有时效限制，可转为正式版 |
| Enterprise | 正式企业版 License |
| Revoked | 已吊销的 License，立即失效 |
| 只读模式 | License 过期或吊销后，系统禁止写操作，仅允许查看 |
| RSA 签名 | 使用 RSA 私钥对 License 载荷签名，公钥内置于产品用于验证 |

---

## 第2章 用户故事

### 2.1 管理员故事

- **US-A01**：作为管理员，我想通过 Web UI 或 CLI 生成 License，以便分发给企业客户。
- **US-A02**：作为管理员，我想查看所有 License 的状态列表，以便掌握授权全局情况。
- **US-A03**：作为管理员，我想按状态、版本、过期范围过滤 License，以便快速定位目标 License。
- **US-A04**：作为管理员，我想查看单个 License 的完整详情与操作历史，以便追溯授权变更。
- **US-A05**：作为管理员，我想对即将过期的 License 设置提醒，以便及时通知客户续约。
- **US-A06**：作为管理员，我想对已有 License 进行续期，以便延长客户授权期限。
- **US-A07**：作为管理员，我想吊销异常 License，以便即时切断非法或违约授权。
- **US-A08**：作为管理员，我想将试用 License 转为正式 License，以便完成客户从试用到付费的转化。
- **US-A09**：作为管理员，我想在生成 License 时指定最大 Agent 数、最大用户数与功能特性集合，以便按合同配额授权。

### 2.2 普通用户故事

- **US-U01**：作为用户，我想在系统 Overview 页面看到当前租户的 License 状态，以便了解授权范围与剩余有效期。
- **US-U02**：作为用户，我想在 License 即将过期时收到系统内提示，以便提前联系管理员续约。
- **US-U03**：作为用户，我想在 License 过期后系统进入只读模式时得到明确提示，以便了解为何无法执行写操作。

### 2.3 系统运维故事

- **US-O01**：作为运维人员，我想通过 CLI 批量生成 License，以便在离线环境或批量发放场景下高效工作。
- **US-O02**：作为运维人员，我想通过 CLI 验证一个 License Key 的合法性，以便排查客户上报的授权问题。
- **US-O03**：作为运维人员，我想 License 私钥与配置分离存储，以便满足密钥安全管理合规要求。

---

## 第3章 数据模型设计

### 3.1 licenses 表

存储所有 License 记录。表结构如下：

表：licenses 参数说明表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | UUID | PK, default `gen_random_uuid()` | 主键 |
| `license_key` | VARCHAR(32) | UNIQUE, NOT NULL | 完整 License Key，格式 `MAOP-XXXX-XXXX-XXXX-XXXX` |
| `license_key_hash` | VARCHAR(64) | UNIQUE, NOT NULL | License Key 的 SHA-256 哈希，用于快速查找与脱敏比对 |
| `tenant_id` | UUID | FK → tenants.id, NOT NULL | 所属租户 |
| `edition` | VARCHAR(16) | NOT NULL, CHECK ∈ {`trial`, `enterprise`} | 发行版本 |
| `status` | VARCHAR(16) | NOT NULL, CHECK ∈ {`active`, `expired`, `revoked`}, default `active` | 当前状态 |
| `issued_at` | TIMESTAMPTZ | NOT NULL, default `now()` | 发放时间 |
| `expires_at` | TIMESTAMPTZ | NOT NULL | 过期时间 |
| `max_agents` | INTEGER | NOT NULL, > 0 | 最大 Agent 数配额 |
| `max_users` | INTEGER | NOT NULL, > 0 | 最大用户数配额 |
| `features` | JSONB | NOT NULL, default `{}` | 功能特性集合，如 `{"sso": true, "audit": true, "custom_plugin": false}` |
| `issued_by` | UUID | FK → users.id, NOT NULL | 发放人（管理员） |
| `customer_name` | VARCHAR(128) | NOT NULL | 客户名称 |
| `customer_email` | VARCHAR(255) | NOT NULL | 客户联系邮箱 |
| `notes` | TEXT | nullable | 备注 |
| `signature` | TEXT | NOT NULL | RSA 私钥对载荷的 Base64 签名 |
| `converted_from` | UUID | FK → licenses.id, nullable | 若由试用转正式而来，指向原试用 License |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` | 记录创建时间 |
| `updated_at` | TIMESTAMPTZ | NOT NULL, default `now()` | 记录更新时间 |

索引设计：

- `idx_licenses_tenant_id` ON (`tenant_id`)
- `idx_licenses_status` ON (`status`)
- `idx_licenses_expires_at` ON (`expires_at`)
- `idx_licenses_license_key_hash` ON (`license_key_hash`)
- `idx_licenses_edition_status` ON (`edition`, `status`)

### 3.2 license_audit_logs 表

记录 License 全生命周期变更审计日志。

表：license_audit_logs 参数说明表

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | UUID | PK | 主键 |
| `license_id` | UUID | FK → licenses.id, NOT NULL | 关联 License |
| `action` | VARCHAR(32) | NOT NULL | 操作类型，见 3.3 |
| `from_status` | VARCHAR(16) | nullable | 变更前状态 |
| `to_status` | VARCHAR(16) | nullable | 变更后状态 |
| `from_expires_at` | TIMESTAMPTZ | nullable | 变更前过期时间 |
| `to_expires_at` | TIMESTAMPTZ | nullable | 变更后过期时间 |
| `operator_id` | UUID | FK → users.id, NOT NULL | 操作人 |
| `detail` | JSONB | nullable | 变更详情（如配额调整前后值） |
| `created_at` | TIMESTAMPTZ | NOT NULL, default `now()` | 操作时间 |

索引设计：

- `idx_license_audit_license_id` ON (`license_id`, `created_at` DESC)
- `idx_license_audit_action` ON (`action`)

### 3.3 审计 action 枚举

- `generated`：License 生成
- `renewed`：续期
- `revoked`：吊销
- `converted`：试用转正式
- `updated`：配额或信息更新
- `expired`：系统自动标记过期
- `verified`：验证（仅记录失败验证）

### 3.4 License Key 格式

#### 3.4.1 格式定义

License Key 采用 `MAOP-XXXX-XXXX-XXXX-XXXX` 格式，共 4 组，每组 4 个字符，前缀 `MAOP`。

```
MAOP-XXXX-XXXX-XXXX-XXXX
 |     |     |     |    |
 |     |     |     |    └─ 校验位（2 字符） + 载荷尾（2 字符）
 |     |     |     └────── 载荷段 3（4 字符）
 |     |     └──────────── 载荷段 2（4 字符）
 |     └────────────────── 载荷段 1（4 字符）
 └──────────────────────── 固定前缀
```

#### 3.4.2 编码规则

1. 载荷（payload）由以下字段拼接而成：
   - `edition`（1 字节）：`0x01` = trial，`0x02` = enterprise
   - `issued_at` 的 Unix 时间戳（4 字节，大端）
   - `expires_at` 的 Unix 时间戳（4 字节，大端）
   - `max_agents`（2 字节，大端）
   - `max_users`（2 字节，大端）
   - 随机 nonce（4 字节）
2. 载荷共 17 字节，Base32 编码后取前 14 字符作为 Key 主体，最后 2 字符为 CRC-8 校验位（Base32 编码）。
3. 按 4 字符一组插入连字符，加前缀 `MAOP`。

#### 3.4.3 签名规则

- 使用 RSA-2048 私钥对完整载荷（17 字节）签名，签名结果以 Base64 编码存入 `signature` 字段。
- 公钥内置于产品发布包中（`maop/core/keys/license_public.pem`），私钥由管理员离线保管，不进入代码库。
- 验证时先用公钥验签，再解析载荷，最后比对数据库记录。

### 3.5 与现有 license.py 的关系

现有 `maop/core/license.py` 提供 `verify_license(key: str) -> LicenseInfo` 函数，仅做本地格式解析。本次改造：

1. 保留 `verify_license` 函数签名，内部实现改为：公钥验签 → 载荷解析 → 数据库状态校验（过期、吊销）。
2. 新增 `LicenseManager` 类（见 4.2），承载生成、续期、吊销等业务逻辑。
3. 新增 `LicenseKeystore` 类，负责私钥/公钥加载，私钥仅用于生成路径，验证路径只用公钥。
4. 现有调用 `verify_license` 的地方（如启动检查、请求中间件）无需改动签名，行为自动升级。

---

## 第4章 API 设计

### 4.1 通用约定

- 所有 API 前缀：`/api/v1`
- 认证：Bearer JWT，要求 `role ∈ {admin, super_admin}` 的接口标注 `[Admin]`
- 分页参数：`page`（从 1 开始）、`page_size`（默认 20，最大 100）
- 排序参数：`sort_by`、`order` ∈ {`asc`, `desc`}
- 时间字段统一 ISO 8601 with timezone
- 错误响应统一格式：

```json
{
  "error": {
    "code": "LICENSE_NOT_FOUND",
    "message": "License with id xxx not found",
    "details": {}
  }
}
```

### 4.2 LicenseManager 类

后端核心业务逻辑封装在 `LicenseManager` 中，API 路由层仅做参数校验与调用委托。

```python
代码示例：LicenseManager 核心方法签名（Python）
class LicenseManager:
    def __init__(self, db: AsyncSession, keystore: LicenseKeystore): ...

    async def generate(
        self,
        edition: Edition,
        customer_name: str,
        customer_email: str,
        max_agents: int,
        max_users: int,
        expires_at: datetime,
        features: dict,
        issued_by: UUID,
        tenant_id: UUID,
        notes: str | None = None,
    ) -> License: ...

    async def list(
        self,
        filters: LicenseFilters,
        pagination: Pagination,
    ) -> Page[License]: ...

    async def get(self, license_id: UUID) -> License: ...

    async def get_current(self, tenant_id: UUID) -> License | None: ...

    async def update(
        self,
        license_id: UUID,
        operator_id: UUID,
        *,
        expires_at: datetime | None = None,
        max_agents: int | None = None,
        max_users: int | None = None,
        features: dict | None = None,
        notes: str | None = None,
    ) -> License: ...

    async def revoke(self, license_id: UUID, operator_id: UUID, reason: str) -> License: ...

    async def renew(
        self,
        license_id: UUID,
        operator_id: UUID,
        new_expires_at: datetime,
    ) -> License: ...

    async def convert_trial(
        self,
        license_id: UUID,
        operator_id: UUID,
        new_expires_at: datetime,
        max_agents: int,
        max_users: int,
        features: dict,
    ) -> License: ...

    async def verify(self, license_key: str) -> LicenseVerifyResult: ...
```

### 4.3 接口清单

#### 4.3.1 POST /api/v1/licenses — 生成 License

- **权限**：`[Admin]`
- **描述**：生成一个新的 License，自动签名并持久化。
- **请求体**：

表：生成 License 请求参数

| 字段 | 类型 | 必填 | 校验 | 说明 |
| --- | --- | --- | --- | --- |
| `edition` | string | 是 | ∈ {`trial`, `enterprise`} | 发行版本 |
| `customer_name` | string | 是 | 1-128 字符 | 客户名称 |
| `customer_email` | string | 是 | email 格式 | 客户邮箱 |
| `tenant_id` | UUID | 是 | 存在性校验 | 所属租户 |
| `max_agents` | integer | 是 | 1-10000 | 最大 Agent 数 |
| `max_users` | integer | 是 | 1-100000 | 最大用户数 |
| `expires_at` | string | 是 | ISO 8601, 未来时间 | 过期时间 |
| `features` | object | 否 | - | 功能特性键值对 |
| `notes` | string | 否 | ≤ 2000 字符 | 备注 |

- **响应**：`201 Created`

```json
{
  "id": "uuid",
  "license_key": "MAOP-XXXX-XXXX-XXXX-XXXX",
  "edition": "enterprise",
  "status": "active",
  "issued_at": "2026-08-13T10:00:00Z",
  "expires_at": "2027-08-13T10:00:00Z",
  "max_agents": 50,
  "max_users": 200,
  "features": {"sso": true, "audit": true},
  "customer_name": "Acme Corp",
  "customer_email": "admin@acme.com",
  "tenant_id": "uuid"
}
```

- **错误码**：`TENANT_NOT_FOUND`、`INVALID_EXPIRES_AT`、`KEYSTORE_UNAVAILABLE`

#### 4.3.2 GET /api/v1/licenses — 列出所有 License

- **权限**：`[Admin]`
- **查询参数**：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `page` | int | 页码，默认 1 |
| `page_size` | int | 每页条数，默认 20 |
| `status` | string | 过滤状态，逗号分隔 |
| `edition` | string | 过滤版本 |
| `expires_before` | string | 过期时间上限 |
| `expires_after` | string | 过期时间下限 |
| `customer_name` | string | 客户名模糊匹配 |
| `sort_by` | string | 默认 `issued_at` |
| `order` | string | 默认 `desc` |

- **响应**：`200 OK`

```json
{
  "items": [LicenseSummary],
  "total": 128,
  "page": 1,
  "page_size": 20
}
```

- `LicenseSummary` 中 `license_key` 字段为脱敏形式（见 7.1）。

#### 4.3.3 GET /api/v1/licenses/{id} — 查看 License 详情

- **权限**：`[Admin]`
- **响应**：`200 OK`，完整 License 对象 + 最近 50 条审计日志。

```json
{
  "license": {License},
  "audit_logs": [AuditLog]
}
```

- **错误码**：`LICENSE_NOT_FOUND`

#### 4.3.4 PUT /api/v1/licenses/{id} — 更新 License

- **权限**：`[Admin]`
- **描述**：续期、调整配额或修改备注。不允许修改 `license_key`、`edition`、`tenant_id`。状态为 `revoked` 的 License 不允许更新。
- **请求体**：可选字段 `expires_at`、`max_agents`、`max_users`、`features`、`notes`。
- **响应**：`200 OK`，更新后的 License。
- **错误码**：`LICENSE_NOT_FOUND`、`LICENSE_REVOKED`、`INVALID_FIELD`

#### 4.3.5 DELETE /api/v1/licenses/{id} — 吊销 License

- **权限**：`[Admin]`
- **描述**：等价于吊销操作，删除式语义但实际为软删除（置 `status=revoked`）。为语义清晰，推荐使用 `POST /revoke`。
- **响应**：`204 No Content`

#### 4.3.6 POST /api/v1/licenses/{id}/revoke — 吊销 License

- **权限**：`[Admin]`
- **请求体**：

```json
{
  "reason": "客户违约，合同终止"
}
```

- **响应**：`200 OK`，更新后的 License（`status=revoked`）。
- **错误码**：`LICENSE_NOT_FOUND`、`LICENSE_ALREADY_REVOKED`

#### 4.3.7 POST /api/v1/licenses/{id}/renew — 续期 License

- **权限**：`[Admin]`
- **请求体**：

```json
{
  "new_expires_at": "2028-08-13T10:00:00Z"
}
```

- **校验**：`new_expires_at` 必须晚于当前 `expires_at` 且晚于 `now()`。
- **响应**：`200 OK`，续期后的 License。若原状态为 `expired`，续期后自动恢复为 `active`。
- **错误码**：`LICENSE_NOT_FOUND`、`LICENSE_REVOKED`、`INVALID_NEW_EXPIRES_AT`

#### 4.3.8 GET /api/v1/licenses/current — 当前租户的 License 信息

- **权限**：任意已认证用户（基于 JWT 中的 `tenant_id`）。
- **描述**：返回当前租户有效的 License 及配额使用情况。
- **响应**：`200 OK`

```json
{
  "license": {License},
  "usage": {
    "agents_used": 12,
    "agents_quota": 50,
    "users_used": 45,
    "users_quota": 200
  },
  "days_remaining": 365,
  "read_only_mode": false
}
```

- **错误码**：`LICENSE_NOT_FOUND`（当前租户无 License）

#### 4.3.9 POST /api/v1/licenses/verify — 验证 License Key

- **权限**：`[Admin]`
- **请求体**：

```json
{
  "license_key": "MAOP-XXXX-XXXX-XXXX-XXXX"
}
```

- **响应**：`200 OK`

```json
{
  "valid": true,
  "reason": null,
  "license": {LicenseSummary}
}
```

- 验证失败时 `valid=false`，`reason` 取值：`INVALID_FORMAT`、`INVALID_SIGNATURE`、`NOT_FOUND`、`EXPIRED`、`REVOKED`。
- **错误码**：无（验证失败也返回 200，通过 `valid` 字段表达）。

#### 4.3.10 POST /api/v1/licenses/{id}/convert — 试用转正式

- **权限**：`[Admin]`
- **描述**：将一个 `edition=trial` 的 License 转化为 `edition=enterprise` 的新 License，原试用 License 标记为 `revoked`，新 License 的 `converted_from` 指向原 License。
- **请求体**：

```json
{
  "new_expires_at": "2027-08-13T10:00:00Z",
  "max_agents": 50,
  "max_users": 200,
  "features": {"sso": true, "audit": true}
}
```

- **响应**：`201 Created`，新生成的正式 License。
- **错误码**：`LICENSE_NOT_FOUND`、`LICENSE_NOT_TRIAL`、`LICENSE_ALREADY_CONVERTED`

### 4.4 状态机

图：License 状态流转示意图

```
                  generate()
                      │
                      ▼
                 ┌─────────┐
                 │ active  │
                 └─────────┘
                  │   │   │
      renew()─────┘   │   └────revoke()─────┐
                        │                    │
                   expire_job()              ▼
                        │              ┌──────────┐
                        ▼              │ revoked  │
                   ┌─────────┐        └──────────┘
                   │ expired │              │
                   └─────────┘              │
                        │                   │
              renew()───┘                   │
              (恢复 active)                 │
                                          不可恢复
```

状态流转规则：

- `active → expired`：由过期检测定时任务自动触发（`expires_at < now()`）。
- `active → revoked`：由 `revoke` 操作触发。
- `expired → active`：由 `renew` 操作触发（续期到未来时间）。
- `expired → revoked`：由 `revoke` 操作触发。
- `revoked` 为终态，不可流转到其他状态。

---

## 第5章 UI 设计

### 5.1 页面结构总览

图：License 管理 UI 架构图

```
┌─────────────────────────────────────────────────┐
│  TopBar (已有)                                   │
├──────────┬──────────────────────────────────────┤
│          │  /overview                            │
│  SideNav │   └─ LicenseStatusCard (新增)         │
│  (新增   │                                      │
│   Licenses│  /licenses                           │
│   入口)   │   └─ ListPageLayout (复用)           │
│          │       ├─ FilterBar (复用)             │
│          │       ├─ DataTable (复用)             │
│          │       └─ [GenerateDialog]             │
│          │       └─ [DetailDrawer] (复用)        │
└──────────┴──────────────────────────────────────┘
```

### 5.2 License 管理页面（/licenses）

#### 5.2.1 布局

采用项目既有 `ListPageLayout` 组件，保持与其他管理列表页（如 Agents、Users）左右宽度一致。

- 顶部：页面标题 `License 管理` + 右侧主操作按钮 `生成 License`。
- 中部：`FilterBar` 过滤区。
- 下部：`DataTable` 数据表格。
- 右侧：`DetailDrawer` 详情抽屉（查看时滑出）。

#### 5.2.2 FilterBar 过滤项

| 过滤项 | 控件 | 选项 |
| --- | --- | --- |
| 状态 | MultiSelect | 全部 / 试用中 / 正式中 / 已过期 / 已吊销 |
| 版本 | Select | 全部 / Trial / Enterprise |
| 过期范围 | DateRangePicker | 起止日期 |
| 客户名 | Input（模糊搜索） | - |

#### 5.2.3 DataTable 列定义

表：License 列表列定义

| 列名 | 字段 | 宽度 | 渲染 | 排序 |
| --- | --- | --- | --- | --- |
| 客户名 | `customer_name` | 180px | 文本 | 是 |
| License Key | `license_key` | 200px | 脱敏 `MAOP-XXXX-****-****-XXXX` + 复制按钮 | 否 |
| 版本 | `edition` | 100px | Tag：trial=灰色、enterprise=蓝色 | 是 |
| 状态 | `status` | 100px | Tag 三态（见 5.6） | 是 |
| 过期时间 | `expires_at` | 160px | 日期 + 剩余天数 | 是 |
| 最大 Agent 数 | `max_agents` | 120px | 数字 | 是 |
| 最大用户数 | `max_users` | 120px | 数字 | 是 |
| 发放时间 | `issued_at` | 160px | 日期 | 是 |
| 操作 | - | 180px | 查看详情 / 续期 / 吊销 | 否 |

#### 5.2.4 行操作

- **查看详情**：打开 `DetailDrawer`。
- **续期**：打开续期子对话框（输入新的过期时间）。
- **吊销**：打开确认对话框（输入吊销原因）。
- **转为正式**（仅 `edition=trial` 且 `status=active` 时显示）：打开转化对话框。

### 5.3 License 详情抽屉（DetailDrawer）

复用项目既有 `DetailDrawer` 组件，从右侧滑出，宽度 480px（与全局抽屉宽度一致）。

内容分区：

1. **基本信息区**：
   - 客户名、客户邮箱、所属租户
   - License Key（完整显示 + 复制按钮 + 二维码生成）
   - 版本、状态 Tag
   - 发放人、发放时间、过期时间、剩余天数
2. **配额区**：
   - 最大 Agent 数 / 当前使用数（进度条）
   - 最大用户数 / 当前使用数（进度条）
3. **功能特性区**：
   - `features` JSON 以键值对标签形式展示
4. **操作历史区**：
   - 时间线展示 `license_audit_logs`，每条含操作类型、操作人、时间、变更前后
5. **底部操作区**：
   - 续期、吊销、转为正式（按当前状态动态显示/隐藏）

### 5.4 生成 License 对话框（GenerateDialog）

模态对话框，宽度 560px，表单字段：

表：生成 License 表单字段

| 字段 | 控件 | 校验 | 默认值 |
| --- | --- | --- | --- |
| 客户名称 | Input | 必填，1-128 字符 | - |
| 客户邮箱 | Input | 必填，email 格式 | - |
| 所属租户 | Select | 必填 | 当前租户 |
| 版本 | RadioGroup | 必填 | `trial` |
| 最大 Agent 数 | NumberInput | 必填，1-10000 | 10 |
| 最大用户数 | NumberInput | 必填，1-100000 | 50 |
| 有效期 | DatePicker 或 预设选择 | 必填，未来时间 | trial=30 天，enterprise=1 年 |
| 功能特性 | CheckboxGroup | - | 按版本预设默认勾选项 |
| 备注 | Textarea | ≤ 2000 字符 | - |

版本选择联动：

- 选择 `trial`：有效期默认 30 天，最大值 90 天；功能特性默认仅勾选基础项。
- 选择 `enterprise`：有效期默认 1 年，无上限；功能特性默认全选。

提交成功后：

- 关闭对话框
- 刷新列表
- 弹出成功提示，展示完整 License Key 并提供一键复制（此为唯一展示完整 Key 的时机之一）

### 5.5 当前 License 状态卡片（Overview 页面）

在 `/overview` 页面新增 `LicenseStatusCard` 组件，位于概览卡片网格的首行。

卡片内容：

- **标题**：当前授权
- **状态 Tag**：三态视觉（见 5.6）
- **版本**：Trial / Enterprise
- **过期倒计时**：`剩余 365 天` / `已过期 12 天`
- **配额使用**：
  - Agent：`12 / 50`（进度条 24%）
  - 用户：`45 / 200`（进度条 22.5%）
- **到期提醒**：剩余 ≤ 30 天时显示橙色提示条 `License 将于 X 天后过期，请联系管理员续约`
- **只读模式提示**：`read_only_mode=true` 时卡片顶部显示红色提示条 `License 已过期，系统处于只读模式`
- **操作**：管理员可见 `管理 License` 跳转按钮 → `/licenses`

### 5.6 试用/正式/到期三态视觉

状态 Tag 配色规范（遵循浅色/白底风格）：

表：License 状态视觉对照表

| 状态 | Tag 文案 | 背景色 | 文字色 | 边框色 | 图标 |
| --- | --- | --- | --- | --- | --- |
| active + trial | 试用中 | `#FFF7E6` | `#D46B08` | `#FFD591` | clock-circle |
| active + enterprise | 正式版 | `#E6F4FF` | `#0958D9` | `#91CAFF` | safety-certificate |
| expired | 已过期 | `#FFF1F0` | `#CF1322` | `#FFA39E` | warning |
| revoked | 已吊销 | `#F5F5F5` | `#8C8C8C` | `#D9D9D9` | stop |

视觉示例：

```
[试用中]  [正式版]  [已过期]  [已吊销]
```

### 5.7 路由与导航

- 路由路径：`/licenses`
- 菜单项：SideNav 中 `系统管理` 分组下新增 `License 管理`，图标使用 `safety-certificate`（统一简约风格）。
- 权限：仅 `admin` / `super_admin` 角色可见菜单项与可访问路由。
- Overview 页面 `LicenseStatusCard` 对所有已认证用户可见（普通用户只读，无操作按钮）。

### 5.8 i18n Key 设计

所有文案通过 i18n key 管理，中英文双语。Key 命名遵循 `license.{scope}.{field}` 规范。

```json
代码示例：i18n 中文资源（JSON）
{
  "license": {
    "nav": {
      "title": "License 管理"
    },
    "list": {
      "title": "License 管理",
      "generate": "生成 License",
      "columns": {
        "customerName": "客户名",
        "licenseKey": "License Key",
        "edition": "版本",
        "status": "状态",
        "expiresAt": "过期时间",
        "maxAgents": "最大 Agent 数",
        "maxUsers": "最大用户数",
        "issuedAt": "发放时间",
        "actions": "操作"
      }
    },
    "filter": {
      "status": "状态",
      "edition": "版本",
      "expiresRange": "过期范围",
      "customerName": "客户名"
    },
    "status": {
      "trial": "试用中",
      "enterprise": "正式版",
      "expired": "已过期",
      "revoked": "已吊销"
    },
    "generate": {
      "title": "生成 License",
      "customerName": "客户名称",
      "customerEmail": "客户邮箱",
      "tenant": "所属租户",
      "edition": "版本",
      "maxAgents": "最大 Agent 数",
      "maxUsers": "最大用户数",
      "expiresAt": "有效期",
      "features": "功能特性",
      "notes": "备注",
      "success": "License 生成成功"
    },
    "detail": {
      "title": "License 详情",
      "basicInfo": "基本信息",
      "quota": "配额",
      "features": "功能特性",
      "auditHistory": "操作历史",
      "renew": "续期",
      "revoke": "吊销",
      "convert": "转为正式"
    },
    "overview": {
      "title": "当前授权",
      "daysRemaining": "剩余 {days} 天",
      "daysExpired": "已过期 {days} 天",
      "expiringSoon": "License 将于 {days} 天后过期，请联系管理员续约",
      "readOnlyMode": "License 已过期，系统处于只读模式",
      "manage": "管理 License"
    }
  }
}
```

英文资源对应翻译同步维护在 `en.json` 中。

---

## 第6章 验收标准

### 6.1 功能验收

- **AC-01**：管理员可通过 Web UI 生成试用版 License，生成后立即获得完整 License Key。
- **AC-02**：管理员可通过 Web UI 生成正式版 License，可自定义有效期、配额与功能特性。
- **AC-03**：管理员可通过 CLI 命令 `maop license generate` 生成 License，参数与 UI 一致。
- **AC-04**：License Key 符合 `MAOP-XXXX-XXXX-XXXX-XXXX` 格式，且包含 RSA-2048 签名，公钥可独立验证。
- **AC-05**：伪造或篡改的 License Key 在 `verify` 接口返回 `valid=false, reason=INVALID_SIGNATURE`。
- **AC-06**：过期 License 被过期检测任务自动标记为 `expired`，系统进入只读模式（写接口返回 `403 READ_ONLY_MODE`）。
- **AC-07**：续期操作可将 `expired` 状态的 License 恢复为 `active`。
- **AC-08**：吊销操作立即将 License 置为 `revoked`，该租户进入只读模式。
- **AC-09**：试用 License 可通过 `convert` 操作转为正式 License，原试用 License 标记 `revoked`，新正式 License 的 `converted_from` 指向原 License。
- **AC-10**：已转化的试用 License 不允许再次转化（返回 `LICENSE_ALREADY_CONVERTED`）。
- **AC-11**：所有 License 变更（生成、续期、吊销、转化、更新、过期）均写入 `license_audit_logs`，含操作人、时间、变更前后值。
- **AC-12**：前端 `/licenses` 页面可展示 License 列表，支持分页、过滤、排序。
- **AC-13**：前端 License 列表中 License Key 以脱敏形式展示，详情抽屉中可查看完整 Key。
- **AC-14**：前端 Overview 页面展示当前 License 状态卡片，含状态、倒计时、配额使用。
- **AC-15**：前端三态视觉（试用/正式/到期）配色符合 5.6 规范。
- **AC-16**：`GET /api/v1/licenses/current` 返回当前租户 License 与配额使用情况。
- **AC-17**：License 验证结果有缓存（见 7.2），缓存命中时不触发数据库查询与签名验证。
- **AC-18**：Personal Edition 不依赖 License，无 License 也可正常运行全部基础能力。
- **AC-19**：Enterprise Edition 启动时若无有效 License，启动失败并给出明确错误提示。
- **AC-20**：剩余 ≤ 30 天的 License 在 Overview 卡片显示橙色到期提醒。

### 6.2 验收测试场景

表：关键验收测试场景

| 场景 ID | 前置条件 | 操作 | 预期结果 |
| --- | --- | --- | --- |
| TC-01 | 管理员登录 | UI 生成 trial License，有效期 30 天 | License 创建成功，Key 格式正确，列表可见 |
| TC-02 | 存在 trial License | 调用 convert 转正式 | 新 enterprise License 创建，原 trial 标记 revoked |
| TC-03 | 存在即将过期 License（剩 5 天） | 查看 Overview | 显示橙色提醒 |
| TC-04 | 存在已过期 License | 触发过期检测任务 | 状态变更为 expired，系统进入只读模式 |
| TC-05 | 存在 expired License | 调用 renew 续期 1 年 | 状态恢复 active，只读模式解除 |
| TC-06 | 存在 active License | 调用 revoke | 状态变更为 revoked，不可恢复 |
| TC-07 | 任意 License | 篡改 Key 后调用 verify | valid=false, reason=INVALID_SIGNATURE |
| TC-08 | 管理员登录 | 查看详情抽屉 | 显示完整信息 + 审计历史时间线 |

---

## 第7章 非功能需求

### 7.1 安全

- **RSA 签名**：License 载荷使用 RSA-2048 私钥签名，公钥内置于产品用于验证。私钥由管理员通过环境变量 `MAOP_LICENSE_PRIVATE_KEY_PATH` 指定路径加载，不进入代码库与容器镜像。
- **Key 脱敏**：列表与日志中 License Key 以 `MAOP-XXXX-****-****-XXXX` 形式展示，仅保留前缀与首尾段。完整 Key 仅在生成时的一次性提示与详情抽屉中展示。
- **Key Hash 存储**：数据库存储 `license_key_hash`（SHA-256）用于查找，避免明文比对。
- **私钥分离**：生成 License 的服务（或 CLI）需要私钥；验证 License 的服务只需要公钥。生产环境建议生成操作在独立运维节点执行。
- **审计完整性**：审计日志 append-only，不允许更新或删除。
- **权限控制**：所有管理 API 要求 `admin` / `super_admin` 角色；`current` 接口要求任意已认证用户但仅返回自身租户信息。

### 7.2 性能

- **验证缓存**：License 验证结果缓存于 Redis，Key 为 `license:verify:{license_key_hash}`，TTL 5 分钟。吊销与续期操作主动失效缓存。
- **过期检测**：定时任务每 10 分钟扫描 `status=active AND expires_at < now()` 的记录，批量标记为 `expired`。单次扫描限制 1000 条，避免长事务。
- **列表查询**：通过 `license_key_hash` 与索引保证查询性能，分页避免全表扫描。
- **签名验证**：RSA-2048 验签单次约 1ms，配合缓存可忽略不计。

### 7.3 兼容性

- **Personal Edition**：不依赖 License，`license.py` 的 `verify_license` 在 Personal 版下直接返回固定有效结果，不查询数据库。
- **Enterprise Edition**：必须有有效 License。启动时调用 `verify_license`，失败则拒绝启动并输出错误日志。
- **现有调用方兼容**：`verify_license(key)` 函数签名不变，内部实现升级，现有调用方零改动。
- **数据库兼容**：通过 Alembic 迁移脚本新增 `licenses` 与 `license_audit_logs` 表，不影响现有表。
- **前端兼容**：新增路由与组件，不修改既有页面结构。`LicenseStatusCard` 作为可选组件挂载到 Overview。

### 7.4 可观测性

- **指标**：暴露 Prometheus 指标 `maop_license_verify_total{result}`、`maop_license_active_count`、`maop_license_expired_count`。
- **日志**：License 生成、吊销、续期、转化操作记录 INFO 级结构化日志；验证失败记录 WARN 级日志。
- **告警**：过期检测任务执行失败、私钥加载失败触发告警。

---

## 第8章 实现计划

### 8.1 文件清单

#### 8.1.1 后端新建文件

| 文件路径 | 职责 |
| --- | --- |
| `maop/api/v1/licenses.py` | License 管理 REST API 路由 |
| `maop/core/license_manager.py` | `LicenseManager` 业务逻辑类 |
| `maop/core/license_keystore.py` | `LicenseKeystore` 密钥加载与管理 |
| `maop/core/license_keygen.py` | License Key 生成与编码算法 |
| `maop/models/license.py` | SQLAlchemy ORM 模型（`License`、`LicenseAuditLog`） |
| `maop/schemas/license.py` | Pydantic 请求/响应模型 |
| `maop/services/license_service.py` | License 服务层（配额查询、过期检测、缓存） |
| `maop/tasks/license_expire.py` | 过期检测定时任务 |
| `maop/cli/license_cli.py` | CLI 命令 `maop license generate/verify` |
| `migrations/versions/xxxx_add_license_tables.py` | Alembic 数据库迁移 |
| `maop/core/keys/license_public.pem` | 内置 RSA 公钥 |
| `tests/unit/test_license_keygen.py` | Key 生成算法单元测试 |
| `tests/unit/test_license_manager.py` | Manager 业务逻辑单元测试 |
| `tests/integration/test_license_api.py` | API 集成测试 |

#### 8.1.2 后端修改文件

| 文件路径 | 修改内容 |
| --- | --- |
| `maop/core/license.py` | `verify_license` 内部实现升级为公钥验签 + 数据库状态校验 |
| `maop/api/v1/__init__.py` | 注册 `licenses` 路由 |
| `maop/cli/__init__.py` | 注册 `license` CLI 子命令 |
| `maop/core/middleware.py` | 请求中间件增加只读模式拦截（License 过期/吊销时拦截写请求） |
| `maop/main.py` | 启动时 Enterprise Edition License 检查 |
| `maop/config.py` | 新增 License 相关配置项（私钥路径、缓存 TTL 等） |
| `maop/tasks/scheduler.py` | 注册过期检测定时任务 |

#### 8.1.3 前端新建文件

| 文件路径 | 职责 |
| --- | --- |
| `src/views/licenses/LicenseList.vue` | License 管理列表页 |
| `src/views/licenses/components/GenerateLicenseDialog.vue` | 生成 License 对话框 |
| `src/views/licenses/components/LicenseDetailDrawer.vue` | License 详情抽屉 |
| `src/views/licenses/components/RenewLicenseDialog.vue` | 续期对话框 |
| `src/views/licenses/components/RevokeLicenseDialog.vue` | 吊销确认对话框 |
| `src/views/licenses/components/ConvertLicenseDialog.vue` | 试用转正式对话框 |
| `src/views/licenses/components/LicenseStatusCard.vue` | Overview 页面状态卡片 |
| `src/views/licenses/components/LicenseStatusTag.vue` | 三态状态 Tag 组件 |
| `src/api/licenses.ts` | License API 请求封装 |
| `src/types/license.ts` | TypeScript 类型定义 |
| `src/locales/zh/license.json` | 中文 i18n 资源 |
| `src/locales/en/license.json` | 英文 i18n 资源 |

#### 8.1.4 前端修改文件

| 文件路径 | 修改内容 |
| --- | --- |
| `src/router/index.ts` | 新增 `/licenses` 路由 |
| `src/layouts/SideNav.vue` | 新增 `License 管理` 菜单项（`系统管理` 分组下） |
| `src/views/overview/index.vue` | 挂载 `LicenseStatusCard` 组件 |
| `src/locales/zh/index.json` | 合并 `license` 命名空间 |
| `src/locales/en/index.json` | 合并 `license` 命名空间 |
| `src/types/index.ts` | 导出 license 类型 |

### 8.2 实现阶段

表：实现阶段划分

| 阶段 | 内容 | 产出 |
| --- | --- | --- |
| Phase 1 | 数据模型 + 迁移 + Key 生成算法 + Keystore | 可通过 CLI 生成与验证 License |
| Phase 2 | LicenseManager + REST API + 审计日志 | API 全部可用，含单元/集成测试 |
| Phase 3 | 过期检测任务 + 只读模式中间件 + 启动检查 | 过期自动失效，系统降级生效 |
| Phase 4 | 前端列表页 + 详情抽屉 + 生成/续期/吊销对话框 | Web UI 管理能力可用 |
| Phase 5 | Overview 状态卡片 + 三态视觉 + i18n | 用户体验完整 |
| Phase 6 | 试用转正式流程 + 端到端验收测试 | 全部 AC 通过 |

### 8.3 测试计划

- **单元测试**：
  - `test_license_keygen.py`：Key 格式、编码解码、校验位、签名验签。
  - `test_license_manager.py`：生成、续期、吊销、转化、状态机流转、审计写入。
  - `test_license_keystore.py`：密钥加载、缺失处理。
- **集成测试**（使用 SQLite 模拟数据库）：
  - `test_license_api.py`：全部 10 个 API 的成功与错误路径。
  - 只读模式中间件拦截验证。
  - 过期检测任务执行。
- **前端测试**：
  - 列表页渲染、过滤、分页。
  - 生成对话框表单校验与提交。
  - 三态 Tag 视觉快照测试。
  - Overview 状态卡片渲染。
- **端到端测试**：覆盖 TC-01 至 TC-08 全部验收场景。

### 8.4 依赖与风险

表：风险与应对

| 风险 | 影响 | 应对 |
| --- | --- | --- |
| 私钥泄露 | 可伪造任意 License | 私钥不进代码库，独立运维节点生成，定期轮换 |
| 过期检测任务宕机 | License 未及时失效 | 任务失败告警；请求中间件兜底校验 `expires_at` |
| 验证缓存与吊销延迟 | 吊销后最长 5 分钟仍可用 | 吊销操作主动失效缓存；对安全敏感场景可缩短 TTL |
| 数据库迁移失败 | 系统无法启动 | 迁移脚本充分测试，提供回滚脚本 |

---

## 附录 A：CLI 命令示例

命令示例：生成正式版 License

```bash
maop license generate \
  --edition enterprise \
  --customer-name "Acme Corp" \
  --customer-email "admin@acme.com" \
  --tenant-id "550e8400-e29b-41d4-a716-446655440000" \
  --max-agents 50 \
  --max-users 200 \
  --expires-at "2027-08-13T10:00:00Z" \
  --features '{"sso": true, "audit": true}' \
  --notes "年度合同"
```

命令示例：验证 License Key

```bash
maop license verify --key "MAOP-XXXX-XXXX-XXXX-XXXX"
```

命令示例：吊销 License

```bash
maop license revoke --id "550e8400-e29b-41d4-a716-446655440000" --reason "客户违约"
```

## 附录 B：配置项

表：License 配置项说明

| 配置项 | 环境变量 | 默认值 | 说明 |
| --- | --- | --- | --- |
| 私钥路径 | `MAOP_LICENSE_PRIVATE_KEY_PATH` | `/etc/maop/keys/private.pem` | RSA 私钥文件路径 |
| 公钥路径 | `MAOP_LICENSE_PUBLIC_KEY_PATH` | 内置 `license_public.pem` | RSA 公钥文件路径 |
| 验证缓存 TTL | `MAOP_LICENSE_VERIFY_CACHE_TTL` | `300` | 秒 |
| 过期检测间隔 | `MAOP_LICENSE_EXPIRE_CHECK_INTERVAL` | `600` | 秒 |
| 到期提醒阈值 | `MAOP_LICENSE_EXPIRE_WARN_DAYS` | `30` | 天 |
| 只读模式 | `MAOP_LICENSE_READ_ONLY_MODE` | `false` | 强制只读（维护场景） |

---

> 本文档为开发实施依据，所有 API、数据模型、UI 组件均可按本文档直接实现。实现过程中如遇与现有架构冲突，以项目既有规范为准并反馈更新本文档。