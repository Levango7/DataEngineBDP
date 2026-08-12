/**
 * 账户与配额 API
 *
 * 后端：platform/account/
 * 端点前缀：/api/v1/account
 *
 * 套餐即容量边界；超额自动扩容或升级套餐，费用清晰可核算。
 */
import { get, post } from './client'

/** 资源根路径 */
const BASE = '/account'

/** 套餐版本 */
export type PlanTier = 'standard' | 'enterprise' | 'flagship'

/** 配额项 */
export interface QuotaItem {
  /** 配额名称 */
  name: string
  /** 总量 */
  total: string
  /** 已用 */
  used: string
  /** 使用百分比 */
  usagePercent: number
}

/** 账户套餐信息 */
export interface AccountPlan {
  /** 当前套餐 */
  plan: PlanTier
  /** 套餐显示名 */
  planName: string
  /** 配额列表 */
  quotas: QuotaItem[]
}

/** 计费明细项 */
export interface BillingItem {
  id: string
  /** 项名 */
  name: string
  /** 用量 */
  usage: string
  /** 费用（元） */
  cost: number
}

/** 计费明细 */
export interface BillingDetail {
  /** 明细项列表 */
  items: BillingItem[]
  /** 合计费用（元） */
  totalCost: number
}

/** 升级套餐参数 */
export interface UpgradePlanParams {
  targetPlan: PlanTier
}

/** 升级套餐结果 */
export interface UpgradePlanResult {
  /** 预计月费（元） */
  estimatedMonthlyFee: number
  /** 提交状态 */
  status: 'submitted' | 'success' | 'failed'
}

// ---------- API 方法 ----------

/**
 * 获取账户套餐信息
 */
export function getAccountPlan(): Promise<AccountPlan> {
  return get<AccountPlan>(`${BASE}/plan`)
}

/**
 * 获取本月计费明细
 */
export function getBillingDetail(): Promise<BillingDetail> {
  return get<BillingDetail>(`${BASE}/billing`)
}

/**
 * 升级套餐
 */
export function upgradePlan(params: UpgradePlanParams): Promise<UpgradePlanResult> {
  return post<UpgradePlanResult>(`${BASE}/upgrade`, params)
}