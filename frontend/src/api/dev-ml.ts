/**
 * 机器学习 API（治理/开发层）
 *
 * 对接后端：
 * - JobController       /jobs（type=ml_train 训练作业）
 * - 专用 ML 端点        /ml/models、/ml/inference-services（需后端补充）
 * - LlmopsApi           /llmops（部分大模型能力复用）
 *
 * 所有方法通过 `@/api/client` 的 get/post/del 调用，
 * 自动享受 Bearer token 注入、ApiResponse<T> 拆包、401/403/500 统一错误提示、30s 超时。
 */
import { get, post, del } from './client'
import type { PagedResult } from './types'

/** 训练作业类型标识 */
const TRAIN_TYPE = 'ml_train'

/** 资源根路径 */
const BASE_JOBS = '/jobs'
const BASE_ML = '/ml'

/* ================================================================== */
/* 类型定义                                                            */
/* ================================================================== */

/** 训练作业状态 */
export type TrainJobStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'KILLED'
  | 'SCHEDULED'

/** 训练算法 */
export type TrainAlgorithm =
  | 'xgboost'
  | 'lightgbm'
  | 'tensorflow'
  | 'pytorch'
  | 'sklearn'
  | 'sparkml'
  | 'huggingface'

/** 训练作业 */
export interface TrainJob {
  /** 作业 ID */
  id: string
  /** 作业名 */
  name: string
  /** 算法 */
  algorithm: TrainAlgorithm | string
  /** 数据集 */
  dataset: string
  /** 状态 */
  status: TrainJobStatus | string
  /** 评估指标 */
  metrics?: Record<string, number>
  /** 超参 JSON */
  hyperparams?: string
  /** 工作空间 ID */
  workspaceId?: string
  /** 负责人 */
  owner?: string
  /** 提交时间 */
  submittedAt?: string
  /** 完成时间 */
  finishedAt?: string
  /** 运行时长（毫秒） */
  durationMs?: number
  /** 日志路径 */
  logPath?: string
}

/** 训练作业创建请求 */
export interface TrainJobRequest {
  name: string
  algorithm: TrainAlgorithm | string
  dataset: string
  hyperparams?: string
  workspaceId?: string
  owner?: string
  /** 资源规格 */
  resourceSpec?: string
  /** 训练轮次 */
  epochs?: number
}

/** 训练作业运行结果 */
export interface TrainRunResult {
  /** 作业 ID */
  jobId: string
  /** DAG ID（接入调度后） */
  dagId?: string
  /** 状态 */
  status: string
}

/** 模型状态 */
export type MlModelStatus = 'DRAFT' | 'REGISTERED' | 'DEPLOYED' | 'ARCHIVED' | 'FAILED'

/** 模型注册项 */
export interface MlModel {
  /** 模型 ID */
  id: string
  /** 模型名 */
  name: string
  /** 算法 */
  algorithm: TrainAlgorithm | string
  /** 最新版本 */
  latestVersion: string
  /** 状态 */
  status: MlModelStatus | string
  /** 评估指标 */
  metrics?: Record<string, number>
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

/** 模型版本 */
export interface ModelVersion {
  /** 模型名 */
  modelName: string
  /** 版本号 */
  version: string
  /** 状态 */
  status: string
  /** 评估指标 */
  metrics?: Record<string, number>
  /** 来源训练作业 ID */
  trainJobId?: string
  /** 模型路径 */
  modelPath?: string
  /** 注册时间 */
  registeredAt?: string
  /** 描述 */
  description?: string
}

/** 模型注册请求 */
export interface ModelRegisterRequest {
  name: string
  algorithm: TrainAlgorithm | string
  /** 来源训练作业 ID */
  trainJobId?: string
  /** 模型路径 */
  modelPath?: string
  /** 版本号 */
  version?: string
  /** 评估指标 */
  metrics?: Record<string, number>
  description?: string
}

/** 推理服务状态 */
export type InferenceStatus = 'DEPLOYING' | 'RUNNING' | 'STOPPED' | 'FAILED' | 'SCALING'

/** 推理服务 */
export interface InferenceService {
  /** 服务 ID */
  id: string
  /** 服务名 */
  serviceName: string
  /** 模型名 */
  modelName: string
  /** 模型版本 */
  modelVersion: string
  /** 状态 */
  status: InferenceStatus | string
  /** 副本数 */
  replicas?: number
  /** 期望副本数 */
  desiredReplicas?: number
  /** QPS */
  qps?: number
  /** 平均延迟（毫秒） */
  latencyMs?: number
  /** 端点 URL */
  endpoint?: string
  /** 部署时间 */
  deployedAt?: string
}

/** 推理服务部署请求 */
export interface InferenceDeployRequest {
  serviceName?: string
  modelName: string
  version: string
  replicas?: number
  /** 资源规格 */
  resourceSpec?: string
}

/** 推理服务扩缩容请求 */
export interface InferenceScaleRequest {
  replicas: number
}

/* ================================================================== */
/* API 方法（训练实验，JobController）                                  */
/* ================================================================== */

/**
 * 列出训练作业（type=ml_train）
 * GET /jobs?type=ml_train
 */
export function listTrainJobs(
  params: { workspaceId?: string; status?: string; page?: number; size?: number } = {}
): Promise<PagedResult<TrainJob>> {
  return get<PagedResult<TrainJob>>(BASE_JOBS, { type: TRAIN_TYPE, ...params })
}

/**
 * 创建训练作业（type=ml_train）
 * POST /jobs
 */
export function createTrainJob(req: TrainJobRequest): Promise<TrainJob> {
  return post<TrainJob>(BASE_JOBS, { ...req, type: TRAIN_TYPE })
}

/**
 * 运行训练作业
 * POST /jobs/{id}/run
 */
export function runTrainJob(id: string): Promise<TrainRunResult> {
  return post<TrainRunResult>(`${BASE_JOBS}/${id}/run`)
}

/**
 * 停止训练作业
 * POST /jobs/{id}/cancel
 */
export function stopTrainJob(id: string): Promise<void> {
  return post<void>(`${BASE_JOBS}/${id}/cancel`)
}

/**
 * 获取训练作业日志
 * GET /jobs/{id}/logs
 */
export function getTrainJobLogs(id: string): Promise<string> {
  return get<string>(`${BASE_JOBS}/${id}/logs`)
}

/* ================================================================== */
/* API 方法（模型仓库，专用 ML 端点）                                   */
/* ================================================================== */

// TODO(backend): 补充 /ml/models* 与 /ml/inference-services* 端点

/**
 * 列出模型仓库
 * GET /ml/models
 */
export function listModels(
  params: { keyword?: string; algorithm?: string } = {}
): Promise<MlModel[]> {
  return get<MlModel[]>(`${BASE_ML}/models`, params)
}

/**
 * 注册模型
 * POST /ml/models
 */
export function registerModel(req: ModelRegisterRequest): Promise<MlModel> {
  return post<MlModel>(`${BASE_ML}/models`, req)
}

/**
 * 列出模型版本
 * GET /ml/models/{name}/versions
 */
export function listModelVersions(name: string): Promise<ModelVersion[]> {
  return get<ModelVersion[]>(`${BASE_ML}/models/${encodeURIComponent(name)}/versions`)
}

/**
 * 删除模型
 * DELETE /ml/models/{id}
 */
export function deleteModel(id: string): Promise<void> {
  return del<void>(`${BASE_ML}/models/${id}`)
}

/* ================================================================== */
/* API 方法（推理服务，专用 ML 端点）                                   */
/* ================================================================== */

/**
 * 列出推理服务
 * GET /ml/inference-services
 */
export function listInferenceServices(
  params: { status?: string } = {}
): Promise<InferenceService[]> {
  return get<InferenceService[]>(`${BASE_ML}/inference-services`, params)
}

/**
 * 部署推理服务
 * POST /ml/inference-services
 */
export function deployInference(req: InferenceDeployRequest): Promise<InferenceService> {
  return post<InferenceService>(`${BASE_ML}/inference-services`, req)
}

/**
 * 停止推理服务
 * DELETE /ml/inference-services/{id}
 */
export function stopInference(id: string): Promise<void> {
  return del<void>(`${BASE_ML}/inference-services/${id}`)
}

/**
 * 扩缩容推理服务
 * POST /ml/inference-services/{id}/scale
 */
export function scaleInference(id: string, req: InferenceScaleRequest): Promise<InferenceService> {
  return post<InferenceService>(`${BASE_ML}/inference-services/${id}/scale`, req)
}