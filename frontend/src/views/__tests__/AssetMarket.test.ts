/**
 * AssetMarket.vue 的租户字段消费守卫（台账 #80）。
 *
 * 后端 `Asset` 自 MODEL-2 起只序列化 `tenantId`（`AliasChoices` 只作用于入参校验），
 * 而本视图曾按 `owner` 读取 ⇒ 运行时恒 undefined：卡片「提供方」空白、
 * 「我上架的资产」表恒空、两块收益 KPI 恒 ¥0.00。TS 接口与真实响应同名不同实，
 * 类型检查与既有单测都放行，所以把"响应里到底有哪个键"钉进用例。
 *
 * 夹具刻意写成**服务端响应体的原始形状**（含 `tenantId`、不含 `owner`），
 * 不用 `Asset` 类型标注——否则接口跟着组件一起写错时，测试会自证清白。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import commonZh from '@/i18n/locales/zh-CN.json'
import assetMarketZh from '@/i18n/locales/modules/assetMarket.zh-CN.json'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: { 'zh-CN': { ...commonZh, ...assetMarketZh } } as never
})

const { listAssetsMock, listSubscriptionsMock } = vi.hoisted(() => ({
  listAssetsMock: vi.fn(),
  listSubscriptionsMock: vi.fn()
}))

vi.mock('@/api/assetMarket', () => ({
  listAssets: listAssetsMock,
  listSubscriptions: listSubscriptionsMock,
  getAsset: vi.fn(),
  listAsset: vi.fn(),
  offlineAsset: vi.fn(),
  relistAsset: vi.fn(),
  subscribeAsset: vi.fn(),
  deliverAsset: vi.fn(),
  getBillingRecords: vi.fn(() => Promise.resolve([]))
}))

import AssetMarket from '../AssetMarket.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * 本仓 vitest 用 jsdom，但 `globalThis.localStorage` 实测为 undefined（probe：
 * `typeof window === 'object'` 而 `typeof localStorage === 'undefined'`），
 * 而 `stores/auth.ts` 在 store 初始化时就读 storage ⇒ 任何触到 auth store 的
 * 挂载型用例都得自带这层最小实现。只补 Web Storage 原语，不 mock 被测契约。
 */
function makeStorage(): Storage {
  const map = new Map<string, string>()
  return {
    getItem: (k: string) => map.get(k) ?? null,
    setItem: (k: string, v: string) => void map.set(k, String(v)),
    removeItem: (k: string) => void map.delete(k),
    clear: () => map.clear(),
    key: (i: number) => [...map.keys()][i] ?? null,
    get length() {
      return map.size
    }
  } as Storage
}

vi.stubGlobal('localStorage', makeStorage())
vi.stubGlobal('sessionStorage', makeStorage())

/** 与 `GET /api/v1/assets` 的真实键集一致（tenantId 有、owner 无） */
const SERVER_ASSET_ROWS = [
  {
    id: 'a-1',
    name: 'dwd.order_wide',
    type: 'table',
    tenantId: 'tenant-alpha',
    description: '订单宽表',
    status: 'listed',
    qualityScore: 88,
    securityLevel: 'internal',
    schema: { fields: [] },
    updateFrequency: 'daily',
    tags: {},
    pricing: { mode: 'by_call', price: 2, unit: 'call' },
    deliveryMethod: 'file',
    subscriberCount: 5,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z'
  }
]

async function mountView(): Promise<VueWrapper> {
  setActivePinia(createPinia())
  const auth = useAuthStore()
  auth.user = {
    id: 'u-1',
    username: 'alpha',
    tenantId: 'tenant-alpha',
    roles: ['USER']
  } as never
  listAssetsMock.mockResolvedValue(SERVER_ASSET_ROWS as never)
  listSubscriptionsMock.mockResolvedValue([] as never)
  const wrapper = mount(AssetMarket, { global: { plugins: [i18n] } })
  await flushPromises()
  return wrapper
}

describe('views/AssetMarket.vue — 租户字段消费', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('卡片「提供方」渲染服务端返回的 tenantId，而非空白', async () => {
    const wrapper = await mountView()
    // 读错字段时这里渲染成 "提供方: "（vue-i18n 把缺失的命名插值填成空串），
    // 所以判据必须是"含租户值"而不是"非空"。
    expect(wrapper.text()).toContain('提供方: tenant-alpha')
  })

  it('自有资产过滤按 tenantId 生效，收益 KPI 不再是 ¥0.00', async () => {
    const wrapper = await mountView()
    // KPI 直接由 `myAssets`（按 tenantId 过滤）算出，是页面上唯一不依赖
    // el-table stub 就能观察该过滤结果的出口：
    // subscriberCount 5 × price 2 ⇒ 提供方 0.8 → ¥8.00，平台 0.2 → ¥2.00。
    // 注：0.8/0.2 是 AssetMarket.vue:609/613 里写死的分成系数，与后端
    // `Settlement.providerShare` 同源但独立成文，本用例把它一并钉住（改了会红）。
    const text = wrapper.text()
    expect(text).toContain('¥8.00')
    expect(text).toContain('¥2.00')
    expect(text).not.toContain('¥0.00')
  })

  it('页签可切到「我上架的」并渲染该视图自身（结构性覆盖，不判租户过滤）', async () => {
    const wrapper = await mountView()
    const listedTab = wrapper.findAll('.el-button').find((b) => b.text().includes('我上架的'))
    if (!listedTab) throw new Error('找不到「我上架的」页签')
    await listedTab.trigger('click')
    await flushPromises()
    // el-table 在 test-setup 里是 slotStub，prop 列不渲染内容，故表体本身断不出名字；
    // 该页签唯一可断的结构性事实是它渲染了自己的标题（不是市场卡片那一片）。
    expect(wrapper.text()).toContain('我上架的资产')
  })
})
