/**
 * Admin.vue 单元测试
 *
 * 【回归守卫】运营后台 KPI 的展示口径 —— "没有数据"必须与"数据是 0"可区分。
 *
 * 2026-10-13 契约收口后，后端 `/api/v1/admin/kpi`（`AdminController#kpi()`）
 * 只返回 4 个按租户口径的真实 count：
 *   `tenantTotal` / `workspaceTotal` / `quotaTotal` / `projectTotal`
 *
 * 其余字段分两种死法，界面**一律显示占位符 `—`**，禁止用 `?? 0` 掩盖：
 *   1. 后端从未返回：`monthlyRevenue`、`alertCount`、`alertAutoHandled`、
 *      `clusterOnprem`、`clusterCloudVm`（需跨服务聚合，尚未实现）
 *   2. 后端曾返回但是编造的，2026-10-13 已从契约删除：
 *      `tenantExternal` / `tenantInternal` / `clusterTotal` / `clusterXinchuang`
 *      （硬编码）、`assetTotal` / `apiTotal` / `datasourceTotal` / `syncTaskTotal`
 *      （结构性恒 0：查询落到本服务 schema 的空副本表）
 *
 * 此前界面写 `?? 0`，会渲染出一个**看起来很确定的 0**，运维据此判断
 * "本月没有营收、底座没有告警" —— 这是界面在说谎。
 *
 * 本用例把"未接入 ⇒ 显示占位符"钉死，防止有人图省事再改回 `?? 0`。
 * 若将来后端真的接入了某些字段，本用例会失败 —— 那时**应同步删掉对应守卫**，
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
 * 后端**真实**响应形状 —— 照抄收口后的 `AdminController#kpi()`，只有 4 个字段。
 *
 * 注意：`vi.mock` 的工厂会被提升到文件顶部，不能引用外部变量，所以下面的对象
 * 必须在工厂内部**内联**；此处保留一份注释副本仅作对照。
 *
 *   { tenantTotal: 1, workspaceTotal: 2, quotaTotal: 2, projectTotal: 3 }
 */
vi.mock('@/api/admin', () => ({
  getKpi: vi.fn(() =>
    Promise.resolve({
      tenantTotal: 1,
      workspaceTotal: 2,
      quotaTotal: 2,
      projectTotal: 3
      // 无 monthlyRevenue / alertCount / alertAutoHandled / clusterOnprem / clusterCloudVm
      // 无 tenantExternal / tenantInternal / clusterTotal / clusterXinchuang（2026-10-13 已删）
      // 无 assetTotal / apiTotal / datasourceTotal / syncTaskTotal（2026-10-13 已删）
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

  /** 按标题文案找到对应卡片，返回该卡片的 .meta 文本 */
  function metaTextOf(wrapper: Awaited<ReturnType<typeof mountAdmin>>, title: string): string {
    const card = wrapper
      .findAll('.card')
      .find((c) => c.find('h3').exists() && c.find('h3').text() === title)
    if (!card) throw new Error(`未找到标题为「${title}」的卡片`)
    return card.find('.meta').text()
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
    const meta = metaTextOf(wrapper, '底座告警')
    expect(meta).toContain('—')
    expect(meta).not.toContain('0')
  })

  it('集群实例卡：总数与信创数后端已无数据模型 ⇒ 整体显示占位符', async () => {
    const wrapper = await mountAdmin()
    // 主数值：clusterTotal 已于 2026-10-13 从契约删除（曾硬编码 1）
    expect(kpiTextOf(wrapper, '集群实例')).toBe('—')
    // meta：信创 / 本地 / 云VM 三者全部未接入
    const meta = metaTextOf(wrapper, '集群实例')
    expect(meta).toBe('信创 — · 本地 — · 云VM —')
  })

  it('租户卡：总数是真实 count，外部/内部已从契约删除 ⇒ 显示占位符', async () => {
    const wrapper = await mountAdmin()
    // tenantTotal = 1 是后端真实返回（tenantRepository.count()），不是硬编码
    expect(kpiTextOf(wrapper, '租户总数')).toBe('1')
    // 外部/内部后端已删除 ⇒ 占位符
    const meta = metaTextOf(wrapper, '租户总数')
    expect(meta).toBe('外部 — · 内部 —')
  })

  it('占位符只兜「缺失」，不篡改后端返回的真实 0（防止有人改成恒显 —）', async () => {
    // 模拟将来后端真的接入了这些字段并返回真实 0
    const adminApi = vi.mocked(await import('@/api/admin'))
    adminApi.getKpi.mockResolvedValueOnce({
      tenantTotal: 1,
      workspaceTotal: 2,
      quotaTotal: 2,
      projectTotal: 3,
      clusterOnprem: 0,
      clusterCloudVm: 0
    } as never)

    const wrapper = await mountAdmin()

    // `?? NOT_WIRED` 只兜 nullish ⇒ 后端真给了 0 就该显示 0，不能被强行变成 —
    const meta = metaTextOf(wrapper, '集群实例')
    expect(meta).toBe('信创 — · 本地 0 · 云VM 0')
    // 仍无数据模型的信创数保持占位符
    expect(meta).toContain('信创 —')
  })
})
