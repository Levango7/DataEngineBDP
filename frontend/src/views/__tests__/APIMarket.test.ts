/**
 * APIMarket.vue 单元测试
 *
 * 重点覆盖：列表加载失败应进入错误态并提供重试入口，不得注入任何 mock 数据
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

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

  it('列表请求失败时应展示错误态与重试入口，且不出现任何 mock 数据', async () => {
    listApisMock.mockRejectedValue(new Error('backend down'))

    const wrapper = mount(APIMarket)
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
    listApisMock
      .mockRejectedValueOnce(new Error('backend down'))
      .mockResolvedValueOnce([realApi])

    const wrapper = mount(APIMarket)
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
})
