/**
 * 集群管理 API
 */
import { get } from './client'
import type { ClusterOverview, Node, Pod } from './types'

/** 集群资源根路径 */
const BASE = '/cluster'

/**
 * 获取集群概览信息（Dashboard 顶部指标卡使用）
 */
export function getClusterOverview(): Promise<ClusterOverview> {
  return get<ClusterOverview>(`${BASE}/overview`)
}

/**
 * 查询集群节点列表
 */
export function listNodes(): Promise<Node[]> {
  return get<Node[]>(`${BASE}/nodes`)
}

/**
 * 查询指定命名空间下的 Pod 列表
 * @param namespace 命名空间，不传则查询全部
 */
export function listPods(namespace?: string): Promise<Pod[]> {
  const params: Record<string, unknown> = {}
  if (namespace) params.namespace = namespace
  return get<Pod[]>(`${BASE}/pods`, params)
}

/* ------------------------------ 大数据组件状态 ------------------------------ */

/** 组件健康状态 */
export type ComponentHealth = 'healthy' | 'warning' | 'error'

/** 大数据组件运行状态 */
export interface ComponentStatus {
  /** 组件名 */
  name: string
  /** 健康状态 */
  status: ComponentHealth
  /** 元信息（版本、节点数等） */
  meta: string
}

/**
 * 查询大数据组件状态列表（Spark/Flink/Trino/Doris/Kafka 等）
 */
export function listComponentStatuses(): Promise<ComponentStatus[]> {
  return get<ComponentStatus[]>(`${BASE}/components`)
}
