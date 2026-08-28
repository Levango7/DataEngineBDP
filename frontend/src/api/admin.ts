/**
 * 运营后台 API（平台侧）
 *
 * 后端：platform/admin/
 * 端点前缀：/api/v1/admin
 *
 * 仅平台运维可见：租户、计量、底座运维与多环境管理。
 */
import { get } from './client'

/** 资源根路径 */
const BASE = '/admin'

/** 运营 KPI */
export interface AdminKpi {
  /** 租户总数 */
  tenantTotal: number
  /** 外部租户数 */
  tenantExternal: number
  /** 内部租户数 */
  tenantInternal: number
  /** 集群实例数 */
  clusterTotal: number
  /** 信创集群数 */
  clusterXinchuang: number
  /** 本地集群数 */
  clusterOnprem: number
  /** 云VM集群数 */
  clusterCloudVm: number
  /** 本月营收（元） */
  monthlyRevenue: number
  /** 底座告警数 */
  alertCount: number
  /** 已自动处置告警数 */
  alertAutoHandled: number
}

/** 环境状态 */
export type EnvStatus = 'healthy' | 'scaling' | 'warning' | 'critical'

/** 环境矩阵项 */
export interface EnvMatrixItem {
  id: string
  /** 环境名称 */
  name: string
  /** Namespace 数量 */
  namespaceCount: number
  /** 节点数 */
  nodeCount: number
  /** 控制面描述 */
  controlPlane: string
  /** 状态 */
  status: EnvStatus
}

// ---------- API 方法 ----------

/**
 * 获取运营 KPI
 */
export function getKpi(): Promise<AdminKpi> {
  return get<AdminKpi>(`${BASE}/kpi`)
}

/**
 * 获取环境矩阵
 */
export function getEnvMatrix(): Promise<EnvMatrixItem[]> {
  return get<EnvMatrixItem[]>(`${BASE}/env-matrix`)
}
