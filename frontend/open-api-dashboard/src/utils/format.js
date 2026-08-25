export function formatTime(ts, range) {
  if (!ts) return ''
  const d = new Date(ts)
  if (Number.isNaN(d.getTime())) return ''
  const pad = (n) => String(n).padStart(2, '0')
  const mm = pad(d.getMonth() + 1)
  const dd = pad(d.getDate())
  if (range === '1h') return `${pad(d.getHours())}:${pad(d.getMinutes())}`
  if (range === '24h') return `${mm}-${dd} ${pad(d.getHours())}:00`
  return `${mm}-${dd}`
}

export function summarizeMetrics(list) {
  let totalCost = 0
  let success = 0
  let error = 0
  for (const m of list || []) {
    totalCost += m.totalCost || 0
    success += m.successCount || 0
    error += m.errorCount || 0
  }
  return { totalCost, success, error }
}
