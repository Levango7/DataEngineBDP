/**
 * 数据集成 API
 *
 * 对应后端 platform/integrate/ SeaTunnel 同步任务管理：
 * - 数据源连接器状态
 * - 同步任务 CRUD
 * - 任务运行/停止
 */
import { get, post, put, del } from './client'
import type { PagedResult, PageQuery } from './types'

/** 同步模式 */
export type SyncMode = 'batch' | 'stream_cdc'

/** 同步任务状态 */
export type SyncStatus = 'running' | 'success' | 'failed' | 'pending' | 'stopped'

/** 连接器状态 */
export type ConnectorStatus = 'connected' | 'disconnected' | 'pending_auth' | 'pending_config'

/** 同步任务 */
export interface SyncTask {
  /** 任务 ID */
  id: string
  /** 任务名 */
  name: string
  /** 源类型 */
  sourceType: string
  /** 目标类型 */
  targetType: string
  /** 源→目标描述 */
  sourceToTarget: string
  /** 同步模式 */
  mode: SyncMode
  /** 状态 */
  status: SyncStatus
  /** 调度表达式 */
  schedule?: string
  /** 最近运行时间 */
  lastRunAt?: string
  /** 最近运行耗时 */
  lastRunDuration?: string
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 连接器 */
export interface Connector {
  /** 连接器名 */
  name: string
  /** Logo 文本 */
  logo: string
  /** 状态 */
  status: ConnectorStatus
  /** 类型 */
  type: string
  /** 分类：source（源）/ sink（目标），由后端连接器服务返回 */
  category?: 'source' | 'sink'
  /** SeaTunnel 插件名（如 Jdbc、Kafka、Iceberg） */
  plugin?: string
}

/** 创建同步任务参数 */
export interface CreateSyncTaskParams {
  name: string
  sourceType: string
  targetType: string
  mode: SyncMode
  schedule?: string
  config?: Record<string, unknown>
}

/** 更新同步任务参数 */
export interface UpdateSyncTaskParams {
  name?: string
  schedule?: string
  config?: Record<string, unknown>
}

/** 同步任务列表查询参数 */
export interface SyncTaskListQuery extends PageQuery {
  status?: SyncStatus
  mode?: SyncMode
}

/** 资源根路径 */
const BASE = '/integrate'

/**
 * 查询同步任务列表（分页）
 */
export function listSyncTasks(params?: SyncTaskListQuery): Promise<PagedResult<SyncTask>> {
  return get<PagedResult<SyncTask>>(`${BASE}/tasks`, params as Record<string, unknown>)
}

/**
 * 获取同步任务详情
 */
export function getSyncTask(id: string): Promise<SyncTask> {
  return get<SyncTask>(`${BASE}/tasks/${id}`)
}

/**
 * 创建同步任务
 */
export function createSyncTask(data: CreateSyncTaskParams): Promise<SyncTask> {
  return post<SyncTask>(`${BASE}/tasks`, data)
}

/**
 * 更新同步任务
 */
export function updateSyncTask(id: string, data: UpdateSyncTaskParams): Promise<SyncTask> {
  return put<SyncTask>(`${BASE}/tasks/${id}`, data)
}

/**
 * 删除同步任务
 */
export function deleteSyncTask(id: string): Promise<void> {
  return del<void>(`${BASE}/tasks/${id}`)
}

/**
 * 立即运行同步任务
 */
export function runSyncTask(id: string): Promise<void> {
  return post<void>(`${BASE}/tasks/${id}/run`)
}

/**
 * 停止同步任务
 */
export function stopSyncTask(id: string): Promise<void> {
  return post<void>(`${BASE}/tasks/${id}/stop`)
}

/**
 * 列出数据源连接器
 */
export function listConnectors(): Promise<Connector[]> {
  return get<Connector[]>(`${BASE}/connectors`)
}
