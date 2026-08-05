/**
 * DataSourceManagement.vue 单元测试
 *
 * 测试数据源管理页面的组件挂载、列表加载、辅助函数、弹窗交互
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DataSourceManagement from '../DataSourceManagement.vue'

// Mock datasource API
const mockDsList = {
  list: [
    {
      id: 'ds1',
      name: '业务订单库',
      type: 'mysql' as const,
      host: '192.168.1.10',
      port: 3306,
      database: 'orders',
      username: 'app_user',
      status: 'connected' as const,
      createdAt: '2024-01-15'
    },
    {
      id: 'ds2',
      name: '分析数据湖',
      type: 'clickhouse' as const,
      host: '192.168.1.20',
      port: 8123,
      database: 'analytics',
      username: 'reader',
      status: 'disconnected' as const,
      createdAt: '2024-02-01'
    }
  ],
  total: 2,
  page: 1,
  pageSize: 20
}

vi.mock('@/api/datasource', () => ({
  listDataSources: vi.fn(() => Promise.resolve(mockDsList)),
  createDataSource: vi.fn(() => Promise.resolve({ id: 'ds3', name: '新数据源' })),
  updateDataSource: vi.fn(() => Promise.resolve({ id: 'ds1', name: '更新数据源' })),
  deleteDataSource: vi.fn(() => Promise.resolve()),
  getDataSource: vi.fn(() => Promise.resolve(mockDsList.list[0])),
  testDataSource: vi.fn(() => Promise.resolve({ success: true, latency: 42, message: '连接成功' }))
}))

describe('DataSourceManagement.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountComponent(): VueWrapper {
    return mount(DataSourceManagement)
  }

  it('应正确挂载组件并渲染页面标题', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('h1').text()).toBe('数据源管理')
    expect(wrapper.find('.sub').exists()).toBe(true)
  })

  it('挂载后应自动加载数据源列表', async () => {
    const { listDataSources } = await import('@/api/datasource')
    mountComponent()
    await flushPromises()
    expect(listDataSources).toHaveBeenCalled()
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
    expect(vm.dsList).toHaveLength(2)
    expect(vm.total).toBe(2)
  })

  it('typeLabel 应正确映射数据源类型', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.typeLabel('mysql')).toBe('MySQL')
    expect(vm.typeLabel('clickhouse')).toBe('ClickHouse')
    expect(vm.typeLabel('kafka')).toBe('Kafka')
  })

  it('statusLabel 应正确映射连接状态', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.statusLabel('connected')).toBe('已连接')
    expect(vm.statusLabel('disconnected')).toBe('未连接')
    expect(vm.statusLabel('testing')).toBe('测试中')
  })

  it('statusTagType 应返回正确的 tag 类型', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.statusTagType('connected')).toBe('success')
    expect(vm.statusTagType('disconnected')).toBe('info')
    expect(vm.statusTagType('testing')).toBe('warning')
  })

  it('handleSearch 应重置页码', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.currentPage = 3
    vm.handleSearch()
    expect(vm.currentPage).toBe(1)
  })

  it('openCreateDialog 应打开弹窗', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.openCreateDialog()
    expect(vm.dialogVisible).toBe(true)
    expect(vm.isEdit).toBe(false)
  })

  it('openEditDialog 应填充表单数据', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.openEditDialog(mockDsList.list[0])
    expect(vm.dialogVisible).toBe(true)
    expect(vm.isEdit).toBe(true)
    expect(vm.formData.name).toBe('业务订单库')
    expect(vm.formData.type).toBe('mysql')
  })

  it('onTypeChange 应更新默认端口', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.onTypeChange('clickhouse')
    expect(vm.formData.port).toBe(8123)
    vm.onTypeChange('kafka')
    expect(vm.formData.port).toBe(9092)
  })

  it('needDatabase 对 kafka 应返回 false', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.formData.type = 'kafka'
    expect(vm.needDatabase).toBe(false)
    vm.formData.type = 'mysql'
    expect(vm.needDatabase).toBe(true)
  })

  it('resetForm 应重置表单', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.formData.name = '测试'
    vm.resetForm()
    expect(vm.formData.name).toBe('')
    expect(vm.formData.type).toBe('mysql')
    expect(vm.formData.port).toBe(3306)
  })
})
