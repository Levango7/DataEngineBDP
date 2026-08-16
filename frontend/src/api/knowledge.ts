/**
 * 知识工程 API（L4.5）
 *
 * 后端：platform/encaps-layer KnowledgeController
 * 端点前缀：/api/v1/knowledge
 *
 * 提供知识库管理、文档入库、切片、向量化、RAG 策略配置能力。
 */
import { get, post, put, del } from './client'

/** 资源根路径 */
const BASE = '/knowledge'

/** 知识库状态 */
export type KbStatus = 'active' | 'pending' | 'disabled' | 'ready' | 'building' | 'failed' | string

/** 切片策略 */
export type ChunkStrategy = 'by_paragraph' | 'by_title' | 'by_turn' | 'by_sentence' | string

/** 检索方式 */
export type RetrievalMethod = 'vector' | 'keyword' | 'hybrid' | string

/** 知识库（对齐后端 KnowledgeController#toView） */
export interface KnowledgeBase {
  id: string
  name: string
  /** 文档数 */
  docCount: number
  /** 切片策略描述 */
  chunkStrategy?: string
  /** 检索方式描述 */
  retrieval?: string
  /** 状态 */
  status: KbStatus
  createdAt?: string
  updatedAt?: string
}

/** 创建/更新知识库参数 */
export interface KnowledgeBaseParams {
  name: string
  chunkStrategy?: string
  retrieval?: string
}

/** RAG 策略配置（对齐后端 KnowledgeController#toRagView） */
export interface RagStrategy {
  /** 检索 TopK */
  topK?: number
  /** 分数阈值 */
  scoreThreshold?: number
  /** 重排模型 */
  rerankerModel?: string
  /** 是否开启引用溯源 */
  citationEnabled?: boolean
  /** 切片策略 */
  chunkStrategy?: string
  /** 检索方式 */
  retrievalMethod?: string
  updatedAt?: string
}

/** RAG 策略更新参数 */
export interface RagStrategyParams {
  topK?: number
  scoreThreshold?: number
  rerankerModel?: string
  citationEnabled?: boolean
  chunkStrategy?: string
  retrievalMethod?: string
}

/** 文档状态 */
export type DocStatus = 'uploaded' | 'parsed' | 'vectorized' | 'failed' | string

/** 知识库文档（对齐后端 KnowledgeController#toDocumentView） */
export interface KnowledgeDocument {
  id: string
  knowledgeBaseId: string
  fileName: string
  fileSize?: number
  fileType?: string
  chunkCount?: number
  vectorCount?: number
  status: DocStatus
  errorMessage?: string
  uploadedAt?: string
}

/** 上传文档参数（兼容旧版 JSON 上传） */
export interface UploadDocParams {
  kbId: string
  fileName: string
  /** 文件内容（Base64）或 URL */
  content?: string
}

/** 上传文档结果 */
export interface UploadDocResult {
  docId: string
  kbId: string
  status: 'parsed' | 'failed' | string
}

// ---------- API 方法 ----------

/**
 * 列出知识库
 * GET /knowledge
 */
export function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  return get<KnowledgeBase[]>(BASE)
}

/**
 * 知识库详情
 * GET /knowledge/{id}
 */
export function getKnowledgeBase(id: string): Promise<KnowledgeBase> {
  return get<KnowledgeBase>(`${BASE}/${id}`)
}

/**
 * 创建知识库
 * POST /knowledge
 */
export function createKnowledgeBase(params: KnowledgeBaseParams): Promise<KnowledgeBase> {
  return post<KnowledgeBase>(BASE, params)
}

/**
 * 更新知识库
 * PUT /knowledge/{id}
 */
export function updateKnowledgeBase(
  id: string,
  params: KnowledgeBaseParams
): Promise<KnowledgeBase> {
  return put<KnowledgeBase>(`${BASE}/${id}`, params)
}

/**
 * 删除知识库
 * DELETE /knowledge/{id}
 */
export function deleteKnowledgeBase(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/**
 * 获取 RAG 策略
 * GET /knowledge/rag-strategy
 */
export function getRagStrategy(): Promise<RagStrategy> {
  return get<RagStrategy>(`${BASE}/rag-strategy`)
}

/**
 * 更新 RAG 策略
 * PUT /knowledge/rag-strategy
 */
export function updateRagStrategy(params: RagStrategyParams): Promise<RagStrategy> {
  return put<RagStrategy>(`${BASE}/rag-strategy`, params)
}

/**
 * 上传文档（multipart/form-data，推荐）
 * POST /knowledge/{id}/documents
 */
export function uploadDocument(kbId: string, file: File): Promise<KnowledgeDocument> {
  const formData = new FormData()
  formData.append('file', file)
  return post<KnowledgeDocument>(`${BASE}/${kbId}/documents`, formData, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}

/**
 * 兼容旧版 JSON 上传
 * POST /knowledge/upload
 */
export function uploadDoc(params: UploadDocParams): Promise<UploadDocResult> {
  return post<UploadDocResult>(`${BASE}/upload`, params)
}

/**
 * 知识库文档列表
 * GET /knowledge/{id}/documents
 */
export function listDocuments(kbId: string): Promise<KnowledgeDocument[]> {
  return get<KnowledgeDocument[]>(`${BASE}/${kbId}/documents`)
}

/**
 * 删除文档
 * DELETE /knowledge/{id}/documents/{docId}
 */
export function deleteDocument(kbId: string, docId: string): Promise<void> {
  return del<void>(`${BASE}/${kbId}/documents/${docId}`)
}
