/**
 * Gateway.vue 单元测试
 *
 * 重点覆盖（B2-2 前端修复）：
 * - 原生 <table> 已替换为 el-table
 * - 原生 <select>/<input>/<button> 已替换为 Element Plus 组件
 * - API Key 列表加载三态（loading / error / data）
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import commonZh from '@/i18n/locales/zh-CN.json'
import gatewayZh from '@/i18n/locales/modules/gateway.zh-CN.json'

// 合并 common + gateway 模块消息
const messages = { 'zh-CN': { ...commonZh, ...gatewayZh } }
const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: messages as never })

const { getStatsMock, listApiKeysMock } = vi.hoisted(() => ({
  getStatsMock: vi.fn(),
  listApiKeysMock: vi.fn()
}))

vi.mock('@/api/gateway', () => ({
  getStats: getStatsMock,
  listApiKeys: listApiKeysMock,
  createApiKey: vi.fn(),
  updateApiKey: vi.fn(),
  deleteApiKey: vi.fn()
}))

// 图标不再单独 mock：test-setup.ts 的 @element-plus/icons-vue mock 已按需覆盖任意图标

import Gateway from '../Gateway.vue'
import type { ApiKey } from '@/api/gateway'

const realKey: ApiKey = {
  id: 'key-1',
  name: 'test-key',
  routeModel: 'qiong-7B',
  rateLimit: 20,
  status: 'enabled',
  createdAt: '2026-01-01T00:00:00Z',
  apiKey: 'sk-xxx'
}

describe('views/Gateway.vue — Element Plus 组件替换验证', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    getStatsMock.mockResolvedValue({
      todayCallCount: 100,
      avgLatencyMs: 50,
      successRate: 99,
      activeKeyCount: 1
    })
  })

  function mountGateway() {
    return mount(Gateway, { global: { plugins: [i18n] } })
  }

  it('使用 el-table 而非原生 <table> 展示 API Key 列表', async () => {
    listApiKeysMock.mockResolvedValue([realKey])
    const wrapper = mountGateway()
    await flushPromises()

    // 应存在 el-table 组件
    const elTable = wrapper.findComponent({ name: 'ElTable' })
    expect(elTable.exists()).toBe(true)

    // 不应存在原生 <table> 元素
    expect(wrapper.find('table').exists()).toBe(false)
    wrapper.unmount()
  })

  it('使用 el-button 而非原生 <button class="btn"> 作为操作按钮', async () => {
    listApiKeysMock.mockResolvedValue([realKey])
    const wrapper = mountGateway()
    await flushPromises()

    // 应存在 el-button 组件（刷新 + 新建 + 编辑 + 删除 + 复制等）
    const elButtons = wrapper.findAllComponents({ name: 'ElButton' })
    expect(elButtons.length).toBeGreaterThanOrEqual(2)

    // 不应存在原生 <button class="btn">
    expect(wrapper.find('button.btn').exists()).toBe(false)
    wrapper.unmount()
  })

  it('页面不使用原生 <select> / <input type="number">', async () => {
    listApiKeysMock.mockResolvedValue([])
    const wrapper = mountGateway()
    await flushPromises()

    // 不应存在原生 <select>
    expect(wrapper.find('select').exists()).toBe(false)
    // 不应存在原生 <input type="number">
    expect(wrapper.find('input[type="number"]').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('views/Gateway.vue — API Key 列表加载三态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    getStatsMock.mockResolvedValue({
      todayCallCount: 0,
      avgLatencyMs: 0,
      successRate: 0,
      activeKeyCount: 0
    })
  })

  function mountGateway() {
    return mount(Gateway, { global: { plugins: [i18n] } })
  }

  it('加载失败应展示错误态与重试入口', async () => {
    listApiKeysMock.mockRejectedValue(new Error('backend down'))
    const wrapper = mountGateway()
    await flushPromises()

    expect(wrapper.text()).toContain('backend down')
    // common.retry = "重试"
    expect(wrapper.text()).toContain('重试')
    wrapper.unmount()
  })

  it('加载成功应将数据传入 el-table', async () => {
    listApiKeysMock.mockResolvedValue([realKey])
    const wrapper = mountGateway()
    await flushPromises()

    // el-table stub 接收 data prop，验证数据已传入
    const elTable = wrapper.findComponent({ name: 'ElTable' })
    expect(elTable.exists()).toBe(true)
    expect(elTable.props('data')).toEqual([realKey])
    wrapper.unmount()
  })
})
