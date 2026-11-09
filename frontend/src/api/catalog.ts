/**
 * 数据目录（Catalog）API —— 数据地图页（/data-map）的数据源。
 *
 * 对应后端 platform/catalog（Go + Gin）：库/表元数据管理与中文分词检索。
 * 注意后端响应外层为 { data, total, page, pageSize }（与前端 PagedResult 的
 * { list, total, page, pageSize } 命名不同），此处统一映射为 PagedResult
 * 供页面消费，避免各页面重复适配。
 */
import { get } from './client'
import type { PagedResult } from './types'

/** 数据库（命名空间）元数据 */
export interface CatalogDatabase {
  id: string
  tenantId: string
  name: string
  description?: string
  owner: string
  createdAt: string
}

/** 表列元数据 */
export interface CatalogColumn {
  name: string
  type: string
  description?: string
  nullable: boolean
}

/** 表元数据 */
export interface CatalogTable {
  id: string
  tenantId: string
  databaseName: string
  tableName: string
  description?: string
  columns: CatalogColumn[]
  partitionKeys?: string[]
  properties?: Record<string, string>
  createdAt: string
  updatedAt: string
}

/** 检索命中（table + 相关性分数 0~1） */
export interface CatalogSearchHit {
  table: CatalogTable
  score: number
}

/** 后端分页外层（Go handler 统一返回 { data, total, page, pageSize }） */
interface CatalogPage<T> {
  data: T[]
  total: number
  page: number
  pageSize: number
}

const BASE = '/catalog'

/**
 * 库列表（分页；pageSize 上限 100，交给后端截断）
 */
export async function listDatabases(params?: {
  page?: number
  pageSize?: number
}): Promise<PagedResult<CatalogDatabase>> {
  const r = await get<CatalogPage<CatalogDatabase>>(
    `${BASE}/databases`,
    params as Record<string, unknown>
  )
  return {
    list: r.data ?? [],
    total: r.total ?? 0,
    page: r.page ?? 1,
    pageSize: r.pageSize ?? 20
  }
}

/**
 * 表列表（可选按库名过滤，分页）
 */
export async function listTables(params?: {
  database?: string
  page?: number
  pageSize?: number
}): Promise<PagedResult<CatalogTable>> {
  const r = await get<CatalogPage<CatalogTable>>(
    `${BASE}/tables`,
    params as Record<string, unknown>
  )
  return {
    list: r.data ?? [],
    total: r.total ?? 0,
    page: r.page ?? 1,
    pageSize: r.pageSize ?? 20
  }
}

/**
 * 表检索（表名 + 描述的中文分词全文检索；q 必填）
 */
export async function searchTables(q: string, limit = 50): Promise<CatalogSearchHit[]> {
  const r = await get<{ data: CatalogSearchHit[]; total: number; query: string }>(
    `${BASE}/search/tables`,
    { q, limit }
  )
  return r.data ?? []
}
