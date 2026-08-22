/**
 * 检索逻辑组合式函数（T007）
 *
 * 职责：
 * - 维护检索查询状态（query / filter / sort / paging）
 * - 调用 api/search 执行检索，维护 loading / error / results
 * - 支持分页与无限滚动两种模式，自动切换
 * - 暴露 setQuery / setFilter / setSort / nextPage / refresh 等方法
 * - 内置 300ms 防抖，避免输入过程中频繁请求
 *
 * 用法：
 * ```ts
 * const {
 *   query, results, loading, error, total, hasMore,
 *   setQueryText, setFilter, setSort, nextPage, refresh, reset
 * } = useSearch()
 * onMounted(refresh)
 * ```
 */
import { ref, reactive, computed, watch, onUnmounted, type Ref, type ComputedRef } from 'vue'
import * as searchApi from '@/api/search'
import {
  createEmptyQuery,
  EMPTY_FILTER,
  DEFAULT_SORT,
  type SearchQuery,
  type SearchResponse,
  type SearchResultItem,
  type SearchFilter,
  type SearchSort,
  type SearchMode,
  type StructuredCondition,
  type FilterFacets,
  type PagingMode
} from '@/types/search'

/** useSearch 配置 */
export interface UseSearchOptions {
  /** 初始每页条数，默认 20 */
  initialPageSize?: number
  /** 初始分页模式，默认 'page' */
  initialPagingMode?: PagingMode
  /** 防抖毫秒，默认 300 */
  debounceMs?: number
  /** 是否立即检索，默认 false */
  immediate?: boolean
}

/** useSearch 返回值 */
export interface UseSearchReturn {
  /** 检索查询（响应式，可双向绑定） */
  query: SearchQuery
  /** 当前检索结果列表 */
  results: Ref<SearchResultItem[]>
  /** 加载状态 */
  loading: Ref<boolean>
  /** 加载更多状态（无限滚动） */
  loadingMore: Ref<boolean>
  /** 错误信息 */
  error: Ref<Error | null>
  /** 总命中数 */
  total: Ref<number>
  /** 检索耗时（毫秒） */
  tookMs: Ref<number>
  /** 是否还有更多 */
  hasMore: Ref<boolean>
  /** 当前页码 */
  page: Ref<number>
  /** 每页条数 */
  pageSize: Ref<number>
  /** 分页模式 */
  pagingMode: Ref<PagingMode>
  /** 过滤器候选项 */
  facets: Ref<FilterFacets | null>
  /** 检索建议 */
  suggestions: Ref<string[]>
  /** 是否已检索过 */
  hasSearched: Ref<boolean>
  /** 是否为空结果 */
  isEmpty: ComputedRef<boolean>

  /** 设置自然语言查询文本 */
  setQueryText: (text: string) => void
  /** 设置检索模式 */
  setMode: (mode: SearchMode) => void
  /** 设置结构化条件 */
  setConditions: (conditions: StructuredCondition[]) => void
  /** 设置过滤器（合并） */
  setFilter: (filter: Partial<SearchFilter>) => void
  /** 重置过滤器 */
  resetFilter: () => void
  /** 设置排序 */
  setSort: (sort: SearchSort) => void
  /** 切换分页模式 */
  setPagingMode: (mode: PagingMode) => void
  /** 设置每页条数 */
  setPageSize: (size: number) => void
  /** 跳转到指定页 */
  setPage: (page: number) => void
  /** 下一页（分页模式） */
  nextPage: () => Promise<void>
  /** 加载更多（无限滚动模式） */
  loadMore: () => Promise<void>
  /** 重新检索（回到第 1 页） */
  refresh: () => Promise<void>
  /** 重置全部状态 */
  reset: () => void
  /** 加载过滤器候选 */
  loadFacets: () => Promise<void>
}

/**
 * 检索组合式函数
 * @param options 配置项
 */
export function useSearch(options: UseSearchOptions = {}): UseSearchReturn {
  const {
    initialPageSize = 20,
    initialPagingMode = 'page',
    debounceMs = 300,
    immediate = false
  } = options

  /* ------------------------------ 状态 ------------------------------ */
  const query = reactive<SearchQuery>(createEmptyQuery(initialPageSize))
  const results = ref<SearchResultItem[]>([])
  const loading = ref(false)
  const loadingMore = ref(false)
  const error = ref<Error | null>(null)
  const total = ref(0)
  const tookMs = ref(0)
  const hasMore = ref(false)
  const page = ref(1)
  const pageSize = ref(initialPageSize)
  const pagingMode = ref<PagingMode>(initialPagingMode)
  const facets = ref<FilterFacets | null>(null)
  const suggestions = ref<string[]>([])
  const hasSearched = ref(false)

  const isEmpty = computed(() => hasSearched.value && results.value.length === 0 && !loading.value)

  /* ------------------------------ 防抖 ------------------------------ */
  let debounceTimer: ReturnType<typeof setTimeout> | null = null

  function clearTimer(): void {
    if (debounceTimer !== null) {
      clearTimeout(debounceTimer)
      debounceTimer = null
    }
  }

  /** 防抖执行检索（用于输入框实时检索） */
  function debouncedSearch(): void {
    clearTimer()
    debounceTimer = setTimeout(() => {
      void doSearch(true)
    }, debounceMs)
  }

  /* ------------------------------ 核心检索 ------------------------------ */

  /**
   * 执行检索
   * @param reset 是否重置到第 1 页
   */
  async function doSearch(reset = true): Promise<void> {
    if (reset) {
      page.value = 1
      results.value = []
    }

    // 同步 query.paging
    query.paging = { page: page.value, pageSize: pageSize.value }

    // 空查询不发起请求
    const f = query.filter
    const hasQuery =
      query.query.trim().length > 0 ||
      query.conditions.length > 0 ||
      f.sources.length > 0 ||
      f.types.length > 0 ||
      f.tags.length > 0 ||
      f.time.preset !== ''

    if (!hasQuery) {
      results.value = []
      total.value = 0
      hasMore.value = false
      hasSearched.value = true
      return
    }

    loading.value = true
    error.value = null
    try {
      const resp: SearchResponse = await searchApi.search(query)
      if (reset || pagingMode.value === 'page') {
        results.value = resp.list
      } else {
        // 无限滚动追加
        results.value = [...results.value, ...resp.list]
      }
      total.value = resp.total
      tookMs.value = resp.tookMs
      hasMore.value = resp.hasMore
      suggestions.value = resp.suggestions ?? []
      hasSearched.value = true
    } catch (e) {
      error.value = e instanceof Error ? e : new Error(String(e))
    } finally {
      loading.value = false
    }
  }

  /* ------------------------------ 公共方法 ------------------------------ */

  function setQueryText(text: string): void {
    query.query = text
    if (text.trim().length === 0) {
      // 清空时立即清结果
      results.value = []
      total.value = 0
      hasSearched.value = false
      return
    }
    debouncedSearch()
  }

  function setMode(mode: SearchMode): void {
    query.mode = mode
  }

  function setConditions(conditions: StructuredCondition[]): void {
    query.conditions = conditions
    void doSearch(true)
  }

  function setFilter(filter: Partial<SearchFilter>): void {
    query.filter = {
      time: filter.time ?? query.filter.time,
      sources: filter.sources ?? query.filter.sources,
      types: filter.types ?? query.filter.types,
      tags: filter.tags ?? query.filter.tags
    }
    void doSearch(true)
  }

  function resetFilter(): void {
    query.filter = {
      time: { ...EMPTY_FILTER.time },
      sources: [],
      types: [],
      tags: []
    }
    void doSearch(true)
  }

  function setSort(sort: SearchSort): void {
    query.sort = sort
    void doSearch(true)
  }

  function setPagingMode(mode: PagingMode): void {
    pagingMode.value = mode
    // 切换模式时重置列表
    void doSearch(true)
  }

  function setPageSize(size: number): void {
    pageSize.value = size
    void doSearch(true)
  }

  function setPage(p: number): void {
    page.value = p
    void doSearch(false)
  }

  async function nextPage(): Promise<void> {
    if (page.value * pageSize.value >= total.value) return
    page.value += 1
    await doSearch(false)
  }

  async function loadMore(): Promise<void> {
    if (loadingMore.value || !hasMore.value) return
    loadingMore.value = true
    try {
      page.value += 1
      query.paging = { page: page.value, pageSize: pageSize.value }
      const resp = await searchApi.search(query)
      results.value = [...results.value, ...resp.list]
      total.value = resp.total
      hasMore.value = resp.hasMore
    } catch (e) {
      error.value = e instanceof Error ? e : new Error(String(e))
      // 失败回滚页码
      page.value -= 1
    } finally {
      loadingMore.value = false
    }
  }

  async function refresh(): Promise<void> {
    await doSearch(true)
  }

  function reset(): void {
    clearTimer()
    Object.assign(query, createEmptyQuery(initialPageSize))
    results.value = []
    loading.value = false
    loadingMore.value = false
    error.value = null
    total.value = 0
    tookMs.value = 0
    hasMore.value = false
    page.value = 1
    suggestions.value = []
    hasSearched.value = false
  }

  async function loadFacets(): Promise<void> {
    try {
      facets.value = await searchApi.getFacets(query)
    } catch {
      // 候选加载失败不阻塞主流程
      facets.value = null
    }
  }

  /* ------------------------------ 生命周期 ------------------------------ */
  onUnmounted(() => {
    clearTimer()
  })

  // immediate 模式：挂载即检索
  if (immediate) {
    void doSearch(true)
  }

  return {
    query,
    results,
    loading,
    loadingMore,
    error,
    total,
    tookMs,
    hasMore,
    page,
    pageSize,
    pagingMode,
    facets,
    suggestions,
    hasSearched,
    isEmpty,
    setQueryText,
    setMode,
    setConditions,
    setFilter,
    resetFilter,
    setSort,
    setPagingMode,
    setPageSize,
    setPage,
    nextPage,
    loadMore,
    refresh,
    reset,
    loadFacets
  }
}

/**
 * 工具：从过滤器构造时间范围 ISO 字符串
 * @param preset 时间预设
 * @param from 自定义起始
 * @param to 自定义结束
 */
export function resolveTimeRange(
  preset: string,
  from?: string,
  to?: string
): { from?: string; to?: string } {
  if (preset === 'custom') return { from, to }
  if (preset === '') return {}
  const now = Date.now()
  const day = 24 * 60 * 60 * 1000
  const map: Record<string, number> = {
    today: 0,
    yesterday: day,
    last7d: 7 * day,
    last30d: 30 * day,
    last90d: 90 * day
  }
  const offset = map[preset]
  if (offset === undefined) return {}
  return {
    from: new Date(now - offset).toISOString(),
    to: new Date(now).toISOString()
  }
}

/**
 * 工具：默认排序常量便捷导出
 */
export { DEFAULT_SORT, EMPTY_FILTER }