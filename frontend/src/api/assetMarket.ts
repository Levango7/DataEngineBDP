/**
 * 数据资产流通市场 API（L5.6）
 *
 * 后端：platform/asset-market/
 * 端点前缀：/api/v1/assets
 *
 * 提供"提供方—平台—消费方"三方市场的资产登记、上架、订阅、交付与计费能力。
 */
import { get, post, del } from './client'

/** 资产资源根路径 */
const BASE = '/assets'

/**
 * 订阅资源根路径
 * Sprint 4.2：恢复独立前缀 /asset-subscriptions——Sprint 2.2 的"对齐"与
 * open-api-catalog 的 /subscriptions 正面冲突（vite proxy 固定指 asset-exchange，
 * APIMarket 页的订阅审批全部错路由），按域隔离修正
 */
const SUB_BASE = '/asset-subscriptions'

// ---------- 类型定义 ----------

/** 资产类型 */
export type AssetType = 'table' | 'api' | 'model' | 'dashboard' | 'stream'

/**
 * 资产状态。取值以 `models/base.py::AssetStatus` 为准（5 个）；
 * 此前少写 `pending_audit`，而审核/上架的可点状态判断都要读它。
 */
export type AssetStatus = 'draft' | 'pending_audit' | 'listed' | 'offline' | 'rejected'

/** 安全分级 */
export type SecurityLevel = 'public' | 'internal' | 'sensitive'

/**
 * 计费方式。取值以 `models/base.py::BillingMode` 为准（5 个）；
 * 此前少写 `subscription`，而资产定价下拉必须能给出这个合法值。
 */
export type BillingMode = 'by_call' | 'by_data' | 'by_time' | 'subscription' | 'one_time'

/** 交付方式 */
export type DeliveryMethod = 'api' | 'file' | 'database_direct'

/** 交付状态 */
export type DeliveryStatus = 'pending' | 'running' | 'succeeded' | 'failed'

/** 订阅状态 */
export type SubscriptionStatus = 'pending' | 'approved' | 'active' | 'expired' | 'rejected'

/** 资产定价 */
export interface AssetPricing {
  /** 计费方式 */
  mode: BillingMode
  /** 单价 */
  price: number
  /** 计量单位 */
  unit: string
}

/** 资产 Schema 字段 */
export interface AssetSchemaField {
  name: string
  type: string
  description?: string
}

/** 资产 Schema */
export interface AssetSchema {
  fields: AssetSchemaField[]
}

/** 资产 */
export interface Asset {
  id: string
  name: string
  type: AssetType
  /**
   * 提供方租户 ID。字段名以后端**序列化输出**为准：`models/asset.py` 的
   * `tenantId` 带 `validation_alias=AliasChoices("tenantId", "owner")`，那只作用于
   * 入参校验，响应体里只有 `tenantId`（`GET /api/v1/assets` 与 `POST /assets/register`
   * 实测键集均无 `owner`）。此前这里写 `owner`，运行时恒为 undefined。
   */
  tenantId: string
  description?: string
  status: AssetStatus
  /** 质量评分（0-100） */
  qualityScore: number
  securityLevel: SecurityLevel
  schema?: AssetSchema
  /** 样本数据 */
  sample?: Array<Record<string, unknown>>
  /** 更新频率 */
  updateFrequency: string
  /** 标签 */
  tags: Record<string, string>
  pricing: AssetPricing
  /**
   * 提供方声明的交付方式。台账 #81 记过它的假形态：前端一直在发这个键，
   * 而后端 `Asset` 模型没有该字段、请求 DTO 又 `extra='ignore'` ⇒ 值被静默丢弃，
   * 下拉只是个装饰件。现在模型、表列（含幂等 ALTER）与请求 DTO 三侧都真了，
   * 响应里一定带这个键。注意与订阅侧 `deliverAsset` 的 `method` 区分：
   * 那是某一次交付实际走的路径（后端 `models/delivery.py` 消费并分支），
   * 这里是资产对外声明"能怎么交付"。
   */
  deliveryMethod: DeliveryMethod
  /** 订阅者数量 */
  subscriberCount: number
  createdAt?: string
  updatedAt?: string
}

/** 订阅 */
export interface Subscription {
  id: string
  assetId: string
  /** 订阅方租户 ID */
  subscriberId: string
  status: SubscriptionStatus
  startTime?: string
  endTime?: string
  /** 交付状态 */
  deliveryStatus?: DeliveryStatus
  createdAt?: string
  updatedAt?: string
}

/** 计费记录 */
export interface BillingRecord {
  id: string
  /** 计费周期，如 2026-08 */
  period: string
  mode: BillingMode
  /** 使用量 */
  usage: number
  /** 计量单位 */
  unit: string
  /** 总金额 */
  amount: number
  /** 提供方收益（扣除平台抽成后） */
  providerRevenue: number
}

/** 资产列表查询参数 */
export interface AssetListQuery {
  keyword?: string
  type?: AssetType
  securityLevel?: SecurityLevel
  owner?: string
  status?: AssetStatus
  limit?: number
  offset?: number
}

/** 上架资产参数 */
export interface ListAssetParams {
  name: string
  type: AssetType
  securityLevel: SecurityLevel
  description?: string
  pricing: AssetPricing
  deliveryMethod: DeliveryMethod
}

/** 订阅资产参数 */
export interface SubscribeAssetParams {
  subscriberId: string
  purpose?: string
  quotaExpect?: number
}

/** 交付请求参数 */
export interface DeliverAssetParams {
  method: DeliveryMethod
  config: {
    endpoint?: string
    format?: string
    jdbcUrl?: string
    tableName?: string
  }
}

// ---------- API 方法 ----------

/**
 * 列出资产（市场浏览）
 */
export function listAssets(params?: AssetListQuery): Promise<Asset[]> {
  return get<Asset[]>(BASE, params as Record<string, unknown>)
}

/**
 * 获取资产详情
 */
export function getAsset(id: string): Promise<Asset> {
  return get<Asset>(`${BASE}/${id}`)
}

/**
 * 上架资产
 */
export function listAsset(data: ListAssetParams): Promise<Asset> {
  return post<Asset>(BASE, data)
}

/**
 * 下架资产
 * Sprint 2.2：对齐后端 asset-exchange 实际端点 DELETE /api/v1/assets/{id}（此前误用 POST /{id}/offline）
 */
export function offlineAsset(id: string): Promise<Asset> {
  return del<Asset>(`${BASE}/${id}`)
}

/**
 * 重新上架
 */
export function relistAsset(id: string): Promise<Asset> {
  return post<Asset>(`${BASE}/${id}/relist`)
}

// ---------- 订阅 ----------

/**
 * 列出订阅
 */
export function listSubscriptions(params?: {
  assetId?: string
  subscriberId?: string
  status?: SubscriptionStatus
  limit?: number
  offset?: number
}): Promise<Subscription[]> {
  return get<Subscription[]>(SUB_BASE, params as Record<string, unknown>)
}

/**
 * 订阅资产
 */
export function subscribeAsset(assetId: string, data: SubscribeAssetParams): Promise<Subscription> {
  return post<Subscription>(`${BASE}/${assetId}/subscribe`, data)
}

/**
 * 数据交付
 */
export function deliverAsset(
  subscriptionId: string,
  data: DeliverAssetParams
): Promise<Subscription> {
  return post<Subscription>(`${SUB_BASE}/${subscriptionId}/deliver`, data)
}

// ---------- 计费 ----------

/**
 * 获取订阅的计费记录
 */
export function getBillingRecords(subscriptionId: string): Promise<BillingRecord[]> {
  return get<BillingRecord[]>(`${SUB_BASE}/${subscriptionId}/billing`)
}

// ---------- 登记与审核（收编自 asset-exchange 子看板，台账 #78 C1b） ----------

/**
 * 登记资产（`POST /assets/register`，api/routers/assets.py::RegisterAssetRequest）。
 *
 * 与 `listAsset`（`POST /assets`，直接上架）是两条不同路径：登记只落 DRAFT，
 * 需再经 `auditAsset` 或 `publishAsset` 才会上架。
 * 未建模的两个字段 `schema`/`sample` 与 `sourceRef` 后端皆可省，页面暂不采集。
 */
export interface RegisterAssetParams {
  name: string
  type: AssetType
  /**
   * 提供方租户。缺省时后端回填 JWT claim；非 admin 填了与 claim 不一致的值会被拒
   * （assets.py 的 `resolve_tenant`）。旧字段名 `owner` 仍能作为**入参**被接受
   * （`validation_alias=AliasChoices`），但响应里不会出现该键。
   */
  tenantId?: string
  description?: string
  securityLevel: SecurityLevel
  qualityScore: number
  updateFrequency?: string
  tags?: Record<string, string>
  pricing?: AssetPricing
  sourceRef?: string
}

/** `models/base.py::AssetAuditResult` 的取值（小写，序列化即用此值） */
export type AssetAuditResult = 'approved' | 'rejected'

/** `models/base.py::AssetStatus` 的取值，页面据此决定哪些动作可点 */
export const ASSET_STATUS_VALUES = [
  'draft',
  'pending_audit',
  'listed',
  'offline',
  'rejected'
] as const

/**
 * 审核资产（`POST /assets/{id}/audit`）。
 *
 * 请求体里的 `auditorId` 后端已标注废弃（"审核人身份一律取 JWT sub claim，
 * 请求体自报不再采信"），所以这里**不发**这个字段——子看板还在硬塞 'admin-001'。
 * 通过与否还要过后端的自动检查（合规：SENSITIVE 需 tags.desensitize=true；
 * 质量：qualityScore >= 60），不通过时后端抛校验错误，页面原样显示。
 */
export function auditAsset(
  assetId: string,
  data: { result: AssetAuditResult; reason?: string }
): Promise<Asset> {
  return post<Asset>(`${BASE}/${encodeURIComponent(assetId)}/audit`, data)
}

/**
 * 上架资产（`POST /assets/{id}/publish`，**仅 admin**，`jwt_auth.py:128` 的 requireAdmin）。
 * 允许的状态为 draft / pending_audit / offline，其余状态后端会拒绝并给出原因。
 */
export function publishAsset(assetId: string): Promise<Asset> {
  return post<Asset>(`${BASE}/${encodeURIComponent(assetId)}/publish`)
}

/**
 * 登记资产（返回 DRAFT 态资产）。
 */
export function registerAsset(data: RegisterAssetParams): Promise<Asset> {
  return post<Asset>(`${BASE}/register`, data)
}
