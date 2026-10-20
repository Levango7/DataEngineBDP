/**
 * 资产流通域（asset-exchange）中主应用尚未覆盖的两块：结算/分账与审计日志。
 *
 * 后端：platform/asset-exchange/asset_exchange/api/routers/{assets,audit}.py
 * 端点：/api/v1/assets/{id}/settlements · /allocate · /allocations，
 *       /api/v1/audit-logs（含 /integrity 与 /{log_id}）
 *
 * 只做增量：资产列表/详情/登记(POST /assets)/订阅/交付/出账记录在
 * `src/api/assetMarket.ts` 已有实现，这里不重复一份，避免同一资源两套客户端漂移。
 *
 * 已知缺口（如实记，不当已完成）：tenant 级 `GET/POST /api/v1/settlements`
 * （按 finops 账单触发结算 settleByBilling）后端存在，但主应用与旧子看板都没有入口。
 */
import { get, post } from './client'

const ASSETS_BASE = '/assets'
const AUDIT_BASE = '/audit-logs'

/** 与后端 models/settlement.py::Settlement 对齐（含分成比例与失败原因） */
export interface Settlement {
  id: string
  assetId: string
  tenantId: string
  period: string
  status: string
  totalAmount: number
  providerRevenue: number
  platformRevenue: number
  billingRecordIds?: string[]
  providerShare: number
  platformShare: number
  settledAt?: string | null
  errorMessage?: string | null
}

/** 与后端 models/settlement.py::Allocation 对齐 */
export interface Allocation {
  id: string
  settlementId: string
  assetId: string
  status: string
  providerAmount: number
  platformAmount: number
  allocatedAt?: string | null
}

/** 后端 models/audit.py::AuditLog（TimestampMixin 带 createdAt/updatedAt） */
export interface AuditLog {
  id: string
  action: string
  assetId?: string | null
  subscriptionId?: string | null
  settlementId?: string | null
  actorId: string
  createdAt: string
  updatedAt: string
}

/** 后端 models/audit.py::AuditIntegrityReport */
export interface AuditIntegrityReport {
  totalLogs: number
  verified: boolean
  brokenAt?: string | null
  message: string
}

export interface AuditLogQuery {
  assetId?: string
  action?: string
  actorId?: string
  /** 非 admin 传他人租户会被后端拒 403（audit.py 显式校验），这里只做同租户过滤 */
  tenantId?: string
  limit?: number
  offset?: number
}

export function listAssetSettlements(assetId: string): Promise<Settlement[]> {
  return get<Settlement[]>(`${ASSETS_BASE}/${encodeURIComponent(assetId)}/settlements`)
}

export function allocateAsset(
  assetId: string,
  data?: { providerAccountId?: string; platformAccountId?: string }
): Promise<Allocation> {
  return post<Allocation>(`${ASSETS_BASE}/${encodeURIComponent(assetId)}/allocate`, data ?? {})
}

export function listAssetAllocations(assetId: string): Promise<Allocation[]> {
  return get<Allocation[]>(`${ASSETS_BASE}/${encodeURIComponent(assetId)}/allocations`)
}

export function listAuditLogs(params?: AuditLogQuery): Promise<AuditLog[]> {
  return get<AuditLog[]>(AUDIT_BASE, params as Record<string, unknown>)
}

export function verifyAuditIntegrity(): Promise<AuditIntegrityReport> {
  return get<AuditIntegrityReport>(`${AUDIT_BASE}/integrity`)
}

export function getAuditLog(logId: string): Promise<AuditLog> {
  return get<AuditLog>(`${AUDIT_BASE}/${encodeURIComponent(logId)}`)
}
