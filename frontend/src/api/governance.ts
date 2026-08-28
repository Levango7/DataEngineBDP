/**
 * 数据资产治理 API
 *
 * 对应后端 platform/governance/ 资产目录管理：
 * - 资产 CRUD 与检索
 * - 资产 Schema、质量分、敏感等级
 * - 资产权限申请与审批
 */
import { get, post, put, del } from './client'
import type { PagedResult, PageQuery } from './types'

/** 数据分层 */
export type DataLayer = 'ODS' | 'DWD' | 'DWS' | 'ADS' | 'DIM'

/** 敏感级别 */
export type SensitivityLevel = 'none' | 'restricted' | 'PII'

/** 资产状态 */
export type AssetStatus = 'active' | 'deprecated' | 'draft'

/** 数据资产 */
export interface Asset {
  /** 资产 ID */
  id: string
  /** 资产名（如 dwd.order_wide） */
  name: string
  /** 分层 */
  layer: DataLayer
  /** 负责人 */
  owner: string
  /** 质量分（0-100） */
  score: number
  /** 敏感级别 */
  sensitivity: SensitivityLevel
  /** 状态 */
  status: AssetStatus
  /** 描述 */
  description?: string
  /** 标签 */
  tags?: string[]
  /** 更新频率 */
  refreshFrequency?: string
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 资产 Schema 字段 */
export interface AssetSchemaField {
  /** 字段名 */
  name: string
  /** 字段类型 */
  type: string
  /** 是否敏感 */
  sensitive: boolean
  /** 敏感级别 */
  sensitivity?: SensitivityLevel
  /** 描述 */
  description?: string
}

/** 资产 Schema */
export interface AssetSchema {
  assetId: string
  fields: AssetSchemaField[]
}

/** 资产质量规则结果 */
export interface AssetQualityItem {
  /** 规则名 */
  ruleName: string
  /** 是否通过 */
  passed: boolean
  /** 实际值 */
  actualValue?: string
  /** 期望值 */
  expectedValue?: string
}

/** 资产权限 */
export interface AssetPermission {
  /** 用户名 */
  user: string
  /** 权限类型 */
  permission: 'read' | 'write' | 'admin'
}

/** 创建资产参数 */
export interface CreateAssetParams {
  name: string
  layer: DataLayer
  owner: string
  sensitivity: SensitivityLevel
  description?: string
}

/** 更新资产参数 */
export interface UpdateAssetParams {
  name?: string
  layer?: DataLayer
  owner?: string
  sensitivity?: SensitivityLevel
  description?: string
  status?: AssetStatus
}

/** 资产列表查询参数 */
export interface AssetListQuery extends PageQuery {
  layer?: DataLayer
  owner?: string
  sensitivity?: SensitivityLevel
  status?: AssetStatus
}

/**
 * 资源根路径
 *
 * 注意：原路径 `/assets` 与 assetMarket.ts（资产流通市场，走 asset-exchange :8092）
 * 在 Vite proxy 上存在冲突。此处改为 `/governance/assets`，由 Vite proxy
 * `/api/v1/governance` 转发至 encaps-layer :8080 的 AssetController，避免冲突。
 * 后端 AssetController 的 @RequestMapping 需同步调整为 `/api/v1/governance/assets`。
 */
const BASE = '/governance/assets'

/**
 * 查询资产列表（分页）
 */
export function listAssets(params?: AssetListQuery): Promise<PagedResult<Asset>> {
  return get<PagedResult<Asset>>(BASE, params as Record<string, unknown>)
}

/**
 * 获取资产详情
 */
export function getAsset(id: string): Promise<Asset> {
  return get<Asset>(`${BASE}/${id}`)
}

/**
 * 创建资产
 */
export function createAsset(data: CreateAssetParams): Promise<Asset> {
  return post<Asset>(BASE, data)
}

/**
 * 更新资产
 */
export function updateAsset(id: string, data: UpdateAssetParams): Promise<Asset> {
  return put<Asset>(`${BASE}/${id}`, data)
}

/**
 * 删除资产
 */
export function deleteAsset(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/**
 * 获取资产 Schema
 */
export function getAssetSchema(id: string): Promise<AssetSchema> {
  return get<AssetSchema>(`${BASE}/${id}/schema`)
}

/**
 * 获取资产质量检查结果
 */
export function getAssetQuality(id: string): Promise<AssetQualityItem[]> {
  return get<AssetQualityItem[]>(`${BASE}/${id}/quality`)
}

/**
 * 获取资产权限列表
 */
export function getAssetPermissions(id: string): Promise<AssetPermission[]> {
  return get<AssetPermission[]>(`${BASE}/${id}/permissions`)
}

/**
 * 申请资产读权限
 */
export function applyAssetPermission(id: string, permission: 'read' | 'write'): Promise<void> {
  return post<void>(`${BASE}/${id}/apply-permission`, { permission })
}
