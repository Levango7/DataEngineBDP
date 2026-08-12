/**
 * 编排 DAG 可视化 API 封装（T007 viz）
 *
 * 后端约定（在已有 /api/v1/orchestrator/dags 基础上扩展）：
 * - DAG 管理：       GET/POST/DELETE /dags, /dags/{id}, /dags/{id}/run, /dags/{id}/stop
 * - DAG 可视化：     GET /dags/{id}/json, /dags/{id}/mermaid
 * - 执行结果：       GET /dags/{id}/results
 * - 思考链：         GET /dags/{id}/thoughts          Agent 推理过程
 * - 工具调用记录：   GET /dags/{id}/tool-calls        工具调用参数与结果
 * - 人工介入：       GET /dags/{id}/intervention      查询待审批节点
 *                    POST /dags/{id}/intervene        提交人工审批结果
 * - 检查点：         GET /dags/{id}/checkpoints       断点列表
 *                    POST /dags/{id}/checkpoint        手动打点
 * - 断点续跑：       POST /dags/{id}/resume           从检查点恢复
 * - 回放：           GET /dags/{id}/executions        执行历史
 *                    GET /dags/{id}/replay/{execId}   单次回放轨迹
 *
 * 说明：
 * - 所有方法返回 Promise<T>，错误由 client 拦截器统一提示
 * - 类型从本文件内联导出，避免新增 types 文件
 * - 命名约定：PascalCase 类型，camelCase 字段
 */
import { get, post, del } from './client'

/** 编排资源根路径 */
const BASE = '/orchestrator/dags'

/* ------------------------------------------------------------------ */
/* DAG 节点状态                                                        */
/* ------------------------------------------------------------------ */

/** 节点运行时状态：与后端 DagNode.STATUS_* 对齐 */
export type NodeStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'WAITING_HUMAN'

/** 图整体状态：与后端 DagGraph.STATUS_* 对齐 */
export type GraphStatus = 'DRAFT' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'STOPPED' | 'PAUSED'

/* ------------------------------------------------------------------ */
/* DAG 图结构（与后端 DagJsonExporter 输出对齐）                       */
/* ------------------------------------------------------------------ */

/** DAG 节点 */
export interface DagNodeDto {
  id: string
  name: string
  taskType: string
  command?: string
  timeoutSeconds: number
  maxRetries: number
  backoffStrategy?: string
  backoffIntervalMs: number
  status?: NodeStatus
  inDegree: number
  startedAt?: string | null
  finishedAt?: string | null
  errorMessage?: string | null
  params?: Record<string, unknown> | null
}

/** DAG 边 */
export interface DagEdgeDto {
  source: string
  target: string
  condition?: string | null
}

/** DAG 图（与后端 DagJsonExporter.toMap 输出对齐） */
export interface DagGraphDto {
  id: string
  name?: string
  description?: string
  status?: GraphStatus
  createdAt?: string | null
  updatedAt?: string | null
  startedAt?: string | null
  finishedAt?: string | null
  nodes: DagNodeDto[]
  edges: DagEdgeDto[]
}

/* ------------------------------------------------------------------ */
/* 任务执行结果                                                        */
/* ------------------------------------------------------------------ */

/** 单节点执行结果 */
export interface TaskResultDto {
  nodeId: string
  status: 'SUCCESS' | 'FAILED' | 'TIMEOUT'
  output?: Record<string, unknown> | null
  errorMessage?: string | null
  durationMs: number
  finishedAt?: string | null
}

/* ------------------------------------------------------------------ */
/* Agent 思考链                                                        */
/* ------------------------------------------------------------------ */

/** 单步思考类型 */
export type ThoughtKind = 'OBSERVE' | 'PLAN' | 'ACT' | 'REFLECT' | 'DECIDE'

/** 思考链单步 */
export interface ThoughtStep {
  /** 步骤序号（从 1 开始） */
  index: number
  /** 思考类型 */
  kind: ThoughtKind
  /** 节点 ID（关联 DAG 节点） */
  nodeId?: string
  /** 思考内容（自然语言推理文本） */
  content: string
  /** 输入观察（可选） */
  observation?: string | null
  /** 该步骤产生的时间戳 */
  timestamp: string
  /** 耗时（毫秒） */
  durationMs?: number
  /** 关联工具调用 ID（若该步触发了工具调用） */
  toolCallId?: string | null
}

/* ------------------------------------------------------------------ */
/* 工具调用记录                                                        */
/* ------------------------------------------------------------------ */

/** 工具调用状态 */
export type ToolCallStatus = 'SUCCESS' | 'FAILED' | 'TIMEOUT' | 'SKIPPED'

/** 工具调用记录 */
export interface ToolCallRecord {
  /** 调用 ID */
  id: string
  /** 工具名称，如 sql_query / http_get / rag_retrieve */
  toolName: string
  /** 关联节点 ID */
  nodeId: string
  /** 调用序号（同一节点内递增） */
  seq: number
  /** 调用参数（结构化） */
  args: Record<string, unknown>
  /** 调用结果（结构化） */
  result?: Record<string, unknown> | null
  /** 调用状态 */
  status: ToolCallStatus
  /** 错误信息 */
  errorMessage?: string | null
  /** 耗时（毫秒） */
  durationMs: number
  /** 起始时间 */
  startedAt: string
  /** 结束时间 */
  finishedAt?: string | null
}

/* ------------------------------------------------------------------ */
/* 人工介入                                                            */
/* ------------------------------------------------------------------ */

/** 介入请求状态 */
export type InterventionStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'TIMEOUT'

/** 人工介入请求 */
export interface InterventionRequest {
  /** 介入 ID */
  id: string
  /** DAG ID */
  dagId: string
  /** 节点 ID */
  nodeId: string
  /** 节点名称 */
  nodeName: string
  /** 介入原因（暂停时由节点写入） */
  reason: string
  /** 上下文数据（供审批人参考） */
  context?: Record<string, unknown> | null
  /** 状态 */
  status: InterventionStatus
  /** 创建时间 */
  createdAt: string
  /** 处理时间 */
  resolvedAt?: string | null
  /** 审批人 */
  approver?: string | null
  /** 审批意见 */
  comment?: string | null
}

/** 提交人工审批请求体 */
export interface IntervenePayload {
  /** 介入 ID */
  interventionId: string
  /** 审批决定 */
  decision: 'APPROVED' | 'REJECTED'
  /** 审批人 */
  approver: string
  /** 审批意见 */
  comment?: string
  /** 覆盖参数（可选，批准时可调整下游参数） */
  overrideParams?: Record<string, unknown>
}

/* ------------------------------------------------------------------ */
/* 检查点与回放                                                        */
/* ------------------------------------------------------------------ */

/** 检查点类型 */
export type CheckpointKind = 'AUTO' | 'MANUAL' | 'INTERVENTION'

/** 检查点 */
export interface Checkpoint {
  /** 检查点 ID */
  id: string
  /** DAG ID */
  dagId: string
  /** 类型 */
  kind: CheckpointKind
  /** 已完成节点 ID 列表 */
  completedNodes: string[]
  /** 已完成节点结果快照 */
  results: Record<string, TaskResultDto>
  /** 创建时间 */
  createdAt: string
  /** 备注 */
  note?: string | null
}

/** 执行历史记录 */
export interface ExecutionRecord {
  /** 执行 ID */
  execId: string
  /** DAG ID */
  dagId: string
  /** 触发方式 */
  trigger: 'RUN' | 'RESUME' | 'REPLAY'
  /** 起始检查点 ID（断点续跑时非空） */
  fromCheckpointId?: string | null
  /** 执行状态 */
  status: GraphStatus
  /** 开始时间 */
  startedAt: string
  /** 结束时间 */
  finishedAt?: string | null
  /** 完成节点数 */
  completedCount: number
  /** 总节点数 */
  totalNodes: number
}

/** 回放轨迹（单次执行的完整事件流） */
export interface ReplayTrace {
  /** 执行 ID */
  execId: string
  /** DAG ID */
  dagId: string
  /** 事件序列（按时间排序） */
  events: ReplayEvent[]
  /** 起始时间 */
  startedAt: string
  /** 结束时间 */
  finishedAt?: string | null
}

/** 回放事件类型 */
export type ReplayEventKind =
  | 'NODE_START'
  | 'NODE_SUCCESS'
  | 'NODE_FAILED'
  | 'NODE_SKIP'
  | 'CHECKPOINT'
  | 'INTERVENE'
  | 'TOOL_CALL'

/** 回放事件 */
export interface ReplayEvent {
  /** 事件序号 */
  seq: number
  /** 事件类型 */
  kind: ReplayEventKind
  /** 关联节点 ID */
  nodeId?: string | null
  /** 时间戳 */
  timestamp: string
  /** 事件数据（结构化，按 kind 不同含义不同） */
  payload?: Record<string, unknown> | null
}

/* ------------------------------------------------------------------ */
/* DAG 管理                                                            */
/* ------------------------------------------------------------------ */

/** 列出所有 DAG */
export function listDags(): Promise<DagGraphDto[]> {
  return get<DagGraphDto[]>(BASE)
}

/** 提交 DAG */
export function submitDag(graph: Partial<DagGraphDto>): Promise<DagGraphDto> {
  return post<DagGraphDto>(BASE, graph)
}

/** 查询 DAG 详情 */
export function getDag(id: string): Promise<DagGraphDto> {
  return get<DagGraphDto>(`${BASE}/${id}`)
}

/** 删除 DAG */
export function deleteDag(id: string): Promise<void> {
  return del<void>(`${BASE}/${id}`)
}

/** 执行 DAG */
export function runDag(id: string): Promise<Record<string, TaskResultDto>> {
  return post<Record<string, TaskResultDto>>(`${BASE}/${id}/run`)
}

/** 停止 DAG */
export function stopDag(id: string): Promise<void> {
  return post<void>(`${BASE}/${id}/stop`)
}

/* ------------------------------------------------------------------ */
/* 可视化                                                              */
/* ------------------------------------------------------------------ */

/** 导出 JSON 结构（含运行时状态，前端着色用） */
export function getDagJson(id: string): Promise<DagGraphDto> {
  return get<DagGraphDto>(`${BASE}/${id}/json`)
}

/** 生成 Mermaid 文本 */
export function getDagMermaid(id: string): Promise<string> {
  return get<string>(`${BASE}/${id}/mermaid`)
}

/** 查询执行结果 */
export function getResults(id: string): Promise<Record<string, TaskResultDto>> {
  return get<Record<string, TaskResultDto>>(`${BASE}/${id}/results`)
}

/* ------------------------------------------------------------------ */
/* Agent 思考链                                                        */
/* ------------------------------------------------------------------ */

/** 拉取 Agent 思考链 */
export function getThoughtChain(id: string): Promise<ThoughtStep[]> {
  return get<ThoughtStep[]>(`${BASE}/${id}/thoughts`)
}

/* ------------------------------------------------------------------ */
/* 工具调用记录                                                        */
/* ------------------------------------------------------------------ */

/** 拉取工具调用记录 */
export function getToolCalls(id: string): Promise<ToolCallRecord[]> {
  return get<ToolCallRecord[]>(`${BASE}/${id}/tool-calls`)
}

/* ------------------------------------------------------------------ */
/* 人工介入                                                            */
/* ------------------------------------------------------------------ */

/** 查询待处理人工介入请求 */
export function getInterventions(id: string): Promise<InterventionRequest[]> {
  return get<InterventionRequest[]>(`${BASE}/${id}/intervention`)
}

/** 提交人工审批 */
export function submitIntervention(id: string, payload: IntervenePayload): Promise<InterventionRequest> {
  return post<InterventionRequest>(`${BASE}/${id}/intervene`, payload)
}

/* ------------------------------------------------------------------ */
/* 检查点与断点续跑                                                    */
/* ------------------------------------------------------------------ */

/** 拉取检查点列表 */
export function getCheckpoints(id: string): Promise<Checkpoint[]> {
  return get<Checkpoint[]>(`${BASE}/${id}/checkpoints`)
}

/** 手动打检查点 */
export function createCheckpoint(id: string, note?: string): Promise<Checkpoint> {
  return post<Checkpoint>(`${BASE}/${id}/checkpoint`, { note })
}

/** 从检查点恢复执行 */
export function resumeFromCheckpoint(id: string, checkpointId: string): Promise<Record<string, TaskResultDto>> {
  return post<Record<string, TaskResultDto>>(`${BASE}/${id}/resume`, { checkpointId })
}

/* ------------------------------------------------------------------ */
/* 回放                                                                */
/* ------------------------------------------------------------------ */

/** 拉取执行历史 */
export function getExecutions(id: string): Promise<ExecutionRecord[]> {
  return get<ExecutionRecord[]>(`${BASE}/${id}/executions`)
}

/** 拉取单次回放轨迹 */
export function getReplayTrace(id: string, execId: string): Promise<ReplayTrace> {
  return get<ReplayTrace>(`${BASE}/${id}/replay/${execId}`)
}