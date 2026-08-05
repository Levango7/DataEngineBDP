/**
 * job.ts 单元测试
 *
 * 测试作业 API 模块的各方法调用参数和返回值
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock client 模块
const mockGet = vi.fn(() => Promise.resolve({ list: [], total: 0, page: 1, pageSize: 20 }))
const mockPost = vi.fn(() => Promise.resolve({ id: 'j1', name: '新作业' }))
const mockDel = vi.fn(() => Promise.resolve())

vi.mock('@/api/client', () => ({
  get: mockGet,
  post: mockPost,
  del: mockDel
}))

describe('api/job.ts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('listJobs 应调用 GET /jobs', async () => {
    const { listJobs } = await import('@/api/job')
    await listJobs({ page: 1, pageSize: 20 })
    expect(mockGet).toHaveBeenCalledWith(
      '/jobs',
      expect.objectContaining({ page: 1, pageSize: 20 })
    )
  })

  it('getJob 应调用 GET /jobs/:id', async () => {
    const { getJob } = await import('@/api/job')
    await getJob('j1')
    expect(mockGet).toHaveBeenCalledWith('/jobs/j1')
  })

  it('submitJob 应调用 POST /jobs', async () => {
    const { submitJob } = await import('@/api/job')
    const data = {
      name: '新作业',
      workspaceId: 'ws1',
      type: 'batch' as const,
      config: '{}'
    }
    await submitJob(data)
    expect(mockPost).toHaveBeenCalledWith('/jobs', data)
  })

  it('cancelJob 应调用 POST /jobs/:id/cancel', async () => {
    const { cancelJob } = await import('@/api/job')
    await cancelJob('j1')
    expect(mockPost).toHaveBeenCalledWith('/jobs/j1/cancel')
  })

  it('deleteJob 应调用 DELETE /jobs/:id', async () => {
    const { deleteJob } = await import('@/api/job')
    await deleteJob('j1')
    expect(mockDel).toHaveBeenCalledWith('/jobs/j1')
  })

  it('getJobLogs 应调用 GET /jobs/:id/logs', async () => {
    const { getJobLogs } = await import('@/api/job')
    await getJobLogs('j1')
    expect(mockGet).toHaveBeenCalledWith('/jobs/j1/logs')
  })

  it('getJobStatus 应调用 GET /jobs/:id/status', async () => {
    const { getJobStatus } = await import('@/api/job')
    await getJobStatus('j1')
    expect(mockGet).toHaveBeenCalledWith('/jobs/j1/status')
  })

  it('listJobs 支持状态筛选参数', async () => {
    const { listJobs } = await import('@/api/job')
    await listJobs({ status: 'running', page: 1, pageSize: 10 })
    expect(mockGet).toHaveBeenCalledWith('/jobs', expect.objectContaining({ status: 'running' }))
  })
})
