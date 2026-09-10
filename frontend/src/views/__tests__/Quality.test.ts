/**
 * Quality.vue 单元测试
 *
 * 测试数据质量页面的组件挂载、列表加载、辅助函数、Element Plus 组件替换后的交互
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import qualityZh from '@/i18n/locales/modules/quality.zh-CN.json'
import qualityEn from '@/i18n/locales/modules/quality.en-US.json'
import Quality from '../Quality.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': qualityZh as never,
    'en-US': qualityEn as never
  }
})

// Mock quality API
const mockRuleList = {
  list: [
    {
      id: 'rule1',
      name: 'order_id_not_null',
      targetTable: 'dwd.order_wide',
      targetField: 'order_id',
      checkType: 'not_null' as const,
      threshold: '100%',
      actionOnFail: 'alert' as const,
      status: 'enabled' as const,
      lastCheckAt: '2024-01-15 10:00',
      lastResult: 'pass' as const,
      createdAt: '2024-01-15',
      updatedAt: '2024-01-15'
    },
    {
      id: 'rule2',
      name: 'user_id_unique',
      targetTable: 'dim.user',
      targetField: 'user_id',
      checkType: 'unique' as const,
      threshold: '100%',
      actionOnFail: 'block_downstream' as const,
      status: 'enabled' as const,
      lastCheckAt: '2024-02-01 11:00',
      lastResult: 'fail' as const,
      createdAt: '2024-02-01',
      updatedAt: '2024-02-01'
    }
  ],
  total: 2,
  page: 1,
  pageSize: 100
}

const mockSummary = {
  total: 2,
  passed: 1,
  passRate: 50
}

vi.mock('@/api/quality', () => ({
  listRules: vi.fn(() => Promise.resolve(mockRuleList)),
  createRule: vi.fn(() =>
    Promise.resolve({ id: 'rule3', name: '新规则', targetTable: '', checkType: 'not_null' })
  ),
  updateRule: vi.fn(() => Promise.resolve({ id: 'rule1', name: '更新规则' })),
  deleteRule: vi.fn(() => Promise.resolve()),
  getRule: vi.fn(() => Promise.resolve(mockRuleList.list[0])),
  runCheck: vi.fn(() => Promise.resolve(mockRuleList.list[0])),
  getSummary: vi.fn(() => Promise.resolve(mockSummary))
}))

describe('Quality.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountComponent(): VueWrapper {
    return mount(Quality, { global: { plugins: [i18n] } })
  }

  it('应正确挂载组件', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.exists()).toBe(true)
  })

  it('挂载后应自动加载规则列表', async () => {
    const { listRules } = await import('@/api/quality')
    mountComponent()
    await flushPromises()
    expect(listRules).toHaveBeenCalled()
  })

  it('应使用 el-table 渲染规则列表（而非原生 table）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.el-table').exists()).toBe(true)
    expect(wrapper.find('select').exists()).toBe(false)
  })

  it('应包含工具栏区域', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.toolbar').exists()).toBe(true)
  })

  it('checkTypeLabel 应正确映射校验类型', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.checkTypeLabel('not_null')).toBe('非空')
    expect(vm.checkTypeLabel('unique')).toBe('唯一')
    expect(vm.checkTypeLabel('range')).toBe('范围')
    expect(vm.checkTypeLabel('fluctuation')).toBe('波动')
  })

  it('checkTypeLabel 对未知类型应原样返回', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.checkTypeLabel('unknown')).toBe('unknown')
  })

  it('resultPillClass 应返回正确的 pill 样式类', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.resultPillClass('pass')).toBe('g')
    expect(vm.resultPillClass('warn')).toBe('a')
    expect(vm.resultPillClass('fail')).toBe('r')
    expect(vm.resultPillClass(undefined)).toBe('b')
  })

  it('resultPillText 应返回正确的 pill 文案', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.resultPillText('pass')).toBe('通过')
    expect(vm.resultPillText('warn')).toBe('告警')
    expect(vm.resultPillText('fail')).toBe('失败')
    expect(vm.resultPillText(undefined)).toBe('未运行')
  })

  it('弹窗应使用 el-input 和 el-select（而非原生 input/select）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.modalVisible = true
    await flushPromises()
    // Modal 使用 Teleport to body，需在 document.body 中查找
    expect(document.body.querySelector('.el-input')).not.toBeNull()
    expect(document.body.querySelector('.el-select')).not.toBeNull()
    expect(document.body.querySelector('select')).toBeNull()
    vm.modalVisible = false
    await flushPromises()
  })

  it('弹窗底部应使用 el-button（而非原生 button）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.modalVisible = true
    await flushPromises()
    expect(document.body.querySelector('.el-button')).not.toBeNull()
    vm.modalVisible = false
    await flushPromises()
  })
})
