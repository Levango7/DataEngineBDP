/**
 * LLMOps API（L4.5）
 *
 * 后端：platform/encaps-layer LLMOpsController
 * 端点前缀：/api/v1/llmops
 *
 * 提供大模型注册、微调、评估、部署一体化运营能力。
 */
import { get, post } from './client'

/** 资源根路径 */
const BASE = '/llmops'

/** 模型状态 */
export type ModelStatus = 'DRAFT' | 'REGISTERED' | 'DEPLOYED' | 'ARCHIVED' | 'FAILED' | string

/** 模型注册项（对齐后端 LLMOpsController#toModelView） */
export interface ModelRegistry {
  id: string
  name: string
  /** 算法 */
  algorithm: string
  /** 版本 */
  version: string
  status: ModelStatus
  /** 来源训练作业 ID */
  trainJobId?: string
  /** 模型路径 */
  modelPath?: string
  /** 描述 */
  description?: string
  /** 注册时间 */
  registeredAt?: string
  /** 更新时间 */
  updatedAt?: string
}

/** 模型注册请求参数 */
export interface ModelRegisterParams {
  name: string
  algorithm: string
  version?: string
  trainJobId?: string
  modelPath?: string
  description?: string
}

/** 评估指标（对齐后端 LLMOpsController#toEvalMetricView） */
export interface EvalMetric {
  id: string
  /** 模型名 */
  modelName: string
  /** 模型版本 */
  modelVersion?: string
  /** 评估类型：auto/human */
  evalType?: string
  /** 准确率 */
  accuracy?: number
  /** 幻觉率 */
  hallucinationRate?: number
  /** 对比基座提升（百分点） */
  baseLiftPt?: number
  /** 评估数据集 */
  dataset?: string
  /** 创建时间 */
  createdAt?: string
}

/** 创建评估指标参数 */
export interface EvalMetricCreateParams {
  modelName: string
  modelVersion?: string
  evalType?: string
  accuracy?: number
  hallucinationRate?: number
  baseLiftPt?: number
  dataset?: string
}

/** 微调任务参数 */
export interface FinetuneParams {
  modelName: string
  baseModel: string
  trainingData: string
  gpuConfig: string
  epochs: number
}

/** 微调任务状态 */
export type FinetuneStatus = 'SUBMITTED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | string

/** 微调任务结果（对齐后端 LLMOpsController#toFinetuneView） */
export interface FinetuneResult {
  taskId: string
  modelName: string
  baseModel: string
  trainingData?: string
  gpuConfig?: string
  epochs?: number
  status: FinetuneStatus
  /** 训练进度百分比 0-100 */
  progress?: number
  errorMessage?: string
  submittedAt?: string
  startedAt?: string
  finishedAt?: string
}

/** 推理服务状态 */
export type InferenceStatus = 'DEPLOYING' | 'RUNNING' | 'STOPPED' | 'FAILED' | 'SCALING' | string

/** 推理服务（对齐后端 LLMOpsController#toInferenceView） */
export interface InferenceService {
  id: string
  serviceName: string
  modelName: string
  modelVersion: string
  status: InferenceStatus
  replicas?: number
  desiredReplicas?: number
  qps?: number
  latencyMs?: number
  endpoint?: string
  resourceSpec?: string
  deployedAt?: string
}

/** 人工评估结果 */
export interface HumanEvalResult {
  metricId: string
  modelName: string
  status: string
}

// ---------- API 方法 ----------

/**
 * 列出模型注册表
 * GET /llmops/models
 */
export function listModels(modelName?: string): Promise<ModelRegistry[]> {
  return get<ModelRegistry[]>(`${BASE}/models`, modelName ? { modelName } : undefined)
}

/**
 * 注册新模型
 * POST /llmops/models
 */
export function registerModel(params: ModelRegisterParams): Promise<ModelRegistry> {
  return post<ModelRegistry>(`${BASE}/models`, params)
}

/**
 * 获取评估指标
 * GET /llmops/eval-metrics
 */
export function getEvalMetrics(modelName?: string): Promise<EvalMetric[]> {
  return get<EvalMetric[]>(`${BASE}/eval-metrics`, modelName ? { modelName } : undefined)
}

/**
 * 创建评估指标
 * POST /llmops/eval-metrics
 */
export function createEvalMetric(params: EvalMetricCreateParams): Promise<EvalMetric> {
  return post<EvalMetric>(`${BASE}/eval-metrics`, params)
}

/**
 * 列出微调任务
 * GET /llmops/finetune
 */
export function listFinetuneTasks(): Promise<FinetuneResult[]> {
  return get<FinetuneResult[]>(`${BASE}/finetune`)
}

/**
 * 提交微调任务
 * POST /llmops/finetune
 */
export function submitFinetune(params: FinetuneParams): Promise<FinetuneResult> {
  return post<FinetuneResult>(`${BASE}/finetune`, params)
}

/**
 * 查询微调任务状态
 * GET /llmops/finetune/{taskId}
 */
export function getFinetuneStatus(taskId: string): Promise<FinetuneResult> {
  return get<FinetuneResult>(`${BASE}/finetune/${encodeURIComponent(taskId)}`)
}

/**
 * 发起人工评估
 * POST /llmops/human-eval
 */
export function triggerHumanEval(modelName: string): Promise<HumanEvalResult> {
  return post<HumanEvalResult>(`${BASE}/human-eval`, { modelName })
}

/**
 * 列出推理服务
 * GET /llmops/inference-services
 */
export function listInferenceServices(status?: string): Promise<InferenceService[]> {
  return get<InferenceService[]>(`${BASE}/inference-services`, status ? { status } : undefined)
}
