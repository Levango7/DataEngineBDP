/**
 * 租户管理 API
 */
import { get, post, put, del } from './client'
import type {
  Tenant,
  TenantListQuery,
  CreateTenantParams,
  UpdateTenantParams,
  PagedResult
} from './types'

/** 租户资源根路径 */
const BASE = '/tenants'

/**
 * 查询租户列表（分页）
 * @param params 查询参数
 */
export function listTenants(params?: TenantListQuery): Promise<PagedResult<Tenant>> {
  return get<PagedResult<Tenant>>(BASE, params as Record<string, unknown>)
}

/**
 * 查询全部租户（不分页，用于下拉选择）
 */
export function listAllTenants(): Promise<Tenant[]> {
  return get<Tenant[]>(`${BASE}/all`)
}

/**
 * 获取租户详情
 * @param id 租户 ID
 */
export function getTenant(id: string): Promise<Tenant> {
  return get<Tenant>(`${BASE}/${id}`)
}

/**
 * 创建租户
 * @param data 租户信息
 */
export function createTenant(data: CreateTenantParams): Promise<Tenant> {
  return post<Tenant>(BASE, data)
}

/**
 * 更新租户
 * @param id 租户 ID
 * @param data 待更新字段
 */
export function updateTenant(id: string, data: UpdateTenantParams): Promise<Tenant> {
  return put<Tenant>(`${BASE}/${id}`, data)
}

/**
 * 删除租户
 * @param id 租户 ID
 */
export function deleteTenant(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}
