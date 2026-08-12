/**
 * tenant.ts 单元测试
 *
 * 测试租户 API 模块的各方法调用参数和返回值
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock client 模块
const mockGet = vi.fn(() => Promise.resolve({ list: [], total: 0, page: 1, pageSize: 20 }))
const mockPost = vi.fn(() => Promise.resolve({ id: 't1', name: '新租户' }))
const mockPut = vi.fn(() => Promise.resolve({ id: 't1', name: '更新租户' }))
const mockDel = vi.fn(() => Promise.resolve())

vi.mock('@/api/client', () => ({
  get: mockGet,
  post: mockPost,
  put: mockPut,
  del: mockDel
}))

describe('api/tenant.ts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('listTenants 应调用 GET /tenants', async () => {
    const { listTenants } = await import('@/api/tenant')
    await listTenants({ page: 1, pageSize: 20 })
    expect(mockGet).toHaveBeenCalledWith(
      '/tenants',
      expect.objectContaining({ page: 1, pageSize: 20 })
    )
  })

  it('listAllTenants 应调用 GET /tenants/all', async () => {
    const { listAllTenants } = await import('@/api/tenant')
    await listAllTenants()
    expect(mockGet).toHaveBeenCalledWith('/tenants/all')
  })

  it('getTenant 应调用 GET /tenants/:id', async () => {
    const { getTenant } = await import('@/api/tenant')
    await getTenant('t1')
    expect(mockGet).toHaveBeenCalledWith('/tenants/t1')
  })

  it('createTenant 应调用 POST /tenants', async () => {
    const { createTenant } = await import('@/api/tenant')
    const data = { name: '新租户', code: 'new-tenant', plan: 'enterprise' as const }
    await createTenant(data)
    expect(mockPost).toHaveBeenCalledWith('/tenants', data)
  })

  it('updateTenant 应调用 PUT /tenants/:id', async () => {
    const { updateTenant } = await import('@/api/tenant')
    const data = { name: '更新租户' }
    await updateTenant('t1', data)
    expect(mockPut).toHaveBeenCalledWith('/tenants/t1', data)
  })

  it('deleteTenant 应调用 DELETE /tenants/:id', async () => {
    const { deleteTenant } = await import('@/api/tenant')
    await deleteTenant('t1')
    expect(mockDel).toHaveBeenCalledWith('/tenants/t1')
  })

  it('listTenants 支持带状态筛选参数', async () => {
    const { listTenants } = await import('@/api/tenant')
    await listTenants({ status: 'active', page: 1, pageSize: 10 })
    expect(mockGet).toHaveBeenCalledWith('/tenants', expect.objectContaining({ status: 'active' }))
  })
})
