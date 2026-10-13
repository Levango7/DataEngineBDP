/**
 * 运营后台 API（平台侧）
 *
 * 后端：platform/encaps-tenant/src/main/java/com/levango7/dataenginebdp/encaps/controller/AdminController.java
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
 *   `tenantTotal` / `workspaceTotal` / `quotaTotal` / `projectTotal`
 *   —— 均为按租户口径的真实 count（2026-10-13 收口：此前后 4 个里有硬编码和恒 0）。
 *
 * 【未接入】后端**不返回**，前端拿到的永远是 `undefined`。
 *   分两种死法，都不许用 `?? 0` 掩盖：
 *     - **后端从未返回**：`monthlyRevenue`（需 finops/billing）、
 *       `alertCount` / `alertAutoHandled`（需 observability）、
 *       `clusterOnprem` / `clusterCloudVm`（需集群清单）
 *     - **后端曾返回但是编造的，2026-10-13 已从契约删除**：
 *       `tenantExternal` / `tenantInternal`（硬编码 0/1，无数据模型）、
 *       `clusterTotal` / `clusterXinchuang`（硬编码 1，无集群注册表）、
 *       `assetTotal` / `apiTotal` / `datasourceTotal`（查询落到 `encaps_tenant`
 *       的空副本表，结构性恒 0；真实数据由 encaps-layer/gateway/data 写在
 *       `encaps_layer` schema）、
 *       `syncTaskTotal`（本服务无 sync_task 写入方）
 *
 * **界面必须对「未接入」字段显示占位符，不得用 `?? 0` 兜底**：
 * 那会让运维看到「本月营收 ¥0 / 告警数 0」并据此判断“真的没有营收、没有告警”，
 * 属于界面说谎。见 `frontend/src/views/Admin.vue`。
 */
export interface AdminKpi {
  // ---------- 已接入（后端实际返回，按租户口径真实 count）----------
  /**
   * 租户总数。本服务 schema 内 `tenants` 表的真实行数。
   * ⚠️ 全平台口径需先收敛 encaps-layer 的同名双写，见后端 AdminController 类注释。
   */
  tenantTotal: number
  /** 工作空间数（当前调用租户下） */
  workspaceTotal: number
  /** 配额数（当前调用租户下） */
  quotaTotal: number
  /** 项目数（当前调用租户下；由 encaps-tenant 自己写入，值正确） */
  projectTotal: number

  // ---------- 未接入（后端不返回，勿用 ?? 0 兜底）----------
  /** @deprecated 后端从未返回（无集群注册表）—— 界面应显示占位符 */
  clusterOnprem?: number
  /** @deprecated 后端从未返回（无集群注册表）—— 界面应显示占位符 */
  clusterCloudVm?: number
  /** @deprecated 后端从未返回（需 finops/billing 聚合）—— 界面应显示占位符 */
  monthlyRevenue?: number
  /** @deprecated 后端从未返回（需 observability 聚合）—— 界面应显示占位符 */
  alertCount?: number
  /** @deprecated 后端从未返回（需 observability 聚合）—— 界面应显示占位符 */
  alertAutoHandled?: number

  // ---------- 2026-10-13 从后端契约删除的字段（曾有假值，现永不返回）----------
  /** @deprecated 后端曾硬编码 0/1，无数据模型；2026-10-13 已删 */
  tenantExternal?: number
  /** @deprecated 后端曾硬编码 1，无数据模型；2026-10-13 已删 */
  tenantInternal?: number
  /** @deprecated 后端曾硬编码 1，无集群注册表；2026-10-13 已删 */
  clusterTotal?: number
  /** @deprecated 后端曾硬编码 1，无集群注册表；2026-10-13 已删 */
  clusterXinchuang?: number
  /** @deprecated 后端曾结构性恒 0（跨 schema 空表）；2026-10-13 已删 */
  assetTotal?: number
  /** @deprecated 后端曾结构性恒 0（跨 schema 空表）；2026-10-13 已删 */
  apiTotal?: number
  /** @deprecated 后端曾结构性恒 0（跨 schema 空表）；2026-10-13 已删 */
  datasourceTotal?: number
  /** @deprecated 后端曾结构性恒 0（本服务无写入方）；2026-10-13 已删 */
  syncTaskTotal?: number
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
  /** 节点数量 */
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
