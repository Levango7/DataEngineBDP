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
