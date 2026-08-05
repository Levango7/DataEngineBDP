/**
 * 行业应用模板 API
 *
 * 对接后端 industry-templates 服务（L5.3）。
 * 后端默认前缀 /api/v1，与全局 axios baseURL 一致。
 */
import { get, post } from './client'

/** 行业枚举 */
export type Industry = 'finance' | 'retail' | 'manufacturing' | 'government' | 'iot'

/** 模板状态 */
export type TemplateStatus = 'dev' | 'review' | 'catalog' | 'deprecated'

/** 参数类型 */
export type ParameterType =
  | 'string'
  | 'integer'
  | 'float'
  | 'boolean'
  | 'enum'
  | 'datasource'

/** 模板元信息 */
export interface TemplateMeta {
  id: string
  name: string
  industry: Industry
  version: string
  appVersion: string
  description: string
  author: string
  status: TemplateStatus
  installCount: number
  rating: number
  tags: string[]
  icon: string
}

/** 模板参数定义 */
export interface TemplateParameter {
  name: string
  type: ParameterType
  description: string
  defaultValue: unknown
  required: boolean
  enumOptions: string[] | null
  placeholder: string | null
}

/** 数据流节点 */
export interface DataFlowNode {
  id: string
  name: string
  nodeType: 'source' | 'transform' | 'sink'
  layer: string | null
  description: string
  inputs: string[]
  outputs: string[]
  config: Record<string, unknown>
}

/** 数据流配置 */
export interface DataFlowConfig {
  nodes: DataFlowNode[]
  schedule: string | null
  description: string
}

/** 计算逻辑步骤 */
export interface ComputeLogicStep {
  id: string
  name: string
  stepType: string
  description: string
  inputs: string[]
  outputs: string[]
  code: string
  params: Record<string, unknown>
}

/** 计算逻辑配置 */
export interface ComputeLogicConfig {
  steps: ComputeLogicStep[]
  description: string
}

/** 可视化面板 */
export interface VisualizationPanel {
  id: string
  title: string
  chartType: string
  description: string
  dataSource: string
  config: Record<string, unknown>
  width: number
  height: number
}

/** 可视化配置 */
export interface VisualizationConfig {
  title: string
  panels: VisualizationPanel[]
  description: string
}

/** 完整模板 */
export interface Template {
  meta: TemplateMeta
  parameters: TemplateParameter[]
  dataFlow: DataFlowConfig
  computeLogic: ComputeLogicConfig
  visualization: VisualizationConfig
  readme: string
  schema: Record<string, unknown>
  createdAt: string
  updatedAt: string
}

/** 部署请求 */
export interface DeploymentRequest {
  tenantId: string
  releaseName: string
  namespace?: string
  values: Record<string, unknown>
  datasourceBindings: Array<{ placeholder: string; assetId: string }>
}

/** 部署状态 */
export type DeploymentStatus =
  | 'pending'
  | 'installing'
  | 'instantiating'
  | 'running'
  | 'failed'
  | 'stopped'

/** 部署记录 */
export interface DeploymentRecord {
  deploymentId: string
  templateId: string
  templateVersion: string
  tenantId: string
  releaseName: string
  namespace: string
  status: DeploymentStatus
  renderedValues: Record<string, unknown>
  errorMessage: string | null
  jobRunId: string | null
  dashboardSnapshotUrl: string | null
  createdAt: string
  updatedAt: string
  finishedAt: string | null
}

/** 模板预览 */
export interface TemplatePreview {
  templateId: string
  templateName: string
  industry: Industry
  architecture: {
    nodes: Array<{ id: string; name: string; nodeType: string; layer: string | null }>
    edges: Array<{ source: string; target: string }>
  }
  parameterSummary: Array<{
    name: string
    type: string
    required: boolean
    defaultValue: unknown
    description: string
  }>
  stats: {
    dataFlowNodes: number
    computeSteps: number
    visualizationPanels: number
    parameters: number
  }
}

/** 分类 */
export interface TemplateCategory {
  industry: string
  name: string
  count: number
  templates: Array<{ id: string; name: string; version: string }>
}

/** 模板根路径（与后端 apiPrefix=/api/v1 拼接） */
const BASE = '/templates'

/**
 * 列出所有模板
 * @param industry 行业过滤
 * @param keyword 关键字过滤
 */
export function listTemplates(
  params?: { industry?: Industry; keyword?: string; status?: TemplateStatus }
): Promise<TemplateMeta[]> {
  return get<TemplateMeta[]>(BASE, params as Record<string, unknown>)
}

/**
 * 获取模板详情
 * @param id 模板 ID
 */
export function getTemplate(id: string): Promise<Template> {
  return get<Template>(`${BASE}/${id}`)
}

/**
 * 部署模板
 * @param id 模板 ID
 * @param data 部署请求
 */
export function deployTemplate(id: string, data: DeploymentRequest): Promise<DeploymentRecord> {
  return post<DeploymentRecord>(`${BASE}/${id}/deploy`, data)
}

/**
 * 预览模板架构
 * @param id 模板 ID
 */
export function previewTemplate(id: string): Promise<TemplatePreview> {
  return get<TemplatePreview>(`${BASE}/${id}/preview`)
}

/**
 * 列出模板分类
 */
export function listCategories(): Promise<TemplateCategory[]> {
  return get<TemplateCategory[]>(`${BASE}/categories`)
}

/**
 * 列出模板的部署记录
 * @param id 模板 ID
 * @param tenantId 租户 ID 过滤
 */
export function listDeployments(
  id: string,
  tenantId?: string
): Promise<DeploymentRecord[]> {
  return get<DeploymentRecord[]>(`${BASE}/${id}/deployments`, tenantId ? { tenantId } : undefined)
}