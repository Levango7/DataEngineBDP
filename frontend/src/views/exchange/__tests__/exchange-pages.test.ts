/**
 * 收编进主应用的流通页面测试。
 *
 * 覆盖两处真实风险：
 *  1. 加载失败必须进错误态并给重试入口，不得回落到任何假数据（与 APIMarket.test.ts 同族）；
 *  2. 取值渲染（金额 / 分成比例 / 时间 / 短 ID）在缺值时必须给 '—'，不能出现 NaN——
 *     Element Plus 的测试 stub 故意不渲染表格列的 scoped slot（见 src/test-setup.ts 的
 *     ElTableColumnStub），这类逻辑只能在 format.ts 上断言，故拆出来直测。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createI18n } from 'vue-i18n'
import rootZh from '@/i18n/locales/zh-CN.json'
import zh from '@/i18n/locales/modules/exchange.zh-CN.json'
import AuditLogs from '@/views/exchange/AuditLogs.vue'
import Settlements from '@/views/exchange/Settlements.vue'
import { EXCHANGE_STATUS_MAP, formatTime, money, share, shortId } from '@/views/exchange/format'

// 与运行时一致：框架级词条（common.*）+ 页面级模块词条。
// 只装模块词条会让 EmptyState 里的 t('common.empty') 退化成裸 key，
// 那时"不许出现裸 key"这条断言测的就不是页面而是夹具了。
const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: { 'zh-CN': { ...(rootZh as object), ...(zh as object) } as never }
})

const {
  listAuditLogsMock,
  verifyIntegrityMock,
  listSettlementsMock,
  listAllocationsMock,
  allocateMock,
  settleMock,
  getAuditLogMock
} = vi.hoisted(() => ({
  listAuditLogsMock: vi.fn(),
  verifyIntegrityMock: vi.fn(),
  listSettlementsMock: vi.fn(),
  listAllocationsMock: vi.fn(),
  allocateMock: vi.fn(),
  settleMock: vi.fn(),
  getAuditLogMock: vi.fn()
}))

vi.mock('@/api/exchange', async (importOriginal) => {
  // AUDIT_ACTIONS 取真实导出的常量：它是后端 AuditAction 枚举的镜像，
  // 若在测试里另抄一份，枚举增删就只会红在浏览器里而不是红在用例里。
  const actual = await importOriginal<typeof import('@/api/exchange')>()
  return {
    ...actual,
    listAuditLogs: listAuditLogsMock,
    verifyAuditIntegrity: verifyIntegrityMock,
    getAuditLog: getAuditLogMock,
    listAssetSettlements: listSettlementsMock,
    listAssetAllocations: listAllocationsMock,
    allocateAsset: allocateMock,
    settleAsset: settleMock
  }
})

/** EP 组件在测试里是 slotStub，class 即组件名 */
function buttonByText(w: ReturnType<typeof mount>, text: string) {
  return w.findAll('.el-button').find((b) => b.text().includes(text))
}

describe('exchange/format.ts（缺值不能变 NaN）', () => {
  it('金额：正常值两位千分位，null/undefined/NaN/Infinity 一律破折号', () => {
    expect(money(1234.5, 'zh-CN')).toBe('1,234.50')
    expect(money(null, 'zh-CN')).toBe('—')
    expect(money(undefined, 'zh-CN')).toBe('—')
    expect(money(Number.NaN, 'zh-CN')).toBe('—')
    expect(money(Number.POSITIVE_INFINITY, 'zh-CN')).toBe('—')
  })

  it('分成比例：0.8/0.2 → 80% : 20%；缺值破折号', () => {
    expect(share(0.8, 0.2)).toBe('80% : 20%')
    expect(share(undefined, 0.2)).toBe('—')
    expect(share(0.8, null)).toBe('—')
  })

  it('时间与短 ID：解析不了的原文回显，不吞数据也不显示 Invalid Date', () => {
    expect(formatTime('not-a-date', 'zh-CN')).toBe('not-a-date')
    expect(formatTime(null, 'zh-CN')).toBe('—')
    expect(shortId('settle-0001-abcd')).toBe('settle-0')
    expect(shortId('')).toBe('—')
  })

  it('状态映射只认后端枚举，未知值不猜语义', () => {
    expect(EXCHANGE_STATUS_MAP.SETTLED).toBe('success')
    expect(EXCHANGE_STATUS_MAP.FAILED).toBe('danger')
    expect(EXCHANGE_STATUS_MAP.WHATEVER_NEW).toBeUndefined()
  })
})

describe('exchange/AuditLogs.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    verifyIntegrityMock.mockResolvedValue({
      totalLogs: 12,
      verified: true,
      brokenAt: null,
      message: 'OK'
    })
  })

  it('挂载即拉取一次审计日志', async () => {
    listAuditLogsMock.mockResolvedValue([])
    mount(AuditLogs, { global: { plugins: [i18n] } })
    await flushPromises()
    expect(listAuditLogsMock).toHaveBeenCalledTimes(1)
  })

  it('页面上不得出现裸词条 key（路径写错时 i18n 会直接印 key 本身）', async () => {
    listAuditLogsMock.mockResolvedValue([])
    const w = mount(AuditLogs, { global: { plugins: [i18n] } })
    await flushPromises()
    expect(w.text()).not.toMatch(/exchange\.[a-zA-Z]/)
    expect(w.text()).toContain('暂无符合条件的审计日志')
  })

  it('加载失败 ⇒ 错误态带原因与重试入口', async () => {
    listAuditLogsMock.mockRejectedValue(new Error('boom'))
    const w = mount(AuditLogs, { global: { plugins: [i18n] } })
    await flushPromises()
    const alert = w.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('boom')
    listAuditLogsMock.mockResolvedValue([])
    const retry = buttonByText(w, '重试')
    expect(retry).toBeTruthy()
    await retry!.trigger('click')
    await flushPromises()
    expect(listAuditLogsMock).toHaveBeenCalledTimes(2)
  })

  it('哈希链断裂 ⇒ 高亮断裂点与后端消息，而不是继续说"完整"', async () => {
    listAuditLogsMock.mockResolvedValue([])
    verifyIntegrityMock.mockResolvedValue({
      totalLogs: 9,
      verified: false,
      brokenAt: 'log-broken-1',
      message: 'hash mismatch'
    })
    const w = mount(AuditLogs, { global: { plugins: [i18n] } })
    await flushPromises()
    const banner = w.find('[role="status"]')
    expect(banner.exists()).toBe(true)
    expect(banner.classes()).toContain('is-broken')
    expect(banner.text()).toContain('log-broken-1')
    expect(banner.text()).toContain('hash mismatch')
  })
})

describe('exchange/Settlements.vue', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('未输入资产 ID ⇒ 不发请求，并给出需要输入的空态文案', async () => {
    const w = mount(Settlements, { global: { plugins: [i18n] } })
    await flushPromises()
    expect(listSettlementsMock).not.toHaveBeenCalled()
    expect(w.text()).toContain('请先输入资产 ID')
    expect(w.text()).not.toMatch(/exchange\.[a-zA-Z]/)
  })

  it('输入资产 ID 后查询 ⇒ 结算与分账各拉一次并带上该 ID', async () => {
    listSettlementsMock.mockResolvedValue([])
    listAllocationsMock.mockResolvedValue([])
    const w = mount(Settlements, { global: { plugins: [i18n] } })
    w.findComponent({ name: 'el-input' }).vm.$emit('update:modelValue', 'ast-9')
    await flushPromises()
    await buttonByText(w, '查询')!.trigger('click')
    await flushPromises()
    expect(listSettlementsMock).toHaveBeenCalledWith('ast-9')
    expect(listAllocationsMock).toHaveBeenCalledWith('ast-9')
  })

  it('查询失败 ⇒ 错误态显示原因', async () => {
    listSettlementsMock.mockRejectedValue(new Error('net down'))
    listAllocationsMock.mockRejectedValue(new Error('net down'))
    const w = mount(Settlements, { global: { plugins: [i18n] } })
    w.findComponent({ name: 'el-input' }).vm.$emit('update:modelValue', 'ast-8')
    await flushPromises()
    await buttonByText(w, '查询')!.trigger('click')
    await flushPromises()
    const alert = w.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('net down')
  })

  it('触发结算 ⇒ 打 POST /assets/{id}/settle 并回填列表（子看板有这一步，收编时不能漏）', async () => {
    listSettlementsMock.mockResolvedValue([])
    listAllocationsMock.mockResolvedValue([])
    settleMock.mockResolvedValue({ id: 'st-1' })
    const w = mount(Settlements, { global: { plugins: [i18n] } })
    w.findComponent({ name: 'el-input' }).vm.$emit('update:modelValue', 'ast-7')
    await flushPromises()
    // 初始未查询时列表为空，结算按钮只受"有没有资产 ID"约束（分账按钮才要求已有结算单）
    const settleBtn = buttonByText(w, '触发结算')
    expect(settleBtn, '找不到「触发结算」按钮').toBeTruthy()
    expect(settleBtn!.attributes('disabled')).toBeUndefined()
    await settleBtn!.trigger('click')
    await flushPromises()
    expect(settleMock).toHaveBeenCalledWith('ast-7')
    // 结算完必须重拉，否则用户看不到刚生成的结算单
    expect(listSettlementsMock).toHaveBeenCalledWith('ast-7')
  })

  it('结算失败（非 admin 得到 403 等）⇒ 原样进错误态，不静默', async () => {
    listSettlementsMock.mockResolvedValue([])
    listAllocationsMock.mockResolvedValue([])
    settleMock.mockRejectedValue(new Error('403 Forbidden'))
    const w = mount(Settlements, { global: { plugins: [i18n] } })
    w.findComponent({ name: 'el-input' }).vm.$emit('update:modelValue', 'ast-6')
    await flushPromises()
    await buttonByText(w, '触发结算')!.trigger('click')
    await flushPromises()
    const alert = w.find('[role="alert"]')
    expect(alert.exists()).toBe(true)
    expect(alert.text()).toContain('403 Forbidden')
  })
})

describe('exchange 的枚举镜像（前端选项必须是后端闭包的子集）', () => {
  it('AUDIT_ACTIONS 与 models/base.py::AuditAction 的 11 个取值逐一对齐', async () => {
    const { AUDIT_ACTIONS } = await import('@/api/exchange')
    expect([...AUDIT_ACTIONS]).toEqual([
      'register',
      'audit',
      'publish',
      'subscribe',
      'download',
      'invoke',
      'deliver',
      'settle',
      'allocate',
      'offline',
      'relist'
    ])
  })
})
