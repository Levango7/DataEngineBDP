/**
 * 租户 + 邀请 + 注册 + 审批一体化 Admin Store（2026-09-07）。
 *
 * 与 src/stores/tenant.ts 区分：后者是轻量级租户选择器（管当前选中），
 * 本 store 负责租户/邀请/注册/审批的 CRUD + 持久化。
 *
 * 设计原则：
 * - 单 store 集中管理 4 类实体，避免 4 个 store 的状态联动复杂度
 * - localStorage 持久化，刷新不丢
 * - mock 后端：使用 crypto.randomUUID + 8 位 Base32 生成邀请码
 *   （生产后端实现在 /api/v1/invites + /api/v1/registrations，
 *    本 store 等价模拟其行为；后端就绪后切真接口只需替换 action）
 * - 时间字段用 ISO 字符串，前端展示用 new Date()
 */
import { defineStore } from 'pinia'
import { i18n } from '@/i18n'

/** store 内使用的 i18n 翻译函数（store 不在组件上下文内，不能用 useI18n()） */
const t = i18n.global.t

const STORAGE_KEY = 'sq_tenant_admin_v1'

/* ==================== 类型定义 ==================== */

export type TenantType = 'XINCHUANG' | 'PRIVATE' | 'PUBLIC' | 'GOVERNMENT' | 'INTERNAL'
export type TenantStatus = 'ACTIVE' | 'INACTIVE' | 'CREATING' | 'SUSPENDED'
export type InviteStatus = 'PENDING' | 'ACTIVE' | 'EXPIRED' | 'CANCELLED'
export type RegStatus = 'PENDING' | 'APPROVED' | 'REJECTED'
export type UserRole = 'PLATFORM_ADMIN' | 'TENANT_ADMIN' | 'USER'

export interface AdminTenant {
  id: number
  name: string
  displayName: string
  namespace: string
  type: TenantType
  status: TenantStatus
  quotaProfile: 'small' | 'medium' | 'large' | 'xlarge'
  adminUsername: string
  userCount: number
  storageQuotaGb: number
  createdAt: string
  updatedAt: string
}

export interface AdminInvite {
  id: number
  code: string
  tenantId: number
  role: UserRole
  invitedBy: string
  note: string
  status: InviteStatus
  activatedBy?: string
  createdAt: string
  expiresAt: string
}

export interface AdminRegistration {
  id: number
  username: string
  email: string
  fullName: string
  department: string
  employeeId: string
  role: UserRole
  tenantId: number
  inviteCode: string
  status: RegStatus
  approvedBy?: string
  approveNote?: string
  createdAt: string
  approvedAt?: string
}

interface State {
  tenants: AdminTenant[]
  invites: AdminInvite[]
  registrations: AdminRegistration[]
  nextIds: { tenant: number; invite: number; registration: number }
}

/* ==================== 工具函数 ==================== */

const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'

function genCode(): string {
  let out = ''
  const buf = new Uint8Array(8)
  crypto.getRandomValues(buf)
  for (let i = 0; i < 8; i++) out += ALPHABET[buf[i] % ALPHABET.length]
  return out
}

function nowIso(offsetDays = 0): string {
  return new Date(Date.now() + offsetDays * 86400_000).toISOString()
}

function loadFromStorage(): State {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw) as State
  } catch {
    /* 解析失败用默认值 */
  }
  return seedInitial()
}

function saveToStorage(state: State): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(state))
  } catch {
    /* 持久化失败忽略 */
  }
}

/** 初始演示数据：1 个平台 demo + 2 个示例租户，4 张邀请码 + 1 条待审注册 */
function seedInitial(): State {
  // @i18n-ignore mock-data — 以下 displayName/note/fullName/department 为演示用静态数据，不参与 i18n
  const platformTenant: AdminTenant = {
    id: 1,
    name: 'platform',
    displayName: '平台自营',
    namespace: 'platform-system',
    type: 'INTERNAL',
    status: 'ACTIVE',
    quotaProfile: 'xlarge',
    adminUsername: 'admin',
    userCount: 1,
    storageQuotaGb: 9999,
    createdAt: nowIso(-90),
    updatedAt: nowIso(-1)
  }
  const huadong: AdminTenant = {
    id: 2,
    name: 'huadong',
    displayName: '华东生产集群',
    namespace: 'huadong-prod',
    type: 'XINCHUANG',
    status: 'ACTIVE',
    quotaProfile: 'large',
    adminUsername: 'huadong-admin',
    userCount: 8,
    storageQuotaGb: 5000,
    createdAt: nowIso(-30),
    updatedAt: nowIso(-3)
  }
  const huabei: AdminTenant = {
    id: 3,
    name: 'huabei',
    displayName: '华北测试集群',
    namespace: 'huabei-staging',
    type: 'PRIVATE',
    status: 'CREATING',
    quotaProfile: 'medium',
    adminUsername: '',
    userCount: 0,
    storageQuotaGb: 1000,
    createdAt: nowIso(-2),
    updatedAt: nowIso(-1)
  }

  const inv1: AdminInvite = {
    id: 1001,
    code: 'KX7M2HQ9',
    tenantId: 2,
    role: 'USER',
    invitedBy: 'huadong-admin',
    note: '数据分析岗',
    status: 'PENDING',
    createdAt: nowIso(-1),
    expiresAt: nowIso(6)
  }
  const inv2: AdminInvite = {
    id: 1002,
    code: 'BT4N8WL3',
    tenantId: 2,
    role: 'TENANT_ADMIN',
    invitedBy: 'platform-admin',
    note: '租户超管候补',
    status: 'PENDING',
    createdAt: nowIso(-3),
    expiresAt: nowIso(4)
  }
  const inv3: AdminInvite = {
    id: 1003,
    code: 'ZD5K9PR2',
    tenantId: 3,
    role: 'TENANT_ADMIN',
    invitedBy: 'platform-admin',
    note: '华北测试集群首账号',
    status: 'PENDING',
    createdAt: nowIso(-1),
    expiresAt: nowIso(6)
  }
  const inv4: AdminInvite = {
    id: 1004,
    code: 'AX8B2GD7',
    tenantId: 2,
    role: 'USER',
    invitedBy: 'huadong-admin',
    note: '已被张三激活',
    status: 'ACTIVE',
    activatedBy: 'zhangsan',
    createdAt: nowIso(-5),
    expiresAt: nowIso(2)
  }

  const reg1: AdminRegistration = {
    id: 2001,
    username: 'zhangsan',
    email: 'zhangsan@huadong.com',
    fullName: '张三',
    department: '数据科学部',
    employeeId: 'E10023',
    role: 'USER',
    tenantId: 2,
    inviteCode: 'AX8B2GD7',
    status: 'PENDING',
    createdAt: nowIso(-2)
  }

  return {
    tenants: [platformTenant, huadong, huabei],
    invites: [inv1, inv2, inv3, inv4],
    registrations: [reg1],
    nextIds: { tenant: 4, invite: 1005, registration: 2002 }
  }
}

/* ==================== Store ==================== */

export const useTenantAdminStore = defineStore('tenantAdmin', {
  state: (): State => loadFromStorage(),

  getters: {
    allTenants: (s) => s.tenants,
    allPendingRegs: (s) => s.registrations.filter((r) => r.status === 'PENDING'),
    invitesByTenant(): (tenantId: number) => AdminInvite[] {
      return (tenantId: number) =>
        this.invites
          .filter((i) => i.tenantId === tenantId)
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    },
    pendingByTenant(): (tenantId: number) => AdminRegistration[] {
      return (tenantId: number) =>
        this.registrations
          .filter((r) => r.tenantId === tenantId && r.status === 'PENDING')
          .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    },
    totalTenantCount: (s) => s.tenants.length,
    activeTenantCount: (s) => s.tenants.filter((tenant) => tenant.status === 'ACTIVE').length,
    pendingInviteCount: (s) => s.invites.filter((i) => i.status === 'PENDING').length,
    pendingRegCount: (s) => s.registrations.filter((r) => r.status === 'PENDING').length
  },

  actions: {
    /* ===== 租户 CRUD ===== */
    createTenant(payload: {
      name: string
      displayName: string
      type: TenantType
      quotaProfile: AdminTenant['quotaProfile']
      storageQuotaGb: number
    }): AdminTenant {
      const tenant: AdminTenant = {
        id: ++this.nextIds.tenant,
        name: payload.name,
        displayName: payload.displayName,
        namespace: payload.name.toLowerCase().replace(/[^a-z0-9]/g, '-'),
        type: payload.type,
        status: 'ACTIVE',
        quotaProfile: payload.quotaProfile,
        adminUsername: '',
        userCount: 0,
        storageQuotaGb: payload.storageQuotaGb,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString()
      }
      this.tenants.push(tenant)
      this.persist()
      return tenant
    },

    updateTenant(id: number, patch: Partial<AdminTenant>): void {
      const tenant = this.tenants.find((x) => x.id === id)
      if (!tenant) return
      Object.assign(tenant, patch, { updatedAt: new Date().toISOString() })
      this.persist()
    },

    deleteTenant(id: number): void {
      this.tenants = this.tenants.filter((tenant) => tenant.id !== id)
      this.invites = this.invites.filter((i) => i.tenantId !== id)
      this.registrations = this.registrations.filter((r) => r.tenantId !== id)
      this.persist()
    },

    setTenantStatus(id: number, status: TenantStatus): void {
      this.updateTenant(id, { status })
    },

    /* ===== 邀请码 ===== */
    createInvite(payload: {
      tenantId: number
      role: UserRole
      note: string
      ttlDays?: number
      invitedBy: string
    }): AdminInvite {
      const ttl = payload.ttlDays ?? 7
      const invite: AdminInvite = {
        id: ++this.nextIds.invite,
        code: genCode(),
        tenantId: payload.tenantId,
        role: payload.role,
        invitedBy: payload.invitedBy,
        note: payload.note,
        status: 'PENDING',
        createdAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + ttl * 86400_000).toISOString()
      }
      this.invites.push(invite)
      this.persist()
      return invite
    },

    cancelInvite(id: number): void {
      const inv = this.invites.find((i) => i.id === id)
      if (!inv) return
      inv.status = 'CANCELLED'
      this.persist()
    },

    /** 员工输入码时预览（不消耗） */
    previewInvite(code: string): {
      ok: boolean
      invite?: AdminInvite
      tenant?: AdminTenant
      error?: string
    } {
      const upper = code.toUpperCase().trim()
      const invite = this.invites.find((i) => i.code === upper)
      if (!invite) return { ok: false, error: t('tenantAdmin.invite.notFound') }
      if (invite.status === 'CANCELLED')
        return { ok: false, invite, error: t('tenantAdmin.invite.cancelled') }
      if (invite.status === 'ACTIVE')
        return { ok: false, invite, error: t('tenantAdmin.invite.used') }
      if (invite.status === 'EXPIRED')
        return { ok: false, invite, error: t('tenantAdmin.invite.expired') }
      if (new Date(invite.expiresAt) < new Date()) {
        invite.status = 'EXPIRED'
        this.persist()
        return { ok: false, invite, error: t('tenantAdmin.invite.expired') }
      }
      const tenant = this.tenants.find((item) => item.id === invite.tenantId)
      return { ok: true, invite, tenant }
    },

    /* ===== 注册申请 ===== */
    submitRegistration(payload: {
      code: string
      username: string
      email: string
      fullName: string
      department: string
      employeeId: string
    }): { ok: boolean; reg?: AdminRegistration; error?: string } {
      const preview = this.previewInvite(payload.code)
      if (!preview.ok) return { ok: false, error: preview.error }
      const inv = preview.invite!
      const exists = this.registrations.find(
        (r) => r.tenantId === inv.tenantId && r.username === payload.username
      )
      if (exists) return { ok: false, error: t('tenantAdmin.reg.usernameExists') }

      const reg: AdminRegistration = {
        id: ++this.nextIds.registration,
        username: payload.username,
        email: payload.email,
        fullName: payload.fullName,
        department: payload.department,
        employeeId: payload.employeeId,
        role: inv.role,
        tenantId: inv.tenantId,
        inviteCode: inv.code,
        status: 'PENDING',
        createdAt: new Date().toISOString()
      }
      this.registrations.push(reg)

      inv.status = 'ACTIVE'
      inv.activatedBy = payload.username
      this.persist()
      return { ok: true, reg }
    },

    /* ===== 审批 ===== */
    decideRegistration(
      id: number,
      approved: boolean,
      approver: string,
      note: string
    ): { ok: boolean; error?: string } {
      const reg = this.registrations.find((r) => r.id === id)
      if (!reg) return { ok: false, error: t('tenantAdmin.reg.notFound') }
      if (reg.status !== 'PENDING') return { ok: false, error: t('tenantAdmin.reg.alreadyDecided') }
      reg.status = approved ? 'APPROVED' : 'REJECTED'
      reg.approvedBy = approver
      reg.approveNote = note
      reg.approvedAt = new Date().toISOString()
      if (approved) {
        const tenant = this.tenants.find((item) => item.id === reg.tenantId)
        if (tenant) {
          tenant.userCount += 1
          tenant.updatedAt = new Date().toISOString()
          if (reg.role === 'TENANT_ADMIN' && !tenant.adminUsername) {
            tenant.adminUsername = reg.username
          }
        }
      }
      this.persist()
      return { ok: true }
    },

    /** 演示用：清空 localStorage 恢复出厂 */
    resetSeed(): void {
      try {
        localStorage.removeItem(STORAGE_KEY)
      } catch {
        /* 忽略 */
      }
      const seed = seedInitial()
      this.tenants = seed.tenants
      this.invites = seed.invites
      this.registrations = seed.registrations
      this.nextIds = seed.nextIds
    },

    persist(): void {
      saveToStorage({
        tenants: this.tenants,
        invites: this.invites,
        registrations: this.registrations,
        nextIds: this.nextIds
      })
    }
  }
})
