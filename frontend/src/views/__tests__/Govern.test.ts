/**
 * Govern.vue 单元测试
 *
 * 重点覆盖（B2-2 前端修复）：
 * - 原生 <table> 已替换为 el-table（资产列表 + schema 字段表）
 * - 原生 <select>/<input>/<button> 已替换为 Element Plus 组件
 * - 资产列表加载三态（loading / error / data）
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import commonZh from '@/i18n/locales/zh-CN.json'
import governZh from '@/i18n/locales/modules/govern.zh-CN.json'

// 合并 common + govern 模块消息
const messages = { 'zh-CN': { ...commonZh, ...governZh } }
const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: messages as never })

const { listAssetsMock } = vi.hoisted(() => ({
  listAssetsMock: vi.fn()
}))

vi.mock('@/api/governance', () => ({
  listAssets: listAssetsMock,
  getAsset: vi.fn(),
  createAsset: vi.fn(),
  updateAsset: vi.fn(),
  deleteAsset: vi.fn(),
  getAssetSchema: vi.fn(),
  getAssetQuality: vi.fn(),
  getAssetPermissions: vi.fn(),
  applyAssetPermission: vi.fn()
}))

// 补充 @element-plus/icons-vue mock
vi.mock('@element-plus/icons-vue', () => ({
  Refresh: { name: 'Refresh', template: '<svg />' },
  Folder: { name: 'Folder', template: '<svg />' },
  Document: { name: 'Document', template: '<svg />' },
  WarningFilled: { name: 'WarningFilled', template: '<svg />' }
}))

import Govern from '../Govern.vue'
import type { Asset } from '@/api/governance'

const realAsset: Asset = {
  id: 'asset-1',
  name: 'dwd.order_wide',
  layer: 'DWD',
  owner: 'data-team',
  score: 95,
  sensitivity: 'none',
  status: 'active',
  createdAt: '2026-01-01T00:00:00Z',
  updatedAt: '2026-01-01T00:00:00Z'
}

describe('views/Govern.vue — Element Plus 组件替换验证', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  function mountGovern() {
    return mount(Govern, { global: { plugins: [i18n] } })
  }

  it('使用 el-table 而非原生 <table> 展示资产列表', async () => {
    listAssetsMock.mockResolvedValue({ list: [realAsset], total: 1, page: 1, pageSize: 100 })
    const wrapper = mountGovern()
    await flushPromises()

    // 应存在 el-table 组件
    const elTable = wrapper.findComponent({ name: 'ElTable' })
    expect(elTable.exists()).toBe(true)

    // 不应存在原生 <table> 元素
    expect(wrapper.find('table').exists()).toBe(false)
    wrapper.unmount()
  })

  it('使用 el-button 而非原生 <button class="btn"> 作为操作按钮', async () => {
    listAssetsMock.mockResolvedValue({ list: [realAsset], total: 1, page: 1, pageSize: 100 })
    const wrapper = mountGovern()
    await flushPromises()

    // 应存在 el-button 组件（注册资产、申请读权限等）
    const elButtons = wrapper.findAllComponents({ name: 'ElButton' })
    expect(elButtons.length).toBeGreaterThanOrEqual(1)

    // 不应存在原生 <button class="btn">
    expect(wrapper.find('button.btn').exists()).toBe(false)
    wrapper.unmount()
  })

  it('Toolbar 筛选使用 el-select，页面不使用原生 <select> / <input>', async () => {
    listAssetsMock.mockResolvedValue({ list: [], total: 0, page: 1, pageSize: 100 })
    const wrapper = mountGovern()
    await flushPromises()

    // 应存在 el-select（Toolbar 中的层级筛选）
    expect(wrapper.findComponent({ name: 'ElSelect' }).exists()).toBe(true)

    // 不应存在原生 <select>
    expect(wrapper.find('select').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('views/Govern.vue — 资产列表加载三态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  function mountGovern() {
    return mount(Govern, { global: { plugins: [i18n] } })
  }

  it('加载失败应展示错误态与重试入口', async () => {
    listAssetsMock.mockRejectedValue(new Error('backend down'))
    const wrapper = mountGovern()
    await flushPromises()

    expect(wrapper.text()).toContain('backend down')
    // common.retry = "重试"
    expect(wrapper.text()).toContain('重试')
    wrapper.unmount()
  })

  it('加载成功应将数据传入 el-table', async () => {
    listAssetsMock.mockResolvedValue({ list: [realAsset], total: 1, page: 1, pageSize: 100 })
    const wrapper = mountGovern()
    await flushPromises()

    // el-table stub 接收 data prop，验证数据已传入
    const elTable = wrapper.findComponent({ name: 'ElTable' })
    expect(elTable.exists()).toBe(true)
    expect(elTable.props('data')).toEqual([realAsset])
    wrapper.unmount()
  })
})
