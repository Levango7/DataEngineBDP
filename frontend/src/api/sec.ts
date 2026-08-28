/**
 * 安全脱敏 API
 *
 * 对应后端 platform/sec/ 字段级脱敏与权限审批：
 * - 脱敏策略 CRUD
 * - 权限申请审批流
 */
import { get, post, put, del } from './client'

/** 脱敏策略 */
export type MaskStrategy = 'mask' | 'hash' | 'authorized_only' | 'plain'

/** 脱敏算法 */
export type MaskAlgorithm = 'SM3' | 'SHA256' | 'AES' | 'MASK_PHONE'

/** 策略状态 */
export type StrategyStatus = 'active' | 'pending' | 'disabled'

/** 审批状态 */
export type ApprovalStatus = 'pending' | 'approved' | 'rejected'

/** 脱敏策略 */
export interface MaskPolicy {
  /** 策略 ID */
  id: string
  /** 字段名 */
  fieldName: string
  /** 所属资产 */
  assetName: string
  /** 策略 */
  strategy: MaskStrategy
  /** 算法 */
  algorithm: MaskAlgorithm
  /** 状态 */
  status: StrategyStatus
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 权限申请 */
export interface PermissionApproval {
  /** 申请 ID */
  id: string
  /** 申请人 */
  applicant: string
  /** 资产名 */
  asset: string
  /** 权限 */
  permission: string
  /** 状态 */
  status: ApprovalStatus
  /** 申请时间 */
  createdAt: string
}

/** 创建策略参数 */
export interface CreateMaskPolicyParams {
  fieldName: string
  assetName: string
  strategy: MaskStrategy
  algorithm: MaskAlgorithm
}

/** 更新策略参数 */
export interface UpdateMaskPolicyParams {
  strategy?: MaskStrategy
  algorithm?: MaskAlgorithm
  status?: StrategyStatus
}

/** 资源根路径 */
const BASE = '/sec'

/**
 * 查询脱敏策略列表
 */
export function listMaskPolicies(): Promise<MaskPolicy[]> {
  return get<MaskPolicy[]>(`${BASE}/policies`)
}

/**
 * 创建脱敏策略
 */
export function createMaskPolicy(data: CreateMaskPolicyParams): Promise<MaskPolicy> {
  return post<MaskPolicy>(`${BASE}/policies`, data)
}

/**
 * 更新脱敏策略
 */
export function updateMaskPolicy(id: string, data: UpdateMaskPolicyParams): Promise<MaskPolicy> {
  return put<MaskPolicy>(`${BASE}/policies/${id}`, data)
}

/**
 * 删除脱敏策略
 */
export function deleteMaskPolicy(id: string): Promise<void> {
  return del<void>(`${BASE}/policies/${id}`)
}

/**
 * 查询权限申请列表
 */
export function listApprovals(status?: ApprovalStatus): Promise<PermissionApproval[]> {
  const params: Record<string, unknown> = {}
  if (status) params.status = status
  return get<PermissionApproval[]>(`${BASE}/approvals`, params)
}

/**
 * 批准权限申请
 */
export function approveApproval(id: string): Promise<void> {
  return post<void>(`${BASE}/approvals/${id}/approve`)
}

/**
 * 驳回权限申请
 */
export function rejectApproval(id: string): Promise<void> {
  return post<void>(`${BASE}/approvals/${id}/reject`)
}
