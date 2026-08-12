/**
 * 知识工程 API（L4.5）
 *
 * 后端：platform/knowledge/
 * 端点前缀：/api/v1/knowledge
 *
 * 提供知识库管理、文档入库、切片、向量化、RAG 策略配置能力。
 */
import { get, post } from './client'

/** 资源根路径 */
const BASE = '/knowledge'

/** 知识库状态 */
export type KbStatus = 'ready' | 'building' | 'failed'

/** 切片策略 */
export type ChunkStrategy =
  | 'by_paragraph'
  | 'by_title'
  | 'by_turn'
  | 'by_sentence'

/** 检索方式 */
export type RetrievalMethod = 'vector' | 'keyword' | 'hybrid'

/** 知识库 */
export interface KnowledgeBase {
  id: string
  name: string
  /** 文档数 */
  docCount: number
  /** 切片策略描述 */
  chunkStrategy: string
  /** 检索方式描述 */
  retrieval: string
  /** 状态 */
  status: KbStatus
}

/** RAG 策略配置 */
export interface RagStrategy {
  /** 检索 TopK */
  topK: number
  /** 分数阈值 */
  scoreThreshold: number
  /** 重排模型 */
  rerankerModel: string
  /** 是否开启引用溯源 */
  citationEnabled: boolean
}

/** 上传文档参数 */
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
  status: 'parsed' | 'failed'
}

// ---------- API 方法 ----------

/**
 * 列出知识库
 */
export function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  return get<KnowledgeBase[]>(BASE)
}

/**
 * 获取 RAG 策略
 */
export function getRagStrategy(): Promise<RagStrategy> {
  return get<RagStrategy>(`${BASE}/rag-strategy`)
}

/**
 * 上传文档
 */
export function uploadDoc(params: UploadDocParams): Promise<UploadDocResult> {
  return post<UploadDocResult>(`${BASE}/upload`, params)
}