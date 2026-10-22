/**
 * Karmada 多集群页面的取值渲染 helper。
 *
 * 与 views/exchange/format.ts 同族：Element Plus 的测试 stub 故意不渲染表格列的
 * scoped slot（见 src/test-setup.ts 的 ElTableColumnStub），"缺值渲染成 — 还是 NaN"
 * 这类真正的风险点在视图里测不到，抽出来才有断言的地方。
 *
 * 这里多一层必要性的根子在后端：failover-api 把 spec / status / allocation / weights /
 * backupClusters / affectedWorkloads 全存成 text 列，返回的是**JSON 字符串**而不是对象
 * （model/failover.go、model/override_policy.go 的 gorm tag `type:text`）。
 * 页面上任何"直接当对象取键"的写法都会拿到 undefined，所以解析必须集中且容错。
 */
import type { TagType } from '../exchange/format'

/** 后端 text 列里的 JSON 对象；解析不了就返回空对象，不让整页因一行脏数据崩掉 */
export function parseJsonRecord(json: string | null | undefined): Record<string, unknown> {
  if (!json) return {}
  try {
    const v = JSON.parse(json) as unknown
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

/** 后端 text 列里的 JSON 数组（如 backupClusters / affectedWorkloads） */
export function parseJsonList(json: string | null | undefined): string[] {
  if (!json) return []
  try {
    const v = JSON.parse(json) as unknown
    return Array.isArray(v) ? v.map((x) => String(x)) : []
  } catch {
    return []
  }
}

/** 数值型映射（weights / allocation）：非数值的键直接丢掉，避免把 "abc" 画成 NaN 柱 */
export function parseJsonNumberRecord(json: string | null | undefined): Record<string, number> {
  const rec = parseJsonRecord(json)
  const out: Record<string, number> = {}
  for (const [k, v] of Object.entries(rec)) {
    if (typeof v === 'number' && Number.isFinite(v)) out[k] = v
  }
  return out
}

/** `{a:1,b:2}` → `a=1, b=2`；空对象给破折号，不显示空白单元格 */
export function formatPairs(rec: Record<string, unknown>): string {
  const entries = Object.entries(rec)
  if (entries.length === 0) return '—'
  return entries.map(([k, v]) => `${k}=${String(v)}`).join(', ')
}

/** 权重/分配 JSON 串一步渲染成 `a=1, b=2`；解析不了给破折号 */
export function formatJsonPairs(json: string | null | undefined): string {
  return formatPairs(parseJsonRecord(json))
}

/** 集群 CPU/内存负载：Go 是 float64，缺值或非法值一律 '—' 而不是 0（0 是"真的空闲"） */
export function loadPercent(v: number | null | undefined): string {
  if (typeof v !== 'number' || !Number.isFinite(v)) return '—'
  return `${v.toFixed(1)}%`
}

/** 迁移耗时：后端单位是毫秒，0 也要显示成 0ms 而不是破折号（0 是合法测量值） */
export function durationMs(v: number | null | undefined): string {
  if (typeof v !== 'number' || !Number.isFinite(v)) return '—'
  return `${v}ms`
}

/** 可用/最大副本：任一缺值都给破折号，不出现 `3 / NaN` */
export function replicaPair(
  available: number | null | undefined,
  max: number | null | undefined
): string {
  if (typeof available !== 'number' || typeof max !== 'number') return '—'
  if (!Number.isFinite(available) || !Number.isFinite(max)) return '—'
  return `${available} / ${max}`
}

/** 秒数 → `30s`；缺值破折号（FailoverPolicy 的三个时间列都用它） */
export function seconds(v: number | null | undefined): string {
  if (typeof v !== 'number' || !Number.isFinite(v)) return '—'
  return `${v}s`
}

/**
 * 布尔 → '是/否' 的**语言无关**表示：调用方传 t() 出来的文案，
 * helper 本身不碰 i18n（模块级纯函数，方便单测）。
 */
export function boolLabel(v: boolean, yes: string, no: string): string {
  return v ? yes : no
}

/**
 * 集群健康状态 → 标签色。
 *
 * 键必须写 model/failover.go 里 ClusterStatus* 常量的实际值（全小写），
 * 因为 StatusTag.vue 是 `statusMap[status] ?? 'info'`——精确匹配、不转大小写。
 * 写大写的键等于整张表作废，down 与 healthy 会长得一样。
 */
export const KARMADA_CLUSTER_STATUS_MAP: Record<string, TagType> = {
  healthy: 'success',
  degraded: 'warning',
  down: 'danger'
}

/** 迁移事件状态 → 标签色（model/failover.go EventStatus*：pending/running/succeeded/failed/rolled_back） */
export const KARMADA_EVENT_STATUS_MAP: Record<string, TagType> = {
  pending: 'warning',
  running: 'info',
  succeeded: 'success',
  failed: 'danger',
  rolled_back: 'danger'
}
