/**
 * 租户管理 / 邀请码 / 注册审批 API（encaps-layer，/api/v1）。
 *
 * 与 {@link ./tenant} 的区别：tenant.ts 面向旧的分页契约（PagedResult<T>），
 * 本模块严格对应后端实际契约（platform/encaps-layer 的 Tenant / InviteCode / UserRegistration），
 * 供 tenantAdmin store 与租户管理三页（TenantManagement / Approvals / Register）使用。
 *
 * 契约要点（与后端实测一致）：
 * - 响应统一由 ApiResponseAdvice 包装为 {code,message,data}，client.ts 已按 code 拆包
 * - GET /tenants 返回裸数组；GET /invites 返回 {items,total,totalPages,page,pageSize}（page 为 0-based）
 * - POST /tenants、/invites、/registrations 返回 201；DELETE 返回 204（无 body）
 * - invitedBy 由后端从 JWT 推导、邀请码 code 由后端生成，创建邀请时均不要传
 * - 错误响应存在三套结构（框架 {code,message}、Controller Map.of("error",...)、过滤器裸 {"error":...}），
 *   统一由 client.ts 的响应拦截器归一为 ApiError(message + httpStatus)
 */
import { get, post, put, del } from './client'

/** 租户状态 */
export type TenantStatus = 'ACTIVE' | 'INACTIVE' | 'CREATING' | 'SUSPENDED'
/** 邀请码状态（EXPIRED 后端从不写入，前端按 expiresAt 推导展示） */
export type InviteStatus = 'PENDING' | 'ACTIVE' | 'EXPIRED' | 'CANCELLED'
/** 注册申请状态 */
export type RegStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
/** 用户角色 */
export type UserRole = 'PLATFORM_ADMIN' | 'TENANT_ADMIN' | 'USER'
/** 配额档位（后端为自由字符串，前端约定四档） */
export type QuotaProfile = 'small' | 'medium' | 'large' | 'xlarge'

/** 租户实体（与后端 encaps Tenant 同名同构） */
export interface Tenant {
  id: number
  name: string
  displayName: string | null
  namespace: string | null
  quotaProfile: string | null
  status: TenantStatus | null
  createdAt: string
  updatedAt: string
}

/** 创建/更新租户请求体（后端仅接收这 5 个字段，name 必填） */
export interface TenantUpsertPayload {
  name: string
  displayName?: string | null
  namespace?: string | null
  quotaProfile?: string | null
  status?: string | null
}

/** 邀请码实体（含 activatedAt，前端旧 interface 缺失） */
export interface InviteCode {
  id: number
  code: string
  tenantId: number
  role: UserRole | string
  invitedBy: string | null
  note: string | null
  status: InviteStatus | string
  activatedAt: string | null
  activatedBy: string | null
  createdAt: string
  expiresAt: string
}

/** 邀请码分页响应（后端 page 为 0-based） */
export interface InvitePage {
  items: InviteCode[]
  total: number
  totalPages: number
  page: number
  pageSize: number
}

/** 邀请码列表查询参数 */
export interface InviteListQuery {
  tenantId?: number
  status?: InviteStatus | string
  /** 页码，0-based（后端默认 0） */
  page?: number
  /** 每页条数（后端默认 50，上限 200） */
  pageSize?: number
}

/** 邀请码预览（注册页第一步；返回 tenantName/tenantCode 而非完整租户对象） */
export interface InvitePreview {
  code: string
  role: UserRole | string
  tenantId: number
  tenantName: string
  tenantCode: string
  note: string | null
  expiresAt: string
}

/** 注册申请实体 */
export interface UserRegistration {
  id: number
  username: string
  email: string
  fullName: string
  department: string | null
  employeeId: string | null
  role: UserRole | string
  tenantId: number
  inviteCode: string
  status: RegStatus | string
  approvedBy: string | null
  approveNote: string | null
  createdAt: string
  approvedAt: string | null
}

/** 提交注册请求体 */
export interface RegistrationSubmitPayload {
  code: string
  username: string
  email: string
  fullName: string
  department: string
  employeeId: string
}

/** 注册列表查询参数（后端无分页） */
export interface RegistrationQuery {
  tenantId?: number
  status?: RegStatus | string
}

/** 审批决策请求体（approvedBy 由后端硬编码写入，前端不传） */
export interface DecisionPayload {
  approved: boolean
  note?: string
}

/** 租户资源根路径 */
const BASE_TENANTS = '/tenants'
/** 邀请码资源根路径 */
const BASE_INVITES = '/invites'
/** 注册资源根路径 */
const BASE_REGS = '/registrations'

/* ------------------------------ 租户 ------------------------------ */

/** 查询租户列表（裸数组） */
export function listTenants(): Promise<Tenant[]> {
  return get<Tenant[]>(BASE_TENANTS)
}

/** 创建租户（201） */
export function createTenant(data: TenantUpsertPayload): Promise<Tenant> {
  return post<Tenant>(BASE_TENANTS, data)
}

/** 更新租户（name 必填，后端会整字段覆盖） */
export function updateTenant(id: number, data: TenantUpsertPayload): Promise<Tenant> {
  return put<Tenant>(`${BASE_TENANTS}/${id}`, data)
}

/** 删除租户（204，无 body） */
export function deleteTenant(id: number): Promise<void> {
  return del<void>(`${BASE_TENANTS}/${id}`)
}

/* ------------------------------ 邀请码 ------------------------------ */

/** 查询邀请码列表（分页体 {items,...}，page 0-based） */
export function listInvites(query?: InviteListQuery): Promise<InvitePage> {
  return get<InvitePage>(BASE_INVITES, query as Record<string, unknown> | undefined)
}

/** 创建邀请码（201；code 由后端生成、invitedBy 从 JWT 取） */
export function createInvite(data: {
  tenantId: number
  role: UserRole | string
  ttlDays?: number
  note?: string
}): Promise<InviteCode> {
  return post<InviteCode>(BASE_INVITES, data)
}

/** 撤销邀请码（204，无 body；后端置为 CANCELLED） */
export function cancelInvite(id: number): Promise<void> {
  return del<void>(`${BASE_INVITES}/${id}`)
}

/** 邀请码预览（不消耗；code 自动大写；404 不存在 / 410 不可用或已过期） */
export function previewInvite(code: string): Promise<InvitePreview> {
  return get<InvitePreview>(`${BASE_INVITES}/${encodeURIComponent(code.toUpperCase())}/preview`)
}

/* ------------------------------ 注册与审批 ------------------------------ */

/** 查询注册列表（裸数组，无分页） */
export function listRegistrations(query?: RegistrationQuery): Promise<UserRegistration[]> {
  return get<UserRegistration[]>(BASE_REGS, query as Record<string, unknown> | undefined)
}

/** 提交注册申请（201；404 邀请码不存在 / 409 冲突 / 410 已过期） */
export function submitRegistration(data: RegistrationSubmitPayload): Promise<UserRegistration> {
  return post<UserRegistration>(BASE_REGS, data)
}

/** 审批决策（200；仅接受 {approved,note}） */
export function decideRegistration(id: number, data: DecisionPayload): Promise<UserRegistration> {
  return post<UserRegistration>(`${BASE_REGS}/${id}/decision`, data)
}
