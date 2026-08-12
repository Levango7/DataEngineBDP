/**
 * AI 助手类型定义（T011）
 *
 * 设计原则：
 * - 自然语言 → SQL → 数据 → 图表 → 解读 全链路类型统一
 * - 聊天消息支持多模态：文本 / SQL / 表格 / 图表 / 摘要
 * - 图表推荐覆盖柱/线/饼/散点/地图五种基础类型
 * - 中英双语：所有面向用户文案均带 zh / en 两份
 * - 命名约定：PascalCase 类型，camelCase 字段
 *
 * 后端约定：
 * - 对话端点：POST /api/v1/ai-assistant/chat
 * - SQL 生成：POST /api/v1/ai-assistant/nl2sql
 * - SQL 执行：POST /api/v1/ai-assistant/execute
 * - 图表推荐：POST /api/v1/ai-assistant/recommend-chart
 * - 数据解读：POST /api/v1/ai-assistant/summarize
 * - Superset 仪表盘：POST /api/v1/ai-assistant/dashboard
 * - 历史会话：GET  /api/v1/ai-assistant/sessions
 */

/* ------------------------------------------------------------------ */
/* 通用                                                                */
/* ------------------------------------------------------------------ */

/** 双语文案 */
export interface Bilingual {
  /** 中文 */
  zh: string
  /** 英文 */
  en: string
}

/** 语言切换枚举 */
export type Locale = 'zh' | 'en'

/* ------------------------------------------------------------------ */
/* 会话与消息                                                          */
/* ------------------------------------------------------------------ */

/** 消息角色 */
export type MessageRole = 'user' | 'assistant' | 'system'

/** 消息内容类型（多模态） */
export type MessageContentType =
  | 'text' // 纯文本
  | 'sql' // SQL 语句
  | 'table' // 数据表格
  | 'chart' // 图表配置
  | 'summary' // 数据解读摘要
  | 'error' // 错误信息
  | 'card' // 卡片（含 SQL + 表格 + 图表 + 摘要）

/** 单条消息内容块 */
export interface MessageContent {
  /** 内容类型 */
  type: MessageContentType
  /** 文本内容（text / sql / error / summary） */
  text?: string
  /** 表格数据（table / card） */
  table?: TableData
  /** 图表配置（chart / card） */
  chart?: ChartConfig
  /** SQL 元信息（sql / card） */
  sqlMeta?: SqlMeta
  /** 摘要元信息（summary / card） */
  summaryMeta?: SummaryMeta
}

/** SQL 元信息 */
export interface SqlMeta {
  /** SQL 方言 */
  dialect: SqlDialect
  /** 涉及的表 */
  tables: string[]
  /** 涉及的列 */
  columns: string[]
  /** 是否跨源 */
  crossSource: boolean
  /** 置信度 0~1 */
  confidence: number
  /** 生成耗时（毫秒） */
  durationMs: number
}

/** 摘要元信息 */
export interface SummaryMeta {
  /** 命中行数 */
  rowCount: number
  /** 命中列数 */
  columnCount: number
  /** 解读维度 */
  dimensions: string[]
  /** 生成耗时（毫秒） */
  durationMs: number
}

/** 聊天消息 */
export interface ChatMessage {
  /** 消息 ID */
  id: string
  /** 会话 ID */
  sessionId: string
  /** 角色 */
  role: MessageRole
  /** 内容块列表（一条消息可包含多块，如卡片） */
  contents: MessageContent[]
  /** 创建时间（ISO 字符串） */
  createdAt: string
  /** 状态 */
  status: MessageStatus
  /** 用户反馈 */
  feedback?: MessageFeedback
}

/** 消息状态 */
export type MessageStatus = 'pending' | 'streaming' | 'done' | 'error'

/** 用户反馈 */
export type MessageFeedback = 'like' | 'dislike' | null

/** 会话 */
export interface ChatSession {
  /** 会话 ID */
  id: string
  /** 会话标题（取首条用户消息前 20 字） */
  title: string
  /** 创建时间 */
  createdAt: string
  /** 最近更新时间 */
  updatedAt: string
  /** 消息条数 */
  messageCount: number
  /** 是否置顶 */
  pinned: boolean
}

/* ------------------------------------------------------------------ */
/* SQL 与执行结果                                                      */
/* ------------------------------------------------------------------ */

/** SQL 方言 */
export type SqlDialect = 'ANSI' | 'HIVE' | 'DORIS' | 'TRINO' | 'MYSQL' | 'POSTGRESQL'

/** 自然语言转 SQL 请求 */
export interface Nl2SqlRequest {
  /** 自然语言查询 */
  query: string
  /** 数据源 ID（可选，限定 schema 上下文） */
  datasourceId?: string
  /** SQL 方言 */
  dialect?: SqlDialect
  /** 会话 ID（用于上下文记忆） */
  sessionId?: string
  /** 语言 */
  locale?: Locale
}

/** 自然语言转 SQL 响应 */
export interface Nl2SqlResponse {
  /** 生成的 SQL */
  sql: string
  /** 方言 */
  dialect: SqlDialect
  /** 涉及的表 */
  tables: string[]
  /** 涉及的列 */
  columns: string[]
  /** 是否跨源 */
  crossSource: boolean
  /** 置信度 0~1 */
  confidence: number
  /** 生成耗时（毫秒） */
  durationMs: number
  /** 候选 SQL（多路召回，按置信度排序） */
  candidates?: Array<{
    sql: string
    confidence: number
    reason: string
  }>
}

/** SQL 执行请求 */
export interface ExecuteSqlRequest {
  /** SQL 文本 */
  sql: string
  /** 方言 */
  dialect?: SqlDialect
  /** 数据源 ID */
  datasourceId?: string
  /** 租户 ID */
  tenantId?: string
  /** 超时秒数 */
  timeoutSeconds?: number
  /** 行数上限（默认 1000） */
  limit?: number
}

/** 表格数据 */
export interface TableData {
  /** 列定义 */
  columns: TableColumn[]
  /** 数据行（按列名 → 值） */
  rows: Record<string, unknown>[]
  /** 总行数（可能超过返回行数） */
  total: number
  /** 是否截断 */
  truncated: boolean
}

/** 表格列定义 */
export interface TableColumn {
  /** 列名 */
  name: string
  /** 显示名（双语） */
  label: Bilingual
  /** 数据类型 */
  dataType: ColumnDataType
  /** 是否度量（数值） */
  isMetric: boolean
  /** 是否维度（分类/时间） */
  isDimension: boolean
}

/** 列数据类型 */
export type ColumnDataType =
  | 'string'
  | 'integer'
  | 'float'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'time'
  | 'geo'
  | 'unknown'

/** SQL 执行响应 */
export interface ExecuteSqlResponse {
  /** 查询 ID */
  queryId: string
  /** 执行状态 */
  status: 'success' | 'failed' | 'degraded'
  /** 结果表格 */
  table: TableData
  /** 执行耗时（毫秒） */
  durationMs: number
  /** 错误信息 */
  error?: string
}

/* ------------------------------------------------------------------ */
/* 图表推荐                                                            */
/* ------------------------------------------------------------------ */

/** 图表类型 */
export type ChartType = 'bar' | 'line' | 'pie' | 'scatter' | 'map' | 'area' | 'radar'

/** 图表推荐请求 */
export interface RecommendChartRequest {
  /** 表格数据（用于推断） */
  table: TableData
  /** 用户原始自然语言（可选，用于语义辅助） */
  intent?: string
  /** 期望推荐数量 */
  topK?: number
  /** 语言 */
  locale?: Locale
}

/** 单个图表推荐项 */
export interface ChartRecommendation {
  /** 推荐项 ID */
  id: string
  /** 图表类型 */
  type: ChartType
  /** 推荐理由（双语） */
  reason: Bilingual
  /** 推荐得分 0~1 */
  score: number
  /** 维度字段（X 轴 / 分类） */
  dimensions: string[]
  /** 度量字段（Y 轴 / 数值） */
  metrics: string[]
  /** 分组字段 */
  groupBy?: string[]
  /** 是否为首选 */
  primary: boolean
}

/** 图表推荐响应 */
export interface RecommendChartResponse {
  /** 推荐列表（按得分降序） */
  recommendations: ChartRecommendation[]
  /** 数据特征描述（双语） */
  dataProfile: Bilingual
  /** 推断耗时（毫秒） */
  durationMs: number
}

/** ECharts 配置（与 echarts/types 兼容的精简版） */
export interface ChartConfig {
  /** 图表类型 */
  type: ChartType
  /** ECharts option */
  option: Record<string, unknown>
  /** 标题（双语） */
  title: Bilingual
  /** 推荐 ID（关联推荐项） */
  recommendationId?: string
}

/* ------------------------------------------------------------------ */
/* 数据解读摘要                                                        */
/* ------------------------------------------------------------------ */

/** 数据解读请求 */
export interface SummarizeRequest {
  /** 表格数据 */
  table: TableData
  /** 用户原始查询（可选） */
  intent?: string
  /** 图表配置（可选，结合图表解读） */
  chart?: ChartConfig
  /** 语言 */
  locale?: Locale
  /** 摘要风格 */
  style?: SummaryStyle
}

/** 摘要风格 */
export type SummaryStyle = 'concise' | 'detailed' | 'business'

/** 数据解读响应 */
export interface SummarizeResponse {
  /** 摘要正文（双语） */
  summary: Bilingual
  /** 关键洞察列表（双语） */
  insights: Bilingual[]
  /** 关键指标 */
  metrics: SummaryMetric[]
  /** 生成耗时（毫秒） */
  durationMs: number
}

/** 摘要关键指标 */
export interface SummaryMetric {
  /** 指标名（双语） */
  label: Bilingual
  /** 指标值 */
  value: number
  /** 单位 */
  unit?: string
  /** 同比变化（百分比，可负） */
  change?: number
  /** 趋势 */
  trend?: 'up' | 'down' | 'flat'
}

/* ------------------------------------------------------------------ */
/* Superset 仪表盘                                                     */
/* ------------------------------------------------------------------ */

/** 创建仪表盘请求 */
export interface CreateDashboardRequest {
  /** 仪表盘标题（双语） */
  title: Bilingual
  /** 数据源 ID */
  datasourceId: string
  /** SQL 语句 */
  sql: string
  /** 图表配置列表 */
  charts: ChartConfig[]
  /** 关联会话 ID */
  sessionId?: string
}

/** 创建仪表盘响应 */
export interface CreateDashboardResponse {
  /** 仪表盘 ID */
  dashboardId: string
  /** Superset 仪表盘 URL */
  url: string
  /** 嵌入式 URL（iframe） */
  embedUrl: string
  /** 创建时间 */
  createdAt: string
}

/** Superset 数据源 */
export interface SupersetDatasource {
  /** 数据源 ID */
  id: string
  /** 名称 */
  name: string
  /** 类型 */
  type: 'table' | 'view' | 'query'
  /** Superset 数据库 ID */
  databaseId: number
  /** Schema */
  schema: string
  /** 表名 / 视图名 */
  tableName: string
}

/* ------------------------------------------------------------------ */
/* 对话请求                                                            */
/* ------------------------------------------------------------------ */

/** 对话请求 */
export interface ChatRequest {
  /** 会话 ID（首次对话可省略，由后端创建） */
  sessionId?: string
  /** 用户输入 */
  message: string
  /** 数据源 ID（可选） */
  datasourceId?: string
  /** 是否自动执行 SQL */
  autoExecute?: boolean
  /** 是否自动推荐图表 */
  autoRecommendChart?: boolean
  /** 是否自动解读 */
  autoSummarize?: boolean
  /** 语言 */
  locale?: Locale
}

/** 对话响应（一次性返回完整结果，非流式） */
export interface ChatResponse {
  /** 会话 ID */
  sessionId: string
  /** 助手消息 */
  message: ChatMessage
  /** 生成的 SQL（如有） */
  sql?: Nl2SqlResponse
  /** 执行结果（如有） */
  execution?: ExecuteSqlResponse
  /** 图表推荐（如有） */
  chartRecommendation?: RecommendChartResponse
  /** 图表配置（首选图表，如有） */
  chart?: ChartConfig
  /** 数据解读（如有） */
  summary?: SummarizeResponse
}

/* ------------------------------------------------------------------ */
/* 历史会话                                                            */
/* ------------------------------------------------------------------ */

/** 历史会话列表查询参数 */
export interface SessionQuery {
  /** 关键字 */
  keyword?: string
  /** 是否仅置顶 */
  pinnedOnly?: boolean
  /** 最多条数 */
  limit?: number
}

/* ------------------------------------------------------------------ */
/* 内部辅助                                                            */
/* ------------------------------------------------------------------ */

/** 创建空消息内容（文本） */
export function createTextContent(text: string): MessageContent {
  return { type: 'text', text }
}

/** 创建错误消息内容 */
export function createErrorContent(text: string): MessageContent {
  return { type: 'error', text }
}

/** 生成消息 ID（前端临时 ID） */
export function generateMessageId(): string {
  return `msg-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

/** 生成会话 ID（前端临时 ID） */
export function generateSessionId(): string {
  return `sess-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
}

/** 图表类型双语标签 */
export const CHART_TYPE_LABELS: Record<ChartType, Bilingual> = {
  bar: { zh: '柱状图', en: 'Bar Chart' },
  line: { zh: '折线图', en: 'Line Chart' },
  pie: { zh: '饼图', en: 'Pie Chart' },
  scatter: { zh: '散点图', en: 'Scatter Chart' },
  map: { zh: '地图', en: 'Map' },
  area: { zh: '面积图', en: 'Area Chart' },
  radar: { zh: '雷达图', en: 'Radar Chart' }
}

/** SQL 方言双语标签 */
export const SQL_DIALECT_LABELS: Record<SqlDialect, Bilingual> = {
  ANSI: { zh: '标准 SQL', en: 'ANSI SQL' },
  HIVE: { zh: 'Hive SQL', en: 'Hive SQL' },
  DORIS: { zh: 'Doris SQL', en: 'Doris SQL' },
  TRINO: { zh: 'Trino SQL', en: 'Trino SQL' },
  MYSQL: { zh: 'MySQL', en: 'MySQL' },
  POSTGRESQL: { zh: 'PostgreSQL', en: 'PostgreSQL' }
}