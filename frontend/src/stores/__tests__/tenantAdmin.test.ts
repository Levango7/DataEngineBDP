/**
 * tenantAdmin store 单元测试（2026-09-17 新增：localStorage 假后端 → 真 API）。
 *
 * 覆盖：
 * - 加载动作：loadTenants / loadRegistrations / loadInvites（分页 {items} 拆包）
 * - 创建租户失败（403）时的错误处理（抛出可读错误 + 不污染缓存）
 * - 邀请码创建请求体（code/invitedBy 不提交）、EXPIRED 前端推导
 * - 注册提交（201 成功 / 409 冲突）与审批决策调用
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { i18n } from '@/i18n'

/** mock 整个 API 层 */
const { api } = vi.hoisted(() => ({
  api: {
    listTenants: vi.fn(),
    createTenant: vi.fn(),
    updateTenant: vi.fn(),
    deleteTenant: vi.fn(),
    listInvites: vi.fn(),
    createInvite: vi.fn(),
    cancelInvite: vi.fn(),
    previewInvite: vi.fn(),
    listRegistrations: vi.fn(),
    submitRegistration: vi.fn(),
    decideRegistration: vi.fn()
  }
}))

vi.mock('@/api/tenantAdminApi', () => api)

import { useTenantAdminStore, inviteDisplayStatus } from '../tenantAdmin'

const t = i18n.global.t

/** 构造后端契约形态的租户 */
function tenant(overrides: Record<string, unknown> = {}) {
  return {
    id: 2,
    name: 'huadong',
    displayName: '华东生产集群',
    namespace: 'huadong-prod',
    quotaProfile: 'large',
    status: 'ACTIVE',
    createdAt: '2026-09-01T08:00:00',
    updatedAt: '2026-09-10T08:00:00',
    ...overrides
  }
}

/** 构造邀请码（含后端新增的 activatedAt） */
function invite(overrides: Record<string, unknown> = {}) {
  return {
    id: 1001,
    code: 'KX7M2HQ9',
    tenantId: 2,
    role: 'USER',
    invitedBy: 'admin',
    note: '数据分析岗',
    status: 'PENDING',
    activatedAt: null,
    activatedBy: null,
    createdAt: '2026-09-15T08:00:00',
    expiresAt: '2099-09-22T08:00:00',
    ...overrides
  }
}

/** 构造注册申请 */
function registration(overrides: Record<string, unknown> = {}) {
  return {
    id: 2001,
    username: 'zhangsan',
    email: 'zhangsan@huadong.com',
    fullName: '张三',
    department: '数据科学部',
    employeeId: 'E10023',
    role: 'USER',
    tenantId: 2,
    inviteCode: 'KX7M2HQ9',
    status: 'PENDING',
    approvedBy: null,
    approveNote: null,
    createdAt: '2026-09-16T08:00:00',
    approvedAt: null,
    ...overrides
  }
}

/** 模拟 client.ts 抛出的 ApiError（duck-typing：message + httpStatus） */
function apiError(message: string, httpStatus: number): Error {
  return Object.assign(new Error(message), { httpStatus, code: httpStatus })
}

describe('stores/tenantAdmin.ts（API 层 mock）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('loadTenants / loadRegistrations 应写入缓存并供 getters 派生', async () => {
    api.listTenants.mockResolvedValue([
      tenant(),
      tenant({ id: 3, name: 'huabei', status: 'SUSPENDED' })
    ])
    api.listRegistrations.mockResolvedValue([registration()])

    const store = useTenantAdminStore()
    await store.loadTenants()
    await store.loadRegistrations()

    expect(api.listTenants).toHaveBeenCalledTimes(1)
    expect(api.listRegistrations).toHaveBeenCalledTimes(1)
    expect(store.allTenants).toHaveLength(2)
    expect(store.totalTenantCount).toBe(2)
    expect(store.activeTenantCount).toBe(1)
    expect(store.pendingRegCount).toBe(1)
    expect(store.allPendingRegs).toHaveLength(1)
  })

  it('loadInvites 应拆包分页体 {items,total,...} 并保留分页元数据', async () => {
    api.listInvites.mockResolvedValue({
      items: [invite(), invite({ id: 1002, code: 'BT4N8WL3', createdAt: '2026-09-16T08:00:00' })],
      total: 51,
      totalPages: 2,
      page: 0,
      pageSize: 50
    })

    const store = useTenantAdminStore()
    const res = await store.loadInvites({ tenantId: 2 })

    expect(api.listInvites).toHaveBeenCalledWith({ tenantId: 2 })
    expect(res.items).toHaveLength(2)
    expect(store.invites).toHaveLength(2)
    expect(store.invitePage).toEqual({ total: 51, totalPages: 2, page: 0, pageSize: 50 })
    expect(store.pendingInviteCount).toBe(2)
    // invitesByTenant 保持原签名：按租户过滤 + createdAt 倒序
    expect(store.invitesByTenant(2).map((i) => i.id)).toEqual([1002, 1001])
  })

  it('loadInvites 再次查询其他租户时应合并缓存（不丢弃先前租户的切片）', async () => {
    const store = useTenantAdminStore()
    api.listInvites.mockResolvedValueOnce({
      items: [invite()],
      total: 1,
      totalPages: 1,
      page: 0,
      pageSize: 50
    })
    await store.loadInvites({ tenantId: 2 })
    api.listInvites.mockResolvedValueOnce({
      items: [invite({ id: 1003, tenantId: 3, code: 'ZD5K9PR2' })],
      total: 1,
      totalPages: 1,
      page: 0,
      pageSize: 50
    })
    await store.loadInvites({ tenantId: 3 })

    expect(store.invites).toHaveLength(2)
    expect(store.invitesByTenant(2)).toHaveLength(1)
    expect(store.invitesByTenant(3)).toHaveLength(1)
  })

  it('EXPIRED 由前端按 expiresAt 推导（后端从不写入），且不计入待激活数', async () => {
    const expired = invite({ id: 1004, code: 'AX8B2GD7', expiresAt: '2000-01-01T00:00:00' })
    expect(inviteDisplayStatus(invite())).toBe('PENDING')
    expect(inviteDisplayStatus(expired)).toBe('EXPIRED')

    api.listInvites.mockResolvedValue({
      items: [invite(), expired],
      total: 2,
      totalPages: 1,
      page: 0,
      pageSize: 50
    })
    const store = useTenantAdminStore()
    await store.loadInvites({ tenantId: 2 })
    expect(store.pendingInviteCount).toBe(1)
  })

  it('创建租户失败（403）应抛出可读错误且不污染缓存', async () => {
    api.createTenant.mockRejectedValue(apiError('无权限访问该资源', 403))

    const store = useTenantAdminStore()
    await expect(store.createTenant({ name: 'new-tenant' })).rejects.toThrow(
      t('errors.http.forbidden')
    )
    expect(store.allTenants).toHaveLength(0)
  })

  it('创建租户成功应合并后端返回实体（仅提交后端接受的字段）', async () => {
    api.createTenant.mockResolvedValue(tenant({ id: 9, name: 'beijing-new' }))

    const store = useTenantAdminStore()
    const created = await store.createTenant({
      name: 'beijing-new',
      displayName: '北京新租户',
      quotaProfile: 'small'
    })

    expect(api.createTenant).toHaveBeenCalledWith({
      name: 'beijing-new',
      displayName: '北京新租户',
      namespace: 'beijing-new',
      quotaProfile: 'small',
      status: 'ACTIVE'
    })
    expect(created.id).toBe(9)
    expect(store.allTenants).toHaveLength(1)
  })

  it('创建邀请码应只提交 {tenantId,role,ttlDays,note}（code/invitedBy 由后端生成）', async () => {
    api.createInvite.mockResolvedValue(invite({ id: 1005, code: 'BT4N8WL3' }))

    const store = useTenantAdminStore()
    const created = await store.createInvite({
      tenantId: 2,
      role: 'TENANT_ADMIN',
      ttlDays: 7,
      note: '首账号'
    })

    expect(api.createInvite).toHaveBeenCalledWith({
      tenantId: 2,
      role: 'TENANT_ADMIN',
      ttlDays: 7,
      note: '首账号'
    })
    expect(created.code).toBe('BT4N8WL3')
    expect(store.invitesByTenant(2)).toHaveLength(1)
  })

  it('预览邀请码：404 → 不存在文案；410 → 过期/不可用文案', async () => {
    const store = useTenantAdminStore()

    api.previewInvite.mockRejectedValue(apiError('邀请码不存在', 404))
    const notFound = await store.previewInvite('zzzzzzzz')
    expect(notFound.ok).toBe(false)
    expect(notFound.error).toBe(t('tenantAdmin.invite.notFound'))
    // code 自动大写后请求
    expect(api.previewInvite).toHaveBeenCalledWith('ZZZZZZZZ')

    api.previewInvite.mockRejectedValue(apiError('邀请码已过期', 410))
    const expired = await store.previewInvite('KX7M2HQ9')
    expect(expired.ok).toBe(false)
    expect(expired.error).toBe(t('tenantAdmin.invite.expired'))
  })

  it('提交注册：201 成功 → 缓存新增注册并把邀请码置 ACTIVE', async () => {
    const store = useTenantAdminStore()
    api.listInvites.mockResolvedValue({
      items: [invite()],
      total: 1,
      totalPages: 1,
      page: 0,
      pageSize: 50
    })
    await store.loadInvites({ tenantId: 2 })
    api.submitRegistration.mockResolvedValue(registration({ id: 2002, username: 'lisi' }))

    const result = await store.submitRegistration({
      code: 'KX7M2HQ9',
      username: 'lisi',
      email: 'lisi@huadong.com',
      fullName: '李四',
      department: '工程部',
      employeeId: 'E10099'
    })

    expect(result.ok).toBe(true)
    expect(result.reg?.id).toBe(2002)
    expect(api.submitRegistration).toHaveBeenCalledWith(
      expect.objectContaining({ code: 'KX7M2HQ9', username: 'lisi' })
    )
    expect(store.registrations).toHaveLength(1)
    expect(store.invitesByTenant(2)[0].status).toBe('ACTIVE')
    expect(store.invitesByTenant(2)[0].activatedBy).toBe('lisi')
  })

  it('提交注册：409 冲突 → 返回冲突文案', async () => {
    api.submitRegistration.mockRejectedValue(apiError('邀请码已被使用或撤销', 409))

    const store = useTenantAdminStore()
    const result = await store.submitRegistration({
      code: 'KX7M2HQ9',
      username: 'lisi',
      email: 'lisi@huadong.com',
      fullName: '李四',
      department: '工程部',
      employeeId: 'E10099'
    })

    expect(result.ok).toBe(false)
    expect(result.error).toBe(t('tenantAdmin.reg.conflict'))
    expect(store.registrations).toHaveLength(0)
  })

  it('审批决策：只提交 {approved,note} 并用后端返回实体刷新缓存', async () => {
    const store = useTenantAdminStore()
    api.listRegistrations.mockResolvedValue([registration()])
    await store.loadRegistrations()
    api.decideRegistration.mockResolvedValue(
      registration({ status: 'APPROVED', approvedBy: 'tenant-admin', approveNote: 'OK' })
    )

    const result = await store.decideRegistration(2001, true, 'OK')

    expect(result.ok).toBe(true)
    // 不再传 approver（后端从 JWT/硬编码写入 approvedBy）
    expect(api.decideRegistration).toHaveBeenCalledWith(2001, { approved: true, note: 'OK' })
    expect(store.registrations[0].status).toBe('APPROVED')
    expect(store.pendingRegCount).toBe(0)
  })

  it('审批决策：404/409 → 返回对应文案', async () => {
    const store = useTenantAdminStore()

    api.decideRegistration.mockRejectedValue(apiError('not found', 404))
    const notFound = await store.decideRegistration(1, false, '')
    expect(notFound.error).toBe(t('tenantAdmin.reg.notFound'))

    api.decideRegistration.mockRejectedValue(apiError('conflict', 409))
    const conflict = await store.decideRegistration(1, false, '')
    expect(conflict.error).toBe(t('tenantAdmin.reg.alreadyDecided'))
  })

  it('删除租户成功应同时清理该租户的邀请码/注册缓存', async () => {
    const store = useTenantAdminStore()
    api.listTenants.mockResolvedValue([tenant()])
    api.listRegistrations.mockResolvedValue([registration()])
    api.listInvites.mockResolvedValue({
      items: [invite()],
      total: 1,
      totalPages: 1,
      page: 0,
      pageSize: 50
    })
    await store.loadTenants()
    await store.loadRegistrations()
    await store.loadInvites({ tenantId: 2 })
    api.deleteTenant.mockResolvedValue(undefined)

    await store.deleteTenant(2)

    expect(api.deleteTenant).toHaveBeenCalledWith(2)
    expect(store.allTenants).toHaveLength(0)
    expect(store.invites).toHaveLength(0)
    expect(store.registrations).toHaveLength(0)
  })
})
