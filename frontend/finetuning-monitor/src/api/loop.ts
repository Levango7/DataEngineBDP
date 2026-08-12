// 闭环编排 API
import { http } from './client'
import type {
  LoopTaskRequest,
  LoopTaskResponse,
  LoopTaskListResponse,
  LoopStatus,
  AdapterVersion,
  ReportVersion,
  DeploymentRecord
} from '@/types'

// ============================================================
// 闭环任务管理
// ============================================================

// 提交闭环任务
export async function submitLoopTask(
  request: LoopTaskRequest
): Promise<LoopTaskResponse> {
  const resp = await http.post('/tasks', request)
  return resp.data
}

// 查询任务列表
export async function listLoopTasks(params?: {
  status?: LoopStatus
  tenantId?: string
  limit?: number
  offset?: number
}): Promise<LoopTaskListResponse> {
  const resp = await http.get('/tasks', { params })
  return resp.data
}

// 查询任务详情
export async function getLoopTask(taskId: string): Promise<LoopTaskResponse> {
  const resp = await http.get(`/tasks/${taskId}`)
  return resp.data
}

// 取消任务
export async function cancelLoopTask(taskId: string): Promise<LoopTaskResponse> {
  const resp = await http.delete(`/tasks/${taskId}`)
  return resp.data
}

// 查询任务日志
export async function getLoopTaskLogs(taskId: string): Promise<{ taskId: string; logs: string[] }> {
  const resp = await http.get(`/tasks/${taskId}/logs`)
  return resp.data
}

// 服务统计
export async function getLoopStats(): Promise<Record<string, any>> {
  const resp = await http.get('/stats')
  return resp.data
}

// ============================================================
// Adapter 版本管理
// ============================================================

export async function listAdapterVersions(params: {
  baseModel: string
  method?: string
  framework?: string
  tenantId?: string
}): Promise<{ versions: AdapterVersion[] }> {
  const resp = await http.get('/adapters/versions', { params })
  return resp.data
}

export async function compareAdapterVersions(params: {
  baseModel: string
  versionA: string
  versionB: string
  tenantId?: string
}): Promise<Record<string, any>> {
  const resp = await http.get('/adapters/compare', { params })
  return resp.data
}

export async function rollbackAdapter(params: {
  baseModel: string
  version: string
  method?: string
  framework?: string
  tenantId?: string
}): Promise<Record<string, any>> {
  const resp = await http.post('/adapters/rollback', null, { params })
  return resp.data
}

// ============================================================
// 评测报告版本管理
// ============================================================

export async function listReportVersions(params?: {
  adapterVersion?: string
  dataset?: string
  tenantId?: string
}): Promise<{ versions: ReportVersion[] }> {
  const resp = await http.get('/reports/versions', { params })
  return resp.data
}

export async function compareReportVersions(params: {
  versionA: string
  versionB: string
  tenantId?: string
}): Promise<Record<string, any>> {
  const resp = await http.get('/reports/compare', { params })
  return resp.data
}

// ============================================================
// WebSocket 实时进度
// ============================================================

export function createTaskWebSocket(
  taskId: string,
  onMessage: (msg: any) => void,
  onError?: (err: Event) => void,
  onClose?: () => void
): WebSocket {
  // 构建 WebSocket URL
  const baseUrl = import.meta.env.VITE_WS_BASE || 'ws://localhost:18088'
  const url = `${baseUrl}/api/v1/loop/tasks/${taskId}/ws`

  const ws = new WebSocket(url)

  ws.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data)
      onMessage(msg)
    } catch (e) {
      console.error('解析 WebSocket 消息失败:', e)
    }
  }

  ws.onerror = (err) => {
    console.error('WebSocket 错误:', err)
    onError?.(err)
  }

  ws.onclose = () => {
    console.log('WebSocket 已关闭')
    onClose?.()
  }

  return ws
}