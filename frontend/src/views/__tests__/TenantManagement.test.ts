/**
 * TenantManagement.vue 单元测试（2026-09-17 重写：localStorage 假后端 → 真 API 层）。
 *
 * 测试策略：mock @/api/tenantAdminApi（真实调用断言），页面与 store 走真实逻辑。
 * - 旧版断言 localStorage 持久化（seed/resetSeed/persist）已全部随假后端移除
 * - 覆盖：挂载加载（listTenants/listRegistrations/listInvites）、指标卡、加载失败提示、
 *   创建租户 + 首账号邀请码的提交字段（不再提交 type/storageQuotaGb/invitedBy）
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia, type Pinia } from 'pinia'
import ElementPlus, { ElMessage } from 'element-plus'
import { createI18n } from 'vue-i18n'
import tenantZh from '@/i18n/locales/modules/tenantManagement.zh-CN.json'
import tenantEn from '@/i18n/locales/modules/tenantManagement.en-US.json'
import TenantManagement from '../TenantManagement.vue'
import { useTenantAdminStore } from '@/stores/tenantAdmin'

/** mock 整个 API 层（store 唯一数据来源） */
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

const TENANTS = [
  {
    id: 1,
    name: 'platform',
    displayName: '平台自营',
    namespace: 'platform-system',
    quotaProfile: 'xlarge',
    status: 'ACTIVE',
    createdAt: '2026-09-01T08:00:00',
    updatedAt: '2026-09-10T08:00:00'
  },
  {
    id: 2,
    name: 'huadong',
    displayName: '华东生产集群',
    namespace: 'huadong-prod',
    quotaProfile: 'large',
    status: 'ACTIVE',
    createdAt: '2026-09-02T08:00:00',
    updatedAt: '2026-09-11T08:00:00'
  },
  {
    id: 3,
    name: 'huabei',
    displayName: '华北测试集群',
    namespace: 'huabei-staging',
    quotaProfile: 'medium',
    status: 'SUSPENDED',
    createdAt: '2026-09-03T08:00:00',
    updatedAt: '2026-09-12T08:00:00'
  }
]

const INVITE_PAGE = {
  items: [
    {
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
      expiresAt: '2099-09-22T08:00:00'
    }
  ],
  total: 1,
  totalPages: 1,
  page: 0,
  pageSize: 200
}

const REGS = [
  {
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
    approvedAt: null
  }
]

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': tenantZh as never,
    'en-US': tenantEn as never
  }
})

function mountComponent(pinia: Pinia): VueWrapper {
  return mount(TenantManagement, {
    global: {
      plugins: [pinia, i18n, ElementPlus]
    }
  })
}

describe('TenantManagement.vue（API 层 mock）', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.listTenants.mockResolvedValue(structuredClone(TENANTS))
    api.listRegistrations.mockResolvedValue(structuredClone(REGS))
    // 邀请码按 tenantId 返回（模拟后端租户隔离；本次数据仅租户 2 有邀请码）
    api.listInvites.mockImplementation((params?: { tenantId?: number }) =>
      Promise.resolve(
        params?.tenantId === 2
          ? structuredClone(INVITE_PAGE)
          : { items: [], total: 0, totalPages: 0, page: 0, pageSize: 200 }
      )
    )
  })

  it('挂载时应通过 API 加载租户/注册/邀请数据', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mountComponent(pinia)
    await flushPromises()

    expect(api.listTenants).toHaveBeenCalledTimes(1)
    expect(api.listRegistrations).toHaveBeenCalledTimes(1)
    // 邀请码为分页且平台超管无法全域查询：逐租户拉取（3 个租户 → 3 次）
    expect(api.listInvites).toHaveBeenCalledTimes(3)
    expect(api.listInvites).toHaveBeenCalledWith({ tenantId: 2, pageSize: 200 })

    const store = useTenantAdminStore()
    expect(store.allTenants).toHaveLength(3)
    expect(store.registrations).toHaveLength(1)
    expect(wrapper.text()).toContain('租户管理')
  })

  it('指标卡应派生自 API 数据（总数/活跃/待激活邀请/待审注册）', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const wrapper = mountComponent(pinia)
    await flushPromises()

    const store = useTenantAdminStore()
    expect(store.totalTenantCount).toBe(3)
    expect(store.activeTenantCount).toBe(2)
    expect(store.pendingInviteCount).toBe(1)
    expect(store.pendingRegCount).toBe(1)
    const text = wrapper.text()
    expect(text).toContain('3')
    expect(text).toContain('2')
  })

  it('列表加载失败时应提示错误（不静默）', async () => {
    api.listTenants.mockRejectedValue(new Error('network down'))
    const pinia = createPinia()
    setActivePinia(pinia)
    mountComponent(pinia)
    await flushPromises()

    expect(ElMessage.error).toHaveBeenCalled()
    const store = useTenantAdminStore()
    expect(store.allTenants).toHaveLength(0)
  })

  it('创建租户 + 首账号邀请码应走 API，且不提交后端不存在的字段', async () => {
    api.createTenant.mockResolvedValue({
      id: 9,
      name: 'beijing-new',
      displayName: '北京新租户',
      namespace: 'beijing-new',
      quotaProfile: 'small',
      status: 'ACTIVE',
      createdAt: '2026-09-17T08:00:00',
      updatedAt: '2026-09-17T08:00:00'
    })
    api.createInvite.mockResolvedValue({
      id: 1005,
      code: 'BT4N8WL3',
      tenantId: 9,
      role: 'TENANT_ADMIN',
      invitedBy: 'admin',
      note: '首账号',
      status: 'PENDING',
      activatedAt: null,
      activatedBy: null,
      createdAt: '2026-09-17T08:00:00',
      expiresAt: '2026-09-24T08:00:00'
    })

    const pinia = createPinia()
    setActivePinia(pinia)
    mountComponent(pinia)
    await flushPromises()

    const store = useTenantAdminStore()
    const tenant = await store.createTenant({
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
    // 后端不接受 type / storageQuotaGb
    const payload = api.createTenant.mock.calls[0][0] as Record<string, unknown>
    expect(payload).not.toHaveProperty('type')
    expect(payload).not.toHaveProperty('storageQuotaGb')
    expect(tenant.id).toBe(9)
    expect(store.allTenants).toHaveLength(4)

    const invite = await store.createInvite({
      tenantId: 9,
      role: 'TENANT_ADMIN',
      note: '首账号',
      ttlDays: 7
    })
    // code 由后端生成、invitedBy 从 JWT 取：均不得出现在请求体
    expect(api.createInvite).toHaveBeenCalledWith({
      tenantId: 9,
      role: 'TENANT_ADMIN',
      note: '首账号',
      ttlDays: 7
    })
    expect(invite.code).toBe('BT4N8WL3')
    expect(store.invitesByTenant(9)).toHaveLength(1)
  })
})
