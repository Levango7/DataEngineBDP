# 前端 RESTful API 规范

> 本规范统一约束 DataEngineBDP 前端与后端之间的 HTTP 接口设计、请求/响应结构、错误码语义、API 模块组织方式与认证机制。所有新增接口与前端 API 模块必须遵循本规范，已有接口在重构时向本规范对齐。
>
> 适用范围：`frontend/src/api/**` 下全部 API 模块，以及后端 `/api/v1/**` 路由。

## 1. 总体原则

### 1.1 设计目标

- **统一**：所有接口遵循相同的 URL 风格、请求语义、响应结构与错误码语义，前端无需为每个接口单独适配。
- **类型安全**：所有接口在 TypeScript 层有完整的入参与出参类型定义，编译期即可发现字段缺失或类型不匹配。
- **薄封装**：前端 API 模块只做"URL + 参数 + 类型"的声明，不承载业务逻辑；业务逻辑放在页面/composable 中。
- **可测试**：API 模块导出纯函数，便于在 Vitest 中 mock 或直接断言。

### 1.2 技术栈约束

| 维度 | 选型 | 备注 |
| --- | --- | --- |
| HTTP 客户端 | axios | 通过 `frontend/src/api/client.ts` 统一封装 |
| 基础路径 | `/api/v1` | 由环境变量 `VITE_API_BASE` 覆盖 |
| 超时 | 30s | 在 `client.ts` 中配置 |
| Content-Type | `application/json` | 全部接口使用 JSON |
| 认证 | Bearer token | 通过请求拦截器自动注入 |
| 类型语言 | TypeScript strict | 所有 API 模块使用 `import type` 导入类型 |

## 2. URL 规范

### 2.1 基础路径

所有业务接口必须挂在 `/api/v1` 下，由 `client.ts` 的 axios 实例 `baseURL` 统一注入。前端 API 模块在调用 `get/post/put/del` 时只写相对路径，不重复写 `/api/v1`。

```ts
// 正确
const BASE = '/tenants'
return get<Tenant>(`${BASE}/${id}`)

// 错误：重复拼接基础路径
return get<Tenant>(`/api/v1/tenants/${id}`)
```

### 2.2 资源命名

- 资源集合使用**复数名词**，全小写 kebab-case：`/tenants`、`/jobs`、`/data-sources`、`/workspaces`。
- 单个资源使用 `/{collection}/{id}` 形式：`/tenants/123`、`/jobs/abc`。
- 子资源使用嵌套路径：`/workspaces/{id}/quotas`、`/tenants/{id}/users`。
- 资源 ID 一律使用 string 类型（即使后端是数字），便于分布式 ID 兼容。

| 场景 | URL 示例 | 说明 |
| --- | --- | --- |
| 资源集合 | `/tenants` | 复数名词 |
| 单个资源 | `/tenants/{id}` | 路径参数 |
| 子资源集合 | `/tenants/{id}/workspaces` | 嵌套 |
| 子资源单个 | `/workspaces/{id}/quota` | 单数名词表示唯一关联资源 |
| 全量（不分页） | `/tenants/all` | 用于下拉选择等小数据量场景 |

### 2.3 操作命名

非 CRUD 操作使用**动词后缀**，放在路径末尾，动词使用小写 kebab-case。

| 操作类型 | URL 示例 | HTTP 方法 |
| --- | --- | --- |
| 取消作业 | `/jobs/{id}/cancel` | POST |
| 测试连接 | `/data-sources/{id}/test` | POST |
| 启动服务 | `/workspaces/{id}/start` | POST |
| 停止服务 | `/workspaces/{id}/stop` | POST |
| 重启服务 | `/workspaces/{id}/restart` | POST |
| 同步元数据 | `/data-sources/{id}/sync-meta` | POST |
| 获取日志 | `/jobs/{id}/logs` | GET |
| 获取状态 | `/jobs/{id}/status` | GET |

### 2.4 查询参数

查询参数统一使用 camelCase，禁止使用 snake_case 或 kebab-case。

| 参数类别 | 参数名 | 类型 | 说明 |
| --- | --- | --- | --- |
| 分页 | `page` | number | 页码，从 1 开始 |
| 分页 | `pageSize` | number | 每页条数，默认 20 |
| 关键字 | `keyword` | string | 模糊搜索关键字 |
| 排序 | `sortBy` | string | 排序字段名 |
| 排序 | `order` | `'asc' \| 'desc'` | 排序方向 |
| 过滤 | `{fieldName}` | 字段对应类型 | 与资源字段同名，如 `status`、`type`、`tenantId` |

查询参数示例：

```text
GET /api/v1/jobs?page=1&pageSize=20&status=running&sortBy=createdAt&order=desc&keyword=etl
```

### 2.5 路径 vs 查询参数选择

- **路径参数**：定位资源身份（`{id}`、`{tenantId}`），必填。
- **查询参数**：过滤、分页、排序、可选筛选，可省略。

```text
# 正确：tenantId 作为路径参数定位资源
GET /tenants/{tenantId}/workspaces

# 正确：status 作为查询参数过滤
GET /jobs?status=running

# 错误：把过滤条件放进路径
GET /jobs/status/running
```

## 3. 请求规范

### 3.1 HTTP 方法语义

| 方法 | 语义 | 是否幂等 | 请求体 | 典型场景 |
| --- | --- | --- | --- | --- |
| GET | 查询资源 | 是 | 无（用查询参数） | 列表、详情、统计 |
| POST | 创建资源 / 触发动作 | 否 | JSON | 新建、取消、测试 |
| PUT | 完整更新资源 | 是 | JSON | 全量替换字段 |
| PATCH | 部分更新资源 | 是 | JSON | 仅更新部分字段 |
| DELETE | 删除资源 | 是 | 无（用查询参数） | 删除单个或批量 |

### 3.2 各方法使用约束

#### 3.2.1 GET

- 严禁携带请求体，所有参数走查询参数。
- 查询参数值需经 axios 自动 encodeURIComponent，禁止手动拼接 URL。

```ts
// 正确：通过 params 传递，axios 自动编码
get<PagedResult<Job>>('/jobs', { keyword: 'ETL & 数据', status: 'running' })

// 错误：手动拼接，未编码
get<PagedResult<Job>>(`/jobs?keyword=${keyword}&status=${status}`)
```

#### 3.2.2 POST

- 创建资源时请求体为资源字段，不含 `id`、`createdAt`、`updatedAt` 等后端生成字段。
- 触发动作时请求体可为空或携带动作参数。

```ts
// 创建资源
post<Tenant>('/tenants', { name, code, plan, contact })

// 触发动作（无参）
post<void>(`/jobs/${id}/cancel`)

// 触发动作（带参）
post<TestResult>(`/data-sources/${id}/test`, { timeout: 5000 })
```

#### 3.2.3 PUT

- 完整更新：请求体需包含所有可变字段，未传字段视为清空或使用默认值。
- 用于"替换式"更新语义。

#### 3.2.4 PATCH

- 部分更新：仅传需要变更的字段，未传字段保持不变。
- 当前项目若后端未区分 PUT/PATCH，可统一用 PUT + `Partial<T>` 模拟部分更新（参考 `datasource.ts` 的 `updateDataSource`）。

#### 3.2.5 DELETE

- 删除单个资源：`DELETE /{collection}/{id}`。
- 批量删除：`DELETE /{collection}?ids=id1,id2,id3` 或 `POST /{collection}/batch-delete`（推荐后者，避免 URL 过长）。

### 3.3 请求头

| 头字段 | 值 | 注入方 |
| --- | --- | --- |
| `Content-Type` | `application/json` | client.ts 默认配置 |
| `Authorization` | `Bearer {token}` | 请求拦截器自动注入 |
| `X-Tenant-Id` | 当前租户 ID（如需） | 业务模块按需添加 |

业务模块原则上不直接操作请求头，如需添加自定义头通过 `get/post/put/del` 的第三个参数 `config` 传入。

```ts
get<User>('/users/me', undefined, { headers: { 'X-Tenant-Id': tenantId } })
```

## 4. 响应规范

### 4.1 统一响应体

所有接口（除文件下载、流式响应外）必须返回统一的 `ApiResponse<T>` 结构：

```ts
interface ApiResponse<T> {
  /** 业务状态码，0 表示成功 */
  code: number
  /** 提示消息（成功可省略或为空，失败为可读错误描述） */
  message: string
  /** 业务数据 */
  data: T
  /** 服务器时间戳（毫秒，可选） */
  timestamp?: number
}
```

`client.ts` 的响应拦截器会自动拆包：当 `code === 0` 时把 `data` 提到 `response.data`，业务调用方直接拿到 `T`；当 `code !== 0` 时抛出 `ApiError`。

### 4.2 成功响应

```json
{
  "code": 0,
  "message": "",
  "data": { "id": "t-001", "name": "华东生产集群" },
  "timestamp": 1723766400000
}
```

### 4.3 失败响应

业务失败（HTTP 200 + 业务码非 0）：

```json
{
  "code": 1001,
  "message": "租户编码已存在",
  "data": null,
  "timestamp": 1723766400000
}
```

HTTP 错误（4xx/5xx）：后端应同样返回 `ApiResponse` 结构，`code` 为业务码或 HTTP 状态码，`message` 为可读错误。若后端未包装，前端拦截器会根据 HTTP 状态码生成默认提示。

### 4.4 分页响应

分页接口的 `data` 字段统一为 `PagedResult<T>`：

```ts
interface PagedResult<T> {
  /** 数据列表 */
  list: T[]
  /** 总条数 */
  total: number
  /** 当前页码（从 1 开始） */
  page: number
  /** 每页条数 */
  pageSize: number
}
```

分页响应示例：

```json
{
  "code": 0,
  "message": "",
  "data": {
    "list": [
      { "id": "t-001", "name": "华东生产集群" },
      { "id": "t-002", "name": "华南测试集群" }
    ],
    "total": 38,
    "page": 1,
    "pageSize": 20
  }
}
```

注意：字段名固定为 `list`，不使用 `items`、`records`、`rows` 等同义词。

### 4.5 空值与可选字段

- 列表查询：无数据时 `list` 为 `[]`，`total` 为 `0`，禁止返回 `null`。
- 单资源查询：资源不存在返回 404 + 业务码，禁止返回 200 + `data: null`。
- 可选字段：在 TypeScript 类型中标记为 `?`，后端可省略或返回 `null`，前端按 `undefined` 处理。

## 5. 错误码规范

### 5.1 错误码分类

| 区间 | 类别 | 说明 |
| --- | --- | --- |
| 0 | 成功 | 业务处理成功 |
| 400 | 参数错误 | 请求参数校验失败 |
| 401 | 未认证 | token 缺失或过期 |
| 403 | 无权限 | 已认证但无该资源权限 |
| 404 | 资源不存在 | 路径或资源 ID 无效 |
| 409 | 冲突 | 资源已存在、状态冲突 |
| 429 | 限流 | 请求频率超限 |
| 500 | 服务器错误 | 后端内部异常 |
| 1000-1999 | 通用业务错误 | 跨模块通用业务码 |
| 2000-2999 | 租户模块 | `tenant` 模块专属 |
| 3000-3999 | 工作空间模块 | `workspace` 模块专属 |
| 4000-4999 | 作业模块 | `job` 模块专属 |
| 5000-5999 | 数据源模块 | `datasource` 模块专属 |
| 6000+ | 其他模块 | 按模块递增分配 |

### 5.2 通用业务错误码

| 错误码 | 含义 | 前端处理 |
| --- | --- | --- |
| 1001 | 资源已存在 | 表单字段错误提示 |
| 1002 | 资源状态非法 | 弹窗提示，刷新列表 |
| 1003 | 资源被引用，不可删除 | 弹窗提示引用方 |
| 1004 | 配额超限 | 弹窗提示，引导扩容 |
| 1005 | 操作过于频繁 | 静默忽略或轻提示 |

### 5.3 前端拦截器处理

`client.ts` 响应拦截器对错误码的统一处理：

| 错误类型 | 拦截器行为 | 页面层是否需要再处理 |
| --- | --- | --- |
| `code === 0` | 拆包返回 `data` | 否 |
| `code !== 0`（业务错误） | 调用 `errorNotifier` 提示，抛 `ApiError` | 通常否；特殊业务可 catch 后自定义提示 |
| HTTP 401 | 调用 `unauthorizedHandler` 跳登录页 | 否 |
| HTTP 403 | 提示"无权限访问该资源" | 否 |
| HTTP 404 | 提示"请求的资源不存在" | 否 |
| HTTP 500 | 提示"服务器内部错误，请联系管理员" | 否 |
| 网络异常（status 0） | 提示"网络异常，请检查网络连接" | 否 |
| 其他 | 透传后端 `message` | 视业务而定 |

### 5.4 ApiError 结构

拦截器抛出的错误统一为 `ApiError` 实例，页面层可读取 `code` 与 `httpStatus` 做分支处理：

```ts
class ApiError extends Error {
  /** 业务码或 HTTP 状态码 */
  code: number
  /** HTTP 状态码 */
  httpStatus: number
}
```

页面层捕获示例：

```ts
try {
  await tenantApi.createTenant(params)
} catch (e) {
  if (e instanceof ApiError && e.code === 1001) {
    // 租户编码已存在，标红 code 字段
    formRef.value?.validateField('code')
  }
  // 其他错误已由拦截器提示，无需重复
}
```

## 6. API 模块规范

### 6.1 文件组织

```
frontend/src/api/
├── client.ts          # axios 封装与通用方法（get/post/put/del）
├── types.ts           # 跨模块共享类型（ApiResponse、PagedResult、PageQuery、User 等）
├── tenant.ts          # 租户模块 API
├── cluster.ts         # 集群模块 API
├── datasource.ts      # 数据源模块 API
├── job.ts             # 作业模块 API
└── ...
```

### 6.2 文件命名

- API 模块文件名使用 **kebab-case**：`data-source.ts`、`job-log.ts`。
- 现有部分模块使用单词形式（`tenant.ts`、`cluster.ts`、`job.ts`），保持不变；新增多词模块使用 kebab-case。
- 禁止使用 PascalCase 或 camelCase 命名 API 文件。

### 6.3 模块结构模板

每个 API 模块按以下顺序组织：

```ts
/**
 * 模块说明（一句话描述职责）
 * 可选：补充业务背景、特殊约定
 */
import { get, post, put, del } from './client'
import type { PagedResult, PageQuery, ... } from './types'

// 1. 模块内部类型（仅本模块使用的类型，不复用的）
export type ComponentHealth = 'healthy' | 'warning' | 'error'
export interface ComponentStatus { ... }

// 2. 资源根路径
const BASE = '/data-sources'

// 3. CRUD 函数（按 list → get → create → update → delete 顺序）
export function listDataSources(params?: DataSourceListQuery): Promise<PagedResult<DataSource>> {
  return get<PagedResult<DataSource>>(BASE, params as Record<string, unknown>)
}

export function getDataSource(id: string): Promise<DataSource> {
  return get<DataSource>(`${BASE}/${id}`)
}

export function createDataSource(data: SaveDataSourceParams): Promise<DataSource> {
  return post<DataSource>(BASE, data)
}

export function updateDataSource(id: string, data: Partial<SaveDataSourceParams>): Promise<DataSource> {
  return put<DataSource>(`${BASE}/${id}`, data)
}

export function deleteDataSource(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

// 4. 非 CRUD 操作（按业务语义命名）
export function testDataSource(id: string): Promise<TestResult> {
  return post<TestResult>(`${BASE}/${id}/test`)
}
```

### 6.4 导出规范

- **命名导出**：所有 API 函数使用 `export function`，禁止默认导出。
- **类型导出**：类型使用 `export interface` / `export type`，导入方使用 `import type`。
- **批量导入**：页面层可使用 `import * as tenantApi from '@/api/tenant'` 命名空间导入。

```ts
// 推荐：命名空间导入，调用处语义清晰
import * as tenantApi from '@/api/tenant'
await tenantApi.createTenant(params)

// 也可：按需导入
import { createTenant, updateTenant } from '@/api/tenant'
```

### 6.5 函数命名

| 操作 | 命名模式 | 示例 |
| --- | --- | --- |
| 列表查询 | `list{Resource}` | `listTenants`、`listJobs` |
| 全量查询 | `listAll{Resource}` | `listAllTenants` |
| 详情查询 | `get{Resource}` | `getTenant`、`getJob` |
| 创建 | `create{Resource}` | `createTenant` |
| 更新 | `update{Resource}` | `updateTenant` |
| 删除 | `delete{Resource}` | `deleteTenant` |
| 自定义操作 | `{verb}{Resource}` | `cancelJob`、`testDataSource` |
| 查询子资源 | `list{Parent}{Child}` | `listWorkspacePods` |

禁止使用 `fetch`、`load`、`query` 等同义词替代 `list`/`get`，保持全项目一致。

### 6.6 类型定义规范

- **共享类型**：`ApiResponse`、`PagedResult`、`PageQuery`、`Identifiable` 以及跨模块复用的实体（`User`、`Tenant`、`Workspace`、`Job` 等）定义在 `types.ts`。
- **模块私有类型**：仅本模块使用的类型（如 `ComponentStatus`、`TestResult`）定义在模块文件内并导出。
- **实体类型**：使用 `interface`，继承 `Identifiable` 表示有 `id` 字段。
- **枚举**：使用字面量联合类型（`'active' | 'suspended'`），禁止使用 TS `enum`。
- **参数类型**：创建参数命名 `{Resource}Params` 或 `Create{Resource}Params`；更新参数命名 `Update{Resource}Params`，通常为 `Partial<CreateParams>` 加额外可变字段。
- **查询参数**：命名 `{Resource}ListQuery`，继承 `PageQuery`，附加过滤字段。

```ts
// 实体类型
export interface Tenant extends Identifiable {
  name: string
  code: string
  plan: PlanTier
  status: TenantStatus
  // ...
}

// 枚举（字面量联合）
export type TenantStatus = 'active' | 'suspended' | 'deleted'

// 创建参数
export interface CreateTenantParams {
  name: string
  code: string
  plan: PlanTier
}

// 更新参数（Partial 模拟部分更新）
export type UpdateTenantParams = Partial<CreateTenantParams> & {
  status?: TenantStatus
}

// 列表查询参数
export interface TenantListQuery extends PageQuery {
  status?: TenantStatus
  plan?: PlanTier
}
```

### 6.7 JSDoc 注释

每个导出函数必须配备 JSDoc 注释，说明用途与参数：

```ts
/**
 * 查询租户列表（分页）
 * @param params 查询参数（分页、状态、套餐过滤）
 */
export function listTenants(params?: TenantListQuery): Promise<PagedResult<Tenant>> {
  return get<PagedResult<Tenant>>(BASE, params as Record<string, unknown>)
}
```

### 6.8 完整模块示例

代码示例：API 模块完整模板（TypeScript）

```ts
/**
 * 数据源管理 API
 *
 * 支持的数据源类型：MySQL / PostgreSQL / ClickHouse / Kafka / Hive 等
 * 提供数据源的增删改查与连接测试能力
 */
import { get, post, put, del } from './client'
import type { PagedResult, PageQuery } from './types'

/** 数据源类型枚举 */
export type DataSourceType =
  | 'mysql'
  | 'postgresql'
  | 'clickhouse'
  | 'kafka'
  | 'hive'

/** 数据源连接状态 */
export type DataSourceStatus = 'connected' | 'disconnected' | 'testing'

/** 数据源信息 */
export interface DataSource {
  id: string
  name: string
  type: DataSourceType
  host: string
  port: number
  database?: string
  username: string
  password?: string
  status: DataSourceStatus
  createdAt: string
  updatedAt?: string
}

/** 创建/更新数据源参数 */
export interface SaveDataSourceParams {
  name: string
  type: DataSourceType
  host: string
  port: number
  database?: string
  username: string
  password?: string
}

/** 数据源列表查询参数 */
export interface DataSourceListQuery extends PageQuery {
  type?: DataSourceType
  status?: DataSourceStatus
}

/** 连接测试结果 */
export interface TestResult {
  success: boolean
  latency?: number
  message: string
}

const BASE = '/data-sources'

export function listDataSources(params?: DataSourceListQuery): Promise<PagedResult<DataSource>> {
  return get<PagedResult<DataSource>>(BASE, params as Record<string, unknown>)
}

export function getDataSource(id: string): Promise<DataSource> {
  return get<DataSource>(`${BASE}/${id}`)
}

export function createDataSource(data: SaveDataSourceParams): Promise<DataSource> {
  return post<DataSource>(BASE, data)
}

export function updateDataSource(id: string, data: Partial<SaveDataSourceParams>): Promise<DataSource> {
  return put<DataSource>(`${BASE}/${id}`, data)
}

export function deleteDataSource(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

export function testDataSource(id: string): Promise<TestResult> {
  return post<TestResult>(`${BASE}/${id}/test`)
}
```

## 7. 认证规范

### 7.1 认证方式

统一使用 Bearer token 认证：

```text
Authorization: Bearer {token}
```

token 由后端登录接口返回，前端存储在 auth store 中。

### 7.2 token 注入机制

`client.ts` 不直接依赖 auth store（避免循环依赖），通过 `setTokenGetter` 注入 token 获取函数：

```ts
// 应用启动时（main.ts）注入
import { setTokenGetter } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

setTokenGetter(() => useAuthStore().token)
```

请求拦截器在每次请求时调用 `tokenGetter()` 动态获取最新 token，无 token 时跳过 `Authorization` 头。

### 7.3 401 处理

响应拦截器检测到 HTTP 401 时：

1. 调用 `unauthorizedHandler`（通常跳转登录页，并携带当前路径作为 redirect 参数）。
2. 提示"登录已过期，请重新登录"。
3. 抛出 `ApiError`，页面层无需重复处理。

`unauthorizedHandler` 同样通过注入方式设置，避免硬耦合路由：

```ts
import { setUnauthorizedHandler } from '@/api/client'
import router from '@/router'

setUnauthorizedHandler(() => {
  router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
})
```

### 7.4 错误提示注入

错误提示函数（通常为 Element Plus 的 `ElMessage.error`）通过 `setErrorNotifier` 注入：

```ts
import { setErrorNotifier } from '@/api/client'
import { ElMessage } from 'element-plus'

setErrorNotifier((msg) => ElMessage.error(msg))
```

### 7.5 token 刷新（如需）

若后端支持 refresh token，应在 auth store 中实现刷新逻辑，并在 `tokenGetter` 中处理过期自动刷新。该逻辑不放在 `client.ts` 中，保持 HTTP 层纯粹。

## 8. 通用请求方法

### 8.1 方法签名

`client.ts` 导出四个泛型方法，所有 API 模块必须通过这四个方法发起请求，禁止直接使用 `axios` 实例：

```ts
export async function get<T>(
  url: string,
  params?: Record<string, unknown>,
  config?: AxiosRequestConfig
): Promise<T>

export async function post<T>(
  url: string,
  data?: unknown,
  config?: AxiosRequestConfig
): Promise<T>

export async function put<T>(
  url: string,
  data?: unknown,
  config?: AxiosRequestConfig
): Promise<T>

export async function del<T>(
  url: string,
  params?: Record<string, unknown>,
  config?: AxiosRequestConfig
): Promise<T>
```

### 8.2 返回值

四个方法均返回 `Promise<T>`，已自动完成 `ApiResponse<T>` 拆包，调用方直接拿到业务数据。删除接口通常返回 `Promise<void>`。

### 8.3 特殊场景

如需绕过拦截器或使用非标准配置（文件上传、流式响应、取消请求等），可使用导出的 `axiosInstance`：

```ts
import { axiosInstance } from '@/api/client'

// 文件上传
const formData = new FormData()
formData.append('file', file)
await axiosInstance.post('/data-assets/upload', formData, {
  headers: { 'Content-Type': 'multipart/form-data' }
})
```

仅在必要时使用 `axiosInstance`，常规接口必须走 `get/post/put/del`。

## 9. 接口版本管理

### 9.1 版本号

- 当前版本：`v1`，路径前缀 `/api/v1`。
- 不兼容变更时新增版本：`/api/v2`，旧版本保持至少 1 个版本的兼容期。
- 兼容变更（新增字段、新增接口）不升级版本号。

### 9.2 字段演进

- 新增字段：直接加，前端按可选字段处理。
- 删除字段：先标记 `@deprecated` 运行 1 个版本，再删除。
- 重命名字段：先新增新字段同时返回新旧两个字段，前端切换后再移除旧字段。

## 10. 检查清单

新增接口或 API 模块时，按以下清单自检：

- [ ] URL 使用复数名词 + kebab-case，操作动词在末尾
- [ ] 查询参数使用 camelCase，分页用 `page`/`pageSize`
- [ ] 响应遵循 `ApiResponse<T>` 结构，分页用 `PagedResult<T>`
- [ ] 错误码使用约定区间，业务码 ≥ 1000
- [ ] API 文件使用 kebab-case 命名，函数使用 `list/get/create/update/delete` 前缀
- [ ] 所有导出函数有 JSDoc 注释
- [ ] 类型定义完整，使用 `import type` 导入
- [ ] 枚举使用字面量联合，不使用 `enum`
- [ ] 通过 `get/post/put/del` 调用，不直接用 axios
- [ ] 不在 API 模块中写业务逻辑（弹窗、跳转、状态修改）