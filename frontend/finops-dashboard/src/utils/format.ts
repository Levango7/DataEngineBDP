export type CostGranularity = 'HOUR' | 'DAY' | 'MONTH'

export function formatTime(ts: string, granularity: string): string {
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n: number): string => String(n).padStart(2, '0')
  const mm = pad(d.getMonth() + 1)
  const dd = pad(d.getDate())
  if (granularity === 'MONTH') return `${d.getFullYear()}-${mm}`
  if (granularity === 'DAY') return `${mm}-${dd}`
  return `${mm}-${dd} ${pad(d.getHours())}:00`
}
