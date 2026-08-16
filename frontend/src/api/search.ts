/**
 * 检索门户 API 封装（T007）
 *
 * 后端约定：
 * - 检索端点：POST /api/v1/search
 * - 过滤器候选：GET  /api/v1/search/facets
 * - 导出：       POST /api/v1/search/export
 * - 检索历史：   GET  /api/v1/search/history
 * - 检索建议：   GET  /api/v1/search/suggest
 *
 * 说明：
 * - 检索为 POST 而非 GET，因结构化条件体可能超过 URL 长度限制
 * - 所有方法返回 Promise<T>，错误由 client 拦截器统一提示
 * - 类型从 @/types/search 集中导出，避免循环依赖
 */
import { get, post } from './client'
import type {
  SearchQuery,
  SearchResponse,
  FilterFacets,
  ExportRequest,
  ExportResult,
  SearchHistoryItem
} from '@/types/search'
// 类型定义见 @/types/search.ts（项目约定：避免循环依赖）

/** 检索资源根路径 */
const BASE = '/search'

/* ------------------------------ 检索 ------------------------------ */

/**
 * 执行检索
 * @param query 检索查询
 */
export function search(query: SearchQuery): Promise<SearchResponse> {
  return post<SearchResponse>(BASE, query)
}

/**
 * 获取过滤器候选项（基于当前查询上下文聚合）
 * @param query 当前查询（用于上下文相关候选）
 */
export function getFacets(query?: Partial<SearchQuery>): Promise<FilterFacets> {
  return get<FilterFacets>(`${BASE}/facets`, query as Record<string, unknown>)
}

/**
 * 检索建议（拼写纠错 / 相关词）
 * @param keyword 输入词
 */
export function suggest(keyword: string): Promise<string[]> {
  return get<string[]>(`${BASE}/suggest`, { keyword })
}

/* ------------------------------ 导出 ------------------------------ */

/**
 * 触发后端导出，返回下载链接
 * @param req 导出请求
 */
export function exportResults(req: ExportRequest): Promise<ExportResult> {
  return post<ExportResult>(`${BASE}/export`, req)
}

/* ------------------------------ 检索历史 ------------------------------ */

/**
 * 获取检索历史
 * @param limit 最大条数，默认 20
 */
export function getHistory(limit = 20): Promise<SearchHistoryItem[]> {
  return get<SearchHistoryItem[]>(`${BASE}/history`, { limit })
}

/**
 * 清空检索历史
 */
export function clearHistory(): Promise<void> {
  return post<void>(`${BASE}/history/clear`)
}

/**
 * 删除单条检索历史
 * @param id 历史 ID
 */
export function deleteHistory(id: string): Promise<void> {
  return post<void>(`${BASE}/history/${id}/delete`)
}