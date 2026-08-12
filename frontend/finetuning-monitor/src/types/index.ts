// 闭环编排服务类型定义

// 闭环任务状态
export type LoopStatus =
  | 'pending'
  | 'finetuning'
  | 'evaluating'
  | 'deploying'
  | 'completed'
  | 'failed'
  | 'cancelled'

// 微调配置
export interface LoRAConfig {
  rank: number
  alpha: number
  dropout: number
  targetModules: string[]
}

export interface Hyperparams {
  epochs: number
  batchSize: number
  learningRate: number
  maxSeqLength: number
  loggingSteps: number
}

export interface FinetuneConfig {
  method: string
  framework: string
  lora?: LoRAConfig
  hyperparams: Hyperparams
}

// 数据集
export interface DatasetSpec {
  name: string
  path: string
  format: string
}

// GPU 需求
export interface GPURequirement {
  count: number
  type: string
  memoryGB: number
}

// 评测配置
export interface EvalConfig {
  dataset: string
  mode: string
  metrics: string[]
  limit: number
}

// 部署配置
export interface DeployConfig {
  runtime: string
  port: number
  replicas: number
  gpuCount: number
  autoRollback: boolean
  minAccuracy: number
}

// 闭环任务提交请求
export interface LoopTaskRequest {
  taskName: string
  baseModel: string
  trainDataset: DatasetSpec
  evalDataset: string
  finetune: FinetuneConfig
  eval: EvalConfig
  deploy: DeployConfig
  gpu: GPURequirement
  outputDir: string
  tenantId: string
  description?: string
  skipDeploy: boolean
}

// 步骤结果
export interface FinetuneStepResult {
  taskId: string | null
  status: string
  adapterPath: string | null
  outputModelPath: string | null
  startedAt: string | null
  finishedAt: string | null
  error: string | null
  metrics: Record<string, any>
}

export interface EvalStepResult {
  jobId: string | null
  status: string
  reportId: string | null
  accuracy: number
  recall: number
  f1: number
  latencyP95: number
  cost: number
  hallucination: number
  startedAt: string | null
  finishedAt: string | null
  error: string | null
}

export interface DeployStepResult {
  deploymentId: string | null
  status: string
  endpoint: string | null
  modelVersion: string | null
  startedAt: string | null
  finishedAt: string | null
  error: string | null
  healthy: boolean
}

// 闭环任务响应
export interface LoopTaskResponse {
  taskId: string
  taskName: string
  status: LoopStatus
  currentStep: string
  baseModel: string
  method: string
  framework: string
  adapterVersion: string | null
  reportVersion: string | null
  createdAt: string
  updatedAt: string
  finishedAt: string | null
  errorMessage: string | null
  finetuneResult: FinetuneStepResult
  evalResult: EvalStepResult
  deployResult: DeployStepResult
}

// 任务列表响应
export interface LoopTaskListResponse {
  total: number
  data: LoopTaskResponse[]
}

// WebSocket 消息
export interface WSMessage {
  type: 'status' | 'metrics' | 'log' | 'error' | 'completed'
  taskId: string
  timestamp: string
  data: Record<string, any>
}

// 训练指标（用于 ECharts）
export interface TrainingMetrics {
  step: number
  loss: number
  learningRate: number
  gpuUtil: number[]
  gpuMemory: number[]
  epoch: number
}

// 版本记录
export interface AdapterVersion {
  version: string
  baseModel: string
  method: string
  framework: string
  tenantId: string
  adapterPath: string
  loopTaskId: string
  metrics: Record<string, any>
  createdAt: string
  isActive: boolean
}

export interface ReportVersion {
  version: string
  adapterVersion: string
  dataset: string
  tenantId: string
  loopTaskId: string
  accuracy: number
  recall: number
  f1: number
  latencyP95: number
  cost: number
  hallucination: number
  createdAt: string
}

// 部署记录
export interface DeploymentRecord {
  deploymentId: string
  modelName: string
  version: string
  runtime: string
  port: number
  replicas: number
  gpuCount: number
  tenantId: string
  status: string
  endpoint: string | null
  createdAt: string
  updatedAt: string
  finishedAt: string | null
  healthy: boolean
  error: string | null
}