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

const { listApisMock, deployMock, undeployMock, apisixConfigMock } = vi.hoisted(() => ({
  listApisMock: vi.fn(),
  deployMock: vi.fn(),
  undeployMock: vi.fn(),
  apisixConfigMock: vi.fn(() => Promise.resolve({}))
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
  getMetrics: vi.fn(),
  // C3 运维标签页新增的五条：缺任何一条，模块 mock 里就是 undefined，
  // 用到它的用例会以"不是函数"崩掉而不是假通过
  getBillingConfig: vi.fn(() =>
    Promise.resolve({
      apiId: 'api-1',
      costStrategy: 'by_call',
      costUnitPrice: 0.01,
      monthlyQuota: null,
      description: '',
      updatedAt: '2026-10-22T00:00:00Z'
    })
  ),
  setBillingConfig: vi.fn(() =>
    Promise.resolve({
      apiId: 'api-1',
      costStrategy: 'by_bytes',
      costUnitPrice: 0.02,
      monthlyQuota: null,
      description: '',
      updatedAt: '2026-10-22T00:05:00Z'
    })
  ),
  getApisixConfig: apisixConfigMock,
  deployApisixRoute: deployMock,
  undeployApisixRoute: undeployMock
}))

import APIMarket from '../APIMarket.vue'
import Toolbar from '@/components/ui/Toolbar.vue'
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

  // 注意：test-setup.ts 把 el-button stub 成 `<div class="el-button">`（不是原生 <button>），
  // 因此这里按 stub 的 class 查找，并按 i18n 译文匹配，避免硬编码文案
  const retryLabel = (apiMarketZh as { apiMarket: { list: { retry: string } } }).apiMarket.list
    .retry

  function findRetryButton(wrapper: VueWrapper) {
    return wrapper.findAll('.el-button').find((b) => b.text() === retryLabel)
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
    // 工具栏已切换为共享 Toolbar 组件（内部 el-input 在测试环境被 stub，无真实 <input>），
    // 直接按 Toolbar 的组件契约发事件：update:searchValue 更新关键字，search 触发查询
    const toolbar = wrapper.findComponent(Toolbar)
    await toolbar.vm.$emit('update:searchValue', 'test')
    await toolbar.vm.$emit('search')
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

/**
 * C3 运维标签页的成败判据。
 *
 * 这条是真浏览器里抓出来的：APISIX Admin 不可达时后端**仍回 HTTP 200**，
 * 成败写在 `status`/`deployed`/`message` 里（`services/apisix_config.py:252-281`，
 * 注释原话"网络错误显式回报，不伪装成功"）。若客户端只看 HTTP 码，
 * 用户就会在页面上看到"APISIX 路由已部署"，而网关里根本没有这条路由。
 */
describe('views/APIMarket.vue 运维标签页：部署成败以 status 为准，不看 HTTP 码', () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  function mountWithApi(): { wrapper: VueWrapper; vm: Record<string, any> } {
    listApisMock.mockResolvedValue([realApi])
    const wrapper = mount(APIMarket, { global: { plugins: [i18n] } })
    return { wrapper, vm: wrapper.vm as unknown as Record<string, any> }
  }

  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  it('status=failed 时把后端 message 与 error 呈现为错误态，绝不报"已部署"', async () => {
    deployMock.mockResolvedValue({
      action: 'deploy',
      routeId: 'api-1',
      status: 'failed',
      deployed: false,
      message: '路由下发失败: real-api（APISIX 未部署或网络不可达）',
      error: 'APISIX Admin API 不可达: getaddrinfo failed'
    })
    const { wrapper, vm } = mountWithApi()
    await flushPromises()
    vm.selectedApi = realApi
    await vm.deployRoute()
    await flushPromises()

    expect(deployMock).toHaveBeenCalledWith('api-1')
    expect(vm.opsError).toContain('路由下发失败')
    expect(vm.opsError).toContain('getaddrinfo failed')
    expect(wrapper.text()).not.toContain('APISIX 路由已部署')
    wrapper.unmount()
  })

  it('status=success 时才清空错误态并提示成功', async () => {
    deployMock.mockResolvedValue({
      action: 'deploy',
      routeId: 'api-1',
      status: 'success',
      deployed: true,
      message: '路由已下发: real-api',
      error: null
    })
    const { wrapper, vm } = mountWithApi()
    await flushPromises()
    vm.selectedApi = realApi
    await vm.deployRoute()
    await flushPromises()

    expect(vm.opsError).toBe('')
    // Toast 由 App.vue 的 <Toast/> 渲染，单挂 APIMarket 时没有那层壳，
    // 所以这里断言"没进错误态"而不是断言弹层文案
    expect(wrapper.text()).not.toContain('APISIX 操作失败')
    wrapper.unmount()
  })

  it('卸载同样按 status 判定；失败时保留已查看的路由配置', async () => {
    undeployMock.mockResolvedValue({
      action: 'undeploy',
      routeId: 'api-1',
      status: 'failed',
      deployed: false,
      message: '路由删除失败: real-api（APISIX 未部署或网络不可达）',
      error: 'getaddrinfo failed'
    })
    const { wrapper, vm } = mountWithApi()
    await flushPromises()
    vm.selectedApi = realApi
    vm.apisixConfig = { name: 'real-api-1.0.0' }
    await vm.undeployRoute()
    await flushPromises()

    expect(vm.opsError).toContain('路由删除失败')
    // 失败不能把配置视图清掉：清掉会让人误以为"已经删干净了"
    expect(vm.apisixConfig).not.toBeNull()
    wrapper.unmount()
  })
})
