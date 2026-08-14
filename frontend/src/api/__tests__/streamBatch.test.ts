/**
 * streamBatch.ts 单元测试
 *
 * 测试任务运维中心 API 模块（运行历史 / 重跑 / 补数据）。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock client 模块
const mockGet = vi.fn<() => Promise<{ content: Array<Record<string, unknown>>; totalElements: number }>>(
  () => Promise.resolve({ content: [], totalElements: 0 })
)
const mockPost = vi.fn<() => Promise<{ created: number }>>(() => Promise.resolve({ created: 3 }))

vi.mock('@/api/client', () => ({
  get: mockGet,
  post: mockPost
}))

// 在 mock 后导入被测模块
const { listDagRuns, rerunDagRun, backfillDag } = await import('@/api/streamBatch')

describe('api/streamBatch.ts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('listDagRuns - 拼接 GET 路径并传分页参数', async () => {
    mockGet.mockResolvedValueOnce({ content: [{ id: 1 }], totalElements: 1 })
    const res = await listDagRuns('dag-001', { status: 'FAILED', page: 0, size: 20 })
    expect(mockGet).toHaveBeenCalledWith('/stream-batch/dags/dag-001/runs', {
      status: 'FAILED',
      page: 0,
      size: 20
    })
    expect(res.totalElements).toBe(1)
  })

  it('listDagRuns - 对含空格的 dagId 做编码', async () => {
    await listDagRuns('dag 001')
    expect(mockGet).toHaveBeenCalledWith('/stream-batch/dags/dag%20001/runs', expect.anything())
  })

  it('rerunDagRun - POST 重跑端点', async () => {
    await rerunDagRun('dag-001', 7)
    expect(mockPost).toHaveBeenCalledWith('/stream-batch/dags/dag-001/runs/7/rerun')
  })

  it('backfillDag - POST 补数据日期区间', async () => {
    const res = await backfillDag('dag-001', {
      startDate: '2026-08-01',
      endDate: '2026-08-07',
      intervalDays: 1
    })
    expect(mockPost).toHaveBeenCalledWith('/stream-batch/dags/dag-001/backfill', {
      startDate: '2026-08-01',
      endDate: '2026-08-07',
      intervalDays: 1
    })
    expect(res.created).toBe(3)
  })
})