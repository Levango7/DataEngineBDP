/**
 * 租户 + 邀请 + 注册 + 审批一体化 Admin Store（2026-09-17 切真后端）。
 *
 * 数据来源：platform/encaps-layer 的 /api/v1/tenants、/invites、/registrations，
 * 经 {@link @/api/tenantAdminApi} 类型化封装（底层复用 {@link @/api/client} 的
 * 鉴权、拆包与错误提示）。
 *
 * 设计原则：
 * - state 仅作前端缓存（供 getters 派生），不做任何 localStorage 持久化，刷新即重新拉取
 * - getters 名称与签名保持不变（页面在用）
 * - 后端不存在 type / adminUsername / userCount / storageQuotaGb 字段，
 *   在 AdminTenant 中保留为可选遗留字段，页面只读展示缺失时显示 “—”
 * - 后端从不写 EXPIRED：邀请码展示状态由 inviteDisplayStatus() 按 expiresAt 推导
 * - 时间字段为后端 ISO 字符串，前端展示时自行 new Date()
 */
import { defineStore } from 'pinia'
import { i18n } from '@/i18n'
import {
  listTenants,
  createTenant as apiCreateTenant,
  updateTenant as apiUpdateTenant,
  deleteTenant as apiDeleteTenant,
  listInvites,
  createInvite as apiCreateInvite,
  cancelInvite as apiCancelInvite,
  previewInvite as apiPreviewInvite,
  listRegistrations,
  submitRegistration as apiSubmitRegistration,
  decideRegistration as apiDecideRegistration
} from '@/api/tenantAdminApi'
import type {
  Tenant as ApiTenant,
  InviteCode,
  InvitePage,
  InvitePreview,
  TenantStatus,
  InviteStatus,
  RegStatus,
  UserRole,
  UserRegistration
} from '@/api/tenantAdminApi'

/** store 内使用的 i18n 翻译函数（store 不在组件上下文内，不能用 useI18n()） */
const t = i18n.global.t

/* ==================== 类型定义 ==================== */

export type { TenantStatus, InviteStatus, RegStatus, UserRole }

/** 遗留租户类型：后端已无该字段，仅为兼容旧 UI（列表/详情只读展示） */
export type TenantType = 'XINCHUANG' | 'PRIVATE' | 'PUBLIC' | 'GOVERNMENT' | 'INTERNAL'

export interface AdminTenant {
  id: number
  name: string
  displayName: string
  namespace: string
  quotaProfile: string
  status: TenantStatus | string
  createdAt: string
  updatedAt: string
  /* -------- 以下为后端不存在的遗留字段（页面只读展示，缺失显示 “—”） -------- */
  type?: TenantType
  adminUsername?: string
  userCount?: number
  storageQuotaGb?: number
}

/** 邀请码（与后端 InviteCode 同构，比旧 interface 多 activatedAt） */
export type AdminInvite = InviteCode

/** 注册申请（与后端 UserRegistration 同构） */
export type AdminRegistration = UserRegistration

/** 邀请码预览结果（后端返回 tenantName/tenantCode，不是完整租户对象） */
export interface InvitePreviewResult {
  ok: boolean
  preview?: InvitePreview
  error?: string
}

/** 无数据返回的动作结果 */
export interface MutationResult {
  ok: boolean
  error?: string
}

/** 提交注册结果 */
export interface SubmitRegistrationResult {
  ok: boolean
  reg?: AdminRegistration
  error?: string
}

interface State {
  tenants: AdminTenant[]
  invites: AdminInvite[]
  registrations: AdminRegistration[]
  /** 最近一次邀请码分页查询的元数据（后端 page 为 0-based） */
  invitePage: { total: number; totalPages: number; page: number; pageSize: number }
}

/* ==================== 工具函数 ==================== */

/** 从任意错误对象中提取 HTTP 状态码（client.ts 的 ApiError 带 httpStatus） */
function errorStatus(err: unknown): number | null {
  const status = (err as { httpStatus?: unknown } | null)?.httpStatus
  return typeof status === 'number' ? status : null
}

/** 提取可展示的错误文案（client.ts 已做脱敏/i18n 兜底） */
function errorMessage(err: unknown): string | null {
  const msg = (err as { message?: unknown } | null)?.message
  return typeof msg === 'string' && msg.trim() ? msg : null
}

/** 统一转换为用户可读的 Error（CRUD 动作向页面抛出，页面 catch 后提示） */
function toFriendlyError(err: unknown, fallbackKey: string): Error {
  const status = errorStatus(err)
  if (status === 401) return new Error(t('errors.http.unauthorized'))
  if (status === 403) return new Error(t('errors.http.forbidden'))
  if (status === 404) return new Error(t('tenantAdmin.tenant.notFound'))
  return new Error(errorMessage(err) || t(fallbackKey))
}

/** namespace 缺省推导（与后端 K8s namespace 命名习惯一致） */
function deriveNamespace(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]/g, '-')
}

/** 后端租户实体 → store 缓存形态（空值归一为 ''） */
function normalizeTenant(raw: ApiTenant): AdminTenant {
  return {
    id: raw.id,
    name: raw.name ?? '',
    displayName: raw.displayName ?? '',
    namespace: raw.namespace ?? '',
    quotaProfile: raw.quotaProfile ?? '',
    status: raw.status ?? '',
    createdAt: raw.createdAt ?? '',
    updatedAt: raw.updatedAt ?? ''
  }
}

/**
 * 邀请码展示状态：后端从不写 EXPIRED，
 * PENDING 且 expiresAt 已过期的邀请码在前端展示为 EXPIRED。
 */
export function inviteDisplayStatus(invite: AdminInvite): string {
  if (
    invite.status === 'PENDING' &&
    invite.expiresAt &&
    new Date(invite.expiresAt).getTime() < Date.now()
  ) {
    return 'EXPIRED'
  }
  return invite.status
}

/* ==================== Store ==================== */

export const useTenantAdminStore = defineStore('tenantAdmin', {
  state: (): State => ({
    tenants: [],
    invites: [],
    registrations: [],
    invitePage: { total: 0, totalPages: 0, page: 0, pageSize: 0 }
  }),

  getters: {
    allTenants: (s) => s.tenants,
    allPendingRegs: (s) => s.registrations.filter((r) => r.status === 'PENDING'),
    invitesByTenant(): (tenantId: number) => AdminInvite[] {
      return (tenantId: number) =>
        this.invites
          .filter((i) => i.tenantId === tenantId)
          .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
    },
    pendingByTenant(): (tenantId: number) => AdminRegistration[] {
      return (tenantId: number) =>
        this.registrations
          .filter((r) => r.tenantId === tenantId && r.status === 'PENDING')
          .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''))
    },
    totalTenantCount: (s) => s.tenants.length,
    activeTenantCount: (s) => s.tenants.filter((tenant) => tenant.status === 'ACTIVE').length,
    pendingInviteCount: (s) => s.invites.filter((i) => inviteDisplayStatus(i) === 'PENDING').length,
    pendingRegCount: (s) => s.registrations.filter((r) => r.status === 'PENDING').length
  },

  actions: {
    /* ===== 加载（缓存刷新） ===== */

    /** 加载租户列表（裸数组） */
    async loadTenants(): Promise<AdminTenant[]> {
      const items = await listTenants()
      this.tenants = (items ?? []).map(normalizeTenant)
      return this.tenants
    },

    /** 加载注册列表（裸数组，无分页） */
    async loadRegistrations(params?: {
      tenantId?: number
      status?: RegStatus | string
    }): Promise<AdminRegistration[]> {
      const items = await listRegistrations(params)
      this.registrations = items ?? []
      return this.registrations
    },

    /**
     * 加载邀请码一页（{items,total,totalPages,page,pageSize} 拆包），
     * 并按 tenantId 替换本地缓存中该租户的切片。
     * 注意：平台管理员不传 tenantId 时后端返回 403（无法全域遍历）。
     */
    async loadInvites(params?: {
      tenantId?: number
      status?: InviteStatus | string
      page?: number
      pageSize?: number
    }): Promise<InvitePage> {
      const res = await listInvites(params)
      const items = res?.items ?? []
      // 按租户切片替换：本次查询涉及的租户（含显式 tenantId）的旧缓存全部让位，
      // 其余租户切片保留；按 id 去重防止分页重叠导致重复项
      const replaced = new Set<number>(items.map((i) => i.tenantId))
      if (params?.tenantId != null) replaced.add(params.tenantId)
      const merged = new Map<number, AdminInvite>()
      for (const inv of this.invites) {
        if (!replaced.has(inv.tenantId)) merged.set(inv.id, inv)
      }
      for (const inv of items) merged.set(inv.id, inv)
      this.invites = Array.from(merged.values())
      this.invitePage = {
        total: res?.total ?? items.length,
        totalPages: res?.totalPages ?? 1,
        page: res?.page ?? params?.page ?? 0,
        pageSize: res?.pageSize ?? params?.pageSize ?? items.length
      }
      return res
    },

    /**
     * 逐租户合并加载邀请码（平台管理员无法无参查询全域邀请码，后端 403）。
     * 单个租户失败不影响其余租户（allSettled + 全局错误提示）。
     */
    async loadInvitesForTenants(tenantIds: number[], pageSize = 200): Promise<void> {
      if (!tenantIds.length) return
      await Promise.allSettled(
        tenantIds.map((tenantId) => this.loadInvites({ tenantId, pageSize }))
      )
    },

    /* ===== 租户 CRUD ===== */

    /** 创建租户（仅提交后端接受的 name/displayName/namespace/quotaProfile/status） */
    async createTenant(payload: {
      name: string
      displayName?: string
      namespace?: string
      quotaProfile?: string
      status?: TenantStatus | string
    }): Promise<AdminTenant> {
      try {
        const created = await apiCreateTenant({
          name: payload.name,
          displayName: payload.displayName ?? null,
          namespace: payload.namespace || deriveNamespace(payload.name),
          quotaProfile: payload.quotaProfile ?? null,
          status: payload.status ?? 'ACTIVE'
        })
        const tenant = normalizeTenant(created)
        this.tenants = [...this.tenants.filter((x) => x.id !== tenant.id), tenant]
        return tenant
      } catch (err) {
        throw toFriendlyError(err, 'tenantAdmin.common.requestFailed')
      }
    },

    /**
     * 更新租户：先与本地缓存合并，再把后端接受的 5 个字段整体 PUT（后端为整字段覆盖）。
     * 遗留字段（type/userCount/storageQuotaGb/adminUsername）不会提交。
     */
    async updateTenant(id: number, patch: Partial<AdminTenant>): Promise<AdminTenant> {
      const current = this.tenants.find((x) => x.id === id)
      if (!current && !patch.name) {
        throw new Error(t('tenantAdmin.tenant.notFound'))
      }
      const merged = { ...current, ...patch }
      try {
        const updated = await apiUpdateTenant(id, {
          name: merged.name ?? '',
          displayName: merged.displayName ?? null,
          namespace: merged.namespace ?? null,
          quotaProfile: merged.quotaProfile ?? null,
          status: merged.status ?? null
        })
        const tenant = normalizeTenant(updated)
        const idx = this.tenants.findIndex((x) => x.id === id)
        if (idx >= 0) this.tenants.splice(idx, 1, tenant)
        else this.tenants = [...this.tenants, tenant]
        return tenant
      } catch (err) {
        throw toFriendlyError(err, 'tenantAdmin.common.requestFailed')
      }
    },

    /** 删除租户（204 无 body），成功后就地清理该租户的邀请码/注册缓存 */
    async deleteTenant(id: number): Promise<void> {
      try {
        await apiDeleteTenant(id)
      } catch (err) {
        throw toFriendlyError(err, 'tenantAdmin.common.requestFailed')
      }
      this.tenants = this.tenants.filter((tenant) => tenant.id !== id)
      this.invites = this.invites.filter((i) => i.tenantId !== id)
      this.registrations = this.registrations.filter((r) => r.tenantId !== id)
    },

    /** 启停租户（复用 PUT /tenants/{id}） */
    async setTenantStatus(id: number, status: TenantStatus): Promise<AdminTenant> {
      return await this.updateTenant(id, { status })
    },

    /* ===== 邀请码 ===== */

    /** 创建邀请码（code 由后端生成、invitedBy 从 JWT 取，均不提交） */
    async createInvite(payload: {
      tenantId: number
      role: UserRole | string
      note?: string
      ttlDays?: number
    }): Promise<AdminInvite> {
      try {
        const invite = await apiCreateInvite({
          tenantId: payload.tenantId,
          role: payload.role,
          note: payload.note,
          ttlDays: payload.ttlDays
        })
        this.invites = [invite, ...this.invites.filter((i) => i.id !== invite.id)]
        return invite
      } catch (err) {
        throw toFriendlyError(err, 'tenantAdmin.common.requestFailed')
      }
    },

    /** 撤销邀请码（DELETE → 后端置 CANCELLED），同步更新本地缓存状态 */
    async cancelInvite(id: number): Promise<void> {
      try {
        await apiCancelInvite(id)
      } catch (err) {
        throw toFriendlyError(err, 'tenantAdmin.common.requestFailed')
      }
      const inv = this.invites.find((i) => i.id === id)
      if (inv) inv.status = 'CANCELLED'
    },

    /** 员工输入码时预览（不消耗；404 不存在、410 不可用/已过期） */
    async previewInvite(code: string): Promise<InvitePreviewResult> {
      const upper = code.toUpperCase().trim()
      try {
        const preview = await apiPreviewInvite(upper)
        return { ok: true, preview }
      } catch (err) {
        const status = errorStatus(err)
        if (status === 404) return { ok: false, error: t('tenantAdmin.invite.notFound') }
        if (status === 410) return { ok: false, error: t('tenantAdmin.invite.expired') }
        if (status === 401) return { ok: false, error: t('errors.http.unauthorized') }
        return { ok: false, error: errorMessage(err) ?? t('tenantAdmin.invite.notFound') }
      }
    },

    /* ===== 注册申请 ===== */

    /** 提交注册申请（后端 404/409/410 映射为明确文案；成功时邀请码已由后端置 ACTIVE） */
    async submitRegistration(payload: {
      code: string
      username: string
      email: string
      fullName: string
      department: string
      employeeId: string
    }): Promise<SubmitRegistrationResult> {
      try {
        const reg = await apiSubmitRegistration(payload)
        this.registrations = [reg, ...this.registrations.filter((r) => r.id !== reg.id)]
        const inv = this.invites.find((i) => i.code?.toUpperCase() === payload.code.toUpperCase())
        if (inv) {
          inv.status = 'ACTIVE'
          inv.activatedBy = payload.username
          inv.activatedAt = new Date().toISOString()
        }
        return { ok: true, reg }
      } catch (err) {
        const status = errorStatus(err)
        if (status === 404) return { ok: false, error: t('tenantAdmin.invite.notFound') }
        if (status === 409) return { ok: false, error: t('tenantAdmin.reg.conflict') }
        if (status === 410) return { ok: false, error: t('tenantAdmin.invite.expired') }
        return { ok: false, error: errorMessage(err) ?? t('tenantAdmin.common.requestFailed') }
      }
    },

    /* ===== 审批 ===== */

    /** 审批（后端只接受 {approved,note}，approvedBy 由后端写入） */
    async decideRegistration(id: number, approved: boolean, note: string): Promise<MutationResult> {
      try {
        const updated = await apiDecideRegistration(id, { approved, note })
        const idx = this.registrations.findIndex((r) => r.id === id)
        if (idx >= 0) this.registrations.splice(idx, 1, updated)
        else this.registrations = [updated, ...this.registrations]
        return { ok: true }
      } catch (err) {
        const status = errorStatus(err)
        if (status === 404) return { ok: false, error: t('tenantAdmin.reg.notFound') }
        if (status === 409) return { ok: false, error: t('tenantAdmin.reg.alreadyDecided') }
        return { ok: false, error: errorMessage(err) ?? t('tenantAdmin.common.requestFailed') }
      }
    }
  }
})
