/**
 * DevMl.vue 单元测试
 *
 * 重点覆盖：
 * 1. 组件挂载后 KPI 卡片正常渲染
 * 2. 加载失败时重试按钮为 el-button（原生 HTML → Element Plus 替换验证）
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import devMlZh from '@/i18n/locales/modules/devMl.zh-CN.json'
import devMlEn from '@/i18n/locales/modules/devMl.en-US.json'

const { listTrainJobsMock, listModelsMock, listInferenceServicesMock } = vi.hoisted(() => ({
  listTrainJobsMock: vi.fn(),
  listModelsMock: vi.fn(),
  listInferenceServicesMock: vi.fn()
}))

vi.mock('@/api/dev-ml', () => ({
  listTrainJobs: listTrainJobsMock,
  listModels: listModelsMock,
  listInferenceServices: listInferenceServicesMock,
  createTrainJob: vi.fn(),
  registerModel: vi.fn(),
  deployInference: vi.fn(),
  scaleInference: vi.fn(),
  getTrainJobLogs: vi.fn(() => Promise.resolve('')),
  stopTrainJob: vi.fn(),
  stopInference: vi.fn(),
  deleteModel: vi.fn()
}))

import DevMl from '../dev/DevMl.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': devMlZh as never,
    'en-US': devMlEn as never
  }
})

describe('views/dev/DevMl.vue 组件渲染', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    // listTrainJobs 返回 { list, total } 结构
    listTrainJobsMock.mockResolvedValue({ list: [], total: 0 })
    // listModels / listInferenceServices 直接返回数组
    listModelsMock.mockResolvedValue([])
    listInferenceServicesMock.mockResolvedValue([])
  })

  async function mountPage() {
    const wrapper = mount(DevMl, { global: { plugins: [i18n] } })
    await flushPromises()
    return wrapper
  }

  it('挂载时应正常渲染 KPI 卡片区域', async () => {
    const wrapper = await mountPage()
    // 验证卡片容器存在
    expect(wrapper.find('.dev-ml-page').exists()).toBe(true)
    // 验证 KPI 卡片已渲染
    expect(wrapper.findAll('.card').length).toBeGreaterThanOrEqual(1)
    wrapper.unmount()
  })

  it('加载失败时应渲染 el-button 重试按钮而非原生 button', async () => {
    // 模拟训练作业加载失败
    listTrainJobsMock.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountPage()
    // 验证错误提示区域存在
    expect(wrapper.find('.kpi-error').exists()).toBe(true)
    // 验证重试按钮是 el-button（Element Plus 组件），而非原生 <button>
    const retryBtn = wrapper.find('.retry-btn')
    expect(retryBtn.exists()).toBe(true)
    // el-button 渲染后会注入 el-button class，原生 button 不会有此 class
    // 这证明原生 <button> 已被替换为 <el-button>
    expect(retryBtn.classes()).toContain('el-button')
    wrapper.unmount()
  })
})
