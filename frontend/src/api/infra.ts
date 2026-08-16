/**
 * 基础设施层 API 模块
 *
 * 涵盖机器供应、K8s 集群、容器网络、容器存储、弹性调度 5 个子域，
 * 对接后端 ClusterController / XinchangClusterController / PrivateClusterController / CloudClusterController
 * 及其网络、存储、扩缩容、HPA 等子资源端点。
 *
 * 所有方法通过 `@/api/client` 的 get/post/put/del 调用，
 * 自动享受 Bearer token 注入、ApiResponse<T> 拆包、401/403/500 统一错误提示、30s 超时。
 */
import { get, post, put, del } from './client'

/* ================================================================== */
/* 通用类型                                                            */
/* ================================================================== */

/** 集群运行状态 */
export type ClusterStatus = 'CREATING' | 'RUNNING' | 'FAILED' | 'DESTROYED' | 'UPDATING'

/** 部署环境类型 */
export type ClusterEnv = 'xinchuang' | 'private' | 'cloud'

/** 集群供应 Provider 标识 */
export type ProviderKind = 'vsphere' | 'openstack' | 'huawei' | 'ali' | 'tencent' | 'xinchang'

/* ================================================================== */
/* 1. 机器供应（信创 / 私有 / 公有）                                    */
/* ================================================================== */

/** 工作节点规格 */
export interface WorkerSpec {
  /** 节点角色：master / worker */
  role: string
  /** 节点数量 */
  count: number
  /** CPU 核数 */
  cpu: number
  /** 内存（GB） */
  memory: number
  /** 系统盘（GB） */
  disk: number
}

/** 创建集群请求 */
export interface ClusterCreateRequest {
  /** 租户 ID（可选，由 token 推导） */
  tenantId?: string
  /** 集群名称 */
  clusterName: string
  /** K8s 版本，如 v1.28 */
  k8sVersion: string
  /** Pod CIDR，如 10.244.0.0/16 */
  podCidr: string
  /** Service CIDR，如 10.96.0.0/12 */
  serviceCidr: string
  /** 工作节点规格列表 */
  workers: WorkerSpec[]
}

/** 扩缩容请求 */
export interface ClusterScaleRequest {
  /** 目标节点数 */
  targetNodeCount: number
  /** 节点规格（扩容时新增节点的规格） */
  workerSpec?: WorkerSpec
}

/** 集群信息响应 */
export interface ClusterInfo {
  /** 集群 ID */
  clusterId: string
  /** 集群名称 */
  clusterName: string
  /** 运行状态 */
  status: ClusterStatus
  /** K8s 版本 */
  k8sVersion: string
  /** Pod CIDR */
  podCidr: string
  /** Service CIDR */
  serviceCidr: string
  /** 工作节点数 */
  workerCount: number
  /** 控制面节点数 */
  controlPlaneCount: number
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
  /** 错误信息（status=FAILED 时填充） */
  errorMessage?: string
}

/** 跨环境集群信息（在 ClusterInfo 基础上补充环境与 Provider） */
export interface CrossEnvClusterInfo extends ClusterInfo {
  /** 部署环境 */
  environment: ClusterEnv
  /** 供应 Provider */
  provider: ProviderKind
}

/** 集群创建结果（异步供应任务） */
export interface SupplyResult {
  /** 集群 ID */
  clusterId: string
  /** 异步任务 ID */
  taskId: string
  /** 是否已接受 */
  accepted: boolean
}

/** Provider 描述符 */
export interface ProviderDescriptor {
  /** Provider 标识 */
  kind: ProviderKind
  /** 显示名称 */
  name: string
  /** 是否启用 */
  enabled: boolean
  /** 支持的 K8s 版本 */
  k8sVersions: string[]
}

/** 环境信息 */
export interface EnvInfo {
  /** 环境标识 */
  env: ClusterEnv
  /** 显示名称 */
  name: string
  /** 该环境下集群数 */
  clusterCount: number
}

/** 环境默认配置 */
export interface ProfileDefaults {
  /** 默认 K8s 版本 */
  defaultK8sVersion: string
  /** 默认 Pod CIDR */
  defaultPodCidr: string
  /** 默认 Service CIDR */
  defaultServiceCidr: string
  /** 默认节点规格 */
  defaultWorkerSpec: WorkerSpec
}

/** Provider 列表响应 */
export interface ProviderListResult {
  providers: ProviderDescriptor[]
  total: number
  enabled: number
}

/** 环境列表响应 */
export interface EnvListResult {
  environments: EnvInfo[]
  total: number
}

/* ------------------------------ 信创集群 ------------------------------ */

const XINCHANG_BASE = '/clusters/xinchang'

/**
 * 列出租户全部信创集群
 */
export function getXinchangClusters(): Promise<ClusterInfo[]> {
  return get<ClusterInfo[]>(XINCHANG_BASE)
}

/**
 * 查询信创集群详情
 * @param clusterId 集群 ID
 */
export function getXinchangCluster(clusterId: string): Promise<ClusterInfo> {
  return get<ClusterInfo>(`${XINCHANG_BASE}/${clusterId}`)
}

/**
 * 创建信创集群
 * @param req 创建请求
 */
export function createXinchangCluster(req: ClusterCreateRequest): Promise<SupplyResult> {
  return post<SupplyResult>(XINCHANG_BASE, req)
}

/**
 * 销毁信创集群
 * @param clusterId 集群 ID
 */
export function destroyXinchangCluster(clusterId: string): Promise<ClusterInfo> {
  return del<ClusterInfo>(`${XINCHANG_BASE}/${clusterId}`)
}

/**
 * 信创集群扩缩容
 * @param clusterId 集群 ID
 * @param req 扩缩容请求
 */
export function scaleXinchangCluster(clusterId: string, req: ClusterScaleRequest): Promise<ClusterInfo> {
  return post<ClusterInfo>(`${XINCHANG_BASE}/${clusterId}/scale`, req)
}

/* ------------------------------ 私有云集群 ------------------------------ */

const PRIVATE_BASE = '/clusters/private'

/**
 * 列出指定 Provider 下的私有云集群
 * @param provider 供应 Provider（vsphere / openstack）
 */
export function getPrivateClusters(provider: ProviderKind): Promise<ClusterInfo[]> {
  return get<ClusterInfo[]>(`${PRIVATE_BASE}/${provider}`)
}

/**
 * 创建私有云集群
 * @param provider 供应 Provider
 * @param req 创建请求
 */
export function createPrivateCluster(provider: ProviderKind, req: ClusterCreateRequest): Promise<SupplyResult> {
  return post<SupplyResult>(`${PRIVATE_BASE}/${provider}`, req)
}

/**
 * 销毁私有云集群
 * @param provider 供应 Provider
 * @param clusterId 集群 ID
 */
export function destroyPrivateCluster(provider: ProviderKind, clusterId: string): Promise<ClusterInfo> {
  return del<ClusterInfo>(`${PRIVATE_BASE}/${provider}/${clusterId}`)
}

/* ------------------------------ 公有云集群 ------------------------------ */

const CLOUD_BASE = '/clusters/cloud'

/**
 * 列出指定 Provider 下的公有云集群
 * @param provider 供应 Provider（huawei / ali / tencent）
 */
export function getCloudClusters(provider: ProviderKind): Promise<ClusterInfo[]> {
  return get<ClusterInfo[]>(`${CLOUD_BASE}/${provider}`)
}

/**
 * 创建公有云集群
 * @param provider 供应 Provider
 * @param req 创建请求
 */
export function createCloudCluster(provider: ProviderKind, req: ClusterCreateRequest): Promise<SupplyResult> {
  return post<SupplyResult>(`${CLOUD_BASE}/${provider}`, req)
}

/**
 * 销毁公有云集群
 * @param provider 供应 Provider
 * @param clusterId 集群 ID
 */
export function destroyCloudCluster(provider: ProviderKind, clusterId: string): Promise<ClusterInfo> {
  return del<ClusterInfo>(`${CLOUD_BASE}/${provider}/${clusterId}`)
}

/* ================================================================== */
/* 2. K8s 集群（编排层统一入口）                                       */
/* ================================================================== */

const CLUSTER_BASE = '/clusters'

/** 集群节点信息 */
export interface ClusterNode {
  /** 节点名称 */
  name: string
  /** 角色 */
  roles: string[]
  /** 状态：Ready / NotReady / Unknown */
  status: string
  /** CPU 容量（核） */
  cpuCapacity: number
  /** CPU 已用（核） */
  cpuUsed: number
  /** 内存容量（GB） */
  memCapacity: number
  /** 内存已用（GB） */
  memUsed: number
  /** Pod 数量 */
  podCount: number
  /** Pod 容量 */
  podCapacity: number
  /** 内核版本 */
  osImage?: string
  /** 容器运行时 */
  containerRuntime?: string
  /** 创建时间 */
  createdAt: string
}

/** 组件健康状态 */
export type ComponentHealth = 'healthy' | 'warning' | 'error'

/** 集群组件状态 */
export interface ClusterComponent {
  /** 组件名 */
  name: string
  /** 健康状态 */
  status: ComponentHealth
  /** 元信息（版本、副本数等） */
  meta: string
}

/**
 * 列出所有集群（跨环境）
 */
export function getClusters(): Promise<CrossEnvClusterInfo[]> {
  return get<CrossEnvClusterInfo[]>(CLUSTER_BASE)
}

/**
 * 列出指定环境集群
 * @param env 部署环境
 */
export function getClustersByEnv(env: ClusterEnv): Promise<CrossEnvClusterInfo[]> {
  return get<CrossEnvClusterInfo[]>(`${CLUSTER_BASE}/${env}`)
}

/**
 * 查询集群详情
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getCluster(env: ClusterEnv, clusterId: string): Promise<CrossEnvClusterInfo> {
  return get<CrossEnvClusterInfo>(`${CLUSTER_BASE}/${env}/${clusterId}`)
}

/**
 * 创建集群（请求体含 environment）
 * @param req 创建请求
 */
export function createCluster(req: ClusterCreateRequest & { environment: ClusterEnv; provider: ProviderKind }): Promise<SupplyResult> {
  return post<SupplyResult>(CLUSTER_BASE, req)
}

/**
 * 销毁集群
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function destroyCluster(env: ClusterEnv, clusterId: string): Promise<ClusterInfo> {
  return del<ClusterInfo>(`${CLUSTER_BASE}/${env}/${clusterId}`)
}

/**
 * 集群扩缩容
 * @param env 部署环境
 * @param clusterId 集群 ID
 * @param req 扩缩容请求
 */
export function scaleCluster(env: ClusterEnv, clusterId: string, req: ClusterScaleRequest): Promise<ClusterInfo> {
  return post<ClusterInfo>(`${CLUSTER_BASE}/${env}/${clusterId}/scale`, req)
}

/**
 * 查询集群节点列表
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getClusterNodes(env: ClusterEnv, clusterId: string): Promise<ClusterNode[]> {
  return get<ClusterNode[]>(`${CLUSTER_BASE}/${env}/${clusterId}/nodes`)
}

/**
 * 查询集群组件健康状态
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getClusterComponents(env: ClusterEnv, clusterId: string): Promise<ClusterComponent[]> {
  return get<ClusterComponent[]>(`${CLUSTER_BASE}/${env}/${clusterId}/components`)
}

/**
 * 列出已注册 Provider
 */
export function listProviders(): Promise<ProviderListResult> {
  return get<ProviderListResult>(`${CLUSTER_BASE}/providers`)
}

/**
 * 列出支持的环境类型
 */
export function listEnvironments(): Promise<EnvListResult> {
  return get<EnvListResult>(`${CLUSTER_BASE}/environments`)
}

/**
 * 列出环境默认配置
 */
export function listProfiles(): Promise<Record<string, ProfileDefaults>> {
  return get<Record<string, ProfileDefaults>>(`${CLUSTER_BASE}/profiles`)
}

/* ================================================================== */
/* 3. 容器网络                                                         */
/* ================================================================== */

/** CNI 插件类型 */
export type CniPlugin = 'calico' | 'flannel' | 'cilium' | 'kube-ovn'

/** IP 协议族 */
export type IpFamily = 'IPv4' | 'IPv6' | 'DualStack'

/** 集群网络配置 */
export interface NetworkConfig {
  /** CNI 插件 */
  cni: CniPlugin
  /** Pod CIDR */
  podCidr: string
  /** Service CIDR */
  serviceCidr: string
  /** IP 协议族 */
  ipFamily: IpFamily
  /** MTU */
  mtu: number
}

/** NetworkPolicy 类型 */
export type NetworkPolicyType = 'ingress' | 'egress' | 'both'

/** NetworkPolicy 策略 */
export interface NetworkPolicy {
  /** 策略名 */
  name: string
  /** 命名空间 */
  namespace: string
  /** 策略类型 */
  type: NetworkPolicyType
  /** 端口列表 */
  ports: number[]
  /** Pod 选择器 */
  selector: string
}

/**
 * 获取集群网络配置
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getNetworkConfig(env: ClusterEnv, clusterId: string): Promise<NetworkConfig> {
  return get<NetworkConfig>(`${CLUSTER_BASE}/${env}/${clusterId}/network`)
}

/**
 * 更新集群网络配置
 * @param env 部署环境
 * @param clusterId 集群 ID
 * @param cfg 网络配置
 */
export function updateNetworkConfig(env: ClusterEnv, clusterId: string, cfg: NetworkConfig): Promise<NetworkConfig> {
  return put<NetworkConfig>(`${CLUSTER_BASE}/${env}/${clusterId}/network`, cfg)
}

/**
 * 列出 NetworkPolicy
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getNetworkPolicies(env: ClusterEnv, clusterId: string): Promise<NetworkPolicy[]> {
  return get<NetworkPolicy[]>(`${CLUSTER_BASE}/${env}/${clusterId}/network/policies`)
}

/**
 * 创建 NetworkPolicy
 * @param env 部署环境
 * @param clusterId 集群 ID
 * @param policy 策略
 */
export function createNetworkPolicy(env: ClusterEnv, clusterId: string, policy: NetworkPolicy): Promise<NetworkPolicy> {
  return post<NetworkPolicy>(`${CLUSTER_BASE}/${env}/${clusterId}/network/policies`, policy)
}

/**
 * 删除 NetworkPolicy
 * @param env 部署环境
 * @param clusterId 集群 ID
 * @param name 策略名
 */
export function deleteNetworkPolicy(env: ClusterEnv, clusterId: string, name: string): Promise<void> {
  return del<void>(`${CLUSTER_BASE}/${env}/${clusterId}/network/policies/${name}`)
}

/**
 * 列出集群已安装 CNI 插件
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getCniPlugins(env: ClusterEnv, clusterId: string): Promise<CniPlugin[]> {
  return get<CniPlugin[]>(`${CLUSTER_BASE}/${env}/${clusterId}/network/cnis`)
}

/* ================================================================== */
/* 4. 容器存储                                                         */
/* ================================================================== */

/** 回收策略 */
export type ReclaimPolicy = 'Retain' | 'Delete'

/** PVC 状态 */
export type PvcStatus = 'Bound' | 'Pending' | 'Lost'

/** StorageClass */
export interface StorageClass {
  /** 名称 */
  name: string
  /** Provisioner */
  provisioner: string
  /** 回收策略 */
  reclaimPolicy: ReclaimPolicy
  /** 是否为默认 StorageClass */
  default: boolean
}

/** PersistentVolumeClaim */
export interface PersistentVolumeClaim {
  /** 名称 */
  name: string
  /** 命名空间 */
  namespace: string
  /** 关联 StorageClass */
  storageClassName: string
  /** 容量，如 100Gi */
  capacity: string
  /** 绑定状态 */
  status: PvcStatus
  /** 绑定的 PV 名称 */
  volumeName?: string
  /** 创建时间 */
  createdAt: string
}

/** StorageClass 容量分布 */
export interface StorageClassUsage {
  /** StorageClass 名称 */
  name: string
  /** 总容量（字节） */
  capacity: number
  /** 已用容量（字节） */
  used: number
}

/** 存储用量统计 */
export interface StorageUsage {
  /** 总容量（字节） */
  totalCapacityBytes: number
  /** 已用容量（字节） */
  usedCapacityBytes: number
  /** 按 StorageClass 分组 */
  byStorageClass: StorageClassUsage[]
}

/** 快照创建结果 */
export interface SnapshotResult {
  /** 快照名称 */
  snapshotName: string
}

/**
 * 列出 StorageClass
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getStorageClasses(env: ClusterEnv, clusterId: string): Promise<StorageClass[]> {
  return get<StorageClass[]>(`${CLUSTER_BASE}/${env}/${clusterId}/storage/classes`)
}

/**
 * 列出 PVC
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getPersistentVolumes(env: ClusterEnv, clusterId: string): Promise<PersistentVolumeClaim[]> {
  return get<PersistentVolumeClaim[]>(`${CLUSTER_BASE}/${env}/${clusterId}/storage/pvcs`)
}

/**
 * 创建 PVC
 * @param env 部署环境
 * @param clusterId 集群 ID
 * @param pvc PVC 信息
 */
export function createPvc(env: ClusterEnv, clusterId: string, pvc: Partial<PersistentVolumeClaim>): Promise<PersistentVolumeClaim> {
  return post<PersistentVolumeClaim>(`${CLUSTER_BASE}/${env}/${clusterId}/storage/pvcs`, pvc)
}

/**
 * 删除 PVC
 * @param env 部署环境
 * @param clusterId 集群 ID
 * @param name PVC 名称
 */
export function deletePvc(env: ClusterEnv, clusterId: string, name: string): Promise<void> {
  return del<void>(`${CLUSTER_BASE}/${env}/${clusterId}/storage/pvcs/${name}`)
}

/**
 * 存储用量统计
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getStorageUsage(env: ClusterEnv, clusterId: string): Promise<StorageUsage> {
  return get<StorageUsage>(`${CLUSTER_BASE}/${env}/${clusterId}/storage/usage`)
}

/**
 * 创建 PVC 快照
 * @param env 部署环境
 * @param clusterId 集群 ID
 * @param pvcName PVC 名称
 */
export function createSnapshot(env: ClusterEnv, clusterId: string, pvcName: string): Promise<SnapshotResult> {
  return post<SnapshotResult>(`${CLUSTER_BASE}/${env}/${clusterId}/storage/pvcs/${pvcName}/snapshot`)
}

/* ================================================================== */
/* 5. 弹性调度                                                         */
/* ================================================================== */

/** HPA 状态 */
export type HpaStatus = 'active' | 'paused'

/** 自定义指标 */
export interface CustomMetric {
  /** 指标名 */
  name: string
  /** 目标值 */
  target: number
}

/** HPA 策略 */
export interface HpaPolicy {
  /** 策略名 */
  name: string
  /** 命名空间 */
  namespace: string
  /** 目标 Deployment */
  targetDeployment: string
  /** 最小副本数 */
  minReplicas: number
  /** 最大副本数 */
  maxReplicas: number
  /** 当前副本数 */
  currentReplicas: number
  /** CPU 阈值（百分比） */
  cpuThreshold: number
  /** 内存阈值（百分比，可选） */
  memoryThreshold?: number
  /** 自定义指标（可选） */
  customMetrics?: CustomMetric[]
  /** 状态 */
  status: HpaStatus
}

/** 扩缩容事件类型 */
export type ScaleEventType = 'scale_up' | 'scale_down'

/** 扩缩容事件 */
export interface ScaleEvent {
  /** 事件时间戳 */
  timestamp: string
  /** 事件类型 */
  type: ScaleEventType
  /** 触发指标，如 cpu>80% */
  trigger: string
  /** 变更前副本数 */
  fromReplicas: number
  /** 变更后副本数 */
  toReplicas: number
  /** 耗时（毫秒） */
  durationMs: number
}

/** 调度策略统计 */
export interface ScalingPolicySummary {
  /** 策略总数 */
  total: number
  /** 今日扩容次数 */
  scaleUpToday: number
  /** 今日缩容次数 */
  scaleDownToday: number
  /** 平均响应时长（毫秒） */
  avgDurationMs: number
}

/**
 * 列出 HPA 策略
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getHpas(env: ClusterEnv, clusterId: string): Promise<HpaPolicy[]> {
  return get<HpaPolicy[]>(`${CLUSTER_BASE}/${env}/${clusterId}/hpa`)
}

/**
 * 创建 HPA 策略
 * @param env 部署环境
 * @param clusterId 集群 ID
 * @param hpa HPA 策略
 */
export function createHpa(env: ClusterEnv, clusterId: string, hpa: HpaPolicy): Promise<HpaPolicy> {
  return post<HpaPolicy>(`${CLUSTER_BASE}/${env}/${clusterId}/hpa`, hpa)
}

/**
 * 更新 HPA 策略
 * @param env 部署环境
 * @param clusterId 集群 ID
 * @param name 策略名
 * @param hpa HPA 策略
 */
export function updateHpa(env: ClusterEnv, clusterId: string, name: string, hpa: HpaPolicy): Promise<HpaPolicy> {
  return put<HpaPolicy>(`${CLUSTER_BASE}/${env}/${clusterId}/hpa/${name}`, hpa)
}

/**
 * 删除 HPA 策略
 * @param env 部署环境
 * @param clusterId 集群 ID
 * @param name 策略名
 */
export function deleteHpa(env: ClusterEnv, clusterId: string, name: string): Promise<void> {
  return del<void>(`${CLUSTER_BASE}/${env}/${clusterId}/hpa/${name}`)
}

/**
 * 扩缩容事件历史
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getScaleEvents(env: ClusterEnv, clusterId: string): Promise<ScaleEvent[]> {
  return get<ScaleEvent[]>(`${CLUSTER_BASE}/${env}/${clusterId}/scale/events`)
}

/**
 * 调度策略统计
 * @param env 部署环境
 * @param clusterId 集群 ID
 */
export function getScalingPolicies(env: ClusterEnv, clusterId: string): Promise<ScalingPolicySummary> {
  return get<ScalingPolicySummary>(`${CLUSTER_BASE}/${env}/${clusterId}/scale/summary`)
}