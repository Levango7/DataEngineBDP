/**
 * 标签画像 API（治理/开发层）
 *
 * 对接后端：
 * - TagController       /tags（标签定义、规则、计算、批计算）
 * - ProfileController   /profiles（单用户画像、按标签查询、按标签统计）
 * - AudienceController  /audiences（人群圈选）
 *
 * 所有方法通过 `@/api/client` 的 get/post/del 调用，
 * 自动享受 Bearer token 注入、ApiResponse<T> 拆包、401/403/500 统一错误提示、30s 超时。
 */
import { get, post, del } from './client'

/** 资源根路径 */
const BASE_TAGS = '/tags'
const BASE_PROFILES = '/profiles'
const BASE_AUDIENCES = '/audiences'

/* ================================================================== */
/* 类型定义                                                            */
/* ================================================================== */

/** 标签值类型 */
export type TagValueType = 'STRING' | 'NUMBER' | 'BOOLEAN' | 'ENUM' | 'DATE'

/** 标签状态 */
export type TagStatus = 'DRAFT' | 'READY' | 'COMPUTING' | 'COMPUTED' | 'FAILED'

/** 标签定义 */
export interface TagDefinition {
  /** 标签 ID */
  id: string
  /** 标签名 */
  name: string
  /** 标签编码 */
  code?: string
  /** 标签值类型 */
  valueType: TagValueType | string
  /** 所属租户 */
  tenantId?: string
  /** 描述 */
  description?: string
  /** 规则数 */
  ruleCount?: number
  /** 状态 */
  status?: TagStatus | string
  /** 最近计算时间 */
  lastComputedAt?: string
  /** 创建时间 */
  createdAt?: string
  /** 更新时间 */
  updatedAt?: string
}

/** 标签定义创建请求 */
export interface TagDefinitionRequest {
  name: string
  code?: string
  valueType: TagValueType | string
  tenantId?: string
  description?: string
}

/** 标签规则 */
export interface TagRule {
  /** 规则 ID */
  id: string
  /** 标签 ID */
  tagId: string
  /** 规则名 */
  name: string
  /** 规则类型：SQL / EXPRESSION / LOOKUP */
  ruleType: 'SQL' | 'EXPRESSION' | 'LOOKUP' | string
  /** 规则表达式 */
  expression: string
  /** 优先级 */
  priority?: number
  /** 输出值 */
  outputValue?: string
  /** 描述 */
  description?: string
}

/** 标签规则创建请求 */
export interface TagRuleRequest {
  name: string
  ruleType: 'SQL' | 'EXPRESSION' | 'LOOKUP' | string
  expression: string
  priority?: number
  outputValue?: string
  description?: string
}

/** 标签计算请求 */
export interface ComputeRequest {
  /** 业务时间 */
  bizTime?: string
  /** 是否强制重算 */
  force?: boolean
  /** 异步执行 */
  async?: boolean
}

/** 标签计算结果 */
export interface TagComputeResult {
  /** 标签 ID */
  tagId: string
  /** 计算任务 ID */
  taskId?: string
  /** 状态 */
  status: string
  /** 已计算用户数 */
  computedCount?: number
  /** 耗时（毫秒） */
  durationMs?: number
}

/** 批量计算结果 */
export interface BatchComputeResult {
  /** 提交的任务数 */
  submitted: number
  /** 成功数 */
  successCount: number
  /** 失败数 */
  failedCount: number
  /** 任务 ID 列表 */
  taskIds?: string[]
}

/** 用户画像（标签值集合） */
export interface UserProfile {
  /** 用户 ID */
  userId: string
  /** 用户名 */
  username?: string
  /** 标签值列表 */
  tags: Array<{
    /** 标签 ID */
    tagId: string
    /** 标签名 */
    tagName: string
    /** 标签值 */
    value: string | number | boolean | null
    /** 标签值类型 */
    valueType?: string
  }>
  /** 最近更新时间 */
  updatedAt?: string
}

/** 标签查询条件 */
export interface TagQuery {
  /** 标签条件列表（AND 关系） */
  tags: Array<{
    /** 标签 ID 或编码 */
    tagId?: string
    tagCode?: string
    /** 操作符：EQ / NE / IN / NOT_IN / GT / LT / GE / LE / BETWEEN / LIKE */
    op: 'EQ' | 'NE' | 'IN' | 'NOT_IN' | 'GT' | 'LT' | 'GE' | 'LE' | 'BETWEEN' | 'LIKE' | string
    /** 值 */
    value: unknown
    /** BETWEEN 时的第二个值 */
    value2?: unknown
  }>
  /** 租户 ID */
  tenantId?: string
  /** 限制条数 */
  limit?: number
}

/** 人群圈选请求 */
export interface AudienceRequest {
  /** 圈选条件 */
  query: TagQuery
  /** 人群名称 */
  audienceName?: string
  /** 是否保存人群 */
  save?: boolean
}

/** 人群圈选结果 */
export interface AudienceResult {
  /** 人群 ID（保存后返回） */
  audienceId?: string
  /** 人群名称 */
  audienceName?: string
  /** 人数 */
  count: number
  /** 用户列表（按 limit 截断） */
  users: Array<{
    userId: string
    username?: string
  }>
  /** 创建时间 */
  createdAt?: string
}

/** 标签查询用户结果（分页） */
export interface UserProfilePage {
  list: UserProfile[]
  total: number
  page: number
  pageSize: number
}

/* ================================================================== */
/* API 方法（TagController）                                            */
/* ================================================================== */

/**
 * 列出标签定义
 * GET /tags?tenantId=
 */
export function listTags(tenantId?: string): Promise<TagDefinition[]> {
  return get<TagDefinition[]>(BASE_TAGS, tenantId ? { tenantId } : undefined)
}

/**
 * 获取标签详情
 * GET /tags/{id}
 */
export function getTag(id: string): Promise<TagDefinition> {
  return get<TagDefinition>(`${BASE_TAGS}/${id}`)
}

/**
 * 创建标签定义
 * POST /tags
 */
export function createTag(req: TagDefinitionRequest): Promise<TagDefinition> {
  return post<TagDefinition>(BASE_TAGS, req)
}

/**
 * 删除标签定义
 * DELETE /tags/{id}
 */
export function deleteTag(id: string): Promise<void> {
  return del<void>(`${BASE_TAGS}/${id}`)
}

/**
 * 列出标签规则
 * GET /tags/{id}/rules
 */
export function listTagRules(id: string): Promise<TagRule[]> {
  return get<TagRule[]>(`${BASE_TAGS}/${id}/rules`)
}

/**
 * 添加标签规则
 * POST /tags/{id}/rules
 */
export function addTagRule(id: string, req: TagRuleRequest): Promise<TagRule> {
  return post<TagRule>(`${BASE_TAGS}/${id}/rules`, req)
}

/**
 * 删除标签规则
 * DELETE /tags/{tagId}/rules/{ruleId}
 */
export function deleteTagRule(tagId: string, ruleId: string): Promise<void> {
  return del<void>(`${BASE_TAGS}/${tagId}/rules/${ruleId}`)
}

/**
 * 计算标签
 * POST /tags/{id}/compute
 */
export function computeTag(id: string, req: ComputeRequest = {}): Promise<TagComputeResult> {
  return post<TagComputeResult>(`${BASE_TAGS}/${id}/compute`, req)
}

/**
 * 批量计算标签
 * POST /tags/batch-compute
 */
export function batchCompute(tagIds: string[], req: ComputeRequest = {}): Promise<BatchComputeResult> {
  return post<BatchComputeResult>(`${BASE_TAGS}/batch-compute`, { tagIds, req })
}

/* ================================================================== */
/* API 方法（ProfileController）                                        */
/* ================================================================== */

/**
 * 查询单用户画像
 * GET /profiles/{userId}
 */
export function getProfile(userId: string): Promise<UserProfile> {
  return get<UserProfile>(`${BASE_PROFILES}/${encodeURIComponent(userId)}`)
}

/**
 * 按标签条件查询用户
 * POST /profiles/query
 */
export function queryByTags(query: TagQuery): Promise<UserProfile[]> {
  return post<UserProfile[]>(`${BASE_PROFILES}/query`, query)
}

/**
 * 按标签条件统计人数
 * POST /profiles/count
 */
export function countByTags(query: TagQuery): Promise<{ count: number }> {
  return post<{ count: number }>(`${BASE_PROFILES}/count`, query)
}

/* ================================================================== */
/* API 方法（AudienceController）                                       */
/* ================================================================== */

/**
 * 人群圈选
 * POST /audiences/select
 */
export function selectAudience(req: AudienceRequest): Promise<AudienceResult> {
  return post<AudienceResult>(`${BASE_AUDIENCES}/select`, req)
}