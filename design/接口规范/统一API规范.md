# 统一 API 规范

> 归属：数据引擎大数据平台 · 接口规范
> 适用范围：前端（Vue3 + axios）与后端（Spring Boot 多模块）全部 REST 接口
> 关联文件：`frontend/src/api/client.ts`、`frontend/src/api/types.ts`、`platform/**/controller/*.java`
> 版本：v1.0  日期：2026-08-17

## 第1章 设计目标与适用范围

### 1.1 设计目标

数擎大数据平台前后端分离架构中，前端 HTTP 客户端 `frontend/src/api/client.ts` 已经按 `ApiResponse<T>` 包装协议实现响应拦截器：拦截器从响应体中读取 `code` 字段，非 0 视为业务失败并 reject；为 0 时把 `data` 字段提到 `response.data`，让业务调用方直接拿到 `T`。然而后端 Controller 普遍直接返回 `ResponseEntity<Tenant>`、`ResponseEntity<List<Tenant>>`、`ResponseEntity<SqlExecuteResponse>` 等裸对象，并未做 `ApiResponse<T>` 包装。这导致前端拦截器找不到 `code` 字段，"拆包"逻辑实际未生效，错误处理、traceId 透传、统一提示等横切能力全部失效。

本规范旨在：

1. **统一响应包装**：所有业务接口必须返回 `ApiResponse<T>` 五字段结构，杜绝裸对象直接出栈。
2. **统一请求头**：规定 `Content-Type`、`Authorization`、`X-Tenant-Id`、`Idempotency-Key` 等横切头的语义与传递规则。
3. **统一分页**：列表查询统一采用 `PagedResult<T>` 结构，字段名、含义、边界一致。
4. **统一错误码**：HTTP 状态码与业务码分离，错误码按模块分段，便于排障与国际化。
5. **统一版本管理**：URL 路径版本 `/api/v1/`，明确向后兼容边界与弃用策略。
6. **统一认证与多租户**：JWT 格式、过期刷新、租户隔离规则统一。
7. **统一幂等性**：写接口支持 `Idempotency-Key` 24h 去重。
8. **统一限流**：按租户 + API 维度限流，超限返回 429 + 业务码 42901。

### 1.2 适用范围

本规范适用于 `platform/` 下所有 Spring Boot 模块对外暴露的 REST 接口，包括但不限于：

- `encaps-layer`：认证、租户、数据源、LLMOps、知识库、集成、网关、安全等 Controller
- `infra-orchestrator`：集群供应、扩缩容、Provider 注册
- `sql-gateway`：SQL 执行、解析、优化、跨源查询
- `rule-engine`：规则、调度、Agent
- `tag-engine`、`finops/*`、`governance/*`、`flink-cdc`、`karmada` 等其余模块

健康检查端点（`/health`、`/actuator/health`）与内部 Prometheus 指标端点（`/actuator/prometheus`）不受本规范约束。

## 第2章 统一响应格式

### 2.1 ApiResponse<T> 结构定义

所有业务接口的 HTTP 响应体必须符合以下五字段结构：

```typescript
interface ApiResponse<T> {
  code: number       // 业务状态码，0 表示成功，非 0 表示业务错误
  message: string    // 提示消息，成功为 "OK"，失败为可读错误描述
  data: T | null     // 业务数据，成功时为 T，失败时为 null
  traceId: string    // 链路追踪 ID（UUID），用于日志关联与排障
  timestamp: number  // 服务器时间戳（毫秒），用于客户端时钟校准
}
```

字段顺序固定为 `code → message → data → traceId → timestamp`，便于日志肉眼对齐。`traceId` 与 `timestamp` 为必填字段，禁止省略。

### 2.2 成功响应示例

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "id": "1001",
    "name": "default-tenant",
    "planTier": "enterprise"
  },
  "traceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "timestamp": 1723891234567
}
```

### 2.3 失败响应示例

```json
{
  "code": 40101,
  "message": "登录已过期，请重新登录",
  "data": null,
  "traceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "timestamp": 1723891234567
}
```

### 2.4 HTTP 状态码与业务码的关系

HTTP 状态码反映协议层结果，业务码反映业务层结果。两者关系如下：

表：HTTP状态码与业务码对照表

| HTTP 状态 | 业务码范围 | 含义 | 是否包装 ApiResponse |
| --- | --- | --- | --- |
| 200 | 0 | 业务成功 | 是 |
| 400 | 40001–40999 | 客户端参数/语义错误 | 是 |
| 401 | 40100–40199 | 未认证或 token 失效 | 是 |
| 403 | 40300–40399 | 已认证但无权限 | 是 |
| 404 | 40400–40499 | 资源不存在 | 是 |
| 409 | 40900–40999 | 资源冲突（如唯一约束） | 是 |
| 429 | 42900–42999 | 限流 | 是 |
| 500 | 50000–50999 | 服务端内部错误 | 是 |
| 502/503/504 | 50003 | 下游依赖不可达 | 是 |

**关键约定**：即使 HTTP 状态码为 4xx/5xx，响应体仍必须是 `ApiResponse<T>` 结构（`data=null`、`code=业务码`、`message=错误描述`）。前端拦截器优先信任 `code` 字段，HTTP 状态码作为兜底。

### 2.5 后端实现要点

后端通过 `@RestControllerAdvice` 全局异常处理器 + `ResponseBodyAdvice` 包装器统一实现，业务 Controller 仍可返回裸领域对象，由包装层在出栈前自动包成 `ApiResponse<T>`。具体方案见 §9 修复方案。

## 第3章 请求规范

### 3.1 通用请求头

表：通用请求头定义表

| 头名称 | 必填 | 说明 | 示例 |
| --- | --- | --- | --- |
| `Content-Type` | 是（POST/PUT/PATCH） | 请求体 MIME 类型，统一 `application/json;charset=UTF-8` | `application/json` |
| `Authorization` | 是（除登录等白名单） | Bearer JWT，格式 `Bearer <token>` | `Bearer eyJhbGciOi...` |
| `X-Tenant-Id` | 否 | 租户 ID；优先从 JWT `tenantId` claim 解析，头仅用于运维绕行场景 | `default` |
| `X-Trace-Id` | 否 | 链路追踪 ID；未提供时后端生成 UUID 并回填响应 | `a1b2c3d4-...` |
| `Idempotency-Key` | 否（写接口推荐） | 幂等键，UUID；24h 内同租户同键命中则返回首次结果 | `f8e7d6c5-...` |
| `Accept-Language` | 否 | 国际化语言，默认 `zh-CN` | `zh-CN` |

### 3.2 请求体规范

- POST/PUT/PATCH 请求体为 JSON 对象，字段名采用 camelCase（与前端 TypeScript 一致）。
- 严禁使用 `snake_case` 或 `SCREAMING_SNAKE_CASE` 作为 JSON 字段名。
- 时间字段统一使用 ISO 8601 字符串（`2026-08-17T10:30:00Z`）或毫秒时间戳，禁止使用 `yyyy-MM-dd HH:mm:ss` 等本地化格式。
- 枚举字段使用小写字符串（如 `"active"`、`"enterprise"`），禁止使用大写常量。

### 3.3 查询参数规范

- 列表查询统一支持 `page`、`pageSize`、`keyword`、`sortBy`、`order` 五个通用参数（见 §4）。
- 布尔参数使用 `true`/`false` 字符串，禁止使用 `1`/`0` 或 `yes`/`no`。
- 时间范围参数命名 `{field}Start` / `{field}End`，如 `createdAtStart`、`createdAtEnd`。

## 第4章 分页规范

### 4.1 分页请求参数

```typescript
interface PageQuery {
  page?: number      // 页码，从 1 开始，默认 1
  pageSize?: number  // 每页条数，默认 20，最大 100
  keyword?: string   // 关键字搜索
  sortBy?: string    // 排序字段，camelCase
  order?: 'asc' | 'desc'  // 排序方向，默认 'desc'
}
```

`page` 从 1 开始（不是 0），与前端 `PagedResult` 契约一致。`pageSize` 上限 100，超过则按 100 截断并返回 40001 警告。

### 4.2 分页响应结构

```typescript
interface PagedResult<T> {
  list: T[]       // 当前页数据列表
  total: number   // 总条数
  page: number    // 当前页码
  pageSize: number // 每页条数
}
```

`PagedResult<T>` 作为 `ApiResponse<T>` 中的 `data` 字段类型出现，即完整响应为 `ApiResponse<PagedResult<Tenant>>`。

### 4.3 分页响应示例

```json
{
  "code": 0,
  "message": "OK",
  "data": {
    "list": [
      { "id": "1", "name": "tenant-a" },
      { "id": "2", "name": "tenant-b" }
    ],
    "total": 35,
    "page": 1,
    "pageSize": 20
  },
  "traceId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "timestamp": 1723891234567
}
```

### 4.4 不分页列表

少数下拉选择场景（如 `GET /api/v1/tenants/all`）返回全量列表，此时 `data` 直接为 `T[]`，不包 `PagedResult`。该类接口必须在 OpenAPI 文档中显式标注"不分页"。

## 第5章 错误码定义

### 5.1 错误码编码规则

错误码为 5 位整数，采用 `HTTP状态码前三位 + 业务序号两位` 的混合编码：

- `0`：成功
- `40001`–`40999`：客户端错误（HTTP 4xx）
- `50001`–`50999`：服务端错误（HTTP 5xx）
- `10000`–`99999`：业务错误，按模块分段（见 §5.2）

详细错误码表见独立文档 `错误码定义.md`。

### 5.2 业务错误码分段

表：业务错误码分段表

| 段 | 模块 | 示例 |
| --- | --- | --- |
| 10000–10999 | 租户管理 | 10001=租户已存在，10002=租户配额超限 |
| 11000–11999 | 作业/调度 | 11001=作业不存在，11002=作业状态非法 |
| 12000–12999 | 计算引擎 | 12001=引擎未就绪，12002=资源不足 |
| 13000–13999 | 数据源 | 13001=连接失败，13002=类型不支持 |
| 14000–14999 | SQL 网关 | 14001=SQL 解析失败，14002=方言不支持 |
| 15000–15999 | 治理/血缘 | 15001=元数据采集失败 |
| 16000–16999 | LLMOps | 16001=模型不存在，16002=微调任务失败 |
| 17000–17999 | 集群/基础设施 | 17001=集群未就绪，17002=Provider 不支持 |
| 18000–18999 | 安全/合规 | 18001=脱敏规则未配置 |
| 19000–19999 | FinOps 计费 | 19001=计费规则缺失 |
| 20000–29999 | 预留扩展 | — |

## 第6章 版本管理

### 6.1 URL 路径版本

采用 URL 路径版本策略，版本号出现在路径前缀：

```text
/api/v1/tenants
/api/v1/datasources
/api/v2/tenants   ← 未来不兼容版本
```

当前所有接口统一为 `v1`。`v2` 仅在发生不兼容变更时引入，且 `v1` 与 `v2` 并存至少 6 个月，便于客户端迁移。

### 6.2 向后兼容判定

表：变更兼容性判定表

| 变更类型 | 兼容性 | 处理方式 |
| --- | --- | --- |
| 新增可选请求字段 | 兼容 | 次版本 +1，路径不变 |
| 新增响应字段 | 兼容 | 次版本 +1，路径不变 |
| 删除字段 | 不兼容 | 主版本 +1，新路径 `/api/v2/` |
| 修改字段类型 | 不兼容 | 主版本 +1，新路径 `/api/v2/` |
| 修改字段语义 | 不兼容 | 主版本 +1，新路径 `/api/v2/` |
| 改变错误码含义 | 不兼容 | 主版本 +1 |
| 新增接口 | 兼容 | 次版本 +1 |
| 改变 HTTP 方法 | 不兼容 | 主版本 +1 |

### 6.3 弃用策略

弃用接口在响应头中返回：

```http
Deprecation: true
Sunset: Wed, 17 Feb 2027 00:00:00 GMT
Link: </api/v2/tenants>; rel="successor-version"
```

`Sunset` 头明确告知客户端该接口的下线时间，至少提前 6 个月声明。

## 第7章 认证规范

### 7.1 JWT Token 格式

平台使用 HMAC HS256 签名的 JWT（与 `AuthController` 本地回退、`JwtAuthFilter` 一致）。Token 三段式：

```text
header.payload.signature
```

- `header`：`{"alg":"HS256","typ":"JWT"}`
- `payload`：`{"sub":"userId","iss":"shuqing","iat":...,"exp":...,"tenantId":"default","preferred_username":"admin","email":"admin@local","roles":["admin"]}`
- `signature`：HMAC-SHA256(jwtSecret, header.payload)

### 7.2 Token 过期与刷新

- Access Token 默认有效期 3600 秒（`app.security.jwt.expiry`）。
- Refresh Token 由 Keycloak 颁发，有效期 7 天，仅用于换取新 Access Token，禁止用于业务请求。
- 客户端在 Access Token 过期前 5 分钟主动调用 `POST /api/v1/auth/refresh` 刷新。
- 刷新失败或 Refresh Token 过期时，前端拦截器跳转登录页（`unauthorizedHandler`）。

### 7.3 多租户隔离

- 租户 ID 优先从 JWT `tenantId` claim 解析，存入 `TenantContext`（ThreadLocal）。
- 严禁信任客户端传入的 `X-Tenant-Id` 头作为越权手段；该头仅在运维绕行场景生效，且需具备 `ops:bypass-tenant` 权限。
- 所有数据查询必须带 `tenantId` 过滤条件，JPA Repository 方法名含 `ByTenantId` 或 `findByIdAndTenantId`。
- 跨租户访问返回 40301（无权限），不返回 40401（不存在），避免信息泄露。

### 7.4 白名单端点

以下端点不需要 `Authorization` 头：

- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `GET /actuator/health`
- `GET /actuator/prometheus`

## 第8章 幂等性与限流

### 8.1 幂等性

写接口（POST/PUT/DELETE）支持 `Idempotency-Key` 头实现 24h 去重：

- 客户端为每个写请求生成 UUID v4 作为 `Idempotency-Key`。
- 后端以 `(tenantId, apiPath, idempotencyKey)` 为复合键查询 Redis。
- 命中则直接返回首次请求的完整 `ApiResponse<T>`（含相同 `traceId`），不重复执行业务逻辑。
- 未命中则执行业务，结果写入 Redis（TTL 24h）。
- 同键不同请求体返回 40901（资源冲突），提示客户端键复用错误。

### 8.2 限流

按 `(tenantId, apiPath)` 维度限流，默认配额：

表：默认限流配额表

| API 类别 | 配额 | 窗口 |
| --- | --- | --- |
| 查询类（GET） | 600 次/分钟 | 滑动窗口 |
| 写类（POST/PUT/DELETE） | 60 次/分钟 | 滑动窗口 |
| SQL 执行（`/sql/execute`） | 30 次/分钟 | 滑动窗口 |
| 集群供应（`/clusters` POST） | 5 次/分钟 | 滑动窗口 |

超限返回 HTTP 429 + `ApiResponse`：

```json
{
  "code": 42901,
  "message": "请求过于频繁，请稍后重试",
  "data": null,
  "traceId": "...",
  "timestamp": 1723891234567
}
```

响应头附带 `Retry-After: 60`，告知客户端重试等待秒数。

## 第9章 当前问题诊断与修复方案

### 9.1 问题诊断

经核查 `frontend/src/api/client.ts` 与后端 Controller 源码，发现以下不一致：

表：前后端响应格式不一致清单

| Controller | 后端实际返回 | 前端期望 | 后果 |
| --- | --- | --- | --- |
| `AuthController.login` | `ResponseEntity<?>` 裸 `LoginResult` | `ApiResponse<LoginResult>` | 拦截器找不到 `code`，不拆包；登录失败时返回 `{error:"..."}` 无 `code` 字段，前端走 HTTP 错误分支 |
| `TenantController.list` | `ResponseEntity<List<Tenant>>` | `ApiResponse<PagedResult<Tenant>>` | 拦截器不拆包，业务层拿到整个 `ApiResponse` 而非 `Tenant[]`；且无分页字段 |
| `TenantController.get` | `ResponseEntity<Tenant>` | `ApiResponse<Tenant>` | 同上 |
| `DataSourceController.list` | `ResponseEntity<List<Map>>` | `ApiResponse<PagedResult<DataSource>>` | 同上，且无 traceId/timestamp |
| `SqlGatewayController.execute` | `ResponseEntity<SqlExecuteResponse>` | `ApiResponse<SqlExecuteResponse>` | 同上 |
| `LLMOpsController.*` | `ResponseEntity<?>` 各类裸对象 | `ApiResponse<T>` | 同上 |
| `ClusterController.*` | `ResponseEntity<?>` | `ApiResponse<T>` | 同上 |

**根因**：后端缺少统一的 `ApiResponse<T>` 包装层与全局异常处理器。前端拦截器中的 `if (body && typeof body === 'object' && 'code' in body)` 判断在裸对象响应下永远走 false 分支，导致：

1. 业务错误（如 404）无法通过 `code` 字段统一提示，只能依赖 HTTP 状态码兜底。
2. `traceId` 无法透传，前后端日志无法关联。
3. 分页元信息（`total`/`page`/`pageSize`）丢失，前端无法渲染分页器。
4. `timestamp` 缺失，客户端无法做时钟校准。

### 9.2 修复方案

采用"后端统一包装 + Controller 零侵入"方案，分三步落地：

#### 9.2.1 引入 ApiResponse<T> 与 PagedResult<T> 公共类

在 `platform/common`（或 `encaps-layer` 公共包）新增：

```java
// 代码示例：统一响应包装（Java）
public record ApiResponse<T>(
    int code,
    String message,
    T data,
    String traceId,
    long timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "OK", data, TraceContext.current(), System.currentTimeMillis());
    }
    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null, TraceContext.current(), System.currentTimeMillis());
    }
}

public record PagedResult<T>(List<T> list, long total, int page, int pageSize) {}
```

#### 9.2.2 ResponseBodyAdvice 自动包装

```java
// 代码示例：响应自动包装器（Java）
@RestControllerAdvice
public class ApiResponseWrapper implements ResponseBodyAdvice<Object> {
    @Override
    public boolean supports(MethodParameter returnType, Class converterType) {
        // 跳过已是 ApiResponse、健康检查、文件下载等
        return !ApiResponse.class.isAssignableFrom(returnType.getParameterType());
    }
    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof ApiResponse) return body;
        return ApiResponse.ok(body);
    }
}
```

#### 9.2.3 全局异常处理器统一错误响应

```java
// 代码示例：全局异常处理器（Java）
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException e) {
        return ResponseEntity.status(e.httpStatus()).body(ApiResponse.fail(e.code(), e.getMessage()));
    }
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValid(MethodArgumentNotValidException e) {
        return ResponseEntity.status(400).body(ApiResponse.fail(40001, "参数校验失败"));
    }
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAll(Exception e) {
        return ResponseEntity.status(500).body(ApiResponse.fail(50001, "内部错误"));
    }
}
```

#### 9.2.4 分页接口改造

将 `TenantController.list` 等列表接口改为返回 `PagedResult<T>`：

```java
@GetMapping
public ResponseEntity<PagedResult<Tenant>> list(@Valid PageQuery q) {
    Page<Tenant> page = tenantService.page(q);
    return ResponseEntity.ok(new PagedResult<>(
        page.getContent(), page.getTotalElements(), q.getPage(), q.getPageSize()));
}
```

包装层自动再包成 `ApiResponse<PagedResult<Tenant>>`。

### 9.3 落地步骤

1. **Phase 1**：在 `platform/common` 新增 `ApiResponse`、`PagedResult`、`BizException`、`ApiResponseWrapper`、`GlobalExceptionHandler`、`TraceContext`。
2. **Phase 2**：各模块引入 `common` 依赖，删除 Controller 内 `Map.of("error", ...)` 等临时错误返回，改抛 `BizException`。
3. **Phase 3**：列表接口统一改为 `PagedResult<T>`，前端 `tenant.ts` 等调用方移除手工拆包。
4. **Phase 4**：回归测试，验证前端拦截器 `code` 判断生效、`traceId` 透传、分页器渲染正常。

### 9.4 兼容性保证

包装层对前端零侵入：前端 `client.ts` 已按 `ApiResponse<T>` 协议实现，无需改动。后端 Controller 仍可返回裸领域对象，由 `ResponseBodyAdvice` 统一包装，业务代码零改动。仅在需要返回分页时显式构造 `PagedResult`。

## 第10章 规范落地检查清单

表：规范落地检查清单

| 检查项 | 验证方法 | 责任方 |
| --- | --- | --- |
| 所有响应含 `code/message/data/traceId/timestamp` 五字段 | 抓包或集成测试断言 | 后端 |
| 成功响应 `code=0`、`message="OK"` | 同上 | 后端 |
| 失败响应 `data=null`、`code` 为 5 位业务码 | 同上 | 后端 |
| 列表接口返回 `PagedResult<T>` 含 `list/total/page/pageSize` | 同上 | 后端 |
| 写接口支持 `Idempotency-Key` 24h 去重 | 集成测试重复提交 | 后端 |
| 限流超限返回 429 + 42901 | 压测触发 | 后端 |
| JWT 含 `tenantId` claim 且 `TenantContext` 正确解析 | 单元测试 | 后端 |
| 前端拦截器 `code !== 0` 时 reject 并提示 | 前端单元测试 | 前端 |
| OpenAPI YAML 与实际接口一致 | CI 校验 | 后端 |
| 弃用接口返回 `Deprecation`/`Sunset` 头 | 抓包 | 后端 |

## 第11章 变更记录

表：文档变更记录表

| 版本 | 日期 | 变更内容 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-08-17 | 初版，定义 ApiResponse/PagedResult/错误码/版本/认证/幂等/限流/修复方案 | 接口规范师 |