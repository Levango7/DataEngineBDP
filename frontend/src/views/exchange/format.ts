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

/** 审计/结算状态 → el-tag 类型；未知值兜到 info，不猜语义 */
export const EXCHANGE_STATUS_MAP: Record<string, TagType> = {
  SETTLED: 'success',
  SUCCESS: 'success',
  PENDING: 'warning',
  PROCESSING: 'warning',
  FAILED: 'danger',
  ERROR: 'danger'
}
