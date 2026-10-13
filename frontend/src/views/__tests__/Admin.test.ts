/**
 * Admin.vue 单元测试
 *
 * 【回归守卫】后端 `/api/v1/admin/kpi`（`AdminController#kpi()`）目前**不返回**
 * `monthlyRevenue` / `alertCount` / `alertAutoHandled` / `clusterOnprem` / `clusterCloudVm`
 * —— 这些值需要跨服务聚合（营收→finops/billing、告警→observability），尚未实现。
 *
 * 界面此前对这些位置写的是 `?? 0`，会渲染出一个**看起来很确定的 0**，
 * 运维据此判断"本月没有营收、底座没有告警" —— 这是界面在说谎。
 *
 * 本用例把"未接入 ⇒ 显示占位符 `—`"钉死，防止有人图省事再改回 `?? 0`。
 * 若将来后端真的接入了这些字段，本用例会失败 —— 那时**应同步删掉本守卫**，
 * 而不是改断言去迁就。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import adminZh from '@/i18n/locales/modules/admin.zh-CN.json'
import adminEn from '@/i18n/locales/modules/admin.en-US.json'
import commonZh from '@/i18n/locales/zh-CN.json'
import commonEn from '@/i18n/locales/en-US.json'
import Admin from '../Admin.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': { ...(commonZh as object), ...(adminZh as object) },
    'en-US': { ...(commonEn as object), ...(adminEn as object) }
  }
})

/**
 * 后端**真实**响应形状 —— 刻意不含 monthlyRevenue / alertCount / alertAutoHandled /
 * clusterOnprem / clusterCloudVm。照抄 `AdminController#kpi()` 的 body。
 *
 * 注意：`vi.mock` 的工厂会被提升到文件顶部，不能引用外部变量，所以下面的对象
 * 必须在工厂内部**内联**；此处保留一份注释副本仅作对照。
 */
vi.mock('@/api/admin', () => ({
  getKpi: vi.fn(() =>
    Promise.resolve({
      tenantTotal: 1,
      tenantExternal: 0,
      tenantInternal: 1,
      clusterTotal: 1,
      clusterXinchuang: 1,
      workspaceTotal: 2,
      quotaTotal: 2,
      assetTotal: 0,
      apiTotal: 0,
      datasourceTotal: 0,
      projectTotal: 3,
      syncTaskTotal: 0
      // 无 monthlyRevenue / alertCount / alertAutoHandled / clusterOnprem / clusterCloudVm
    })
  ),
  getEnvMatrix: vi.fn(() => Promise.resolve([]))
}))

describe('Admin.vue — 未接入 KPI 字段的展示口径', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  async function mountAdmin() {
    const wrapper = mount(Admin, { global: { plugins: [i18n] } })
    await flushPromises()
    return wrapper
  }

  /** 按标题文案找到对应卡片，返回该卡片的 .kpi 文本 */
  function kpiTextOf(wrapper: Awaited<ReturnType<typeof mountAdmin>>, title: string): string {
    const card = wrapper
      .findAll('.card')
      .find((c) => c.find('h3').exists() && c.find('h3').text() === title)
    if (!card) throw new Error(`未找到标题为「${title}」的卡片`)
    return card.find('.kpi').text()
  }

  /** 按标题文案找到对应卡片；找不到即抛错（同时给 TS 收窄类型） */
  function cardOf(wrapper: Awaited<ReturnType<typeof mountAdmin>>, title: string) {
    const card = wrapper
      .findAll('.card')
      .find((c) => c.find('h3').exists() && c.find('h3').text() === title)
    if (!card) throw new Error(`未找到标题为「${title}」的卡片`)
    return card
  }

  it('本月营收未接入时显示占位符，且不得显示 ¥0', async () => {
    const wrapper = await mountAdmin()
    const text = kpiTextOf(wrapper, '本月营收')
    expect(text).toBe('—')
    // 关键：不能出现被 `?? 0` 兜底出来的假零
    expect(text).not.toContain('0')
    expect(text).not.toContain('¥')
  })

  it('底座告警未接入时显示占位符，且不得显示 0', async () => {
    const wrapper = await mountAdmin()
    const text = kpiTextOf(wrapper, '底座告警')
    expect(text).toBe('—')
    expect(text).not.toContain('0')
  })

  it('告警卡的「已自动处置」meta 也必须是占位符（此前是 0）', async () => {
    const wrapper = await mountAdmin()
    const card = cardOf(wrapper, '底座告警')
    const meta = card.find('.meta').text()
    expect(meta).toContain('—')
    expect(meta).not.toContain('0')
  })

  it('集群卡的 meta 中，未接入的本地/云VM 显示占位符，已接入的信创仍显示真实值', async () => {
    const wrapper = await mountAdmin()
    const card = cardOf(wrapper, '集群实例')
    const meta = card.find('.meta').text()
    // 信创 = 1（后端真实返回）
    expect(meta).toContain('1')
    // 本地 / 云VM 未接入 ⇒ 占位符
    expect(meta).toContain('—')
    expect(meta).not.toContain('本地 0')
    expect(meta).not.toContain('云VM 0')
  })

  it('已接入字段仍正常显示（不得因本次改动被误伤）', async () => {
    const wrapper = await mountAdmin()
    expect(kpiTextOf(wrapper, '租户总数')).toBe('1')
    expect(kpiTextOf(wrapper, '集群实例')).toBe('1')
  })
})
