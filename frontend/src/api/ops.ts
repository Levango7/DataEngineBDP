/**
 * 运维中心 API
 *
 * 对应后端 platform/ops/ 客户视角运维：
 * - 集群健康、作业监控、告警
 * - 作业日志查询
 */
import { get, post } from './client'

/** 作业类型 */
export type OpsJobType = 'stream_flink' | 'batch_spark' | 'batch_dag'

/** 作业状态 */
export type OpsJobStatus = 'running' | 'success' | 'failed' | 'pending'

/** 告警级别 */
export type AlertLevel = 'info' | 'warn' | 'critical'

/** 运维概览 */
export interface OpsOverview {
  /** 集群健康状态 */
  clusterHealth: 'healthy' | 'warning' | 'critical'
  /** 运行作业数 */
  runningJobCount: number
  /** 今日失败作业数 */
  todayFailedCount: number
  /** 平均延迟（秒） */
  avgLatencySec: number
}

/** 运维作业 */
export interface OpsJob {
  /** 作业 ID */
  id: string
  /** 作业名 */
  name: string
  /** 类型 */
  type: OpsJobType
  /** 运行时长描述 */
  duration: string
  /** 状态 */
  status: OpsJobStatus
}

/** 告警 */
export interface Alert {
  /** 告警 ID */
  id: string
  /** 告警内容 */
  content: string
  /** 级别 */
  level: AlertLevel
  /** 触发时间 */
  triggeredAt: string
  /** 是否已处理 */
  handled: boolean
}

/** 资源根路径 */
const BASE = '/ops'

/**
 * 获取运维概览
 */
export function getOverview(): Promise<OpsOverview> {
  return get<OpsOverview>(`${BASE}/overview`)
}

/**
 * 查询作业监控列表
 */
export function listJobs(): Promise<OpsJob[]> {
  return get<OpsJob[]>(`${BASE}/jobs`)
}

/**
 * 查询告警列表
 */
export function listAlerts(): Promise<Alert[]> {
  return get<Alert[]>(`${BASE}/alerts`)
}

/**
 * 处理告警
 */
export function handleAlert(id: string, action: string): Promise<void> {
  return post<void>(`${BASE}/alerts/${id}/handle`, { action })
}

/**
 * 获取作业日志
 */
export function getJobLogs(jobId: string): Promise<string> {
  return get<string>(`${BASE}/jobs/${jobId}/logs`)
}