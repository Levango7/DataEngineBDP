/**
 * 开放 API 服务目录 API 客户端
 *
 * 对应后端 platform/open-api-catalog/，L5.5 开放 API 服务目录。
 */
import { get, post, put, del } from './client'

/** API 资源根路径 */
const BASE = '/apis'

/** 订阅资源根路径 */
const SUB_BASE = '/subscriptions'

// ---------- 类型定义 ----------

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE' | 'PATCH'
export type APIStatus =
  | 'draft'
  | 'reviewing'
  | 'approved'
  | 'rejected'
  | 'published'
  | 'running'
  | 'deprecated'
  | 'archived'
  | 'offline'
export type AuthType = 'api_key' | 'jwt' | 'oauth2' | 'none'
export type SLALevel = 'platinum' | 'gold' | 'silver'
export type CostStrategy = 'by_call' | 'by_bytes' | 'monthly_package'
export type SubscriptionStatus =
  'pending' | 'approved' | 'active' | 'suspended' | 'rejected' | 'revoked'
export type ParamLocation = 'path' | 'query' | 'header' | 'body'
export type ParamType = 'string' | 'integer' | 'number' | 'boolean' | 'array' | 'object'

/** API 参数 */
export interface APIParam {
  name: string
  location: ParamLocation
  type: ParamType
  required: boolean
  description?: string
  default?: unknown
  example?: unknown
  enum?: unknown[]
}

/** API 响应 */
export interface APIResponse {
  statusCode: number
  description?: string
  schema?: Record<string, unknown>
  example?: unknown
}

/** API 上游 */
export interface APIUpstream {
  type: string
  url: string
  method: HttpMethod
  timeout?: number
  retries?: number
}

/** API 定义 */
export interface APIDefinition {
  id: string
  name: string
  version: string
  description?: string
  category: string
  tags: string[]
  method: HttpMethod
  path: string
  params: APIParam[]
  responses: APIResponse[]
  authType: AuthType
  upstream: APIUpstream
  sla: SLALevel
  costStrategy: CostStrategy
  costUnitPrice: number
  monthlyQuota?: number
  status: APIStatus
  providerTenantId: string
  callCount: number
  errorCount: number
  totalLatencyMs: number
  totalTrafficBytes: number
  createdAt: string
  updatedAt: string
}

/** API 订阅 */
export interface APISubscription {
  id: string
  apiId: string
  subscriberId: string
  subscriberTenantId: string
  providerTenantId: string
  purpose: string
  quotaExpect: number
  status: SubscriptionStatus
  accessKey?: string
  secretKey?: string
  approveReason?: string
  approvedBy?: string
  grantedQuota: number
  callCount: number
  errorCount: number
  lastCalledAt?: string
  createdAt: string
  updatedAt: string
}

/** API 计量 */
export interface APIMetrics {
  apiId: string
  callCount: number
  successCount: number
  errorCount: number
  errorRate: number
  successRate: number
  avgLatencyMs: number
  p99LatencyMs: number
  totalTrafficBytes: number
  totalCost: number
  lastCalledAt?: string
  byConsumer: ConsumerMetrics[]
  timeseries: MetricPoint[]
}

/** 消费者计量 */
export interface ConsumerMetrics {
  consumerTenantId: string
  subscriptionId: string
  callCount: number
  errorCount: number
  avgLatencyMs: number
  totalCost: number
}

/** 计量时间点 */
export interface MetricPoint {
  timestamp: string
  callCount: number
  errorCount: number
  avgLatencyMs: number
}

/** 调用结果 */
export interface CallResult {
  callId: string
  statusCode: number
  latencyMs: number
  result?: Record<string, unknown>
  error?: string
  costAmount: number
}

// ---------- API 列表查询 ----------

export interface APIListQuery {
  name?: string
  category?: string
  tag?: string
  status?: APIStatus
  providerTenantId?: string
  keyword?: string
  limit?: number
  offset?: number
}

// ---------- API ----------

/**
 * 列出 API（服务目录浏览）
 */
export function listApis(params?: APIListQuery): Promise<APIDefinition[]> {
  return get<APIDefinition[]>(BASE, params as Record<string, unknown>)
}

/**
 * 获取 API 详情
 */
export function getApi(id: string): Promise<APIDefinition> {
  return get<APIDefinition>(`${BASE}/${id}`)
}

/**
 * 注册 API
 */
export function registerApi(data: Partial<APIDefinition>): Promise<APIDefinition> {
  return post<APIDefinition>(BASE, data)
}

/**
 * 更新 API
 */
export function updateApi(id: string, data: Partial<APIDefinition>): Promise<APIDefinition> {
  return put<APIDefinition>(`${BASE}/${id}`, data)
}

/**
 * 注销 API
 */
export function deleteApi(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/**
 * 提交安全审核
 */
export function submitReview(id: string): Promise<APIDefinition> {
  return post<APIDefinition>(`${BASE}/${id}/submit-review`)
}

/**
 * 审核通过
 */
export function approveApi(id: string): Promise<APIDefinition> {
  return post<APIDefinition>(`${BASE}/${id}/approve`)
}

/**
 * 审核驳回
 */
export function rejectApi(id: string): Promise<APIDefinition> {
  return post<APIDefinition>(`${BASE}/${id}/reject`)
}

/**
 * 发布 API 到网关
 */
export function publishApi(id: string): Promise<APIDefinition> {
  return post<APIDefinition>(`${BASE}/${id}/publish`)
}

/**
 * 废弃 API
 */
export function deprecateApi(id: string): Promise<APIDefinition> {
  return post<APIDefinition>(`${BASE}/${id}/deprecate`)
}

/**
 * 归档 API
 */
export function archiveApi(id: string): Promise<APIDefinition> {
  return post<APIDefinition>(`${BASE}/${id}/archive`)
}

// ---------- 订阅 ----------

/**
 * 申请订阅
 */
export function subscribeApi(
  id: string,
  data: { subscriberId: string; subscriberTenantId: string; purpose: string; quotaExpect: number }
): Promise<APISubscription> {
  return post<APISubscription>(`${BASE}/${id}/subscribe`, data)
}

/**
 * 订阅者列表
 */
export function listSubscribers(id: string): Promise<APISubscription[]> {
  return get<APISubscription[]>(`${BASE}/${id}/subscribers`)
}

/**
 * 列出订阅
 */
export function listSubscriptions(params?: {
  apiId?: string
  subscriberId?: string
  subscriberTenantId?: string
  status?: SubscriptionStatus
  limit?: number
  offset?: number
}): Promise<APISubscription[]> {
  return get<APISubscription[]>(SUB_BASE, params as Record<string, unknown>)
}

/**
 * 审批订阅
 */
export function approveSubscription(
  id: string,
  data: { approve: boolean; reason?: string; grantedQuota?: number; approver: string }
): Promise<APISubscription> {
  return post<APISubscription>(`${SUB_BASE}/${id}/approve`, data)
}

/**
 * 暂停订阅
 */
export function suspendSubscription(id: string): Promise<APISubscription> {
  return post<APISubscription>(`${SUB_BASE}/${id}/suspend`)
}

/**
 * 恢复订阅
 */
export function resumeSubscription(id: string): Promise<APISubscription> {
  return post<APISubscription>(`${SUB_BASE}/${id}/resume`)
}

/**
 * 吊销订阅
 */
export function revokeSubscription(id: string): Promise<APISubscription> {
  return post<APISubscription>(`${SUB_BASE}/${id}/revoke`)
}

// ---------- 调用与计量 ----------

/**
 * 调用 API（鉴权 + 限流 + 计量 + 转发）
 */
export function callApi(
  id: string,
  data: { payload?: Record<string, unknown>; headers?: Record<string, string> },
  apiKey: string
): Promise<CallResult> {
  return post<CallResult>(`${BASE}/${id}/call`, data, {
    headers: { 'X-API-Key': apiKey }
  })
}

/**
 * 获取 API 调用计量
 */
export function getMetrics(
  id: string,
  params?: { range?: string; consumerTenantId?: string }
): Promise<APIMetrics> {
  return get<APIMetrics>(`${BASE}/${id}/metrics`, params as Record<string, unknown>)
}

// ---------- 文档与 APISIX 配置 ----------

/**
 * 获取 API 文档（OpenAPI 3.0）
 */
export function getApiDocs(id: string, format?: 'openapi' | 'markdown'): Promise<unknown> {
  return get<unknown>(`${BASE}/${id}/docs`, format ? { format } : undefined)
}

/**
 * APISIX 部署结果。
 *
 * 后端（`services/apisix_config.py:252-281`）在 APISIX Admin 不可达时**仍回 HTTP 200**，
 * 只把成败写进 `status`/`deployed`/`message`——注释原话是"网络错误显式回报，不伪装成功"。
 * 所以调用方必须以 `status` 为准；只看 HTTP 码会把"没下发成功"报成"已部署"。
 */
export interface ApisixDeployResult {
  action: 'deploy' | 'undeploy'
  routeId: string
  apisixAdminUrl?: string
  status: 'success' | 'failed'
  deployed: boolean
  message: string
  error?: string | null
}

/**
 * 获取 APISIX 路由配置
 */
export function getApisixConfig(id: string): Promise<Record<string, unknown>> {
  return get<Record<string, unknown>>(`${BASE}/${id}/apisix-config`)
}

/**
 * 部署 APISIX 路由
 */
export function deployApisixRoute(id: string): Promise<ApisixDeployResult> {
  return post<ApisixDeployResult>(`${BASE}/${id}/apisix-deploy`)
}

/**
 * 卸载 APISIX 路由（`metrics_docs.py:102`，原客户端只有部署没有卸载）
 */
export function undeployApisixRoute(id: string): Promise<ApisixDeployResult> {
  return del<ApisixDeployResult>(`${BASE}/${id}/apisix-deploy`)
}

// ---------- 一键生成 API（`generate.py`，前缀 `/apis/generate`） ----------

/** `GET /apis/generate/options` 的三组候选值 */
export interface GenerateOptions {
  datasources: string[]
  modelTypes: string[]
  runtimes: string[]
}

/** 三种生成方式共有的字段（后端三个 Body 模型在这几项上逐字同形） */
export interface GenerateCommon {
  name: string
  providerTenantId: string
  description?: string
  category?: string
  tags?: string[]
  costStrategy?: CostStrategy
  costUnitPrice?: number
  sla?: SLALevel
}

export interface SqlGenerateRequest extends GenerateCommon {
  sql: string
  datasource: string
}

export interface ModelGenerateRequest extends GenerateCommon {
  modelId: string
  modelType: string
  inputSchema?: Record<string, unknown> | null
}

export interface FunctionGenerateRequest extends GenerateCommon {
  functionName: string
  runtime: string
  /** 后端 `ge=1000, le=900000`（毫秒） */
  timeout?: number
  /** 后端 `ge=128, le=32768`（MB） */
  memoryMB?: number
}

export function getGenerateOptions(): Promise<GenerateOptions> {
  return get<GenerateOptions>(`${BASE}/generate/options`)
}

/** 三条生成端点都直接返回新建的 API 定义（201） */
export function generateFromSql(data: SqlGenerateRequest): Promise<APIDefinition> {
  return post<APIDefinition>(`${BASE}/generate/sql`, data)
}

export function generateFromModel(data: ModelGenerateRequest): Promise<APIDefinition> {
  return post<APIDefinition>(`${BASE}/generate/model`, data)
}

export function generateFromFunction(data: FunctionGenerateRequest): Promise<APIDefinition> {
  return post<APIDefinition>(`${BASE}/generate/function`, data)
}

// ---------- 按 API 的计费配置（`billing.py:304/367`，前缀 `/apis`） ----------

export interface BillingConfigRequest {
  costStrategy: CostStrategy
  costUnitPrice: number
  /** 仅 monthly_package 有意义；后端 `ge=0` */
  monthlyQuota?: number | null
}

export interface BillingConfigResponse extends BillingConfigRequest {
  apiId: string
  description: string
  updatedAt: string
}

export function getBillingConfig(id: string): Promise<BillingConfigResponse> {
  return get<BillingConfigResponse>(`${BASE}/${id}/billing`)
}

export function setBillingConfig(
  id: string,
  data: BillingConfigRequest
): Promise<BillingConfigResponse> {
  return put<BillingConfigResponse>(`${BASE}/${id}/billing`, data)
}

// ---------- 订阅详情 / AK·SK / 限流（`billing.py` 的 `/subscriptions` 半边） ----------

export interface IssueKeyRequest {
  reason?: string
  /** 后端必填（`Field(...)`），页面取当前登录用户 */
  operator: string
}

/** 只有颁发响应里带明文 SK，之后 `GET /keys` 只回 `hasSecretKey` */
export interface IssueKeyResponse {
  subscriptionId: string
  accessKey: string
  secretKey: string
  issuedAt: string
  reason: string
}

export interface KeyInfoResponse {
  subscriptionId: string
  accessKey: string | null
  hasSecretKey: boolean
  status: string
}

export interface RateLimitConfig {
  /** 后端 `ge=1, le=100000` */
  qps: number
  /** 0 = 不限并发 */
  concurrent: number
  /** 0 = 与 qps 同值 */
  burst: number
}

export interface RateLimitConfigResponse extends RateLimitConfig {
  subscriptionId: string
  updatedAt: string
}

export function getSubscription(id: string): Promise<APISubscription> {
  return get<APISubscription>(`${SUB_BASE}/${id}`)
}

export function issueSubscriptionKey(id: string, data: IssueKeyRequest): Promise<IssueKeyResponse> {
  return post<IssueKeyResponse>(`${SUB_BASE}/${id}/keys`, data)
}

export function getSubscriptionKeys(id: string): Promise<KeyInfoResponse> {
  return get<KeyInfoResponse>(`${SUB_BASE}/${id}/keys`)
}

export function setRateLimit(id: string, data: RateLimitConfig): Promise<RateLimitConfigResponse> {
  return put<RateLimitConfigResponse>(`${SUB_BASE}/${id}/rate-limit`, data)
}

export function getRateLimit(id: string): Promise<RateLimitConfigResponse> {
  return get<RateLimitConfigResponse>(`${SUB_BASE}/${id}/rate-limit`)
}
