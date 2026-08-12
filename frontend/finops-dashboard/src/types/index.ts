/**
 * FinOps 看板类型定义
 */

/** 资源维度 */
export type ResourceDimension = 'CPU' | 'MEMORY' | 'STORAGE' | 'GPU' | 'NETWORK'

/** 闲置模式（5 类） */
export type IdlePattern =
  | 'LOW_CPU_UTILIZATION'
  | 'LOW_MEMORY_UTILIZATION'
  | 'UNMOUNTED_STORAGE'
  | 'IDLE_GPU'
  | 'LOW_NETWORK_TRAFFIC'

/** Top10 成本资源 */
export interface TopCostResource {
  resourceId: string
  resourceType: string
  tenant: string
  namespace: string
  workspace: string
  totalCost: number
  cpuCost: number
  memoryCost: number
  storageCost: number
  gpuCost: number
  networkCost: number
  percentage: number
  start: string
  end: string
}

/** 成本趋势数据点 */
export interface CostTrendPoint {
  timestamp: string
  totalCost: number
  cpuCost: number
  memoryCost: number
  storageCost: number
  gpuCost: number
  networkCost: number
  granularity: string
}

/** 资源成本明细 */
export interface ResourceCostDetail {
  resourceId: string
  resourceType: string
  tenant: string
  namespace: string
  workspace: string
  dimensionUsages: Record<string, number>
  dimensionCosts: Record<string, number>
  totalCost: number
  gpuModel?: string
  start: string
  end: string
}

/** 闲置资源 */
export interface IdleResource {
  resourceId: string
  resourceType: string
  tenant: string
  namespace: string
  workspace: string
  pattern: IdlePattern
  avgUtilization: number
  sustainedHours: number
  estimatedSaving: number
  suggestion: string
  start: string
  end: string
}

/** 优化建议 */
export interface OptimizationSuggestion {
  id: string
  title: string
  pattern: IdlePattern
  actionType: string
  resourceIds: string[]
  resourceCount: number
  estimatedMonthlySaving: number
  description: string
  riskLevel: string
  tenant: string
  namespace: string
  generatedAt: string
}

/** 看板统一响应 */
export interface DashboardResponse<T> {
  items: T[]
  total: number
  start: string
  end: string
  tenant: string
  summary: Record<string, unknown>
}

/** 分账配置 */
export interface AllocationConfig {
  id: string
  parentWorkspace: string
  dimension: string
  ratios: Record<string, number>
  enabled: boolean
  remark?: string
}

/** 分账结果项 */
export interface AllocationItem {
  parentWorkspace: string
  subWorkspace: string
  ratio: number
  originalCost: number
  allocatedCost: number
  dimensionAllocatedCosts: Record<string, number>
  dimension: string
}

/** 闲置模式显示名映射 */
export const IDLE_PATTERN_LABELS: Record<IdlePattern, string> = {
  LOW_CPU_UTILIZATION: '低利用率 CPU',
  LOW_MEMORY_UTILIZATION: '低利用率内存',
  UNMOUNTED_STORAGE: '未挂载存储',
  IDLE_GPU: '空闲 GPU',
  LOW_NETWORK_TRAFFIC: '低流量负载'
}

/** 闲置模式颜色映射（ECharts） */
export const IDLE_PATTERN_COLORS: Record<IdlePattern, string> = {
  LOW_CPU_UTILIZATION: '#5470c6',
  LOW_MEMORY_UTILIZATION: '#91cc75',
  UNMOUNTED_STORAGE: '#fac858',
  IDLE_GPU: '#ee6666',
  LOW_NETWORK_TRAFFIC: '#73c0de'
}