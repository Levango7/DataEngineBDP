/**
 * 数据资产流通市场 API（L5.6）
 *
 * 后端：platform/asset-market/
 * 端点前缀：/api/v1/assets
 *
 * 提供"提供方—平台—消费方"三方市场的资产登记、上架、订阅、交付与计费能力。
 */
import { get, post, put, del } from './client'

/** 资产资源根路径 */
const BASE = '/assets'

/** 订阅资源根路径 */
const SUB_BASE = '/asset-subscriptions'

// ---------- 类型定义 ----------

/** 资产类型 */
export type AssetType = 'table' | 'api' | 'model' | 'dashboard' | 'stream'

/** 资产状态 */
export type AssetStatus = 'draft' | 'listed' | 'offline' | 'rejected'

/** 安全分级 */
export type SecurityLevel = 'public' | 'internal' | 'sensitive'

/** 计费方式 */
export type BillingMode = 'by_call' | 'by_data' | 'by_time' | 'one_time'

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
  /** 提供方租户 ID */
  owner: string
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
 */
export function offlineAsset(id: string): Promise<Asset> {
  return post<Asset>(`${BASE}/${id}/offline`)
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
