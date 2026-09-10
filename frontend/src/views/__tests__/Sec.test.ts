/**
 * Sec.vue 单元测试
 *
 * 测试安全脱敏页面的组件挂载、列表加载、辅助函数、Element Plus 组件替换后的交互
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import secZh from '@/i18n/locales/modules/sec.zh-CN.json'
import secEn from '@/i18n/locales/modules/sec.en-US.json'
import Sec from '../Sec.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': secZh as never,
    'en-US': secEn as never
  }
})

// Mock sec API
const mockPolicies = [
  {
    id: 'pol1',
    fieldName: 'real_name',
    assetName: 'dim.user',
    strategy: 'mask' as const,
    algorithm: 'SM3' as const,
    status: 'active' as const,
    createdAt: '2024-01-15',
    updatedAt: '2024-01-15'
  },
  {
    id: 'pol2',
    fieldName: 'phone',
    assetName: 'dim.user',
    strategy: 'hash' as const,
    algorithm: 'SHA256' as const,
    status: 'pending' as const,
    createdAt: '2024-02-01',
    updatedAt: '2024-02-01'
  }
]

const mockApprovals = [
  {
    id: 'appr1',
    applicant: '张三',
    asset: 'dim.user',
    permission: 'read',
    status: 'pending' as const,
    createdAt: '2024-01-15'
  }
]

vi.mock('@/api/sec', () => ({
  listMaskPolicies: vi.fn(() => Promise.resolve(mockPolicies)),
  createMaskPolicy: vi.fn(() =>
    Promise.resolve({
      id: 'pol3',
      fieldName: '新字段',
      assetName: '',
      strategy: 'mask',
      algorithm: 'SM3'
    })
  ),
  updateMaskPolicy: vi.fn(() => Promise.resolve({ id: 'pol1', fieldName: 'real_name' })),
  deleteMaskPolicy: vi.fn(() => Promise.resolve()),
  listApprovals: vi.fn(() => Promise.resolve(mockApprovals)),
  approveApproval: vi.fn(() => Promise.resolve()),
  rejectApproval: vi.fn(() => Promise.resolve())
}))

describe('Sec.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountComponent(): VueWrapper {
    return mount(Sec, { global: { plugins: [i18n] } })
  }

  it('应正确挂载组件', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.exists()).toBe(true)
  })

  it('挂载后应自动加载脱敏策略列表', async () => {
    const { listMaskPolicies } = await import('@/api/sec')
    mountComponent()
    await flushPromises()
    expect(listMaskPolicies).toHaveBeenCalled()
  })

  it('挂载后应自动加载审批列表', async () => {
    const { listApprovals } = await import('@/api/sec')
    mountComponent()
    await flushPromises()
    expect(listApprovals).toHaveBeenCalled()
  })

  it('应使用 el-table 渲染策略列表（而非原生 table）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.el-table').exists()).toBe(true)
    expect(wrapper.find('select').exists()).toBe(false)
  })

  it('应包含两个卡片容器（策略 + 审批）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.findAll('.card').length).toBeGreaterThanOrEqual(2)
  })

  it('strategyLabel 应正确映射脱敏策略', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.strategyLabel('mask')).toBe('掩码')
    expect(vm.strategyLabel('hash')).toBe('哈希')
    expect(vm.strategyLabel('authorized_only')).toBe('仅授权可见')
    expect(vm.strategyLabel('plain')).toBe('明文')
  })

  it('strategyLabel 对未知策略应原样返回', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.strategyLabel('unknown')).toBe('unknown')
  })

  it('statusPillClass 应返回正确的 pill 样式类', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.statusPillClass('active')).toBe('g')
    expect(vm.statusPillClass('pending')).toBe('a')
    expect(vm.statusPillClass('disabled')).toBe('b')
  })

  it('statusPillText 应返回正确的状态文案', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.statusPillText('active')).toBe('生效')
    expect(vm.statusPillText('pending')).toBe('待审批')
    expect(vm.statusPillText('disabled')).toBe('已禁用')
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

  it('审批操作列应使用 el-button（而非原生 button）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    // 审批卡片中的 el-button（在 el-table 中，非 teleport）
    expect(wrapper.find('.el-button').exists()).toBe(true)
  })

  it('handleApprove 应调用 approveApproval API', async () => {
    const { approveApproval } = await import('@/api/sec')
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    await vm.handleApprove('appr1')
    expect(approveApproval).toHaveBeenCalledWith('appr1')
  })

  it('handleReject 应调用 rejectApproval API', async () => {
    const { rejectApproval } = await import('@/api/sec')
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    await vm.handleReject('appr1')
    expect(rejectApproval).toHaveBeenCalledWith('appr1')
  })
})
