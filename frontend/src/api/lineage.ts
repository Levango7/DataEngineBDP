/**
 * 数据血缘分析 API 封装
 *
 * 对接后端 lineage-analyzer 服务（独立部署于 :8089，前缀 /lineage）。
 *
 * 说明：lineage-analyzer 是独立服务，路径前缀为 `/lineage`，不在 client.ts
 * 默认 baseURL（`/api/v1`）覆盖范围内。因此通过 `{ baseURL: '' }` 覆盖默认
 * baseURL，使请求路径保持为 `/lineage/api/v1/lineage/...`，由 vite proxy
 * 的 `/lineage` 规则转发到 8089 端口。
 *
 * 注意：此处仍走 client.ts 的 get/post 方法，请求/响应拦截器、统一错误处理、
 * 401 跳登录、Bearer token 注入等封装均正常生效，仅 baseURL 被覆盖。
 */
import { post, get } from './client'

/** ECharts 关系图节点 */
export interface LineageGraphNode {
  id: string
  name: string
  category: number
  nodeType: 'TABLE' | 'COLUMN'
}

/** ECharts 关系图边 */
export interface LineageGraphLink {
  source: string
  target: string
  relationType: 'TABLE_LINEAGE' | 'COLUMN_LINEAGE'
  expression?: string
}

/** ECharts 分类 */
export interface LineageCategory {
  name: string
}

/** 血缘图谱元信息 */
export interface LineageMeta {
  sourceSql: string
  dialect: string
  analyzeTimeMs: number
  nodeCount: number
  edgeCount: number
}

/** POST /analyze 返回的 ECharts 格式图谱 */
export interface LineageGraph {
  categories: LineageCategory[]
  nodes: LineageGraphNode[]
  links: LineageGraphLink[]
  meta: LineageMeta
}

/** 查询方向 */
export type LineageDirection = 'UPSTREAM' | 'DOWNSTREAM' | 'IMPACT'

/** 上下游/影响分析查询结果 */
export interface LineageQueryResult {
  rootTable: string
  direction: LineageDirection
  depth: number
  tables: string[]
  paths: string[]
  queryTimeMs: number
}

/**
 * 分析 SQL 血缘
 * @param sql SQL 文本
 * @param dialect 方言（ANSI/HIVE/DORIS/TRINO），缺省自动检测
 */
export function analyzeLineage(sql: string, dialect?: string): Promise<LineageGraph> {
  // lineage-analyzer 独立服务前缀 /lineage，覆盖 client 默认 baseURL(/api/v1)
  return post<LineageGraph>('/lineage/api/v1/lineage/analyze', { sql, dialect }, { baseURL: '' })
}

/**
 * 查询上游依赖表
 * @param table 表全名
 * @param depth 遍历深度，默认 5
 */
export function getUpstream(table: string, depth = 5): Promise<LineageQueryResult> {
  return get<LineageQueryResult>(
    `/lineage/api/v1/lineage/upstream/${encodeURIComponent(table)}`,
    { depth },
    { baseURL: '' }
  )
}

/**
 * 查询下游依赖表
 * @param table 表全名
 * @param depth 遍历深度，默认 5
 */
export function getDownstream(table: string, depth = 5): Promise<LineageQueryResult> {
  return get<LineageQueryResult>(
    `/lineage/api/v1/lineage/downstream/${encodeURIComponent(table)}`,
    { depth },
    { baseURL: '' }
  )
}

/**
 * 影响分析：变更 table 会影响哪些下游表
 * @param table 表全名
 */
export function impactAnalysis(table: string): Promise<LineageQueryResult> {
  return get<LineageQueryResult>(
    `/lineage/api/v1/lineage/impact/${encodeURIComponent(table)}`,
    undefined,
    { baseURL: '' }
  )
}
