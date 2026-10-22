/**
 * 流通域页面的取值渲染helper。
 *
 * 单独成模块的原因：Element Plus 的测试 stub 故意不渲染表格列的 scoped slot
 * （见 src/test-setup.ts 的 ElTableColumnStub），"缺值渲染成 — 还是 NaN"这类
 * 真正的风险点在视图里测不到，抽出来才有断言的地方。
 */

/** 金额：两位小数 + 千分位；非数值一律 '—'，避免把"没有数据"显示成 0 或 NaN */
export function money(v: number | null | undefined, locale: string): string {
  if (typeof v !== 'number' || !Number.isFinite(v)) return '—'
  return v.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

/** 分成比例，如 80% : 20%；缺值 '—' */
export function share(
  provider: number | null | undefined,
  platform: number | null | undefined
): string {
  if (typeof provider !== 'number' || typeof platform !== 'number') return '—'
  if (!Number.isFinite(provider) || !Number.isFinite(platform)) return '—'
  return `${Math.round(provider * 100)}% : ${Math.round(platform * 100)}%`
}

/** 时间：无法解析时原样回显（不吞数据），可解析则按当前 locale 本地化 */
export function formatTime(iso: string | null | undefined, locale: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(locale)
}

/** 短 ID：只截前 8 位，缺值 '—' */
export function shortId(id: string | null | undefined): string {
  if (!id) return '—'
  return id.slice(0, 8)
}

export type TagType = 'success' | 'warning' | 'danger' | 'info'

/**
 * 流通/资产状态 → el-tag 类型。
 *
 * 键必须写**后端序列化出来的实际值**：`models/base.py` 的枚举全是小写
 * （success/failure/pending/settled/allocated/failed/draft/pending_audit/listed/
 * rejected/offline），而 `StatusTag.vue:26` 是 `statusMap[status] ?? 'info'`——
 * 精确匹配、不转大小写。此前这张表按大写写（SETTLED/FAILED…），于是所有标签
 * 恒落 'info'：失败与成功长得一模一样。
 */
export const EXCHANGE_STATUS_MAP: Record<string, TagType> = {
  // AuditResult
  success: 'success',
  failure: 'danger',
  pending: 'warning',
  // SettlementStatus / AllocationStatus
  settled: 'success',
  allocated: 'success',
  failed: 'danger',
  // AssetStatus
  draft: 'info',
  pending_audit: 'warning',
  listed: 'success',
  offline: 'info',
  rejected: 'danger'
}
