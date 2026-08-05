/**
 * cluster.ts 单元测试
 *
 * 测试集群管理 API 模块的各方法调用参数和返回值
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock client 模块
const mockGet = vi.fn(() => Promise.resolve({ clusterName: 'prod-cluster', nodeTotal: 6 }))

vi.mock('@/api/client', () => ({
  get: mockGet
}))

describe('api/cluster.ts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('getClusterOverview 应调用 GET /cluster/overview', async () => {
    const { getClusterOverview } = await import('@/api/cluster')
    await getClusterOverview()
    expect(mockGet).toHaveBeenCalledWith('/cluster/overview')
  })

  it('listNodes 应调用 GET /cluster/nodes', async () => {
    const { listNodes } = await import('@/api/cluster')
    await listNodes()
    expect(mockGet).toHaveBeenCalledWith('/cluster/nodes')
  })

  it('listPods 不传 namespace 应调用 GET /cluster/pods', async () => {
    const { listPods } = await import('@/api/cluster')
    await listPods()
    expect(mockGet).toHaveBeenCalledWith('/cluster/pods', {})
  })

  it('listPods 传 namespace 应带查询参数', async () => {
    const { listPods } = await import('@/api/cluster')
    await listPods('default')
    expect(mockGet).toHaveBeenCalledWith('/cluster/pods', { namespace: 'default' })
  })
})
