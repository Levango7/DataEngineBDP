/**
 * 向量库 API（L4.5）
 *
 * 后端：platform/vector/
 * 端点前缀：/api/v1/vector
 *
 * 基于 Milvus 统一管理 embedding 集合，支撑语义检索与 RAG。
 */
import { get, post } from './client'

/** 资源根路径 */
const BASE = '/vector'

/** 索引类型 */
export type IndexType = 'HNSW' | 'IVF_PQ' | 'FLAT'

/** 向量集合 */
export interface VectorCollection {
  id: string
  name: string
  /** 维度 */
  dimension: number
  /** 条数 */
  count: string
  /** 索引类型 */
  index: IndexType
  /** 关联知识库 */
  relatedKb: string
}

/** 创建集合参数 */
export interface CreateCollectionParams {
  name: string
  dimension: number
  index: IndexType
  relatedKb: string
}

/** 相似度检索结果 */
export interface SearchResult {
  id: string
  score: number
  payload: Record<string, unknown>
}

// ---------- API 方法 ----------

/**
 * 列出向量集合
 */
export function listCollections(): Promise<VectorCollection[]> {
  return get<VectorCollection[]>(BASE)
}

/**
 * 创建向量集合
 */
export function createCollection(params: CreateCollectionParams): Promise<VectorCollection> {
  return post<VectorCollection>(BASE, params)
}

/**
 * 相似度检索
 */
export function search(query: string, topK = 5): Promise<SearchResult[]> {
  return post<SearchResult[]>(`${BASE}/search`, { query, topK })
}