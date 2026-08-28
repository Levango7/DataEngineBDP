/**
 * 调度编排 API（治理/开发层）
 *
 * 对接后端：
 * - JobController                  /jobs（DAG 作业 CRUD + 运行）
 * - StreamBatchSchedulerController /stream-batch/dags（提交 DAG、查询结果、运行历史、失败重跑、补数据）
 *
 * 复用已有模块：`./streamBatch`（listDagRuns / rerunDagRun / backfillDag）
 *
 * 所有方法通过 `@/api/client` 的 get/post/put/del 调用，
 * 自动享受 Bearer token 注入、ApiResponse<T> 拆包、401/403/500 统一错误提示、30s 超时。
 */
import { get, post, put, del } from './client'
import type { PagedResult } from './types'
import * as streamBatchApi from './streamBatch'

/** 复用 streamBatch 模块 */
export { streamBatchApi }

/** 资源根路径 */
const BASE_JOBS = '/jobs'
const BASE_DAGS = '/stream-batch/dags'

/* ================================================================== */
/* 类型定义                                                            */
/* ================================================================== */

/** DAG 作业状态 */
export type DagStatus =
  'DRAFT' | 'PENDING' | 'SCHEDULED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'KILLED' | 'PAUSED'

/** DAG 作业定义 */
export interface DagJob {
  /** 作业 ID */
  id: string
  /** 作业名 */
  name: string
  /** 描述 */
  description?: string
  /** 工作空间 ID */
  workspaceId?: string
  /** DAG JSON 定义（节点 + 依赖关系） */
  dagJson?: string
  /** Cron 调度表达式 */
  schedule?: string
  /** 状态 */
  status: DagStatus | string
  /** 负责人 */
  owner?: string
  /** 最近运行时间 */
  lastRunAt?: string
  /** 最近运行状态 */
  lastRunStatus?: string
  /** 创建时间 */
  createdAt?: string
  /** 更新时间 */
  updatedAt?: string
}

/** DAG 创建请求 */
export interface DagCreateRequest {
  name: string
  description?: string
  workspaceId?: string
  dagJson?: string
  schedule?: string
  owner?: string
}

/** DAG 更新请求 */
export interface DagUpdateRequest {
  name?: string
  description?: string
  dagJson?: string
  schedule?: string
  owner?: string
}

/** DAG 运行结果 */
export interface DagRunResult {
  /** DAG ID */
  dagId: string
  /** 运行实例 ID */
  runId?: string
  /** 状态 */
  status: string
}

/** DAG 执行结果（提交后查询） */
export interface DagExecutionResult {
  /** DAG ID */
  dagId: string
  /** 状态 */
  status: string
  /** 开始时间 */
  startTime?: string
  /** 结束时间 */
  endTime?: string
  /** 耗时（毫秒） */
  durationMs?: number
  /** 任务实例结果 */
  taskResults?: Array<{
    taskId: string
    status: string
    durationMs?: number
    errorMessage?: string
  }>
}

/** 流批 DAG 提交请求 */
export interface StreamBatchDag {
  dagId: string
  /** 业务时间 */
  bizTime?: string
  /** DAG 定义 JSON */
  definition?: string
  /** 触发人 */
  triggeredBy?: string
}

/** DAG 列表查询参数 */
export interface DagListQuery {
  workspaceId?: string
  status?: string
  keyword?: string
  page?: number
  size?: number
}

/* ================================================================== */
/* API 方法（JobController）                                            */
/* ================================================================== */

/**
 * 列出 DAG 作业（分页）
 * GET /jobs
 */
export function listDags(query: DagListQuery = {}): Promise<PagedResult<DagJob>> {
  return get<PagedResult<DagJob>>(BASE_JOBS, query as Record<string, unknown>)
}

/**
 * 获取 DAG 作业详情
 * GET /jobs/{id}
 */
export function getDag(id: string): Promise<DagJob> {
  return get<DagJob>(`${BASE_JOBS}/${id}`)
}

/**
 * 创建 DAG 作业
 * POST /jobs
 */
export function createDag(req: DagCreateRequest): Promise<DagJob> {
  return post<DagJob>(BASE_JOBS, req)
}

/**
 * 更新 DAG 作业
 * PUT /jobs/{id}
 */
export function updateDag(id: string, req: DagUpdateRequest): Promise<DagJob> {
  return put<DagJob>(`${BASE_JOBS}/${id}`, req)
}

/**
 * 删除 DAG 作业
 * DELETE /jobs/{id}
 */
export function deleteDag(id: string): Promise<void> {
  return del<void>(`${BASE_JOBS}/${id}`)
}

/**
 * 运行 DAG 作业
 * POST /jobs/{id}/run
 */
export function runDag(id: string): Promise<DagRunResult> {
  return post<DagRunResult>(`${BASE_JOBS}/${id}/run`)
}

/**
 * 取消 DAG 作业
 * POST /jobs/{id}/cancel
 */
export function cancelDag(id: string): Promise<void> {
  return post<void>(`${BASE_JOBS}/${id}/cancel`)
}

/**
 * 暂停 DAG 调度
 * POST /jobs/{id}/pause
 */
export function pauseDag(id: string): Promise<void> {
  return post<void>(`${BASE_JOBS}/${id}/pause`)
}

/**
 * 恢复 DAG 调度
 * POST /jobs/{id}/resume
 */
export function resumeDag(id: string): Promise<void> {
  return post<void>(`${BASE_JOBS}/${id}/resume`)
}

/* ================================================================== */
/* API 方法（StreamBatchSchedulerController）                           */
/* ================================================================== */

/**
 * 提交流批 DAG
 * POST /stream-batch/dags
 */
export function submitDag(dag: StreamBatchDag): Promise<DagExecutionResult> {
  return post<DagExecutionResult>(BASE_DAGS, dag)
}

/**
 * 查询 DAG 执行结果
 * GET /stream-batch/dags/{dagId}
 */
export function getDagResult(dagId: string): Promise<DagExecutionResult> {
  return get<DagExecutionResult>(`${BASE_DAGS}/${encodeURIComponent(dagId)}`)
}

/**
 * 查询全部 DAG 历史执行
 * GET /stream-batch/dags
 */
export function listDagHistory(
  params: { page?: number; size?: number } = {}
): Promise<PagedResult<DagExecutionResult>> {
  return get<PagedResult<DagExecutionResult>>(BASE_DAGS, params)
}

// 复用 streamBatch.ts 的 listDagRuns / rerunDagRun / backfillDag
// 调用方式：devSchedApi.streamBatchApi.listDagRuns(dagId, params)
