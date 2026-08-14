/**
 * 任务运维中心 API（stream-batch-scheduler）
 *
 * 后端端点（Spring Boot, /api/v1/stream-batch/dags):
 * - 运行历史:   GET  /dags/{dagId}/runs?status=&page=&size=
 * - 失败重跑:   POST /dags/{dagId}/runs/{runId}/rerun
 * - 补数据:     POST /dags/{dagId}/backfill  { startDate, endDate, intervalDays }
 */
import { get, post } from './client'

/** 补数据请求体 */
export interface BackfillRequest {
  startDate: string
  endDate: string
  intervalDays?: number
}

/** 运行实例（dag_run 表投影） */
export interface DagRunRecord {
  id: number
  dagId: string
  runType: 'MANUAL' | 'SCHEDULED' | 'RERUN' | 'BACKFILL'
  status: string
  bizTime?: string | null
  triggeredBy?: string | null
  sourceRunId?: number | null
  startTime?: string | null
  endTime?: string | null
  durationMs?: number | null
  errorMessage?: string | null
  createdAt?: string
}

/** 分页运行历史响应 */
export interface DagRunPage {
  content: DagRunRecord[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

/** 查询某 DAG 的运行历史（分页） */
export function listDagRuns(
  dagId: string,
  params: { status?: string; page?: number; size?: number } = {}
): Promise<DagRunPage> {
  // client.get 签名: get(url, params?, config?); params 为第二参, 直接传对象
  return get(`/stream-batch/dags/${encodeURIComponent(dagId)}/runs`, params)
}

/** 失败重跑：按 runId 复原参数重新执行 */
export function rerunDagRun(dagId: string, runId: number): Promise<unknown> {
  return post(`/stream-batch/dags/${encodeURIComponent(dagId)}/runs/${runId}/rerun`)
}

/** 补数据：按时间区间生成回填实例 */
export function backfillDag(dagId: string, req: BackfillRequest): Promise<{ created: number }> {
  return post(`/stream-batch/dags/${encodeURIComponent(dagId)}/backfill`, req)
}