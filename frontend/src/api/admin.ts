/**
 * 运营后台 API（平台侧）
 *
 * 后端：platform/admin/
 * 端点前缀：/api/v1/admin
 *
 * 仅平台运维可见：租户、计量、底座运维与多环境管理。
 */
import { get } from './client'

/** 资源根路径 */
const BASE = '/admin'

/**
 * 运营 KPI。
 *
 * ⚠️ 字段分两组，改动前务必看清 —— 这里此前是错的（前端声明了后端根本不返回的字段）：
 *
 * 【已接入】后端 `AdminController#kpi()` 实际返回（`/api/v1/admin/kpi`）。
 * 【未接入】后端**当前不返回**，前端拿到的永远是 `undefined`。
 *   这些值需要跨服务聚合，尚未实现：
 *     - `clusterOnprem` / `clusterCloudVm` —— 需集群清单
 *     - `monthlyRevenue` —— 需从 finops/billing 聚合
 *     - `alertCount` / `alertAutoHandled` —— 需从 observability 聚合
 *
 * **界面必须对"未接入"字段显示占位符，不得用 `?? 0` 掩盖**：
 * 那会让运维看到「本月营收 ¥0 / 告警数 0」并据此判断"真的没有营收、没有告警"，
 * 属于界面说谎。见 `frontend/src/views/Admin.vue`。
 */
export interface AdminKpi {
  // ---------- 已接入（后端实际返回）----------
  /** 租户总数 */
  tenantTotal: number
  /** 外部租户数 */
  tenantExternal: number
  /** 内部租户数 */
  tenantInternal: number
  /** 集群实例数 */
  clusterTotal: number
  /** 信创集群数 */
  clusterXinchuang: number
  /** 工作空间数（后端返回，界面暂未渲染） */
  workspaceTotal?: number
  /** 配额数（后端返回，界面暂未渲染） */
  quotaTotal?: number
  /**
   * 资产数（后端返回，界面暂未渲染）。
   * ⚠️ 后端查的是 `encaps_tenant` schema 的 `data_asset` 空表，
   * 而资产实际由 encaps-layer 写入 `encaps_layer` schema ⇒ **该值结构性恒 0**。
   */
  assetTotal?: number
  /** API 数（同上：api_definition 由 encaps-gateway 写入 ⇒ 结构性恒 0） */
  apiTotal?: number
  /** 数据源数（同上：datasource 由 encaps-data 写入 ⇒ 结构性恒 0） */
  datasourceTotal?: number
  /** 项目数（由 encaps-tenant 自己写入，**该值正确**） */
  projectTotal?: number
  /** 同步任务数（写入方待确认） */
  syncTaskTotal?: number

  // ---------- 未接入（后端不返回，勿用 ?? 0 兜底）----------
  /** @deprecated 后端未接入 —— 界面应显示占位符 */
  clusterOnprem?: number
  /** @deprecated 后端未接入 —— 界面应显示占位符 */
  clusterCloudVm?: number
  /** @deprecated 后端未接入（需 finops/billing 聚合）—— 界面应显示占位符 */
  monthlyRevenue?: number
  /** @deprecated 后端未接入（需 observability 聚合）—— 界面应显示占位符 */
  alertCount?: number
  /** @deprecated 后端未接入 —— 界面应显示占位符 */
  alertAutoHandled?: number
}

/** 环境状态 */
export type EnvStatus = 'healthy' | 'scaling' | 'warning' | 'critical'

/** 环境矩阵项 */
export interface EnvMatrixItem {
  id: string
  /** 环境名称 */
  name: string
  /** Namespace 数量 */
  namespaceCount: number
  /** 节点数 */
  nodeCount: number
  /** 控制面描述 */
  controlPlane: string
  /** 状态 */
  status: EnvStatus
}

// ---------- API 方法 ----------

/**
 * 获取运营 KPI
 */
export function getKpi(): Promise<AdminKpi> {
  return get<AdminKpi>(`${BASE}/kpi`)
}

/**
 * 获取环境矩阵
 */
export function getEnvMatrix(): Promise<EnvMatrixItem[]> {
  return get<EnvMatrixItem[]>(`${BASE}/env-matrix`)
}
