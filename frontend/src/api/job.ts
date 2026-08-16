/**
 * 作业管理 API
 */
import { get, post, del } from './client'
import type { Job, JobListQuery, SubmitJobParams, PagedResult, JobStatus } from './types'
// 类型定义见 @/api/types.ts（项目约定：避免循环依赖）

/** 作业资源根路径 */
const BASE = '/jobs'

/**
 * 查询作业列表（分页）
 * @param params 查询参数
 */
export function listJobs(params?: JobListQuery): Promise<PagedResult<Job>> {
  return get<PagedResult<Job>>(BASE, params as Record<string, unknown>)
}

/**
 * 获取作业详情
 * @param id 作业 ID
 */
export function getJob(id: string): Promise<Job> {
  return get<Job>(`${BASE}/${id}`)
}

/**
 * 提交作业
 * @param data 作业参数
 */
export function submitJob(data: SubmitJobParams): Promise<Job> {
  return post<Job>(BASE, data)
}

/**
 * 取消作业
 * @param id 作业 ID
 */
export function cancelJob(id: string): Promise<void> {
  return post<void>(`${BASE}/${id}/cancel`)
}

/**
 * 删除作业
 * @param id 作业 ID
 */
export function deleteJob(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/**
 * 获取作业运行日志
 * @param id 作业 ID
 */
export function getJobLogs(id: string): Promise<string> {
  return get<string>(`${BASE}/${id}/logs`)
}

/**
 * 查询作业当前状态
 * @param id 作业 ID
 */
export function getJobStatus(id: string): Promise<{ status: JobStatus; progress?: number }> {
  return get<{ status: JobStatus; progress?: number }>(`${BASE}/${id}/status`)
}
