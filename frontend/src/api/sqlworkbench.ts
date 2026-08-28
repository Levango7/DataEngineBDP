/**
 * SQL 工作台 API
 *
 * 跨源归并引擎前端 API 封装：
 * - 跨源 SQL 执行（POST /sql/cross-source）
 * - 跨源执行计划（POST /sql/cross-source/explain）
 * - SQL 解析/校验/优化（复用现有端点）
 */
import { get, post } from './client'

/** SQL 方言 */
export type SqlDialect = 'ANSI' | 'HIVE' | 'DORIS' | 'TRINO'

/** 跨源查询请求参数 */
export interface CrossSourceQueryParams {
  /** SQL 文本 */
  sql: string
  /** SQL 方言，默认 ANSI */
  dialect?: SqlDialect
  /** 租户 ID */
  tenantId?: string
  /** 超时秒数 */
  timeoutSeconds?: number
}

/** 跨源查询响应 */
export interface CrossSourceQueryResult {
  /** 查询 ID */
  queryId: string
  /** 状态：SUCCESS / FAILED / DEGRADED */
  status: string
  /** 列名列表 */
  columns: string[]
  /** 数据行 */
  rows: unknown[][]
  /** 行数 */
  rowCount: number
  /** 结果来源标识 */
  source: string
  /** 是否跨源查询 */
  crossSource: boolean
  /** 涉及的源列表 */
  sources: string[]
  /** 表→源映射 */
  tableToSource: Record<string, string>
  /** 执行耗时（毫秒） */
  durationMs: number
  /** 错误信息 */
  error?: string
}

/** 跨源执行计划响应 */
export interface CrossSourceExplainResult {
  /** 原始 SQL */
  sql: string
  /** 语句类型 */
  statementType?: string
  /** 涉及的表 */
  tables?: string[]
  /** 表→源映射 */
  tableToSource?: Record<string, string>
  /** 涉及的源列表 */
  sources?: string[]
  /** 是否跨源 */
  crossSource: boolean
  /** 执行策略 */
  strategy?: string
  /** 解析耗时（毫秒） */
  durationMs: number
  /** 错误信息 */
  error?: string
}

/** SQL 解析请求参数 */
export interface SqlParseParams {
  sql: string
  dialect?: SqlDialect
}

/** SQL 解析响应 */
export interface SqlParseResult {
  dialect: string
  statementType: string
  properties: Record<string, unknown>
  children: unknown[]
  tables: string[]
  columns: string[]
}

/** SQL 优化响应 */
export interface SqlOptimizeResult {
  originalSql: string
  optimizedSql?: string
  executionPlan?: string
  rulesApplied?: string[]
  estimatedCost?: number
  estimatedRows?: number
  tableAccesses?: string[]
  suggestions?: string[]
  success: boolean
  error?: string
  dialect: string
}

/**
 * 执行跨源 SQL 查询
 * @param params 查询参数
 */
export function executeCrossSourceSql(
  params: CrossSourceQueryParams
): Promise<CrossSourceQueryResult> {
  return post<CrossSourceQueryResult>('/sql/cross-source', params)
}

/**
 * 生成跨源 SQL 执行计划（不实际执行）
 * @param params 查询参数
 */
export function explainCrossSourceSql(
  params: CrossSourceQueryParams
): Promise<CrossSourceExplainResult> {
  return post<CrossSourceExplainResult>('/sql/cross-source/explain', params)
}

/**
 * 解析 SQL 并返回 AST
 * @param params 解析参数
 */
export function parseSql(params: SqlParseParams): Promise<SqlParseResult> {
  return post<SqlParseResult>('/sql/parse', params)
}

/**
 * 校验 SQL 语法
 * @param params 解析参数
 */
export function validateSql(
  params: SqlParseParams
): Promise<{ valid: boolean; dialect: string; error?: string }> {
  return post('/sql/validate', params)
}

/**
 * 优化 SQL 并返回执行计划
 * @param params 优化参数
 */
export function optimizeSql(
  params: SqlParseParams & { enableAllRules?: boolean }
): Promise<SqlOptimizeResult> {
  return post<SqlOptimizeResult>('/sql/optimize', params)
}

/**
 * 生成 SQL 执行计划（EXPLAIN 等价）
 * @param params 解析参数
 */
export function explainSql(params: SqlParseParams): Promise<SqlOptimizeResult> {
  return post<SqlOptimizeResult>('/sql/explain', params)
}

/**
 * 列出可用引擎
 *
 * 注意：后端 SqlGatewayController 使用 GET /sql/engines，前端原误用 POST，
 * 此处修正为 GET 以与后端保持一致。
 */
export function listEngines(): Promise<string[]> {
  return get<string[]>('/sql/engines')
}
