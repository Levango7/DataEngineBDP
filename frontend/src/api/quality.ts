/**
 * 数据质量规则 API
 *
 * 对应后端 platform/quality/ 规则管理：
 * - 规则 CRUD
 * - 规则校验执行
 * - 通过率统计
 */
import { get, post, put, del } from './client'
import type { PagedResult, PageQuery } from './types'

/** 校验类型 */
export type CheckType = 'not_null' | 'unique' | 'range' | 'fluctuation' | 'regex' | 'sql'

/** 异常动作 */
export type ActionOnFail = 'alert' | 'block_downstream'

/** 规则状态 */
export type RuleStatus = 'enabled' | 'disabled'

/** 最近校验结果 */
export type CheckResult = 'pass' | 'warn' | 'fail'

/** 质量规则 */
export interface QualityRule {
  /** 规则 ID */
  id: string
  /** 规则名 */
  name: string
  /** 对象表 */
  targetTable: string
  /** 字段（可选） */
  targetField?: string
  /** 校验类型 */
  checkType: CheckType
  /** 阈值表达式 */
  threshold: string
  /** 异常动作 */
  actionOnFail: ActionOnFail
  /** 状态 */
  status: RuleStatus
  /** 最近校验时间 */
  lastCheckAt?: string
  /** 最近校验结果 */
  lastResult?: CheckResult
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 创建规则参数 */
export interface CreateRuleParams {
  name: string
  targetTable: string
  targetField?: string
  checkType: CheckType
  threshold: string
  actionOnFail: ActionOnFail
}

/** 更新规则参数 */
export interface UpdateRuleParams {
  name?: string
  threshold?: string
  actionOnFail?: ActionOnFail
  status?: RuleStatus
}

/** 规则列表查询参数 */
export interface RuleListQuery extends PageQuery {
  targetTable?: string
  checkType?: CheckType
  status?: RuleStatus
}

/** 通过率统计 */
export interface QualitySummary {
  /** 总规则数 */
  total: number
  /** 通过规则数 */
  passed: number
  /** 通过率（百分比） */
  passRate: number
}

/** 资源根路径 */
const BASE = '/quality/rules'

/**
 * 查询规则列表（分页）
 */
export function listRules(params?: RuleListQuery): Promise<PagedResult<QualityRule>> {
  return get<PagedResult<QualityRule>>(BASE, params as Record<string, unknown>)
}

/**
 * 获取规则详情
 */
export function getRule(id: string): Promise<QualityRule> {
  return get<QualityRule>(`${BASE}/${id}`)
}

/**
 * 创建规则
 */
export function createRule(data: CreateRuleParams): Promise<QualityRule> {
  return post<QualityRule>(BASE, data)
}

/**
 * 更新规则
 */
export function updateRule(id: string, data: UpdateRuleParams): Promise<QualityRule> {
  return put<QualityRule>(`${BASE}/${id}`, data)
}

/**
 * 删除规则
 */
export function deleteRule(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/**
 * 立即触发规则校验
 */
export function runCheck(id: string): Promise<QualityRule> {
  return post<QualityRule>(`${BASE}/${id}/check`)
}

/**
 * 查询通过率统计
 */
export function getSummary(): Promise<QualitySummary> {
  return get<QualitySummary>(`${BASE}/summary`)
}
