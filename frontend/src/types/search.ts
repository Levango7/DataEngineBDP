/**
 * 检索门户类型定义（T007）
 *
 * 设计原则：
 * - 自然语言 + 结构化查询统一为 SearchQuery
 * - 检索结果以卡片为单位，支持字段级高亮
 * - 过滤器覆盖时间 / 来源 / 类型 / 标签四个维度
 * - 分页与无限滚动共用同一份分页状态
 * - 导出格式支持 CSV / JSON / Excel（xlsx）
 *
 * 命名约定：PascalCase 类型，camelCase 字段
 */

/* ------------------------------------------------------------------ */
/* 检索模式                                                            */
/* ------------------------------------------------------------------ */

/** 检索语句模式 */
export type SearchMode = 'natural' | 'structured'

/** 排序方向 */
export type SortOrder = 'asc' | 'desc'

/** 可排序字段 */
export type SearchSortField = 'relevance' | 'updatedAt' | 'createdAt' | 'score'

/* ------------------------------------------------------------------ */
/* 结构化查询条件                                                      */
/* ------------------------------------------------------------------ */

/**
 * 结构化查询条件（与自然语言 query 互斥但可组合）
 * - 字段级精确匹配 / 范围匹配
 * - 多个字段之间为 AND 关系
 * - 同字段多值为 OR 关系
 */
export interface StructuredCondition {
  /** 字段名，如 datasource、owner、tags */
  field: string
  /** 操作符 */
  op: 'eq' | 'ne' | 'in' | 'not_in' | 'gt' | 'gte' | 'lt' | 'lte' | 'contains' | 'exists'
  /** 比较值，in/not_in 时为数组 */
  value: string | number | boolean | Array<string | number>
}

/* ------------------------------------------------------------------ */
/* 过滤器                                                              */
/* ------------------------------------------------------------------ */

/** 时间过滤预设 */
export type TimePreset =
  | 'today'
  | 'yesterday'
  | 'last7d'
  | 'last30d'
  | 'last90d'
  | 'custom'
  | ''

/** 时间范围过滤 */
export interface TimeRangeFilter {
  /** 预设区间，custom 时使用 from/to */
  preset: TimePreset
  /** 起始时间（ISO 字符串） */
  from?: string
  /** 结束时间（ISO 字符串） */
  to?: string
}

/** 多维度过滤器 */
export interface SearchFilter {
  /** 时间维度 */
  time: TimeRangeFilter
  /** 来源维度（数据源 ID 列表） */
  sources: string[]
  /** 类型维度（资产类型） */
  types: string[]
  /** 标签维度 */
  tags: string[]
}

/** 空过滤器常量 */
export const EMPTY_FILTER: SearchFilter = {
  time: { preset: '' },
  sources: [],
  types: [],
  tags: []
}

/* ------------------------------------------------------------------ */
/* 排序                                                                */
/* ------------------------------------------------------------------ */

/** 排序参数 */
export interface SearchSort {
  field: SearchSortField
  order: SortOrder
}

/** 默认按相关度降序 */
export const DEFAULT_SORT: SearchSort = { field: 'relevance', order: 'desc' }

/* ------------------------------------------------------------------ */
/* 完整检索请求                                                        */
/* ------------------------------------------------------------------ */

/** 分页参数 */
export interface SearchPaging {
  /** 当前页码，从 1 开始 */
  page: number
  /** 每页条数 */
  pageSize: number
}

/**
 * 检索请求
 *
 * - mode = 'natural' 时仅使用 query 字段
 * - mode = 'structured' 时仅使用 conditions 字段
 * - 两者可同时存在：自然语言做语义召回，结构化做精确过滤
 */
export interface SearchQuery {
  /** 检索模式 */
  mode: SearchMode
  /** 自然语言查询语句 */
  query: string
  /** 结构化查询条件 */
  conditions: StructuredCondition[]
  /** 多维度过滤器 */
  filter: SearchFilter
  /** 排序 */
  sort: SearchSort
  /** 分页 */
  paging: SearchPaging
}

/** 构造空查询 */
export function createEmptyQuery(pageSize = 20): SearchQuery {
  return {
    mode: 'natural',
    query: '',
    conditions: [],
    filter: { ...EMPTY_FILTER, time: { ...EMPTY_FILTER.time } },
    sort: { ...DEFAULT_SORT },
    paging: { page: 1, pageSize }
  }
}

/* ------------------------------------------------------------------ */
/* 检索结果                                                            */
/* ------------------------------------------------------------------ */

/** 资产类型枚举（与 AssetMarket 对齐） */
export type AssetType =
  | 'table'
  | 'view'
  | 'api'
  | 'model'
  | 'dashboard'
  | 'stream'
  | 'job'
  | 'notebook'
  | 'metric'
  | 'document'

/** 高亮字段定位 */
export interface HighlightSpan {
  /** 字段名 */
  field: string
  /** 命中片段起始偏移 */
  start: number
  /** 命中片段结束偏移 */
  end: number
  /** 命中文本 */
  text: string
}

/**
 * 检索结果项
 *
 * - snippets 为字段→高亮文本片段的映射，前端直接 v-html 渲染
 * - highlights 为精确偏移，用于自定义渲染
 */
export interface SearchResultItem {
  /** 全局唯一 ID */
  id: string
  /** 资产名称 */
  name: string
  /** 资产类型 */
  type: AssetType
  /** 数据源 ID */
  sourceId: string
  /** 数据源名称（冗余，便于展示） */
  sourceName: string
  /** 摘要描述 */
  description: string
  /** 负责人 */
  owner?: string
  /** 标签列表 */
  tags: string[]
  /** 创建时间（ISO） */
  createdAt: string
  /** 更新时间（ISO） */
  updatedAt: string
  /** 相关度评分（0~1） */
  score: number
  /** 字段→高亮 HTML 片段（已转义，可直接渲染） */
  snippets: Record<string, string>
  /** 精确高亮偏移（可选） */
  highlights?: HighlightSpan[]
  /** 资产可访问 URL */
  url?: string
}

/** 检索结果分页响应 */
export interface SearchResponse {
  /** 命中结果列表 */
  list: SearchResultItem[]
  /** 总命中数 */
  total: number
  /** 当前页码 */
  page: number
  /** 每页条数 */
  pageSize: number
  /** 检索耗时（毫秒） */
  tookMs: number
  /** 是否还有更多数据（无限滚动使用） */
  hasMore: boolean
  /** 检索会话 ID（用于追踪） */
  sessionId?: string
  /** 检索建议（拼写纠错 / 相关词） */
  suggestions?: string[]
}

/* ------------------------------------------------------------------ */
/* 过滤器候选项                                                        */
/* ------------------------------------------------------------------ */

/** 过滤器候选项 */
export interface FilterOption {
  /** 选项值 */
  value: string
  /** 显示标签 */
  label: string
  /** 命中数量（可选，用于显示 count） */
  count?: number
}

/** 过滤器候选集合 */
export interface FilterFacets {
  /** 数据源候选 */
  sources: FilterOption[]
  /** 类型候选 */
  types: FilterOption[]
  /** 标签候选 */
  tags: FilterOption[]
}

/* ------------------------------------------------------------------ */
/* 导出                                                                */
/* ------------------------------------------------------------------ */

/** 导出格式 */
export type ExportFormat = 'csv' | 'json' | 'xlsx'

/** 导出范围 */
export type ExportScope = 'current' | 'all'

/** 导出请求 */
export interface ExportRequest {
  /** 原始检索查询 */
  query: SearchQuery
  /** 导出格式 */
  format: ExportFormat
  /** 导出范围：当前页 / 全部命中 */
  scope: ExportScope
  /** 导出字段白名单，空表示全部字段 */
  fields?: string[]
}

/** 导出响应 */
export interface ExportResult {
  /** 文件名 */
  filename: string
  /** 下载 URL（后端生成） */
  downloadUrl: string
  /** 文件大小（字节） */
  size: number
  /** 导出条数 */
  count: number
}

/* ------------------------------------------------------------------ */
/* 检索历史                                                            */
/* ------------------------------------------------------------------ */

/** 检索历史记录 */
export interface SearchHistoryItem {
  /** 历史 ID */
  id: string
  /** 检索语句 */
  query: string
  /** 命中数 */
  total: number
  /** 检索时间（ISO） */
  searchedAt: string
}

/* ------------------------------------------------------------------ */
/* 分页模式                                                            */
/* ------------------------------------------------------------------ */

/** 分页模式 */
export type PagingMode = 'page' | 'infinite'

/** 分页 UI 状态 */
export interface PagingState {
  /** 当前模式 */
  mode: PagingMode
  /** 当前页码 */
  page: number
  /** 每页条数 */
  pageSize: number
  /** 总条数 */
  total: number
  /** 是否正在加载更多（无限滚动） */
  loadingMore: boolean
  /** 是否已加载全部 */
  noMore: boolean
}