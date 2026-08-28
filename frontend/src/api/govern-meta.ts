/**
 * 元数据管理 API（治理/开发层）
 *
 * 对接后端：
 * - CollectorController   /metadata/sources、/metadata/collect/*、/metadata/collectors
 * - LineageController     /lineage（血缘检索，本模块暂留扩展位）
 *
 * 所有方法通过 `@/api/client` 的 get/post/put/del 调用，
 * 自动享受 Bearer token 注入、ApiResponse<T> 拆包、401/403/500 统一错误提示、30s 超时。
 */
import { get, post, put, del } from './client'

/** 资源根路径 */
const BASE = '/metadata'

/* ================================================================== */
/* 类型定义                                                            */
/* ================================================================== */

/** 数据源状态 */
export type MetadataSourceStatus = 'ACTIVE' | 'INACTIVE' | 'ERROR'

/** 数据源类型（hive / mysql / kafka / iotdb / doris / ...） */
export type MetadataSourceType =
  'hive' | 'mysql' | 'postgres' | 'kafka' | 'iotdb' | 'doris' | 'clickhouse' | 'hbase' | 'es'

/** 元数据采集数据源 */
export interface MetadataSource {
  /** 数据源 ID */
  id?: number
  /** 数据源名称 */
  name: string
  /** 数据源类型 */
  type: MetadataSourceType | string
  /** JDBC / 连接 URL */
  connectionUrl: string
  /** 用户名 */
  username?: string
  /** 密码（写入时使用，列表返回时脱敏） */
  password?: string
  /** 定时采集 Cron 表达式 */
  cron?: string
  /** 状态 */
  status?: MetadataSourceStatus
  /** 最近一次采集时间 */
  lastCollectedAt?: string
  /** 最近一次采集对象数 */
  lastCollectedCount?: number
  /** 创建时间 */
  createdAt?: string
  /** 更新时间 */
  updatedAt?: string
  /** 备注 */
  comment?: string
}

/** 采集结果 */
export interface CollectionResult {
  /** 触发的采集任务 ID */
  taskId?: string
  /** 本次采集对象数 */
  collectedCount: number
  /** 耗时（毫秒） */
  durationMs?: number
  /** 状态 */
  status?: string
}

/** 采集历史记录 */
export interface CollectionHistory {
  /** 任务 ID */
  taskId: string
  /** 数据源 ID */
  sourceId: number
  /** 触发方式：MANUAL / SCHEDULED */
  triggerType: 'MANUAL' | 'SCHEDULED'
  /** 状态：RUNNING / SUCCESS / FAILED */
  status: 'RUNNING' | 'SUCCESS' | 'FAILED'
  /** 触发时间 */
  triggeredAt: string
  /** 完成时间 */
  finishedAt?: string
  /** 耗时（毫秒） */
  durationMs?: number
  /** 采集对象数 */
  collectedCount?: number
  /** 错误信息 */
  errorMessage?: string
}

/** 连接测试结果 */
export interface ConnectionTestResult {
  /** 是否连通 */
  connected: boolean
  /** 提示信息 */
  message: string
  /** 延迟（毫秒） */
  latencyMs?: number
}

/** 调度注册结果 */
export interface ScheduleResult {
  /** 是否已注册 */
  scheduled: boolean
  /** 调度任务 ID */
  taskId?: string
  /** 下次触发时间 */
  nextFireAt?: string
}

/** 调度取消结果 */
export interface UnscheduleResult {
  /** 是否已取消 */
  unscheduled: boolean
}

/* ================================================================== */
/* API 方法                                                            */
/* ================================================================== */

/**
 * 列出全部元数据采集数据源
 * GET /metadata/sources
 */
export function listSources(): Promise<MetadataSource[]> {
  return get<MetadataSource[]>(`${BASE}/sources`)
}

/**
 * 获取单个数据源详情
 * GET /metadata/sources/{id}
 */
export function getSource(id: number): Promise<MetadataSource> {
  return get<MetadataSource>(`${BASE}/sources/${id}`)
}

/**
 * 添加数据源
 * POST /metadata/sources
 */
export function addSource(source: MetadataSource): Promise<MetadataSource> {
  return post<MetadataSource>(`${BASE}/sources`, source)
}

/**
 * 更新数据源
 * PUT /metadata/sources/{id}
 */
export function updateSource(id: number, source: MetadataSource): Promise<MetadataSource> {
  return put<MetadataSource>(`${BASE}/sources/${id}`, source)
}

/**
 * 删除数据源
 * DELETE /metadata/sources/{id}
 */
export function deleteSource(id: number): Promise<void> {
  return del<void>(`${BASE}/sources/${id}`)
}

/**
 * 手动触发采集
 * POST /metadata/collect/{sourceId}
 */
export function triggerCollection(sourceId: number): Promise<CollectionResult> {
  return post<CollectionResult>(`${BASE}/collect/${sourceId}`)
}

/**
 * 查询采集状态（最近一次采集历史）
 * GET /metadata/collect/status/{sourceId}
 */
export function getCollectionStatus(sourceId: number): Promise<CollectionHistory> {
  return get<CollectionHistory>(`${BASE}/collect/status/${sourceId}`)
}

/**
 * 查询采集历史列表
 * GET /metadata/collect/history/{sourceId}
 */
export function listCollectionHistory(sourceId: number): Promise<CollectionHistory[]> {
  return get<CollectionHistory[]>(`${BASE}/collect/history/${sourceId}`)
}

/**
 * 测试连接
 * POST /metadata/collect/test/{sourceId}
 */
export function testConnection(sourceId: number): Promise<ConnectionTestResult> {
  return post<ConnectionTestResult>(`${BASE}/collect/test/${sourceId}`)
}

/**
 * 注册定时采集
 * POST /metadata/collect/schedule/{sourceId}  body: { cron }
 */
export function scheduleCollection(sourceId: number, cron: string): Promise<ScheduleResult> {
  return post<ScheduleResult>(`${BASE}/collect/schedule/${sourceId}`, { cron })
}

/**
 * 取消定时采集
 * DELETE /metadata/collect/schedule/{sourceId}
 */
export function unscheduleCollection(sourceId: number): Promise<UnscheduleResult> {
  return del<UnscheduleResult>(`${BASE}/collect/schedule/${sourceId}`)
}

/**
 * 已注册 Collector 类型
 * GET /metadata/collectors
 */
export function listCollectors(): Promise<string[]> {
  return get<string[]>(`${BASE}/collectors`)
}
