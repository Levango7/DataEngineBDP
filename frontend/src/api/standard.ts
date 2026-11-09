/**
 * 数据标准 API
 *
 * 对应后端 platform/standard/ 数据标准管理：
 * - 标准项 CRUD
 * - 引用资产统计
 * - 落标率统计
 */
import { get, post, put, del } from './client'
import type { PagedResult, PageQuery } from './types'

/** 标准项类型 */
export type StandardType = 'primary_key' | 'enum' | 'dict' | 'amount' | 'date' | 'string'

/** 数据标准 */
export interface Standard {
  /** 标准 ID */
  id: string
  /** 标准项名（如 user_id） */
  name: string
  /** 类型 */
  type: StandardType
  /** 码值/规则描述 */
  rule: string
  /** 引用资产数 */
  refAssetCount: number
  /** 描述 */
  description?: string
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 创建标准参数 */
export interface CreateStandardParams {
  name: string
  type: StandardType
  rule: string
  description?: string
}

/** 更新标准参数 */
export interface UpdateStandardParams {
  name?: string
  type?: StandardType
  rule?: string
  description?: string
}

/** 标准列表查询参数 */
export interface StandardListQuery extends PageQuery {
  type?: StandardType
}

/** 落标率统计 */
export interface StandardSummary {
  /** 总标准数 */
  total: number
  /** 已落标数 */
  applied: number
  /** 落标率（百分比） */
  applyRate: number
}

/** 资源根路径 */
const BASE = '/standards'

/**
 * 查询标准列表（分页）
 */
export function listStandards(params?: StandardListQuery): Promise<PagedResult<Standard>> {
  return get<PagedResult<Standard>>(BASE, params as Record<string, unknown>)
}

/**
 * 获取标准详情
 */
export function getStandard(id: string): Promise<Standard> {
  return get<Standard>(`${BASE}/${id}`)
}

/**
 * 创建标准
 */
export function createStandard(data: CreateStandardParams): Promise<Standard> {
  return post<Standard>(BASE, data)
}

/**
 * 更新标准
 */
export function updateStandard(id: string, data: UpdateStandardParams): Promise<Standard> {
  return put<Standard>(`${BASE}/${id}`, data)
}

/**
 * 删除标准
 */
export function deleteStandard(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/**
 * 查询落标率统计
 */
export function getSummary(): Promise<StandardSummary> {
  return get<StandardSummary>(`${BASE}/summary`)
}

/** 标准符合性检查：单条标准的检查明细 */
export interface ComplianceItem {
  /** 标准 ID */
  standardId: string
  /** 标准名 */
  standardName: string
  /** 标准类型 */
  type: StandardType
  /** 已检查资产数（字段缺失不适用者不计入） */
  checked: number
  /** 违反数 */
  violations: number
  /** 是否全部符合 */
  compliant: boolean
  /** 违反样例（服务端最多返回 20 条） */
  violationSamples: Array<{ assetId: string; assetName: string; reason: string }>
}

/** 标准符合性检查结果（GET /standards/compliance，无参数） */
export interface ComplianceResult {
  standards: ComplianceItem[]
  totalChecked: number
  totalViolations: number
  /** 符合率（百分比，一位小数） */
  complianceRate: number
}

/**
 * 标准符合性检查（标准落地校验）
 *
 * 后端按标准类型把 rule 真实作用于资产数据：
 * enum/dict=码值集、string/primary_key=命名正则、amount=数值格式、date=ISO 日期。
 */
export function getCompliance(): Promise<ComplianceResult> {
  return get<ComplianceResult>(`${BASE}/compliance`)
}
