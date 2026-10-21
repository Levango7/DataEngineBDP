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
 * 已知缺口（如实记，不当已完成）：tenant 级 `POST /api/v1/settlements`
 * （按 finops 账单触发结算 settleByBilling）后端存在，但主应用仍无入口；
 * 而 tenant 级 `GET /api/v1/settlements` 已由 `listTenantSettlements` 接进
 * `/exchange-overview` 看板（旧子看板的图表通路是逐资产扇出，#673 收权后不可用）。
 */
import { get, post } from './client'

const ASSETS_BASE = '/assets'
const AUDIT_BASE = '/audit-logs'
const SETTLEMENTS_BASE = '/settlements'

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

/**
 * 后端 models/audit.py::AuditLog（TimestampMixin 带 createdAt/updatedAt）。
 * 键集以真服务实测为准：`GET /api/v1/audit-logs` 返回 14 个键
 * （action actorId actorRole assetId createdAt detail hash id prevHash result
 * settlementId subscriptionId tenantId updatedAt），这里逐一对齐；
 * 少了 `result/detail/hash` 的话，审计页既看不到成败也看不到链哈希，
 * 而"能自证没被改过"正是这页存在的理由。
 */
export interface AuditLog {
  id: string
  action: string
  assetId?: string | null
  subscriptionId?: string | null
  settlementId?: string | null
  actorId: string
  actorRole?: string | null
  tenantId?: string | null
  /** models/base.py::AuditResult = success | failure | pending */
  result: string
  detail: Record<string, unknown>
  prevHash: string
  hash: string
  createdAt: string
  updatedAt: string
}

/**
 * models/base.py::AuditAction 的全部取值（11 个）。
 * 后端把 `action` 查询参数按枚举校验，写错的值直接 422，
 * 所以过滤控件必须从这个闭包里选，不能留自由输入。
 */
export const AUDIT_ACTIONS = [
  'register',
  'audit',
  'publish',
  'subscribe',
  'download',
  'invoke',
  'deliver',
  'settle',
  'allocate',
  'offline',
  'relist'
] as const

export type AuditActionValue = (typeof AUDIT_ACTIONS)[number]

/** 后端 models/audit.py::AuditIntegrityReport */
export interface AuditIntegrityReport {
  totalLogs: number
  verified: boolean
  brokenAt?: string | null
  message: string
}

export interface AuditLogQuery {
  assetId?: string
  action?: AuditActionValue
  actorId?: string
  /** 非 admin 传他人租户会被后端拒 403（audit.py 显式校验），这里只做同租户过滤 */
  tenantId?: string
  limit?: number
  offset?: number
}

/**
 * 资产结算（`POST /assets/{id}/settle`，models/settlement.py::SettleRequest）。
 * 两个 share 字段后端约束 ge=0/le=1 且缺省走配置默认值；该端点仅 admin 可执行
 * （assets.py 的 requireAdmin），非 admin 会得到 403，页面须原样呈现而不是吞掉。
 */
export function settleAsset(
  assetId: string,
  data?: { period?: string; providerShare?: number; platformShare?: number }
): Promise<Settlement> {
  return post<Settlement>(`${ASSETS_BASE}/${encodeURIComponent(assetId)}/settle`, data ?? {})
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

/**
 * `GET /settlements` 的行形状是路由里手工拼的投影（settlements.py:157-178），
 * 只有 8 个键——比序列化整个 Settlement 模型少 `tenantId`/`providerShare`/
 * `platformShare`/`billingRecordIds`。按实测形状声明，别拿上面的 `Settlement`
 * 去套这个半截投影（那是 #80 那类字段级漂移：类型说有别的东西、DOM 上是空值）。
 */
export interface TenantSettlementRow {
  id: string
  assetId: string
  period: string
  status: string
  totalAmount: number
  providerRevenue: number
  platformRevenue: number
  settledAt?: string | null
}

/** `listSettlements` 的信封：`{tenant, count, settlements}`（租户按 JWT claim 强制过滤） */
export interface TenantSettlementList {
  tenant: string
  count: number
  settlements: TenantSettlementRow[]
}

export interface TenantSettlementQuery {
  assetId?: string
  period?: string
  limit?: number
  offset?: number
}

export function listTenantSettlements(
  params?: TenantSettlementQuery
): Promise<TenantSettlementList> {
  return get<TenantSettlementList>(SETTLEMENTS_BASE, params as Record<string, unknown>)
}
