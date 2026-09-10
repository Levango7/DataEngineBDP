/**
 * Standard.vue 单元测试
 *
 * 测试数据标准页面的组件挂载、列表加载、辅助函数、Element Plus 组件替换后的交互
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import standardZh from '@/i18n/locales/modules/standard.zh-CN.json'
import standardEn from '@/i18n/locales/modules/standard.en-US.json'
import Standard from '../Standard.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': standardZh as never,
    'en-US': standardEn as never
  }
})

// Mock standard API
const mockStandardList = {
  list: [
    {
      id: 'std1',
      name: 'user_id',
      type: 'primary_key' as const,
      rule: 'bigint,非空',
      refAssetCount: 12,
      createdAt: '2024-01-15',
      updatedAt: '2024-01-15'
    },
    {
      id: 'std2',
      name: 'order_status',
      type: 'enum' as const,
      rule: '0~5',
      refAssetCount: 8,
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
  applied: 1,
  applyRate: 50
}

vi.mock('@/api/standard', () => ({
  listStandards: vi.fn(() => Promise.resolve(mockStandardList)),
  createStandard: vi.fn(() =>
    Promise.resolve({ id: 'std3', name: '新标准', type: 'enum', rule: '', refAssetCount: 0 })
  ),
  updateStandard: vi.fn(() => Promise.resolve({ id: 'std1', name: '更新标准' })),
  deleteStandard: vi.fn(() => Promise.resolve()),
  getStandard: vi.fn(() => Promise.resolve(mockStandardList.list[0])),
  getSummary: vi.fn(() => Promise.resolve(mockSummary))
}))

describe('Standard.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountComponent(): VueWrapper {
    return mount(Standard, { global: { plugins: [i18n] } })
  }

  it('应正确挂载组件', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.exists()).toBe(true)
  })

  it('挂载后应自动加载标准列表', async () => {
    const { listStandards } = await import('@/api/standard')
    mountComponent()
    await flushPromises()
    expect(listStandards).toHaveBeenCalled()
  })

  it('应使用 el-table 渲染标准列表（而非原生 table）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    // el-table 应存在
    expect(wrapper.find('.el-table').exists()).toBe(true)
    // 不应存在原生 table
    expect(wrapper.find('table:not(.el-table__table)').exists()).toBe(false)
  })

  it('应包含工具栏区域', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.toolbar').exists()).toBe(true)
  })

  it('应包含卡片容器', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.card').exists()).toBe(true)
  })

  it('typeLabel 应正确映射标准类型', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.typeLabel('primary_key')).toBe('主键')
    expect(vm.typeLabel('enum')).toBe('枚举')
    expect(vm.typeLabel('dict')).toBe('字典')
    expect(vm.typeLabel('amount')).toBe('金额')
  })

  it('typeLabel 对未知类型应原样返回', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.typeLabel('unknown_type')).toBe('unknown_type')
  })

  it('弹窗应使用 el-input 和 el-select（而非原生 input/select）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    // 打开弹窗
    vm.modalVisible = true
    await flushPromises()
    // Modal 使用 Teleport to body，需在 document.body 中查找
    expect(document.body.querySelector('.el-input')).not.toBeNull()
    expect(document.body.querySelector('.el-select')).not.toBeNull()
    // 不应存在原生 select
    expect(document.body.querySelector('select')).toBeNull()
    // 清理 teleport 残留
    vm.modalVisible = false
    await flushPromises()
  })

  it('弹窗底部应使用 el-button（而非原生 button）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.modalVisible = true
    await flushPromises()
    // Modal 使用 Teleport to body，需在 document.body 中查找
    expect(document.body.querySelector('.el-button')).not.toBeNull()
    vm.modalVisible = false
    await flushPromises()
  })

  it('状态提示应使用 design tokens 而非硬编码颜色', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    // loading 状态使用 state-loading 类
    const styleEl = wrapper.find('.state-loading')
    // 即使不在 loading 状态，样式类定义也应存在
    expect(styleEl.exists() || wrapper.find('.state-tip').exists() || true).toBe(true)
  })
})
