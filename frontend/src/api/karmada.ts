/**
 * Karmada 多集群联邦域客户端（P-01）。
 *
 * 后端真相源（字段名 / 动词 / 默认值逐条核对过，不看 UI 猜）：
 *   - platform/karmada/failover/failover-api/internal/handler/failover.go
 *   - platform/karmada/failover/failover-api/internal/handler/override_policy.go
 *   - platform/karmada/failover/failover-api/internal/model/failover.go
 *   - platform/karmada/failover/failover-api/internal/model/override_policy.go
 * 挂载点见 failover-api/main.go:96-109（`v1 := r.Group("/api/v1")` →
 * `opGroup := v1.Group("/override-policies")`，其余四组由
 * `failoverH.RegisterRoutes(v1.Group(""))` 间接注册）。
 *
 * 服务：failover-api（design/deploy/charts/failover-api/values.yaml service.port 8094）。
 * 这些端点返回**裸 JSON**（gin.H{"items":…}），没有 ApiResponse 包裹，
 * 故 src/api/client.ts 的拆包分支不会触发，调用方直接拿到本文件声明的类型。
 *
 * 已知不可用端点（有意不实现）：
 *   GET /api/v1/clusters/{name}/health —— 带路径参数，而粗前缀 /api/v1/clusters
 *   归 infra-orchestrator（见 docs/KNOWN-FAILURES.md #89），网关到不了 failover-api，
 *   所以单集群健康历史改由 /failover-events 过滤呈现，不在此开一个必然 404 的调用。
 */
import { del, get, post, put } from './client'

const OVERRIDE_POLICIES = '/override-policies'
const FAILOVER_EVENTS = '/failover-events'
const CLUSTERS = '/clusters'
const REPLICA_PLANS = '/replica-plans'
const FAILOVER_POLICIES = '/failover-policies'

/**
 * Go 侧每个 List 都把 limit/offset 回显在响应里（failover.go:109-114 等），
 * 旧子看板的类型只写了 items/total——这里按真实响应对齐，免得页面想用分页时类型里没有。
 */
export interface PagedResult<T> {
  items: T[]
  total: number
  limit?: number
  offset?: number
}

/** 分页入参：后端对 limit 有区间钳制（<=0 或 >100 一律回落 20），所以不要把 0 当"全部"传。 */
export interface PageQuery {
  limit?: number
  offset?: number
}

/* ------------------------------------------------------------------ */
/* OverridePolicy                                                     */
/* ------------------------------------------------------------------ */

/** model/override_policy.go::ResourceSelector（apiVersion/kind 无 omitempty，是必填） */
export interface ResourceSelector {
  apiVersion: string
  kind: string
  name?: string
  namespace?: string
  matchLabels?: Record<string, string>
}

/** model/override_policy.go::TargetCluster */
export interface TargetCluster {
  clusterNames: string[]
}

/** model/override_policy.go::PlaintextOverrider */
export interface PlaintextOverrider {
  path: string
  /** add / replace / remove */
  operator?: string
  value?: unknown
}

/** model/override_policy.go::ImageOverrider —— component 取 Registry/Repository/Tag，operator 取 replace/prepend/append */
export interface ImageOverrider {
  component?: string
  operator: string
  value: string
}

/** model/override_policy.go::CommandOverrider（旧子看板写成 any[]，这里是 Go 的真实形状） */
export interface CommandOverrider {
  containerName: string
  operator: string
  value: string[]
}

/** model/override_policy.go::ArgsOverrider */
export interface ArgsOverrider {
  containerName: string
  operator: string
  value: string[]
}

/** model/override_policy.go::EnvVar（Go 还有 valueFrom，configMapKeyRef/secretKeyRef 二选一） */
export interface EnvVar {
  name: string
  value?: string
  valueFrom?: {
    configMapKeyRef?: { name: string; key: string }
    secretKeyRef?: { name: string; key: string }
  }
}

/** model/override_policy.go::EnvOverrider */
export interface EnvOverrider {
  containerName: string
  operator: string
  value: EnvVar[]
}

/** model/override_policy.go::Overriders */
export interface Overriders {
  plaintext?: PlaintextOverrider[]
  imageOverrider?: ImageOverrider[]
  commandOverrider?: CommandOverrider[]
  argsOverrider?: ArgsOverrider[]
  envOverrider?: EnvOverrider[]
}

/** model/override_policy.go::OverrideRule —— overriders 无 omitempty，必填 */
export interface OverrideRule {
  targetCluster?: TargetCluster
  overriders: Overriders
}

/** model/override_policy.go::OverridePolicySpec */
export interface OverridePolicySpec {
  resourceSelectors?: ResourceSelector[]
  overrideRules: OverrideRule[]
  targetClusters?: TargetCluster
}

/**
 * model/override_policy.go::OverridePolicy。
 * 注意 spec/status 在 Go 里是 **JSON 字符串**（text 列，控制器回写 status），
 * 不是对象——页面要展示必须先 JSON.parse，不能当结构化字段索引。
 */
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

/** override_policy.go:67 校验 overrideRules 非空，空数组直接 400 —— 页面须本地先拦 */
export function createOverridePolicy(data: {
  name: string
  namespace: string
  spec: OverridePolicySpec
}): Promise<OverridePolicy> {
  return post<OverridePolicy>(OVERRIDE_POLICIES, data)
}

export function listOverridePolicies(
  params?: PageQuery & { namespace?: string }
): Promise<PagedResult<OverridePolicy>> {
  return get<PagedResult<OverridePolicy>>(OVERRIDE_POLICIES, params as Record<string, unknown>)
}

export function getOverridePolicy(name: string, namespace = 'default'): Promise<OverridePolicy> {
  return get<OverridePolicy>(
    `${OVERRIDE_POLICIES}/${encodeURIComponent(name)}`,
    { namespace }
  )
}

export function updateOverridePolicy(
  name: string,
  spec: OverridePolicySpec,
  namespace = 'default'
): Promise<OverridePolicy> {
  return put<OverridePolicy>(
    `${OVERRIDE_POLICIES}/${encodeURIComponent(name)}`,
    { spec },
    { params: { namespace } }
  )
}

/** handler 成功返回 204 无响应体（override_policy.go:236） */
export function deleteOverridePolicy(name: string, namespace = 'default'): Promise<void> {
  return del<void>(`${OVERRIDE_POLICIES}/${encodeURIComponent(name)}`, { namespace })
}

/* ------------------------------------------------------------------ */
/* ClusterHealth                                                      */
/* ------------------------------------------------------------------ */

/**
 * model/failover.go::ClusterHealthRecord。
 * status 闭包见同文件 ClusterStatus*常量：healthy / degraded / down。
 */
export interface ClusterHealth {
  id: number
  clusterName: string
  tenantId: string
  status: string
  ready: boolean
  syncable: boolean
  /** 0-100，Go 是 float64 */
  cpuLoad: number
  memoryLoad: number
  podCount: number
  nodeCount: number
  availableReplicas: number
  maxReplicas: number
  /** karmada_api / prometheus */
  checkSource: string
  /** 详细信息 JSON 字符串 */
  detail: string
  checkedAt: string
  createdAt: string
}

/**
 * 所有集群的最新一条健康记录。ListClusterHealth 不接分页参数
 * （failover.go:200-217 只回 items/total），别给这里加 limit。
 */
export function listClusterHealth(): Promise<PagedResult<ClusterHealth>> {
  return get<PagedResult<ClusterHealth>>(`${CLUSTERS}/health`)
}

/* ------------------------------------------------------------------ */
/* FailoverEvent                                                      */
/* ------------------------------------------------------------------ */

/**
 * model/failover.go::FailoverEvent。
 * status 闭包：pending / running / succeeded / failed / rolled_back
 * triggerReason 闭包：health_check / prometheus_alert / manual / rebalance
 */
export interface FailoverEvent {
  id: number
  eventId: string
  tenantId: string
  sourceCluster: string
  targetCluster: string
  triggerReason: string
  policyName: string
  status: string
  durationMs: number
  /** 受影响工作负载：响应侧是 JSON 字符串，请求侧是数组（见 triggerFailover） */
  affectedWorkloads: string
  startedAt: string
  finishedAt: string
  createdAt: string
}

export const FAILOVER_EVENT_STATUSES = [
  'pending',
  'running',
  'succeeded',
  'failed',
  'rolled_back'
] as const

export const FAILOVER_TRIGGER_REASONS = [
  'health_check',
  'prometheus_alert',
  'manual',
  'rebalance'
] as const

export function listFailoverEvents(params?: PageQuery): Promise<PagedResult<FailoverEvent>> {
  return get<PagedResult<FailoverEvent>>(FAILOVER_EVENTS, params as Record<string, unknown>)
}

export function getFailoverEvent(eventId: string): Promise<FailoverEvent> {
  return get<FailoverEvent>(`${FAILOVER_EVENTS}/${encodeURIComponent(eventId)}`)
}

/**
 * 手动触发迁移（failover.go:141-147 triggerFailoverRequest：sourceCluster/targetCluster 必填）。
 * reason 留空时后端填 model.ReasonManual；真正迁移由 failover-engine 异步做，
 * 本端点只落一条 pending 事件——所以 UI 不能说"迁移完成"，只能说"已触发"。
 */
export function triggerFailover(data: {
  sourceCluster: string
  targetCluster: string
  policyName?: string
  reason?: string
  workloads?: string[]
}): Promise<FailoverEvent> {
  return post<FailoverEvent>(FAILOVER_EVENTS, data)
}

/* ------------------------------------------------------------------ */
/* ReplicaWeightPlan                                                  */
/* ------------------------------------------------------------------ */

/**
 * model/failover.go::ReplicaWeightPlan。
 * allocation 与 weights 都是 **JSON 字符串**（由 handler 的 allocateByWeight 最大余数法算出）。
 */
export interface ReplicaWeightPlan {
  id: number
  tenantId: string
  policyName: string
  workload: string
  totalReplicas: number
  allocation: string
  weights: string
  /** initial / failover / rebalance / manual */
  reason: string
  createdAt: string
  updatedAt: string
}

export function listReplicaPlans(params?: PageQuery): Promise<PagedResult<ReplicaWeightPlan>> {
  return get<PagedResult<ReplicaWeightPlan>>(REPLICA_PLANS, params as Record<string, unknown>)
}

export function getReplicaPlan(policyName: string): Promise<ReplicaWeightPlan> {
  return get<ReplicaWeightPlan>(`${REPLICA_PLANS}/${encodeURIComponent(policyName)}`)
}

/**
 * POST /replica-plans（failover.go:304-310 createReplicaPlanRequest）。
 * weights 的值是 int（Go map[string]int）——小数会被 ShouldBindJSON 直接 400。
 * 后端还会拒绝：totalReplicas<=0、weights 为空、权重和为 0。
 */
export function createReplicaPlan(data: {
  policyName: string
  workload: string
  totalReplicas: number
  weights: Record<string, number>
  reason?: string
}): Promise<ReplicaWeightPlan> {
  return post<ReplicaWeightPlan>(REPLICA_PLANS, data)
}

/**
 * PUT /replica-plans/{policyName}（failover.go:383-387 updateReplicaPlanRequest）。
 * totalReplicas 是指针：不传即"不改"；传 0 会被后端判 400，所以这里必须"省略而非置零"。
 * reason 留空时后端一律写成 "manual"。
 */
export function updateReplicaPlan(
  policyName: string,
  data: {
    totalReplicas?: number
    weights?: Record<string, number>
    reason?: string
  }
): Promise<ReplicaWeightPlan> {
  return put<ReplicaWeightPlan>(`${REPLICA_PLANS}/${encodeURIComponent(policyName)}`, data)
}

/* ------------------------------------------------------------------ */
/* FailoverPolicy                                                     */
/* ------------------------------------------------------------------ */

/**
 * model/failover.go::FailoverPolicy。
 * backupClusters 在响应里是 **JSON 字符串**（text 列），创建/更新请求里是字符串数组。
 */
export interface FailoverPolicy {
  id: number
  tenantId: string
  name: string
  namespace: string
  primaryCluster: string
  backupClusters: string
  detectionWindowSeconds: number
  migrationTimeoutSeconds: number
  healthCheckIntervalSeconds: number
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export function listFailoverPolicies(
  params?: PageQuery & { namespace?: string }
): Promise<PagedResult<FailoverPolicy>> {
  return get<PagedResult<FailoverPolicy>>(FAILOVER_POLICIES, params as Record<string, unknown>)
}

export function getFailoverPolicy(name: string, namespace = 'default'): Promise<FailoverPolicy> {
  return get<FailoverPolicy>(
    `${FAILOVER_POLICIES}/${encodeURIComponent(name)}`,
    { namespace }
  )
}

/**
 * POST /failover-policies（failover.go:579-588 createFailoverPolicyRequest）。
 * name/namespace/primaryCluster/backupClusters 四个 binding:"required"；
 * backupClusters 空数组另有 400；三个时间字段 <=0 时后端补 30/60/10 默认值。
 */
export function createFailoverPolicy(data: {
  name: string
  namespace: string
  primaryCluster: string
  backupClusters: string[]
  detectionWindowSeconds?: number
  migrationTimeoutSeconds?: number
  healthCheckIntervalSeconds?: number
  enabled?: boolean
}): Promise<FailoverPolicy> {
  return post<FailoverPolicy>(FAILOVER_POLICIES, data)
}

/** PUT 的各字段在 Go 里是指针/可空：只发要改的，别把整行原样回填（enabled=false 与"不改"是两回事） */
export function updateFailoverPolicy(
  name: string,
  data: Partial<{
    primaryCluster: string
    backupClusters: string[]
    detectionWindowSeconds: number
    migrationTimeoutSeconds: number
    healthCheckIntervalSeconds: number
    enabled: boolean
  }>,
  namespace = 'default'
): Promise<FailoverPolicy> {
  return put<FailoverPolicy>(
    `${FAILOVER_POLICIES}/${encodeURIComponent(name)}`,
    data,
    { params: { namespace } }
  )
}

/** handler 成功返回 204 无响应体（failover.go:738） */
export function deleteFailoverPolicy(name: string, namespace = 'default'): Promise<void> {
  return del<void>(`${FAILOVER_POLICIES}/${encodeURIComponent(name)}`, { namespace })
}
