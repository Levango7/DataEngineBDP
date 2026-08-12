/**
 * LLMOps API（L4.5）
 *
 * 后端：platform/llmops/
 * 端点前缀：/api/v1/llmops
 *
 * 提供大模型注册、微调、评估、部署一体化运营能力。
 */
import { get, post } from './client'

/** 资源根路径 */
const BASE = '/llmops'

/** 模型类型 */
export type ModelType = 'base' | 'finetuned'

/** 模型状态 */
export type ModelStatus = 'deployed' | 'training' | 'draft' | 'failed'

/** 模型注册项 */
export interface ModelRegistry {
  id: string
  name: string
  type: ModelType
  /** 基座模型名（微调模型才有） */
  baseModel: string
  status: ModelStatus
  /** 部署端点 */
  endpoint: string
}

/** 评估指标 */
export interface EvalMetric {
  /** 模型名 */
  modelName: string
  /** 准确率 */
  accuracy: number
  /** 幻觉率 */
  hallucinationRate: number
  /** 对比基座提升（百分点） */
  baseLiftPt: number
}

/** 微调任务参数 */
export interface FinetuneParams {
  modelName: string
  baseModel: string
  trainingData: string
  gpuConfig: string
  epochs: number
}

/** 微调任务结果 */
export interface FinetuneResult {
  taskId: string
  status: 'submitted' | 'running' | 'success' | 'failed'
}

// ---------- API 方法 ----------

/**
 * 列出模型注册表
 */
export function listModels(): Promise<ModelRegistry[]> {
  return get<ModelRegistry[]>(`${BASE}/models`)
}

/**
 * 获取评估指标
 */
export function getEvalMetrics(modelName?: string): Promise<EvalMetric[]> {
  return get<EvalMetric[]>(`${BASE}/eval-metrics`, modelName ? { modelName } : undefined)
}

/**
 * 提交微调任务
 */
export function submitFinetune(params: FinetuneParams): Promise<FinetuneResult> {
  return post<FinetuneResult>(`${BASE}/finetune`, params)
}

/**
 * 发起人工评估
 */
export function triggerHumanEval(modelName: string): Promise<void> {
  return post<void>(`${BASE}/human-eval`, { modelName })
}