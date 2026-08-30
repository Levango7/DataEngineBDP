/**
 * analyze.ts 单元测试
 *
 * 验证 /dashboards 契约的调用形态——该契约自本迭代起由
 * business-portal dashboards 路由真实承接（此前前端库指向不存在的后端）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockGet = vi.fn(() => Promise.resolve([]))
const mockPost = vi.fn(() => Promise.resolve({ id: 'd1', name: '看板' }))
const mockPut = vi.fn(() => Promise.resolve({ id: 'd1', name: '改名' }))
const mockDel = vi.fn(() => Promise.resolve())

vi.mock('@/api/client', () => ({
  get: mockGet,
  post: mockPost,
  put: mockPut,
  del: mockDel
}))

describe('api/analyze.ts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('listDashboards 应调用 GET /dashboards 并透传分页参数', async () => {
    const { listDashboards } = await import('@/api/analyze')
    await listDashboards({ page: 1, pageSize: 20, keyword: 'GMV' })
    expect(mockGet).toHaveBeenCalledWith(
      '/dashboards',
      expect.objectContaining({ page: 1, pageSize: 20, keyword: 'GMV' })
    )
  })

  it('getDashboard 应调用 GET /dashboards/:id', async () => {
    const { getDashboard } = await import('@/api/analyze')
    await getDashboard('d1')
    expect(mockGet).toHaveBeenCalledWith('/dashboards/d1')
  })

  it('createDashboard 应调用 POST /dashboards', async () => {
    const { createDashboard } = await import('@/api/analyze')
    await createDashboard({ name: '经营驾驶舱', panels: [] })
    expect(mockPost).toHaveBeenCalledWith('/dashboards', {
      name: '经营驾驶舱',
      panels: []
    })
  })

  it('updateDashboard 应调用 PUT /dashboards/:id', async () => {
    const { updateDashboard } = await import('@/api/analyze')
    await updateDashboard('d1', { name: '改名' })
    expect(mockPut).toHaveBeenCalledWith('/dashboards/d1', { name: '改名' })
  })

  it('deleteDashboard 应调用 DELETE /dashboards/:id', async () => {
    const { deleteDashboard } = await import('@/api/analyze')
    await deleteDashboard('d1')
    expect(mockDel).toHaveBeenCalledWith('/dashboards/d1')
  })

  it('getRealtimeMetrics 应调用 GET /dashboards/realtime', async () => {
    const { getRealtimeMetrics } = await import('@/api/analyze')
    await getRealtimeMetrics()
    expect(mockGet).toHaveBeenCalledWith('/dashboards/realtime')
  })
})
