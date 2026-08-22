/**
 * API 层通用 TypeScript 类型定义
 *
 * 约定：
 * - 所有接口使用 PascalCase 命名
 * - 列表查询统一返回 PagedResult<T>
 * - 后端响应统一封装为 ApiResponse<T>
 */

/** 后端统一响应封装 */
export interface ApiResponse<T> {
  /** 业务状态码，0 表示成功 */
  code: number
  /** 提示消息 */
  message: string
  /** 业务数据 */
  data: T
  /** 服务器时间戳（毫秒） */
  timestamp?: number
}

/** 分页查询结果 */
export interface PagedResult<T> {
  /** 数据列表 */
  list: T[]
  /** 总条数 */
  total: number
  /** 当前页码（从 1 开始） */
  page: number
  /** 每页条数 */
  pageSize: number
}

/** 通用分页查询参数 */
export interface PageQuery {
  page?: number
  pageSize?: number
  /** 关键字搜索 */
  keyword?: string
  /** 排序字段 */
  sortBy?: string
  /** 排序方向 */
  order?: 'asc' | 'desc'
}

/** 通用 ID 标识 */
export interface Identifiable {
  id: string
}

/* ------------------------------------------------------------------ */
/* 用户与认证                                                          */
/* ------------------------------------------------------------------ */

/** 用户信息 */
export interface User extends Identifiable {
  /** 用户名 */
  username: string
  /** 显示昵称 */
  nickname: string
  /** 邮箱 */
  email: string
  /** 手机号 */
  phone?: string
  /** 头像 URL */
  avatar?: string
  /** 所属租户 ID */
  tenantId: string
  /** 角色列表 */
  roles: string[]
  /** 账号状态 */
  status: 'active' | 'disabled'
  /** 最近登录时间 */
  lastLoginAt?: string
}

/** 登录请求参数 */
export interface LoginParams {
  username: string
  password: string
  /** 验证码（可选） */
  captcha?: string
}

/** 登录响应 */
export interface LoginResult {
  token: string
  /** token 过期时间（秒） */
  expiresIn: number
  /** token 刷新令牌 */
  refreshToken?: string
  user: User
}

/* ------------------------------------------------------------------ */
/* 租户                                                                */
/* ------------------------------------------------------------------ */

/** 套餐版本 */
export type PlanTier = 'standard' | 'enterprise' | 'flagship' | 'internal'

/** 租户状态 */
export type TenantStatus = 'active' | 'suspended' | 'deleted'

/** 租户信息 */
export interface Tenant extends Identifiable {
  /** 租户名称 */
  name: string
  /** 租户编码 */
  code: string
  /** 套餐版本 */
  plan: PlanTier
  /** 状态 */
  status: TenantStatus
  /** 联系人 */
  contact?: string
  /** 联系电话 */
  contactPhone?: string
  /** 工作空间数量 */
  workspaceCount: number
  /** 用户数量 */
  userCount: number
  /** 本月资源消耗百分比 */
  resourceUsage: number
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 创建租户参数 */
export interface CreateTenantParams {
  name: string
  code: string
  plan: PlanTier
  contact?: string
  contactPhone?: string
}

/** 更新租户参数 */
export type UpdateTenantParams = Partial<CreateTenantParams> & {
  status?: TenantStatus
}

/** 租户列表查询参数 */
export interface TenantListQuery extends PageQuery {
  status?: TenantStatus
  plan?: PlanTier
}

/* ------------------------------------------------------------------ */
/* 工作空间                                                            */
/* ------------------------------------------------------------------ */

/** 工作空间状态 */
export type WorkspaceStatus = 'running' | 'stopped' | 'limited' | 'creating' | 'failed'

/** 部署环境 */
export type DeployEnv = 'xinchuang' | 'onprem' | 'public-cloud' | 'private-cloud'

/** 工作空间信息 */
export interface Workspace extends Identifiable {
  /** 工作空间名称 */
  name: string
  /** 所属租户 ID */
  tenantId: string
  /** 所属租户名称（冗余字段，便于展示） */
  tenantName?: string
  /** 套餐版本 */
  plan: PlanTier
  /** 部署环境 */
  env: DeployEnv
  /** 状态 */
  status: WorkspaceStatus
  /** CPU 使用率（百分比） */
  cpuUsage: number
  /** 内存使用率（百分比） */
  memUsage: number
  /** 存储使用率（百分比） */
  storageUsage?: number
  /** 底层 Namespace 名称 */
  namespace?: string
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 创建工作空间参数 */
export interface CreateWorkspaceParams {
  name: string
  tenantId: string
  plan: PlanTier
  env: DeployEnv
}

/** 更新工作空间参数（仅可变字段） */
export interface UpdateWorkspaceParams {
  name?: string
  description?: string
  resourceQuota?: string
  networkPolicy?: string
}

/** 工作空间 K8s Namespace 实时状态 */
export interface WorkspaceK8sStatus {
  /** K8s Namespace phase：Active/Terminating/NotFound/Unknown */
  status: string
}

/** 工作空间列表查询参数 */
export interface WorkspaceListQuery extends PageQuery {
  tenantId?: string
  status?: WorkspaceStatus
}

/* ------------------------------------------------------------------ */
/* 集群与节点                                                          */
/* ------------------------------------------------------------------ */

/** 集群概览 */
export interface ClusterOverview {
  /** 集群名称 */
  clusterName: string
  /** 集群版本 */
  version: string
  /** 节点总数 */
  nodeTotal: number
  /** 就绪节点数 */
  nodeReady: number
  /** Pod 总数 */
  podTotal: number
  /** 运行中 Pod 数 */
  podRunning: number
  /** CPU 容量（核） */
  cpuCapacity: number
  /** CPU 已用（核） */
  cpuUsed: number
  /** 内存容量（GB） */
  memCapacity: number
  /** 内存已用（GB） */
  memUsed: number
  /** 存储用量（TB） */
  storageUsed: number
  /** 数据项目数 */
  projectCount: number
  /** 运行中项目数（API 未返回时前端按 Pod 运行率估算） */
  projectRunning?: number
  /** 调度作业数 */
  jobCount: number
  /** 今日作业成功数 */
  jobSuccessToday: number
  /** 今日作业失败数 */
  jobFailToday: number
  /** 数据资产数 */
  assetCount: number
  /** 近 7 日资源趋势（百分比，0-100） */
  trendCpu: number[]
  trendMem: number[]
}

/** 节点状态 */
export type NodeStatus = 'ready' | 'not-ready' | 'unknown'

/** 集群节点 */
export interface Node extends Identifiable {
  /** 节点名称 */
  name: string
  /** 角色 */
  roles: string[]
  /** 状态 */
  status: NodeStatus
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

/** Pod 状态 */
export type PodStatus = 'running' | 'pending' | 'succeeded' | 'failed' | 'unknown'

/** Pod 信息 */
export interface Pod extends Identifiable {
  /** Pod 名称 */
  name: string
  /** 命名空间 */
  namespace: string
  /** 节点名称 */
  nodeName: string
  /** 状态 */
  status: PodStatus
  /** 重启次数 */
  restartCount: number
  /** CPU 请求（核） */
  cpuRequest: number
  /** 内存请求（Mi） */
  memRequest: number
  /** 所属工作负载类型 */
  workloadKind?: string
  /** 所属工作负载名称 */
  workloadName?: string
  /** 启动时间 */
  startedAt?: string
}

/* ------------------------------------------------------------------ */
/* 作业                                                                */
/* ------------------------------------------------------------------ */

/** 作业状态 */
export type JobStatus = 'running' | 'success' | 'failed' | 'canceled' | 'pending' | 'scheduled'

/** 作业类型 */
export type JobType = 'batch' | 'stream' | 'sql' | 'python' | 'shell'

/** 作业信息 */
export interface Job extends Identifiable {
  /** 作业名称 */
  name: string
  /** 所属工作空间 ID */
  workspaceId: string
  /** 作业类型 */
  type: JobType
  /** 状态 */
  status: JobStatus
  /** 调度表达式（cron） */
  schedule?: string
  /** 负责人 */
  owner?: string
  /** 最近运行开始时间 */
  lastRunAt?: string
  /** 最近运行耗时（秒） */
  lastRunDuration?: number
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 提交作业参数 */
export interface SubmitJobParams {
  name: string
  workspaceId: string
  type: JobType
  /** 作业配置（JSON 字符串） */
  config: string
  schedule?: string
  owner?: string
}

/** 作业列表查询参数 */
export interface JobListQuery extends PageQuery {
  workspaceId?: string
  status?: JobStatus
  type?: JobType
}

/* ------------------------------------------------------------------ */
/* 配额（Quota）                                                        */
/* ------------------------------------------------------------------ */

/** 配额状态 */
export type QuotaStatus =
  | 'SETTING'
  | 'ACTIVE'
  | 'UPDATING'
  | 'DELETING'
  | 'DELETED'
  | 'FAILED'

/** 配额信息 */
export interface Quota extends Identifiable {
  /** 所属 Workspace ID */
  workspaceId: string
  /** 所属租户 ID */
  tenantId: string
  /** CPU 核数限制，如 "10" */
  cpuLimit: string
  /** 内存限制，如 "20Gi" */
  memoryLimit: string
  /** 存储限制，如 "100Gi" */
  storageLimit: string
  /** Pod 数量限制，如 "100" */
  podLimit: string
  /** PVC 数量限制，如 "50" */
  pvcLimit: string
  /** Service 数量限制，如 "20" */
  serviceLimit: string
  /** 单 Pod 最大 CPU，如 "4" */
  maxCpuPerPod?: string
  /** 单 Pod 最大内存，如 "8Gi" */
  maxMemoryPerPod?: string
  /** 单 Pod 最小 CPU，如 "100m" */
  minCpuPerPod?: string
  /** 单 Pod 最小内存，如 "256Mi" */
  minMemoryPerPod?: string
  /** 状态 */
  status: QuotaStatus
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 设置配额参数 */
export interface SetQuotaParams {
  workspaceId: string
  tenantId: string
  cpuLimit: string
  memoryLimit: string
  storageLimit: string
  podLimit: string
  pvcLimit: string
  serviceLimit: string
  maxCpuPerPod?: string
  maxMemoryPerPod?: string
  minCpuPerPod?: string
  minMemoryPerPod?: string
}

/** 更新配额参数（仅可变字段） */
export interface UpdateQuotaParams {
  cpuLimit?: string
  memoryLimit?: string
  storageLimit?: string
  podLimit?: string
  pvcLimit?: string
  serviceLimit?: string
  maxCpuPerPod?: string
  maxMemoryPerPod?: string
  minCpuPerPod?: string
  minMemoryPerPod?: string
}

/** 配额列表查询参数 */
export interface QuotaListQuery {
  tenantId?: string
  workspaceId?: string
}

/** 配额用量信息 */
export interface QuotaUsage {
  /** 已用量 Map（键为 K8s ResourceQuota hard 键名） */
  used: Record<string, string>
  /** 硬上限 Map */
  hard: Record<string, string>
}
