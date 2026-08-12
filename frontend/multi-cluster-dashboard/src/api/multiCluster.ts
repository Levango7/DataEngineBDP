import client from './client'

// ---------------------------------------------------------------------------
// OverridePolicy 类型
// ---------------------------------------------------------------------------
export interface ResourceSelector {
  apiVersion: string
  kind: string
  name?: string
  namespace?: string
  matchLabels?: Record<string, string>
}

export interface TargetCluster {
  clusterNames: string[]
}

export interface PlaintextOverrider {
  path: string
  operator?: string
  value?: any
}

export interface ImageOverrider {
  component?: string
  operator: string
  value: string
}

export interface EnvVar {
  name: string
  value?: string
}

export interface EnvOverrider {
  containerName: string
  operator: string
  value: EnvVar[]
}

export interface Overriders {
  plaintext?: PlaintextOverrider[]
  imageOverrider?: ImageOverrider[]
  commandOverrider?: any[]
  argsOverrider?: any[]
  envOverrider?: EnvOverrider[]
}

export interface OverrideRule {
  targetCluster?: TargetCluster
  overriders: Overriders
}

export interface OverridePolicySpec {
  resourceSelectors?: ResourceSelector[]
  overrideRules: OverrideRule[]
  targetClusters?: TargetCluster
}

export interface OverridePolicy {
  id: number
  name: string
  namespace: string
  tenantId: string
  spec: string
  status: string
  createdAt: string
  updatedAt: string
}

// ---------------------------------------------------------------------------
// ClusterHealth 类型
// ---------------------------------------------------------------------------
export interface ClusterHealth {
  clusterName: string
  status: 'healthy' | 'degraded' | 'down'
  ready: boolean
  syncable: boolean
  cpuLoad: number
  memoryLoad: number
  podCount: number
  nodeCount: number
  availableReplicas: number
  maxReplicas: number
  checkSource: string
  detail: string
  checkedAt: string
}

// ---------------------------------------------------------------------------
// FailoverEvent 类型
// ---------------------------------------------------------------------------
export interface FailoverEvent {
  eventId: string
  tenantId: string
  sourceCluster: string
  targetCluster: string
  triggerReason: string
  policyName: string
  status: 'pending' | 'running' | 'succeeded' | 'failed' | 'rolled_back'
  durationMs: number
  affectedWorkloads: string
  startedAt: string
  finishedAt: string
}

// ---------------------------------------------------------------------------
// ReplicaWeightPlan 类型
// ---------------------------------------------------------------------------
export interface ReplicaWeightPlan {
  policyName: string
  workload: string
  totalReplicas: number
  allocation: string
  weights: string
  reason: string
}

// ---------------------------------------------------------------------------
// FailoverPolicy 类型
// ---------------------------------------------------------------------------
export interface FailoverPolicy {
  name: string
  namespace: string
  primaryCluster: string
  backupClusters: string
  detectionWindowSeconds: number
  migrationTimeoutSeconds: number
  healthCheckIntervalSeconds: number
  enabled: boolean
}

// ---------------------------------------------------------------------------
// OverridePolicy API
// ---------------------------------------------------------------------------
export const createOverridePolicy = (data: { name: string; namespace: string; spec: OverridePolicySpec }) =>
  client.post<OverridePolicy>('/override-policies', data)

export const listOverridePolicies = (params?: Record<string, any>) =>
  client.get<{ items: OverridePolicy[]; total: number }>('/override-policies', { params })

export const getOverridePolicy = (name: string, namespace = 'default') =>
  client.get<OverridePolicy>(`/override-policies/${name}`, { params: { namespace } })

export const updateOverridePolicy = (name: string, spec: OverridePolicySpec, namespace = 'default') =>
  client.put<OverridePolicy>(`/override-policies/${name}`, { spec }, { params: { namespace } })

export const deleteOverridePolicy = (name: string, namespace = 'default') =>
  client.delete(`/override-policies/${name}`, { params: { namespace } })

// ---------------------------------------------------------------------------
// ClusterHealth API
// ---------------------------------------------------------------------------
export const listClusterHealth = () =>
  client.get<{ items: ClusterHealth[]; total: number }>('/clusters/health')

export const getClusterHealthHistory = (name: string, limit = 100) =>
  client.get<{ items: ClusterHealth[]; total: number; cluster: string }>(`/clusters/${name}/health`, {
    params: { limit },
  })

// ---------------------------------------------------------------------------
// FailoverEvent API
// ---------------------------------------------------------------------------
export const listFailoverEvents = (params?: Record<string, any>) =>
  client.get<{ items: FailoverEvent[]; total: number }>('/failover-events', { params })

export const getFailoverEvent = (eventId: string) =>
  client.get<FailoverEvent>(`/failover-events/${eventId}`)

export const triggerFailover = (data: {
  sourceCluster: string
  targetCluster: string
  policyName?: string
  reason?: string
  workloads?: string[]
}) => client.post<FailoverEvent>('/failover-events', data)

// ---------------------------------------------------------------------------
// ReplicaWeightPlan API
// ---------------------------------------------------------------------------
export const listReplicaPlans = (params?: Record<string, any>) =>
  client.get<{ items: ReplicaWeightPlan[]; total: number }>('/replica-plans', { params })

export const getReplicaPlan = (policyName: string) =>
  client.get<ReplicaWeightPlan>(`/replica-plans/${policyName}`)

export const createReplicaPlan = (data: {
  policyName: string
  workload: string
  totalReplicas: number
  weights: Record<string, number>
  reason?: string
}) => client.post<ReplicaWeightPlan>('/replica-plans', data)

export const updateReplicaPlan = (
  policyName: string,
  data: {
    totalReplicas?: number
    weights?: Record<string, number>
    reason?: string
  },
) => client.put<ReplicaWeightPlan>(`/replica-plans/${policyName}`, data)

// ---------------------------------------------------------------------------
// FailoverPolicy API
// ---------------------------------------------------------------------------
export const listFailoverPolicies = (params?: Record<string, any>) =>
  client.get<{ items: FailoverPolicy[]; total: number }>('/failover-policies', { params })

export const createFailoverPolicy = (data: {
  name: string
  namespace: string
  primaryCluster: string
  backupClusters: string[]
  detectionWindowSeconds?: number
  migrationTimeoutSeconds?: number
  healthCheckIntervalSeconds?: number
  enabled?: boolean
}) => client.post<FailoverPolicy>('/failover-policies', data)

export const getFailoverPolicy = (name: string, namespace = 'default') =>
  client.get<FailoverPolicy>(`/failover-policies/${name}`, { params: { namespace } })

export const updateFailoverPolicy = (
  name: string,
  data: Partial<{
    primaryCluster: string
    backupClusters: string[]
    detectionWindowSeconds: number
    migrationTimeoutSeconds: number
    healthCheckIntervalSeconds: number
    enabled: boolean
  }>,
  namespace = 'default',
) => client.put<FailoverPolicy>(`/failover-policies/${name}`, data, { params: { namespace } })

export const deleteFailoverPolicy = (name: string, namespace = 'default') =>
  client.delete(`/failover-policies/${name}`, { params: { namespace } })