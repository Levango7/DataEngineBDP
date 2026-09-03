/**
 * APIMarket.vue 单元测试
 *
 * 重点覆盖：列表加载失败应进入错误态并提供重试入口，不得注入任何 mock 数据；
 *           错误态时 KPI 概览不得展示旧数据（P2-9）
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import apiMarketZh from '@/i18n/locales/modules/apiMarket.zh-CN.json'
import apiMarketEn from '@/i18n/locales/modules/apiMarket.en-US.json'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': apiMarketZh as never,
    'en-US': apiMarketEn as never
  }
})

const { listApisMock } = vi.hoisted(() => ({
  listApisMock: vi.fn()
}))

vi.mock('@/api/apiCatalog', () => ({
  listApis: listApisMock,
  getApi: vi.fn(),
  registerApi: vi.fn(),
  publishApi: vi.fn(),
  submitReview: vi.fn(),
  approveApi: vi.fn(),
  subscribeApi: vi.fn(),
  listSubscribers: vi.fn(() => Promise.resolve([])),
  callApi: vi.fn(),
  getMetrics: vi.fn()
}))

import APIMarket from '../APIMarket.vue'
import type { APIDefinition } from '@/api/apiCatalog'

const realApi: APIDefinition = {
  id: 'api-1',
  name: 'real-api',
  version: '1.0.0',
  description: '真实接口',
  category: 'core',
  tags: [],
  method: 'GET',
  path: '/real',
  params: [],
  responses: [],
  authType: 'api_key',
  upstream: { type: 'http', url: 'http://backend', method: 'GET' },
  sla: 'silver',
  costStrategy: 'by_call',
  costUnitPrice: 0.01,
  status: 'running',
  providerTenantId: 'tenant-provider',
  callCount: 3,
  errorCount: 0,
  totalLatencyMs: 30,
  totalTrafficBytes: 300,
  createdAt: '',
  updatedAt: ''
}

describe('views/APIMarket.vue 列表加载三态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  function findRetryButton(wrapper: VueWrapper) {
    return wrapper.findAll('button').find((b) => b.text() === '重试')
  }

  function mountApiMarket() {
    return mount(APIMarket, { global: { plugins: [i18n] } })
  }

  it('列表请求失败时应展示错误态与重试入口，且不出现任何 mock 数据', async () => {
    listApisMock.mockRejectedValue(new Error('backend down'))

    const wrapper = mountApiMarket()
    await flushPromises()

    expect(listApisMock).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('API 列表加载失败')
    expect(wrapper.text()).toContain('backend down')
    expect(wrapper.text()).not.toContain('weather-query')
    expect(wrapper.text()).not.toContain('risk-score')
    expect(findRetryButton(wrapper)).toBeDefined()
    wrapper.unmount()
  })

  it('点击重试成功后应渲染真实列表数据', async () => {
    listApisMock.mockRejectedValueOnce(new Error('backend down')).mockResolvedValueOnce([realApi])

    const wrapper = mountApiMarket()
    await flushPromises()
    expect(wrapper.text()).toContain('API 列表加载失败')

    const retryBtn = findRetryButton(wrapper)
    expect(retryBtn).toBeDefined()
    await retryBtn!.trigger('click')
    await flushPromises()

    expect(listApisMock).toHaveBeenCalledTimes(2)
    expect(wrapper.text()).toContain('real-api')
    expect(wrapper.text()).not.toContain('API 列表加载失败')
    expect(wrapper.text()).not.toContain('weather-query')
    wrapper.unmount()
  })

  it('P2-9: 先成功后失败时 KPI 概览不应展示旧数据', async () => {
    // 第一次成功，KPI 概览应显示 realApi 的数据
    listApisMock.mockResolvedValueOnce([realApi])
    // 第二次失败，KPI 概览应清空，不保留旧数据
    listApisMock.mockRejectedValueOnce(new Error('backend down'))

    const wrapper = mountApiMarket()
    await flushPromises()

    // 第一次加载成功，KPI 应显示 1 个 API
    expect(wrapper.text()).toContain('real-api')
    // KPI 概览"已发布 API"应为 1
    const kpiCards = wrapper.findAll('.kpi')
    expect(kpiCards.length).toBeGreaterThanOrEqual(1)

    // 触发重试（第二次请求），将失败
    const retryBtn = findRetryButton(wrapper)
    // 没有重试按钮（因为第一次成功），改为通过搜索触发第二次请求
    // 使用直接调用 refreshList 不方便，改为模拟输入触发防抖后请求
    const searchInput = wrapper.find('.search-input')
    await searchInput.setValue('test')
    // 等待防抖 300ms
    await new Promise((r) => setTimeout(r, 350))
    await flushPromises()

    // 第二次请求失败，应进入错误态
    expect(wrapper.text()).toContain('API 列表加载失败')
    // KPI 概览不应显示旧数据（已发布 API 应为 0，而非 1）
    // 错误态下 safeApiList 为空，所有 KPI 计数应为 0
    expect(wrapper.text()).toContain('API 列表加载失败')
    // 确保旧数据 real-api 不再出现在列表区域（错误态卡片不显示列表）
    // 注意：real-api 可能仍出现在 KPI 概览中如果未正确清空，这里验证 KPI 为 0
    const kpiAfterError = wrapper.findAll('.kpi')
    // 第一个 KPI 是"已发布 API"，错误态应为 0
    expect(kpiAfterError[0].text()).toBe('0')
    wrapper.unmount()
  })
})
