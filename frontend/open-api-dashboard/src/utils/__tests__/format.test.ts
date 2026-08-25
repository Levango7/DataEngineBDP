import { describe, it, expect } from 'vitest'
import { formatTime, summarizeMetrics } from '../format.js'

describe('open-api formatTime', () => {
  it('1h 档输出 HH:mm', () => {
    const ts = new Date(2026, 7, 5, 9, 5).toISOString()
    expect(formatTime(ts, '1h')).toBe('09:05')
  })

  it('24h 档输出 MM-DD HH:00', () => {
    const ts = new Date(2026, 7, 5, 15, 42).toISOString()
    expect(formatTime(ts, '24h')).toBe('08-05 15:00')
  })

  it('7d 档输出 MM-DD', () => {
    const ts = new Date(2026, 7, 5, 15, 42).toISOString()
    expect(formatTime(ts, '7d')).toBe('08-05')
  })

  it('30d 档输出 MM-DD 且补零', () => {
    const ts = new Date(2026, 0, 9, 3, 1).toISOString()
    expect(formatTime(ts, '30d')).toBe('01-09')
  })

  it('空时间戳与非法时间戳返回空串', () => {
    expect(formatTime('', '1h')).toBe('')
    expect(formatTime('not-a-date', '24h')).toBe('')
  })
})

describe('open-api summarizeMetrics', () => {
  it('对完整列表汇总 totalCost 与状态码分布', () => {
    const list = [
      { totalCost: 1.25, successCount: 90, errorCount: 10 },
      { totalCost: 0.75, successCount: 95, errorCount: 5 },
      {}
    ]
    expect(summarizeMetrics(list)).toEqual({ totalCost: 2, success: 185, error: 15 })
  })

  it('空列表与非列表输入返回零值', () => {
    expect(summarizeMetrics([])).toEqual({ totalCost: 0, success: 0, error: 0 })
    expect(summarizeMetrics(undefined)).toEqual({ totalCost: 0, success: 0, error: 0 })
  })
})
