/**
 * tenant store 单元测试
 *
 * 测试租户状态管理：拉取列表、切换租户、重置状态
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTenantStore } from '../tenant'

// Mock localStorage
const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: vi.fn((key: string) => store[key] ?? null),
    setItem: vi.fn((key: string, value: string) => {
      store[key] = value
    }),
    removeItem: vi.fn((key: string) => {
      delete store[key]
    }),
    clear: vi.fn(() => {
      store = {}
    })
  }
})()

Object.defineProperty(globalThis, 'localStorage', { value: localStorageMock })

// Mock tenant API
const mockTenants = [
  {
    id: 't1',
    name: '华东生产集群',
    code: 'east-prod',
    plan: 'enterprise' as const,
    status: 'active' as const,
    workspaceCount: 5,
    userCount: 20,
    resourceUsage: 65,
    createdAt: '2024-01-01',
    updatedAt: '2024-06-01'
  },
  {
    id: 't2',
    name: '测试租户',
    code: 'test-tenant',
    plan: 'standard' as const,
    status: 'active' as const,
    workspaceCount: 1,
    userCount: 3,
    resourceUsage: 30,
    createdAt: '2024-03-01',
    updatedAt: '2024-06-01'
  }
]

vi.mock('@/api/tenant', () => ({
  listAllTenants: vi.fn(() => Promise.resolve(mockTenants)),
  listTenants: vi.fn(() => Promise.resolve({ list: mockTenants, total: 2, page: 1, pageSize: 20 })),
  getTenant: vi.fn(() => Promise.resolve(mockTenants[0])),
  createTenant: vi.fn(() => Promise.resolve({ id: 't3', name: '新租户' })),
  updateTenant: vi.fn(() => Promise.resolve(mockTenants[0])),
  deleteTenant: vi.fn(() => Promise.resolve())
}))

describe('stores/tenant.ts', () => {
  beforeEach(() => {
    localStorageMock.clear()
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('初始状态：空列表，无选中租户', () => {
    const store = useTenantStore()
    expect(store.tenants).toEqual([])
    expect(store.currentTenant).toBeNull()
    expect(store.loading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('fetchTenants 应加载租户列表', async () => {
    const store = useTenantStore()
    await store.fetchTenants(true)
    expect(store.tenants.length).toBe(2)
    expect(store.tenants[0].name).toBe('华东生产集群')
    expect(store.loading).toBe(false)
  })

  it('fetchTenants 首次加载应自动选中第一个租户', async () => {
    const store = useTenantStore()
    await store.fetchTenants(true)
    expect(store.currentTenant).not.toBeNull()
    expect(store.currentTenant?.id).toBe('t1')
  })

  it('selectTenant 应切换当前租户', async () => {
    const store = useTenantStore()
    await store.fetchTenants(true)
    store.selectTenant('t2')
    expect(store.currentTenant?.id).toBe('t2')
    expect(store.currentTenant?.name).toBe('测试租户')
  })

  it('selectTenant 应持久化选中租户 ID', async () => {
    const store = useTenantStore()
    await store.fetchTenants(true)
    store.selectTenant('t2')
    expect(localStorageMock.setItem).toHaveBeenCalledWith('sq_current_tenant', 't2')
  })

  it('selectTenant 不存在的 ID 不应改变当前租户', async () => {
    const store = useTenantStore()
    await store.fetchTenants(true)
    const before = store.currentTenant
    store.selectTenant('non-existent')
    expect(store.currentTenant).toEqual(before)
  })

  it('reset 应清除所有状态', async () => {
    const store = useTenantStore()
    await store.fetchTenants(true)
    store.reset()
    expect(store.tenants).toEqual([])
    expect(store.currentTenant).toBeNull()
    expect(store.error).toBeNull()
    expect(localStorageMock.removeItem).toHaveBeenCalledWith('sq_current_tenant')
  })

  it('fetchTenants 非强制且有缓存时不应重复请求', async () => {
    const store = useTenantStore()
    await store.fetchTenants(true)
    const { listAllTenants } = await import('@/api/tenant')
    const callCount = (listAllTenants as ReturnType<typeof vi.fn>).mock.calls.length
    await store.fetchTenants(false)
    expect((listAllTenants as ReturnType<typeof vi.fn>).mock.calls.length).toBe(callCount)
  })

  it('fetchTenants 失败应设置 error 状态', async () => {
    const { listAllTenants } = await import('@/api/tenant')
    ;(listAllTenants as ReturnType<typeof vi.fn>).mockRejectedValueOnce(new Error('网络错误'))
    const store = useTenantStore()
    await store.fetchTenants(true)
    expect(store.error).toBe('网络错误')
    expect(store.loading).toBe(false)
  })

  it('fetchTenants 应从 localStorage 恢复上次选中的租户', async () => {
    localStorageMock.setItem('sq_current_tenant', 't2')
    const store = useTenantStore()
    await store.fetchTenants(true)
    expect(store.currentTenant).not.toBeNull()
    expect(store.currentTenant?.id).toBe('t2')
    expect(store.currentTenant?.name).toBe('测试租户')
  })
})
