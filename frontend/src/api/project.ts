/**
 * 数据项目 API
 *
 * 对应后端 platform/projects/ 数据项目管理：
 * - 项目 CRUD
 * - 项目数据集、作业、成员
 */
import { get, post, put, del } from './client'
import type { PagedResult, PageQuery } from './types'

/** 项目状态 */
export type ProjectStatus = 'running' | 'stopped' | 'failed' | 'creating'

/** 数据项目 */
export interface Project {
  /** 项目 ID */
  id: string
  /** 项目名 */
  name: string
  /** 业务域 */
  domain: string
  /** 数据集数量 */
  datasets: number
  /** 作业数量 */
  jobs: number
  /** 负责人 */
  owner: string
  /** 状态 */
  status: ProjectStatus
  /** 描述 */
  description?: string
  /** 创建时间 */
  createdAt: string
  /** 更新时间 */
  updatedAt: string
}

/** 项目数据集 */
export interface ProjectDataset {
  /** 数据集名 */
  name: string
  /** 类型 */
  type: string
  /** 字段数 */
  fieldCount: number
}

/** 项目作业 */
export interface ProjectJob {
  /** 作业名 */
  name: string
  /** 引擎 */
  engine: string
  /** 状态 */
  status: 'running' | 'success' | 'failed'
}

/** 项目成员 */
export interface ProjectMember {
  /** 成员名 */
  name: string
  /** 角色 */
  role: string
}

/** 创建项目参数 */
export interface CreateProjectParams {
  name: string
  domain: string
  description?: string
}

/** 更新项目参数 */
export interface UpdateProjectParams {
  name?: string
  domain?: string
  description?: string
}

/** 项目列表查询参数 */
export interface ProjectListQuery extends PageQuery {
  status?: ProjectStatus
  domain?: string
}

/** 资源根路径 */
const BASE = '/projects'

/**
 * 查询项目列表（分页）
 */
export function listProjects(params?: ProjectListQuery): Promise<PagedResult<Project>> {
  return get<PagedResult<Project>>(BASE, params as Record<string, unknown>)
}

/**
 * 获取项目详情
 */
export function getProject(id: string): Promise<Project> {
  return get<Project>(`${BASE}/${id}`)
}

/**
 * 创建项目
 */
export function createProject(data: CreateProjectParams): Promise<Project> {
  return post<Project>(BASE, data)
}

/**
 * 更新项目
 */
export function updateProject(id: string, data: UpdateProjectParams): Promise<Project> {
  return put<Project>(`${BASE}/${id}`, data)
}

/**
 * 删除项目
 */
export function deleteProject(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/**
 * 获取项目数据集列表
 */
export function listDatasets(id: string): Promise<ProjectDataset[]> {
  return get<ProjectDataset[]>(`${BASE}/${id}/datasets`)
}

/**
 * 获取项目作业列表
 */
export function listJobs(id: string): Promise<ProjectJob[]> {
  return get<ProjectJob[]>(`${BASE}/${id}/jobs`)
}

/**
 * 获取项目成员列表
 */
export function listMembers(id: string): Promise<ProjectMember[]> {
  return get<ProjectMember[]>(`${BASE}/${id}/members`)
}