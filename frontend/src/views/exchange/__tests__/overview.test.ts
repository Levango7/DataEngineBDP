/**
 * 流通看板（/exchange-overview）的测试。
 *
 * 两处必须在的地方：
 *  1. 聚合层是纯函数，直接喂数据断言——ECharts 画在 canvas 上，jsdom 里断不到柱高，
 *     但"看板上的数字从哪来"完全可以断言（KPI 与表格读的就是这些函数的输出）；
 *  2. 错误态与空态必须分流。台账 #78 续 2 记过一次假绿：旧页面的 404 被渲染成
 *     "暂无审计日志"，于是"后端没打到"与"确实没数据"在 DOM 上长得一样，
 *     当时那句"空态文案正确"根本没证明通路。这里把这条钉成断言。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import rootZh from '@/i18n/locales/zh-CN.json'
import zh from '@/i18n/locales/modules/exchange.zh-CN.json'
import ExchangeOverview from '@/views/exchange/ExchangeOverview.vue'
import type { TenantSettlementRow } from '@/api/exchange'
import { statusBreakdown, summarize, topAssets, trendByPeriod } from '@/views/exchange/overview'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: { 'zh-CN': { ...(rootZh as object), ...(zh as object) } as never }
})

const { listTenantSettlementsMock } = vi.hoisted(() => ({ listTenantSettlementsMock: vi.fn() }))

vi.mock('@/api/exchange', async () => {
  const actual = await vi.importActual<typeof import('@/api/exchange')>('@/api/exchange')
  return { ...actual, listTenantSettlements: listTenantSettlementsMock }
})

// ECharts 在 jsdom 下没有真实画布；这里只要求它别把测试拖死，
// 并记录 setOption 被调过（渲染链路真的走到了图表）。
const setOptionSpy = vi.fn()
vi.mock('echarts', () => ({
  init: () => ({ setOption: setOptionSpy, resize: vi.fn(), dispose: vi.fn() })
}))

function row(partial: Partial<TenantSettlementRow>): TenantSettlementRow {
  return {
    id: 's-1',
    assetId: 'asset-1',
    period: '2026-10',
    status: 'settled',
    totalAmount: 10,
    providerRevenue: 8,
    platformRevenue: 2,
    settledAt: '2026-10-05T00:00:00Z',
    ...partial
  }
}

describe('overview 聚合层', () => {
  it('summarize 去重账期与资产，并把坏值按 0 处理而不是打成 NaN', () => {
    const rows = [
      row({
        assetId: 'a',
        period: '2026-09',
        providerRevenue: 5,
        platformRevenue: 1,
        totalAmount: 6
      }),
      row({
        assetId: 'a',
        period: '2026-10',
        providerRevenue: 7,
        platformRevenue: 3,
        totalAmount: 10
      }),
      row({
        assetId: 'b',
        period: '2026-10',
        providerRevenue: Number.NaN,
        platformRevenue: 4,
        totalAmount: 4
      })
    ]
    const s = summarize(rows)
    expect(s.settlementCount).toBe(3)
    expect(s.periodCount).toBe(2)
    expect(s.assetCount).toBe(2)
    expect(s.providerRevenue).toBe(12)
    expect(s.platformRevenue).toBe(8)
    expect(s.totalAmount).toBe(20)
    expect(Number.isNaN(s.providerRevenue)).toBe(false)
  })

  it('trendByPeriod 按账期升序聚合同一期多单，空账期不静默丢', () => {
    const t = trendByPeriod([
      row({ period: '2026-10', providerRevenue: 3 }),
      row({ period: '2026-09', providerRevenue: 4 }),
      row({ period: '2026-10', providerRevenue: 5 }),
      row({ period: '' as unknown as string, providerRevenue: 1 })
    ])
    expect(t.map((p) => p.period)).toEqual(['', '2026-09', '2026-10'])
    expect(t[2]).toEqual({ period: '2026-10', providerRevenue: 8, platformRevenue: 4 })
    expect(trendByPeriod([])).toEqual([])
  })

  it('topAssets 按收益降序、同分按 assetId 升序，且截到 N', () => {
    const rows = [
      row({ assetId: 'zzz', providerRevenue: 9 }),
      row({ assetId: 'aaa', providerRevenue: 9 }),
      row({ assetId: 'mmm', providerRevenue: 12 }),
      row({ assetId: 'small', providerRevenue: 1 })
    ]
    const top = topAssets(rows, 3)
    expect(top.map((a) => a.assetId)).toEqual(['mmm', 'aaa', 'zzz'])
    expect(top[0].providerRevenue).toBe(12)
    expect(top[0].settlementCount).toBe(1)
    expect(top.length).toBe(3)
  })

  it('statusBreakdown 计数不合并语义不同的状态', () => {
    const s = statusBreakdown([
      row({ status: 'settled' }),
      row({ status: 'settled' }),
      row({ status: 'failed' })
    ])
    expect(s).toEqual({ settled: 2, failed: 1 })
  })
})

describe('ExchangeOverview 页面', () => {
  beforeEach(() => {
    listTenantSettlementsMock.mockReset()
    setOptionSpy.mockClear()
  })

  it('成功态：KPI 数字来自聚合层，且图表收到过 setOption', async () => {
    listTenantSettlementsMock.mockResolvedValue({
      tenant: 'tenant-a',
      count: 2,
      settlements: [
        row({
          assetId: 'asset-a1',
          period: '2026-09',
          providerRevenue: 6,
          platformRevenue: 2,
          totalAmount: 8
        }),
        row({
          assetId: 'asset-a1',
          period: '2026-10',
          providerRevenue: 14,
          platformRevenue: 6,
          totalAmount: 20
        })
      ]
    })
    const w = mount(ExchangeOverview, {
      global: { plugins: [i18n], stubs: { ElTable: true, ElTableColumn: true } }
    })
    await flushPromises()

    expect(w.get('[data-test="kpi-settlement-count"]').text()).toBe('2')
    expect(w.get('[data-test="kpi-provider-revenue"]').text()).toBe('20.00')
    expect(w.text()).toContain('流通看板')
    expect(w.find('[data-test="overview-error"]').exists()).toBe(false)
    expect(w.find('[data-test="chart-trend"]').exists()).toBe(true)
    expect(setOptionSpy).toHaveBeenCalledTimes(2)
    // 取数走的是租户级端点，不是逐资产扇出
    expect(listTenantSettlementsMock).toHaveBeenCalledWith({ limit: 500 })
  })

  it('失败态：必须出错误提示且不渲染空态文案', async () => {
    listTenantSettlementsMock.mockRejectedValue(new Error('404 Not Found'))
    const w = mount(ExchangeOverview, {
      global: { plugins: [i18n], stubs: { ElTable: true, ElTableColumn: true } }
    })
    await flushPromises()

    const alert = w.find('[data-test="overview-error"]')
    expect(alert.exists()).toBe(true)
    expect(w.text()).toContain('加载失败：404 Not Found')
    expect(w.text()).not.toContain('该租户暂无结算记录')
    expect(w.find('[data-test="kpi-settlement-count"]').exists()).toBe(false)
    expect(setOptionSpy).not.toHaveBeenCalled()
  })

  it('空数据态：出空态文案、不出错误提示', async () => {
    listTenantSettlementsMock.mockResolvedValue({
      tenant: 'tenant-empty',
      count: 0,
      settlements: []
    })
    const w = mount(ExchangeOverview, {
      global: { plugins: [i18n], stubs: { ElTable: true, ElTableColumn: true } }
    })
    await flushPromises()

    expect(w.text()).toContain('该租户暂无结算记录')
    expect(w.find('[data-test="overview-error"]').exists()).toBe(false)
  })
})
