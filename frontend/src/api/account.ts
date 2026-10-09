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
/**
 * 套餐档位（账户域）。
 *
 * 与后端唯一真源对齐（`AccountController.PLANS` / `GET /api/v1/account/plans`）：
 * 此前这里是 standard/enterprise/flagship，而后端只有 free/pro/enterprise ⇒
 * 弹窗默认 flagship 必被 400 拒（台账 #62）。档位与月费一律以后端返回为准。
 */
export type PlanTier = 'free' | 'pro' | 'enterprise'

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

/** 套餐档位项（GET /account/plans；月费为唯一真源，前端不再硬编码） */
export interface PlanOption {
  /** 档位键（与 PlanTier 同值域） */
  key: PlanTier
  /** 后端显示名（中文；界面标签走 i18n） */
  name: string
  /** 月费（元） */
  monthlyFee: number
  /** CPU 配额 */
  cpu: string
  /** 内存配额 */
  memory: string
}

/** 套餐档位目录 */
export interface AccountPlans {
  plans: PlanOption[]
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
 * 获取套餐档位目录（升级弹窗的选项与月费真源）
 */
export function getAccountPlans(): Promise<AccountPlans> {
  return get<AccountPlans>(`${BASE}/plans`)
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
