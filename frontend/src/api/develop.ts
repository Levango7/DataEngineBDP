/**
 * 数据开发 API
 *
 * 对应后端 platform/develop/ Web IDE 与调度：
 * - 文件树（项目脚本）
 * - 作业运行
 * - 调度提交
 */
import { get, post } from './client'

/** 文件类型 */
export type FileType = 'file' | 'folder'

/** 文件树节点 */
export interface FileNode {
  /** 节点 ID */
  id: string
  /** 名称 */
  name: string
  /** 类型 */
  type: FileType
  /** 子节点（仅 folder） */
  children?: FileNode[]
  /** 文件路径（仅 file） */
  path?: string
}

/** 执行引擎 */
export type ExecuteEngine = 'spark' | 'flink' | 'trino' | 'doris'

/** 运行参数 */
export interface RunParams {
  /** 引擎 */
  engine: ExecuteEngine
  /** CPU 核数 */
  cpu: number
  /** 内存 GB */
  memory: number
  /** 并发度 */
  parallelism: number
  /** 调度表达式 */
  schedule?: string
}

/** 运行日志行 */
export interface RunLogLine {
  /** 日志级别 */
  level: 'info' | 'ok' | 'warn' | 'error'
  /** 日志内容 */
  text: string
  /** 时间戳 */
  timestamp?: string
}

/** 运行结果 */
export interface RunResult {
  /** 运行 ID */
  runId: string
  /** 状态 */
  status: 'running' | 'success' | 'failed'
  /** 日志 */
  logs: RunLogLine[]
  /** 耗时（秒） */
  durationSec?: number
}

/** DAG 节点 */
export interface DagNode {
  /** 节点 ID */
  id: string
  /** 节点名 */
  name: string
  /** 是否高亮 */
  highlight?: boolean
  /** 数据分层（ods/dwd/dws/ads/other，由后端 DAG 解析推导） */
  layer?: string
}

/** DAG 边 */
export interface DagEdge {
  /** 源节点 ID */
  source: string
  /** 目标节点 ID */
  target: string
}

/** 任务 DAG */
export interface TaskDag {
  /** DAG ID */
  dagId: string
  /** 节点 */
  nodes: DagNode[]
  /** 边 */
  edges: DagEdge[]
}

/** 资源根路径 */
const BASE = '/develop'

/**
 * 获取文件树
 */
export function getFileTree(): Promise<FileNode[]> {
  return get<FileNode[]>(`${BASE}/files`)
}

/**
 * 读取文件内容
 */
export function readFile(path: string): Promise<string> {
  return get<string>(`${BASE}/files/content`, { path })
}

/**
 * 运行作业
 */
export function runJob(params: {
  filePath: string
  engine: ExecuteEngine
  cpu?: number
  memory?: number
  parallelism?: number
}): Promise<RunResult> {
  return post<RunResult>(`${BASE}/run`, params)
}

/**
 * 提交调度
 */
export function submitSchedule(params: {
  filePath: string
  schedule: string
  engine: ExecuteEngine
}): Promise<void> {
  return post<void>(`${BASE}/schedule`, params)
}

/**
 * 获取任务 DAG
 */
export function getTaskDag(filePath: string): Promise<TaskDag> {
  return get<TaskDag>(`${BASE}/dag`, { filePath })
}
