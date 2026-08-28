/**
 * BI 分析 API
 *
 * 对应后端 platform/analyze/ 看板管理：
 * - 看板 CRUD
 * - 看板组件配置
 * - 实时指标查询
 */
import { get, post, put, del } from './client'
import type { PagedResult, PageQuery } from './types'

/** 看板组件类型 */
export type PanelType = 'line' | 'pie' | 'bar' | 'metric' | 'funnel' | 'table'

/** 看板组件 */
export interface Panel {
  /** 组件 ID */
  id: string
  /** 标题 */
  title: string
  /** 类型 */
  type: PanelType
  /** 配置 */
  config: Record<string, unknown>
  /** 数据 */
  data?: Record<string, unknown>
}

/** 看板 */
export interface Dashboard {
  /** 看板 ID */
  id: string
  /** 看板名 */
  name: string
  /** 描述 */
  description?: string
  /** 组件列表 */
  panels: Panel[]
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 创建看板参数 */
export interface CreateDashboardParams {
  name: string
  description?: string
  panels?: Array<Pick<Panel, 'title' | 'type' | 'config'>>
}

/** 更新看板参数 */
export interface UpdateDashboardParams {
  name?: string
  description?: string
  panels?: Panel[]
}

/** 看板列表查询参数 */
export interface DashboardListQuery extends PageQuery {
  keyword?: string
}

/** 实时指标 */
export interface RealtimeMetric {
  /** 指标键 */
  key: string
  /** 指标名 */
  label: string
  /** 当前值 */
  value: number
  /** 单位 */
  unit: string
  /** 延迟（秒） */
  latencySec: number
}

/** 资源根路径 */
const BASE = '/dashboards'

/**
 * 查询看板列表（分页）
 */
export function listDashboards(params?: DashboardListQuery): Promise<PagedResult<Dashboard>> {
  return get<PagedResult<Dashboard>>(BASE, params as Record<string, unknown>)
}

/**
 * 获取看板详情
 */
export function getDashboard(id: string): Promise<Dashboard> {
  return get<Dashboard>(`${BASE}/${id}`)
}

/**
 * 创建看板
 */
export function createDashboard(data: CreateDashboardParams): Promise<Dashboard> {
  return post<Dashboard>(BASE, data)
}

/**
 * 更新看板
 */
export function updateDashboard(id: string, data: UpdateDashboardParams): Promise<Dashboard> {
  return put<Dashboard>(`${BASE}/${id}`, data)
}

/**
 * 删除看板
 */
export function deleteDashboard(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/**
 * 查询实时指标
 */
export function getRealtimeMetrics(): Promise<RealtimeMetric[]> {
  return get<RealtimeMetric[]>(`${BASE}/realtime`)
}
