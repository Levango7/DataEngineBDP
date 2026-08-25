/**
 * useSearch 组合式函数单元测试
 *
 * 重点覆盖检索竞态守卫：旧响应不得覆盖新结果（doSearch 与 loadMore）
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { SearchResponse, SearchResultItem } from '@/types/search'

vi.mock('@/api/search', () => ({
  search: vi.fn(),
  getFacets: vi.fn(),
  suggest: vi.fn()
}))

import { useSearch } from '../useSearch'
import { search } from '@/api/search'

const mockedSearch = vi.mocked(search)

function item(id: string): SearchResultItem {
  return {
    id,
    name: `name-${id}`,
    type: 'table',
    sourceId: 'src-1',
    sourceName: '数据源',
    description: '',
    tags: [],
    createdAt: '2026-01-01T00:00:00.000Z',
    updatedAt: '2026-01-01T00:00:00.000Z',
    score: 1,
    snippets: {},
    highlights: []
  }
}

function resp(list: string[], extra: Partial<SearchResponse> = {}): SearchResponse {
  return {
    list: list.map(item),
    total: list.length,
    page: 1,
    pageSize: 20,
    tookMs: 5,
    hasMore: false,
    ...extra
  }
}

describe('composables/useSearch.ts 竞态守卫', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('慢请求 A 后发快请求 B，B 先返回，A 完成时不应覆盖 B 的结果', async () => {
    let resolveA!: (r: SearchResponse) => void
    let resolveB!: (r: SearchResponse) => void
    mockedSearch
      .mockImplementationOnce(() => new Promise<SearchResponse>((r) => { resolveA = r }))
      .mockImplementationOnce(() => new Promise<SearchResponse>((r) => { resolveB = r }))

    const s = useSearch({ debounceMs: 0 })
    s.query.query = 'alpha'
    const promiseA = s.refresh()

    s.query.query = 'beta'
    const promiseB = s.refresh()

    resolveB(resp(['b1', 'b2']))
    await promiseB
    expect(s.results.value.map((i) => i.id)).toEqual(['b1', 'b2'])
    expect(s.loading.value).toBe(false)

    resolveA(resp(['a1']))
    await promiseA

    expect(s.results.value.map((i) => i.id)).toEqual(['b1', 'b2'])
    expect(s.total.value).toBe(2)
    expect(s.loading.value).toBe(false)
  })

  it('过期请求失败不应写入 error 或卡死 loading', async () => {
    let rejectA!: (e: Error) => void
    let resolveB!: (r: SearchResponse) => void
    mockedSearch
      .mockImplementationOnce(() => new Promise<SearchResponse>((_, rej) => { rejectA = rej }))
      .mockImplementationOnce(() => new Promise<SearchResponse>((r) => { resolveB = r }))

    const s = useSearch({ debounceMs: 0 })
    s.query.query = 'first'
    const promiseA = s.refresh()

    s.query.query = 'second'
    const promiseB = s.refresh()

    resolveB(resp(['ok']))
    await promiseB
    expect(s.error.value).toBeNull()

    rejectA(new Error('stale failure'))
    await promiseA

    expect(s.error.value).toBeNull()
    expect(s.results.value.map((i) => i.id)).toEqual(['ok'])
    expect(s.loading.value).toBe(false)
  })

  it('loadMore 进行中若发生新检索，过期的追加不应写入结果', async () => {
    let resolveLoadMore!: (r: SearchResponse) => void
    let resolveRefresh!: (r: SearchResponse) => void
    mockedSearch
      .mockImplementationOnce(() => Promise.resolve(resp(['p1'], { hasMore: true })))
      .mockImplementationOnce(() => new Promise<SearchResponse>((r) => { resolveLoadMore = r }))
      .mockImplementationOnce(() => new Promise<SearchResponse>((r) => { resolveRefresh = r }))

    const s = useSearch({ debounceMs: 0 })
    s.query.query = 'alpha'
    await s.refresh()
    expect(s.results.value.map((i) => i.id)).toEqual(['p1'])

    const morePromise = s.loadMore()

    const refreshPromise = s.refresh()
    resolveRefresh(resp(['fresh']))
    await refreshPromise
    expect(s.results.value.map((i) => i.id)).toEqual(['fresh'])

    resolveLoadMore(resp(['old-page2']))
    await morePromise

    expect(s.results.value.map((i) => i.id)).toEqual(['fresh'])
    expect(s.loadingMore.value).toBe(false)
  })

  it('正常顺序的 loadMore 追加行为保持不变', async () => {
    mockedSearch
      .mockImplementationOnce(() => Promise.resolve(resp(['p1'], { hasMore: true })))
      .mockImplementationOnce(() => Promise.resolve(resp(['p2'], { hasMore: false })))

    const s = useSearch({ debounceMs: 0 })
    s.query.query = 'alpha'
    await s.refresh()
    await s.loadMore()

    expect(s.results.value.map((i) => i.id)).toEqual(['p1', 'p2'])
    expect(s.hasMore.value).toBe(false)
    expect(mockedSearch).toHaveBeenCalledTimes(2)
  })
})
