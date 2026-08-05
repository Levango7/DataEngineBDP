/**
 * TenantManagement.vue 单元测试
 *
 * 测试租户管理页面的组件挂载、列表加载、搜索、弹窗交互、辅助函数
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import TenantManagement from '../TenantManagement.vue'

// Mock tenant API
const mockTenantList = {
  list: [
    {
      id: 't1',
      name: '华东生产集群',
      code: 'east-prod',
      plan: 'enterprise' as const,
      status: 'active' as const,
      workspaceCount: 5,
      userCount: 20,
      resourceUsage: 65,
      createdAt: '2024-01-01',
      updatedAt: '2024-06-01'
    },
    {
      id: 't2',
      name: '测试租户',
      code: 'test-tenant',
      plan: 'standard' as const,
      status: 'suspended' as const,
      workspaceCount: 1,
      userCount: 3,
      resourceUsage: 30,
      createdAt: '2024-03-01',
      updatedAt: '2024-06-01'
    }
  ],
  total: 2,
  page: 1,
  pageSize: 20
}

vi.mock('@/api/tenant', () => ({
  listTenants: vi.fn(() => Promise.resolve(mockTenantList)),
  createTenant: vi.fn(() => Promise.resolve({ id: 't3', name: '新租户' })),
  updateTenant: vi.fn(() => Promise.resolve({ id: 't1', name: '更新租户' })),
  deleteTenant: vi.fn(() => Promise.resolve()),
  listAllTenants: vi.fn(() => Promise.resolve(mockTenantList.list)),
  getTenant: vi.fn(() => Promise.resolve(mockTenantList.list[0]))
}))

describe('TenantManagement.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountComponent(): VueWrapper {
    return mount(TenantManagement)
  }

  it('应正确挂载组件并渲染页面标题', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('h1').text()).toBe('租户管理')
    expect(wrapper.find('.sub').exists()).toBe(true)
  })

  it('挂载后应自动加载租户列表', async () => {
    const { listTenants } = await import('@/api/tenant')
    mountComponent()
    await flushPromises()
    expect(listTenants).toHaveBeenCalled()
  })

  it('应包含页面主卡片容器', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.page-card').exists()).toBe(true)
  })

  it('应包含工具栏区域', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.toolbar').exists()).toBe(true)
  })

  it('应包含分页区域', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.pagination-wrap').exists()).toBe(true)
  })

  it('列表加载后应更新内部状态', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.loading).toBe(false)
    expect(vm.tenantList).toHaveLength(2)
  })

  it('planLabel 辅助函数应正确映射套餐名称', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.planLabel('enterprise')).toBe('企业版')
    expect(vm.planLabel('standard')).toBe('标准版')
    expect(vm.planLabel('flagship')).toBe('旗舰版')
    expect(vm.planLabel('internal')).toBe('内部无限')
  })

  it('statusLabel 辅助函数应正确映射状态名称', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.statusLabel('active')).toBe('活跃')
    expect(vm.statusLabel('suspended')).toBe('已暂停')
    expect(vm.statusLabel('deleted')).toBe('已删除')
  })

  it('planTagType 辅助函数应返回正确的 tag 类型', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.planTagType('standard')).toBe('info')
    expect(vm.planTagType('enterprise')).toBe('primary')
    expect(vm.planTagType('flagship')).toBe('warning')
    expect(vm.planTagType('internal')).toBe('success')
  })

  it('statusTagType 辅助函数应返回正确的 tag 类型', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.statusTagType('active')).toBe('success')
    expect(vm.statusTagType('suspended')).toBe('warning')
    expect(vm.statusTagType('deleted')).toBe('info')
  })

  it('usageColor 辅助函数应根据百分比返回颜色', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.usageColor(95)).toBe('#c0504d')
    expect(vm.usageColor(75)).toBe('#c08a2e')
    expect(vm.usageColor(50)).toBe('#2f9e6f')
  })

  it('handleSearch 应重置页码', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.currentPage = 3
    vm.handleSearch()
    expect(vm.currentPage).toBe(1)
  })

  it('openCreateDialog 应打开弹窗并设置 isEdit 为 false', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.openCreateDialog()
    expect(vm.dialogVisible).toBe(true)
    expect(vm.isEdit).toBe(false)
  })

  it('openEditDialog 应打开弹窗并填充表单数据', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    const row = mockTenantList.list[0]
    vm.openEditDialog(row)
    expect(vm.dialogVisible).toBe(true)
    expect(vm.isEdit).toBe(true)
    expect(vm.editingId).toBe('t1')
    expect(vm.formData.name).toBe('华东生产集群')
    expect(vm.formData.code).toBe('east-prod')
  })

  it('resetForm 应重置表单数据', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.formData.name = '测试'
    vm.formData.code = 'test'
    vm.resetForm()
    expect(vm.formData.name).toBe('')
    expect(vm.formData.code).toBe('')
    expect(vm.formData.plan).toBe('enterprise')
  })
})
