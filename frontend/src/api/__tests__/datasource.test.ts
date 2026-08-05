/**
 * datasource.ts 单元测试
 *
 * 测试数据源 API 模块的各方法调用参数和返回值
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock client 模块
const mockGet = vi.fn(() => Promise.resolve({ list: [], total: 0, page: 1, pageSize: 20 }))
const mockPost = vi.fn(() => Promise.resolve({ id: 'ds1', name: '新数据源' }))
const mockPut = vi.fn(() => Promise.resolve({ id: 'ds1', name: '更新数据源' }))
const mockDel = vi.fn(() => Promise.resolve())

vi.mock('@/api/client', () => ({
  get: mockGet,
  post: mockPost,
  put: mockPut,
  del: mockDel
}))

describe('api/datasource.ts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('listDataSources 应调用 GET /datasources', async () => {
    const { listDataSources } = await import('@/api/datasource')
    await listDataSources({ page: 1, pageSize: 20 })
    expect(mockGet).toHaveBeenCalledWith(
      '/datasources',
      expect.objectContaining({ page: 1, pageSize: 20 })
    )
  })

  it('getDataSource 应调用 GET /datasources/:id', async () => {
    const { getDataSource } = await import('@/api/datasource')
    await getDataSource('ds1')
    expect(mockGet).toHaveBeenCalledWith('/datasources/ds1')
  })

  it('createDataSource 应调用 POST /datasources', async () => {
    const { createDataSource } = await import('@/api/datasource')
    const data = {
      name: '新数据源',
      type: 'mysql' as const,
      host: '192.168.1.10',
      port: 3306,
      username: 'root'
    }
    await createDataSource(data)
    expect(mockPost).toHaveBeenCalledWith('/datasources', data)
  })

  it('updateDataSource 应调用 PUT /datasources/:id', async () => {
    const { updateDataSource } = await import('@/api/datasource')
    const data = { name: '更新数据源' }
    await updateDataSource('ds1', data)
    expect(mockPut).toHaveBeenCalledWith('/datasources/ds1', data)
  })

  it('deleteDataSource 应调用 DELETE /datasources/:id', async () => {
    const { deleteDataSource } = await import('@/api/datasource')
    await deleteDataSource('ds1')
    expect(mockDel).toHaveBeenCalledWith('/datasources/ds1')
  })

  it('testDataSource 应调用 POST /datasources/:id/test', async () => {
    const { testDataSource } = await import('@/api/datasource')
    await testDataSource('ds1')
    expect(mockPost).toHaveBeenCalledWith('/datasources/ds1/test')
  })

  it('listDataSources 支持类型筛选参数', async () => {
    const { listDataSources } = await import('@/api/datasource')
    await listDataSources({ type: 'mysql', page: 1, pageSize: 10 })
    expect(mockGet).toHaveBeenCalledWith('/datasources', expect.objectContaining({ type: 'mysql' }))
  })
})
