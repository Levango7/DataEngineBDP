import type { TenantSettlementRow } from '@/api/exchange'

/**
 * 流通看板的纯聚合层。
 *
 * 单独成文件而不是写在 .vue 里，是为了能被 vitest 直接喂数据断言——
 * 图表页的"看板上写着什么"必须由这些数字决定，而 ECharts 画布在 jsdom 里断不到。
 * 数据源固定为租户级 `GET /settlements`（信封行），不依赖逐资产扇出：
 * 后者自 PR #673 起是 owner-or-admin，非提供方拿不到别家数据。
 */

export interface OverviewSummary {
  /** 结算单条数（信封里的 settlements 长度，不按 id 去重） */
  settlementCount: number
  /** 出现过的账期数 */
  periodCount: number
  /** 涉及多少个不同资产 */
  assetCount: number
  totalAmount: number
  providerRevenue: number
  platformRevenue: number
}

export interface PeriodPoint {
  period: string
  providerRevenue: number
  platformRevenue: number
}

export interface AssetRevenue {
  assetId: string
  providerRevenue: number
  settlementCount: number
}

const ZERO = 0

/** 金额求和：null/undefined/NaN 一律按 0，不让一个坏值把整块看板打成 NaN */
function sum(rows: TenantSettlementRow[], pick: (row: TenantSettlementRow) => number): number {
  return rows.reduce((acc, row) => {
    const v = pick(row)
    return acc + (Number.isFinite(v) ? v : ZERO)
  }, ZERO)
}

export function summarize(rows: TenantSettlementRow[]): OverviewSummary {
  return {
    settlementCount: rows.length,
    periodCount: new Set(rows.map((r) => r.period)).size,
    assetCount: new Set(rows.map((r) => r.assetId)).size,
    totalAmount: sum(rows, (r) => r.totalAmount),
    providerRevenue: sum(rows, (r) => r.providerRevenue),
    platformRevenue: sum(rows, (r) => r.platformRevenue)
  }
}

/**
 * 账期趋势：按 period 升序（`2026-09 < 2026-10` 这种字符串序即字典序）。
 * 空 period 归到 ''，不静默丢弃——丢掉会让"趋势图合计"与 KPI 对不上账。
 */
export function trendByPeriod(rows: TenantSettlementRow[]): PeriodPoint[] {
  const by = new Map<string, PeriodPoint>()
  for (const r of rows) {
    const key = r.period ?? ''
    const cur = by.get(key) ?? { period: key, providerRevenue: 0, platformRevenue: 0 }
    cur.providerRevenue += Number.isFinite(r.providerRevenue) ? r.providerRevenue : 0
    cur.platformRevenue += Number.isFinite(r.platformRevenue) ? r.platformRevenue : 0
    by.set(key, cur)
  }
  return [...by.values()].sort((a, b) => (a.period < b.period ? -1 : a.period > b.period ? 1 : 0))
}

/**
 * Top N 资产（按提供方收益降序）。同分时按 assetId 升序，保证同一份数据
 * 每次渲染顺序一致——榜单跳动会让"排第几"这件事不可复现。
 */
export function topAssets(rows: TenantSettlementRow[], n = 5): AssetRevenue[] {
  const by = new Map<string, AssetRevenue>()
  for (const r of rows) {
    const cur = by.get(r.assetId) ?? { assetId: r.assetId, providerRevenue: 0, settlementCount: 0 }
    cur.providerRevenue += Number.isFinite(r.providerRevenue) ? r.providerRevenue : 0
    cur.settlementCount += 1
    by.set(r.assetId, cur)
  }
  return [...by.values()]
    .sort((a, b) => b.providerRevenue - a.providerRevenue || (a.assetId < b.assetId ? -1 : 1))
    .slice(0, n)
}

/** 状态分布（结算单口径）：settled/pending/failed… 计数，供"分成结构/健康度"卡片 */
export function statusBreakdown(rows: TenantSettlementRow[]): Record<string, number> {
  const out: Record<string, number> = {}
  for (const r of rows) {
    const key = r.status ?? 'unknown'
    out[key] = (out[key] ?? 0) + 1
  }
  return out
}
