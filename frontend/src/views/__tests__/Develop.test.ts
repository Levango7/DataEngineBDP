/**
 * Develop.vue 单元测试
 *
 * 重点覆盖（B2-2 前端修复）：
 * - 原生 HTML 已替换为 Element Plus 组件（el-select / el-input-number / el-button）
 * - 文件树加载三态（loading / error / data）
 * - 运行参数区使用 el-select 选择引擎、el-input-number 输入 CPU/内存/并发度
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import commonZh from '@/i18n/locales/zh-CN.json'
import developZh from '@/i18n/locales/modules/develop.zh-CN.json'

// 合并 common + develop 模块消息
const messages = { 'zh-CN': { ...commonZh, ...developZh } }
const i18n = createI18n({ legacy: false, locale: 'zh-CN', messages: messages as never })

const { getFileTreeMock } = vi.hoisted(() => ({
  getFileTreeMock: vi.fn()
}))

vi.mock('@/api/develop', () => ({
  getFileTree: getFileTreeMock,
  readFile: vi.fn(),
  runJob: vi.fn(),
  submitSchedule: vi.fn(),
  getTaskDag: vi.fn()
}))

// 图标不再单独 mock：test-setup.ts 的 @element-plus/icons-vue mock 已对任意
// PascalCase 图标名按需生成 SVG 占位组件（此前这里只列了 4 个，Develop.vue 用到
// VideoPlay 时直接抛 "No ... export is defined on the mock"）

import Develop from '../Develop.vue'

describe('views/Develop.vue — Element Plus 组件替换验证', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  function mountDevelop() {
    return mount(Develop, { global: { plugins: [i18n] } })
  }

  it('使用 el-select 而非原生 <select> 作为引擎选择器', async () => {
    getFileTreeMock.mockResolvedValue([])
    const wrapper = mountDevelop()
    await flushPromises()

    // 应存在 el-select stub（引擎 + 调度两个）
    const elSelects = wrapper.findAllComponents({ name: 'ElSelect' })
    expect(elSelects.length).toBeGreaterThanOrEqual(2)

    // 不应存在原生 <select> 元素
    expect(wrapper.find('select').exists()).toBe(false)
    wrapper.unmount()
  })

  it('使用 el-input-number 而非原生 <input type="number"> 作为 CPU/内存/并发度输入', async () => {
    getFileTreeMock.mockResolvedValue([])
    const wrapper = mountDevelop()
    await flushPromises()

    // 应存在 el-input-number stub（CPU + 内存 + 并发度三个）
    const elInputNumbers = wrapper.findAllComponents({ name: 'ElInputNumber' })
    expect(elInputNumbers.length).toBeGreaterThanOrEqual(3)

    // 不应存在原生 <input type="number">
    expect(wrapper.find('input[type="number"]').exists()).toBe(false)
    wrapper.unmount()
  })

  it('使用 el-button 而非原生 <button class="btn"> 作为运行/调度按钮', async () => {
    getFileTreeMock.mockResolvedValue([])
    const wrapper = mountDevelop()
    await flushPromises()

    // 应存在 el-button stub（运行 + 提交调度）
    const elButtons = wrapper.findAllComponents({ name: 'ElButton' })
    expect(elButtons.length).toBeGreaterThanOrEqual(2)

    // 不应存在原生 <button class="btn">
    expect(wrapper.find('button.btn').exists()).toBe(false)
    wrapper.unmount()
  })
})

describe('views/Develop.vue — 文件树加载三态', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
  })

  function mountDevelop() {
    return mount(Develop, { global: { plugins: [i18n] } })
  }

  it('加载中应展示 loading 文案', async () => {
    getFileTreeMock.mockReturnValue(new Promise(() => {})) // 永不 resolve
    const wrapper = mountDevelop()
    await flushPromises()

    expect(wrapper.find('.tree-loading').exists()).toBe(true)
    expect(wrapper.text()).toContain('加载文件树')
    wrapper.unmount()
  })

  it('加载失败应展示错误态与重试入口', async () => {
    getFileTreeMock.mockRejectedValue(new Error('network error'))
    const wrapper = mountDevelop()
    await flushPromises()

    expect(wrapper.find('.tree-error').exists()).toBe(true)
    // common.retry = "重试"
    expect(wrapper.text()).toContain('重试')
    wrapper.unmount()
  })

  it('加载成功应渲染文件树节点', async () => {
    getFileTreeMock.mockResolvedValue([
      { id: '1', name: 'query.sql', type: 'file', path: '/workspace/query.sql' },
      { id: '2', name: 'src', type: 'folder' }
    ])
    const wrapper = mountDevelop()
    await flushPromises()

    // 文件树节点应存在
    const treeNodes = wrapper.findAll('.tree-node')
    expect(treeNodes.length).toBe(2)
    expect(wrapper.text()).toContain('query.sql')
    expect(wrapper.text()).toContain('src')
    wrapper.unmount()
  })
})
