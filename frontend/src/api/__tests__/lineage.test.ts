/**
 * lineage.ts 单元测试
 *
 * 钉住"血缘查询面如何寻址含 `/` 的表全名"这条契约：
 * OpenLineage 摄取产生的节点全名是 `<namespace>/<name>`，放进路径段会 404，
 * 经 encodeURIComponent 变成 %2F 又被 Tomcat 400 拒——两者都表现为
 * "写入成功但查询为空"，且只在运行时暴露，故在此前置成秒级门禁。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

const mockGet = vi.fn(() =>
  Promise.resolve({
    rootTable: '',
    direction: 'DOWNSTREAM',
    depth: 5,
    tables: [],
    paths: [],
    queryTimeMs: 0
  })
)
const mockPost = vi.fn(() => Promise.resolve({ categories: [], nodes: [], links: [], meta: {} }))

vi.mock('@/api/client', () => ({
  get: mockGet,
  post: mockPost
}))

describe('api/lineage.ts 查询面寻址契约', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('getUpstream 用查询参数带 table，不把表名拼进路径', async () => {
    const { getUpstream } = await import('@/api/lineage')
    await getUpstream('hive/ods.orders', 3)
    expect(mockGet).toHaveBeenCalledWith(
      '/lineage/api/v1/lineage/upstream',
      { table: 'hive/ods.orders', depth: 3 },
      { baseURL: '' }
    )
  })

  it('getDownstream 用查询参数带 table，默认 depth=5', async () => {
    const { getDownstream } = await import('@/api/lineage')
    await getDownstream('s3/raw/a.parquet')
    expect(mockGet).toHaveBeenCalledWith(
      '/lineage/api/v1/lineage/downstream',
      { table: 's3/raw/a.parquet', depth: 5 },
      { baseURL: '' }
    )
  })

  it('impactAnalysis 用查询参数带 table', async () => {
    const { impactAnalysis } = await import('@/api/lineage')
    await impactAnalysis('hive/ods.orders')
    expect(mockGet).toHaveBeenCalledWith(
      '/lineage/api/v1/lineage/impact',
      { table: 'hive/ods.orders' },
      { baseURL: '' }
    )
  })

  it('三个查询函数的请求 URL 里不得出现编码斜杠或裸斜杠表名', async () => {
    const { getUpstream, getDownstream, impactAnalysis } = await import('@/api/lineage')
    const slashed = 'hive/ods.orders'
    await Promise.all([getUpstream(slashed), getDownstream(slashed), impactAnalysis(slashed)])
    for (const call of mockGet.mock.calls) {
      const url = call[0] as string
      expect(url).not.toContain('%2F')
      // 表名只能出现在 params 里；出现在 URL 里就意味着退回路径段形态
      expect(url.endsWith(slashed)).toBe(false)
    }
  })
})
