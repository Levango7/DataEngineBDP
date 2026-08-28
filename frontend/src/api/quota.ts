/**
 * 配额 API
 *
 * 对应后端 REST 端点（encaps-layer QuotaController）：
 * - POST   /quotas                          — 设置配额
 * - GET    /quotas                          — 列表（支持 tenantId/workspaceId 过滤）
 * - GET    /quotas/{id}                      — 详情
 * - PUT    /quotas/{id}                      — 更新
 * - DELETE /quotas/{id}                      — 删除（级联删除 K8s ResourceQuota + LimitRange）
 * - GET    /quotas/workspace/{workspaceId}/usage — 查询当前用量
 */
import { get, post, put, del } from './client'
import type { Quota, QuotaListQuery, SetQuotaParams, UpdateQuotaParams, QuotaUsage } from './types'
// 类型定义见 @/api/types.ts（项目约定：避免循环依赖）

/** 配额资源根路径 */
const BASE = '/quotas'

/**
 * 查询配额列表
 * @param params 查询参数（tenantId / workspaceId 过滤）
 */
export function listQuotas(params?: QuotaListQuery): Promise<Quota[]> {
  return get<Quota[]>(BASE, params as Record<string, unknown>)
}

/**
 * 获取配额详情
 * @param id 配额 ID
 */
export function getQuota(id: string): Promise<Quota> {
  return get<Quota>(`${BASE}/${id}`)
}

/**
 * 设置配额
 *
 * 后端将 Quota 翻译为 K8s ResourceQuota + LimitRange。
 * @param data 配额信息
 */
export function setQuota(data: SetQuotaParams): Promise<Quota> {
  return post<Quota>(BASE, data)
}

/**
 * 更新配额（仅可变字段：配额字段 + per-Pod 限制）
 * @param id 配额 ID
 * @param data 待更新字段
 */
export function updateQuota(id: string, data: UpdateQuotaParams): Promise<Quota> {
  return put<Quota>(`${BASE}/${id}`, data)
}

/**
 * 删除配额（级联删除 K8s ResourceQuota + LimitRange）
 * @param id 配额 ID
 */
export function deleteQuota(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/**
 * 查询 Workspace 当前资源用量（已用 / 配额）
 * @param workspaceId Workspace ID
 * @returns 用量信息 { used: {...}, hard: {...} }
 */
export function getQuotaUsage(workspaceId: string): Promise<QuotaUsage> {
  return get<QuotaUsage>(`${BASE}/workspace/${workspaceId}/usage`)
}
