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
  | 'pending'
  | 'approved'
  | 'active'
  | 'suspended'
  | 'rejected'
  | 'revoked'
export type ParamLocation = 'path' | 'query' | 'header' | 'body'
export type ParamType =
  | 'string'
  | 'integer'
  | 'number'
  | 'boolean'
  | 'array'
  | 'object'

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
 * 获取 APISIX 路由配置
 */
export function getApisixConfig(id: string): Promise<Record<string, unknown>> {
  return get<Record<string, unknown>>(`${BASE}/${id}/apisix-config`)
}

/**
 * 部署 APISIX 路由
 */
export function deployApisixRoute(id: string): Promise<Record<string, unknown>> {
  return post<Record<string, unknown>>(`${BASE}/${id}/apisix-deploy`)
}