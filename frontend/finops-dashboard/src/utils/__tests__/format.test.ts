import { describe, it, expect } from 'vitest'
import { formatTime } from '../format'

describe('finops formatTime', () => {
  it('HOUR 粒度输出 MM-DD HH:00', () => {
    const ts = new Date(2026, 7, 5, 9, 0).toISOString()
    expect(formatTime(ts, 'HOUR')).toBe('08-05 09:00')
  })

  it('DAY 粒度输出 MM-DD', () => {
    const ts = new Date(2026, 7, 5, 23, 59).toISOString()
    expect(formatTime(ts, 'DAY')).toBe('08-05')
  })

  it('MONTH 粒度输出 YYYY-MM', () => {
    const ts = new Date(2026, 11, 31, 10, 30).toISOString()
    expect(formatTime(ts, 'MONTH')).toBe('2026-12')
  })

  it('单位数月日时补零', () => {
    const ts = new Date(2026, 0, 9, 3, 0).toISOString()
    expect(formatTime(ts, 'HOUR')).toBe('01-09 03:00')
    expect(formatTime(ts, 'DAY')).toBe('01-09')
    expect(formatTime(ts, 'MONTH')).toBe('2026-01')
  })

  it('非法时间戳返回空串', () => {
    expect(formatTime('not-a-date', 'HOUR')).toBe('')
  })
})
