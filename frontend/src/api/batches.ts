/**
 * 批次 API（batch-pipeline / data-quality 服务）
 *
 * 对应后端 platform/batch-pipeline/batch_pipeline/api/routers/batches.py：
 * - 批次列表（含历史 manifest 回读）
 * - 批次质量报告（validate 阶段落盘的 quality_summary）
 */
import { get } from './client'

/** 批次记录 */
export interface BatchRecord {
  /** 批次 ID */
  batch_id: string
  /** 状态（running/success/failed/unknown） */
  status: string
  /** 提交时间 */
  submitted_at?: string
  /** 完成时间 */
  finished_at?: string
  /** 失败原因（仅 failed） */
  error?: string | null
}

/** 批次质量单条规则结果 */
export interface BatchQualityRule {
  /** 数据集 */
  dataset: string
  /** 规则名 */
  rule: string
  /** 校验行数 */
  checked: number
  /** 通过行数 */
  passed: number
  [key: string]: unknown
}

/** 批次质量报告（quality_summary + 运行模式） */
export interface BatchQualityReport {
  /** 质量分（0-1） */
  dq_score: number
  /** 规则总数 */
  rules_total: number
  /** 校验总行数 */
  checks_total: number
  /** 通过行数 */
  checks_passed: number
  /** 失败行数 */
  checks_failed: number
  /** 各数据集隔离行数 */
  quarantined_rows: Record<string, number>
  /** 规则明细 */
  rules: BatchQualityRule[]
  /** 运行模式（full=全量 / incremental=增量） */
  mode?: string
}

/** 批次质量响应（含租户与批次标识） */
export interface BatchQualityResponse {
  batch_id: string
  tenant_id: string
  quality: BatchQualityReport
}

const BASE = '/batches'

/**
 * 查询批次列表（当前租户；内存注册表 + 磁盘 manifest 历史回读）
 */
export function listBatches(): Promise<{ batches: BatchRecord[] }> {
  return get<{ batches: BatchRecord[] }>(BASE)
}

/**
 * 获取批次质量报告
 */
export function getBatchQuality(batchId: string): Promise<BatchQualityResponse> {
  return get<BatchQualityResponse>(`${BASE}/${batchId}/quality`)
}
