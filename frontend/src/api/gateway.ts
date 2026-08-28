/**
 * 大模型网关 API
 *
 * 对应后端 platform/gateway/ L4.5 大模型网关：
 * - API Key 管理
 * - 路由配置
 * - 调用统计
 */
import { get, post, put, del } from './client'

/** Key 状态 */
export type KeyStatus = 'enabled' | 'disabled' | 'pending'

/** 调用统计 */
export interface GatewayStats {
  /** 今日调用数 */
  todayCallCount: number
  /** 平均时延（毫秒） */
  avgLatencyMs: number
  /** 成功率（百分比） */
  successRate: number
  /** 活跃 Key 数 */
  activeKeyCount: number
}

/** API Key */
export interface ApiKey {
  /** Key ID */
  id: string
  /** Key 名称 */
  name: string
  /** 路由模型 */
  routeModel: string
  /** 限流（次/秒） */
  rateLimit: number
  /** 状态 */
  status: KeyStatus
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt?: string
  /** 权限范围 */
  scope?: string
  /** apiKey（调用方持有） */
  apiKey?: string
  /** secret（仅创建时一次性返回明文，之后为 ***） */
  secret?: string
  /** 是否一次性展示 secret（仅创建响应为 true） */
  secretShownOnce?: boolean
}

/** 创建 Key 参数 */
export interface CreateApiKeyParams {
  name: string
  routeModel: string
  rateLimit: number
  /** 权限范围（可选） */
  scope?: string
}

/** 更新 Key 参数 */
export interface UpdateApiKeyParams {
  name?: string
  routeModel?: string
  rateLimit?: number
  status?: KeyStatus
  /** 权限范围 */
  scope?: string
}

/** 资源根路径 */
const BASE = '/gateway'

/**
 * 获取网关调用统计
 */
export function getStats(): Promise<GatewayStats> {
  return get<GatewayStats>(`${BASE}/stats`)
}

/**
 * 查询 API Key 列表
 */
export function listApiKeys(): Promise<ApiKey[]> {
  return get<ApiKey[]>(`${BASE}/keys`)
}

/**
 * 创建 API Key
 */
export function createApiKey(data: CreateApiKeyParams): Promise<ApiKey> {
  return post<ApiKey>(`${BASE}/keys`, data)
}

/**
 * 更新 API Key
 */
export function updateApiKey(id: string, data: UpdateApiKeyParams): Promise<ApiKey> {
  return put<ApiKey>(`${BASE}/keys/${id}`, data)
}

/**
 * 删除 API Key
 */
export function deleteApiKey(id: string): Promise<void> {
  return del<void>(`${BASE}/keys/${id}`)
}
