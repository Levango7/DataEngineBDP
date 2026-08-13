/**
 * FinOps 看板 API 封装
 */
import { http } from './client'
import type {
  TopCostResource,
  CostTrendPoint,
  ResourceCostDetail,
  IdleResource,
  OptimizationSuggestion,
  DashboardResponse,
  AllocationConfig,
  AllocationItem
} from '@/types'

/** 时间窗口查询参数 */
export interface TimeWindow {
  start: string
  end: string
  namespace?: string
}

/** Top10 成本资源 */
export async function getTop10(
  params: TimeWindow
): Promise<DashboardResponse<TopCostResource>> {
  const res = await http.get('/dashboard/top10', { params })
  return res.data
}

/** 成本趋势 */
export async function getCostTrend(
  params: TimeWindow & { granularity?: string }
): Promise<DashboardResponse<CostTrendPoint>> {
  const res = await http.get('/dashboard/trend', { params })
  return res.data
}

/** 成本明细 */
export async function getCostDetails(
  params: TimeWindow
): Promise<DashboardResponse<ResourceCostDetail>> {
  const res = await http.get('/dashboard/details', { params })
  return res.data
}

/** 闲置资源清单 */
export async function getIdleResources(
  params: TimeWindow
): Promise<DashboardResponse<IdleResource>> {
  const res = await http.get('/suggestions/idle', { params })
  return res.data
}

/** 优化建议列表 */
export async function getSuggestions(
  params: TimeWindow
): Promise<DashboardResponse<OptimizationSuggestion>> {
  const res = await http.get('/suggestions/list', { params })
  return res.data
}

/** 账单导出 - CSV */
export async function exportBillCsv(
  params: TimeWindow & { type?: string; groupBy?: string }
): Promise<Blob> {
  const res = await http.get('/bill/export/csv', {
    params,
    responseType: 'blob'
  })
  return res.data
}

/** 账单导出 - Excel */
export async function exportBillExcel(
  params: TimeWindow & { type?: string; groupBy?: string }
): Promise<Blob> {
  const res = await http.get('/bill/export/excel', {
    params,
    responseType: 'blob'
  })
  return res.data
}

/** 列出分账配置 */
export async function listAllocationConfigs(): Promise<AllocationConfig[]> {
  const res = await http.get('/allocation/configs')
  return res.data
}

/** 获取分账配置 */
export async function getAllocationConfig(id: string): Promise<AllocationConfig> {
  const res = await http.get(`/allocation/configs/${id}`)
  return res.data
}

/** 保存分账配置 */
export async function saveAllocationConfig(
  config: AllocationConfig
): Promise<AllocationConfig> {
  const res = await http.post('/allocation/configs', config)
  return res.data
}

/** 删除分账配置 */
export async function deleteAllocationConfig(id: string): Promise<void> {
  await http.delete(`/allocation/configs/${id}`)
}

/** 执行分账 */
export async function executeAllocation(
  params: { configId: string } & TimeWindow
): Promise<DashboardResponse<AllocationItem>> {
  const res = await http.get('/allocation/execute', { params })
  return res.data
}
/**
 * 查询计费账单（透传 cost-model 聚合结果）。
 * @param params 时间窗口
 */
export async function getQueryBilling(
  params: { start?: string; end?: string }
): Promise<{
  tenant: string
  start: string
  end: string
  totalCost: number
  usages?: Record<string, number>
  note?: string
}> {
  const res = await http.get('/billing/tenant', { params })
  return res.data
}

/**
 * 查询计费账单按日趋势（透传 cost-model trend 端点）。
 * @param params 时间窗口
 */
export async function getQueryBillingTrend(
  params: { start?: string; end?: string }
): Promise<{
  tenant?: string
  start?: string
  end?: string
  points?: Array<{
    day: string
    bytesScanned: number
    tbScanned: number
    queryCount: number
    cost: number
  }>
}> {
  const res = await http.get('/billing/tenant/trend', { params })
  return res.data
}
