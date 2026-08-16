/**
 * 工作空间 API
 *
 * 对应后端 REST 端点（encaps-layer WorkspaceController）：
 * - POST   /workspaces              — 创建工作空间
 * - GET    /workspaces               — 列表（支持 tenantId 过滤）
 * - GET    /workspaces/{id}          — 详情
 * - PUT    /workspaces/{id}          — 更新
 * - DELETE /workspaces/{id}          — 删除（级联删除 K8s Namespace）
 * - GET    /workspaces/{id}/status   — K8s Namespace 实时状态
 */
import { get, post, put, del } from './client'
import type {
  Workspace,
  WorkspaceListQuery,
  CreateWorkspaceParams,
  UpdateWorkspaceParams,
  WorkspaceK8sStatus,
  PagedResult
} from './types'
// 类型定义见 @/api/types.ts（项目约定：避免循环依赖）

/** 工作空间资源根路径 */
const BASE = '/workspaces'

/**
 * 查询工作空间列表（分页）
 * @param params 查询参数
 */
export function listWorkspaces(params?: WorkspaceListQuery): Promise<PagedResult<Workspace>> {
  return get<PagedResult<Workspace>>(BASE, params as Record<string, unknown>)
}

/**
 * 查询全部工作空间（不分页，用于下拉选择）
 */
export function listAllWorkspaces(): Promise<Workspace[]> {
  return get<Workspace[]>(`${BASE}/all`)
}

/**
 * 获取工作空间详情
 * @param id 工作空间 ID
 */
export function getWorkspace(id: string): Promise<Workspace> {
  return get<Workspace>(`${BASE}/${id}`)
}

/**
 * 创建工作空间
 *
 * 后端将 Workspace 翻译为 K8s Namespace + NetworkPolicy + RBAC + ResourceQuota。
 * @param data 工作空间信息
 */
export function createWorkspace(data: CreateWorkspaceParams): Promise<Workspace> {
  return post<Workspace>(BASE, data)
}

/**
 * 更新工作空间（仅可变字段：name、description、resourceQuota、networkPolicy）
 * @param id 工作空间 ID
 * @param data 待更新字段
 */
export function updateWorkspace(id: string, data: UpdateWorkspaceParams): Promise<Workspace> {
  return put<Workspace>(`${BASE}/${id}`, data)
}

/**
 * 删除工作空间（级联删除 K8s Namespace 及其下全部资源）
 * @param id 工作空间 ID
 */
export function deleteWorkspace(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/**
 * 查询工作空间对应 K8s Namespace 的实时状态
 * @param id 工作空间 ID
 * @returns K8s Namespace 状态（Active/Terminating/NotFound）
 */
export function getWorkspaceK8sStatus(id: string): Promise<WorkspaceK8sStatus> {
  return get<WorkspaceK8sStatus>(`${BASE}/${id}/status`)
}
