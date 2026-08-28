/**
 * 业务线门户 API（L5.4）
 *
 * 后端：platform/business-portal/
 * 端点前缀：/api/v1/business-lines
 */
import { get, post, put, del } from './client'

/** 业务线资源根路径 */
const BASE = '/business-lines'

/* ------------------------------ 类型定义 ------------------------------ */

/** 业务线状态 */
export type BusinessLineStatus = 'active' | 'suspended' | 'archived'

/** 报表类型 */
export type ReportType = 'table' | 'chart' | 'dashboard' | 'pivot'

/** 报表状态 */
export type ReportStatus = 'draft' | 'published' | 'archived'

/** 目录节点类型 */
export type CatalogNodeType = 'database' | 'schema' | 'table' | 'view' | 'dataset' | 'model'

/** 预算 */
export interface Budget {
  total: number
  used: number
  cycle: string
  softLimit: boolean
}

/** 业务线配置 */
export interface BusinessLineConfig {
  dataIsolation: 'strict' | 'relaxed'
  permissionScope: 'bl' | 'team' | 'project'
  features: Record<string, boolean>
  tags: Record<string, string>
}

/** 业务线 */
export interface BusinessLine {
  id: string
  name: string
  tenantId: string
  description?: string
  status: BusinessLineStatus
  budget: Budget
  config: BusinessLineConfig
  ownerIds: string[]
  teamIds: string[]
  memberIds: string[]
  createdAt: string
  updatedAt: string
}

/** 创建业务线参数 */
export interface CreateBusinessLineParams {
  name: string
  tenantId: string
  description?: string
  budget?: Partial<Budget>
  config?: Partial<BusinessLineConfig>
  ownerIds?: string[]
  teamIds?: string[]
  memberIds?: string[]
}

/** 更新业务线参数 */
export interface UpdateBusinessLineParams {
  name?: string
  description?: string
  status?: BusinessLineStatus
  budget?: Budget
  config?: BusinessLineConfig
  ownerIds?: string[]
  teamIds?: string[]
  memberIds?: string[]
}

/** 列表查询参数 */
export interface BusinessLineListQuery {
  tenantId?: string
  status?: BusinessLineStatus
  name?: string
  memberId?: string
  limit?: number
  offset?: number
}

/* ------------------------------ Dashboard ------------------------------ */

export interface Kpi {
  key: string
  label: string
  value: number
  unit: string
  trend: number
  description?: string
}

export interface Trend {
  key: string
  label: string
  unit: string
  bars: number[]
}

export interface RealtimeMonitor {
  key: string
  label: string
  status: 'ok' | 'warn' | 'critical'
  value: number
  unit: string
  threshold?: number
}

export interface TopProject {
  projectId: string
  projectName: string
  cost: number
  usageRatio: number
  jobCount: number
}

export interface Dashboard {
  blId: string
  kpis: Kpi[]
  trends: Trend[]
  realtime: RealtimeMonitor[]
  topProjects: TopProject[]
  updatedAt: string
}

/* ------------------------------ Workbench ------------------------------ */

export interface Task {
  id: string
  type: 'approval' | 'apply' | 'share' | 'alert'
  title: string
  applicant: string
  status: 'pending' | 'approved' | 'rejected'
  priority: 'normal' | 'high' | 'urgent'
  createdAt: string
}

export interface Tool {
  key: string
  label: string
  icon: string
  url: string
  description?: string
}

export interface RecentTask {
  id: string
  name: string
  kind: 'job' | 'training' | 'deployment' | 'share'
  status: string
  updatedAt: string
}

export interface Workbench {
  blId: string
  todos: Task[]
  tools: Tool[]
  recentTasks: RecentTask[]
  updatedAt: string
}

/* ------------------------------ Catalog ------------------------------ */

export interface CatalogNode {
  id: string
  blId: string
  parentId: string | null
  name: string
  type: CatalogNodeType
  children: string[]
  assetCount: number
  description?: string
  tags: Record<string, string>
}

export interface CatalogTree {
  blId: string
  nodes: CatalogNode[]
  rootIds: string[]
  updatedAt: string
}

/* ------------------------------ Report ------------------------------ */

export interface ReportConfig {
  type: ReportType
  chartType: string
  dimensions: string[]
  measures: string[]
  filters: Record<string, unknown>
  refreshInterval: number
}

export interface Report {
  id: string
  blId: string
  name: string
  description?: string
  status: ReportStatus
  config: ReportConfig
  creatorId: string
  tags: Record<string, string>
  createdAt: string
  updatedAt: string
}

export interface CreateReportParams {
  name: string
  description?: string
  config?: Partial<ReportConfig>
  tags?: Record<string, string>
}

/* ------------------------------ API 方法 ------------------------------ */

/** 列出业务线 */
export function listBusinessLines(params?: BusinessLineListQuery): Promise<BusinessLine[]> {
  return get<BusinessLine[]>(BASE, params as Record<string, unknown>)
}

/** 获取业务线详情 */
export function getBusinessLine(id: string): Promise<BusinessLine> {
  return get<BusinessLine>(`${BASE}/${id}`)
}

/** 创建业务线 */
export function createBusinessLine(data: CreateBusinessLineParams): Promise<BusinessLine> {
  return post<BusinessLine>(BASE, data)
}

/** 更新业务线 */
export function updateBusinessLine(
  id: string,
  data: UpdateBusinessLineParams
): Promise<BusinessLine> {
  return put<BusinessLine>(`${BASE}/${id}`, data)
}

/** 删除业务线 */
export function deleteBusinessLine(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/** 获取数据概览 */
export function getDashboard(blId: string): Promise<Dashboard> {
  return get<Dashboard>(`${BASE}/${blId}/dashboard`)
}

/** 获取工作台 */
export function getWorkbench(blId: string): Promise<Workbench> {
  return get<Workbench>(`${BASE}/${blId}/workbench`)
}

/** 获取数据目录 */
export function getCatalog(blId: string): Promise<CatalogTree> {
  return get<CatalogTree>(`${BASE}/${blId}/catalog`)
}

/** 列出 BI 报表 */
export function listReports(blId: string): Promise<Report[]> {
  return get<Report[]>(`${BASE}/${blId}/reports`)
}

/** 创建 BI 报表 */
export function createReport(blId: string, data: CreateReportParams): Promise<Report> {
  return post<Report>(`${BASE}/${blId}/reports`, data)
}

/** 删除 BI 报表 */
export function deleteReport(blId: string, reportId: string): Promise<void> {
  return del<void>(`${BASE}/${blId}/reports/${reportId}`)
}
