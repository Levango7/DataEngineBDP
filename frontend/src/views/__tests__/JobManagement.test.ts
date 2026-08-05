/**
 * JobManagement.vue 单元测试
 *
 * 测试作业管理页面的组件挂载、列表加载、辅助函数、弹窗交互
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import JobManagement from '../JobManagement.vue'

// Mock job API
const mockJobList = {
  list: [
    {
      id: 'j1',
      name: '订单宽表ETL',
      workspaceId: 'ws1',
      type: 'batch' as const,
      status: 'running' as const,
      schedule: '0 0 * * *',
      owner: '张工',
      lastRunAt: '2024-06-01 00:00:00',
      lastRunDuration: 3600,
      createdAt: '2024-01-01',
      updatedAt: '2024-06-01'
    },
    {
      id: 'j2',
      name: '实时风控流',
      workspaceId: 'ws1',
      type: 'stream' as const,
      status: 'success' as const,
      owner: '李工',
      lastRunAt: '2024-05-30 12:00:00',
      lastRunDuration: 7200,
      createdAt: '2024-02-01',
      updatedAt: '2024-05-30'
    },
    {
      id: 'j3',
      name: '数据质量检查',
      workspaceId: 'ws1',
      type: 'sql' as const,
      status: 'failed' as const,
      owner: '王工',
      lastRunAt: '2024-05-29 08:00:00',
      lastRunDuration: 120,
      createdAt: '2024-03-01',
      updatedAt: '2024-05-29'
    }
  ],
  total: 3,
  page: 1,
  pageSize: 20
}

vi.mock('@/api/job', () => ({
  listJobs: vi.fn(() => Promise.resolve(mockJobList)),
  submitJob: vi.fn(() => Promise.resolve({ id: 'j4', name: '新作业' })),
  cancelJob: vi.fn(() => Promise.resolve()),
  deleteJob: vi.fn(() => Promise.resolve()),
  getJob: vi.fn(() => Promise.resolve(mockJobList.list[0])),
  getJobLogs: vi.fn(() => Promise.resolve('Job log content here...')),
  getJobStatus: vi.fn(() => Promise.resolve({ status: 'running', progress: 50 }))
}))

describe('JobManagement.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountComponent(): VueWrapper {
    return mount(JobManagement)
  }

  it('应正确挂载组件并渲染页面标题', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('h1').text()).toBe('作业管理')
    expect(wrapper.find('.sub').exists()).toBe(true)
  })

  it('挂载后应自动加载作业列表', async () => {
    const { listJobs } = await import('@/api/job')
    mountComponent()
    await flushPromises()
    expect(listJobs).toHaveBeenCalled()
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
    expect(vm.jobList).toHaveLength(3)
    expect(vm.total).toBe(3)
  })

  it('typeLabel 应正确映射作业类型', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.typeLabel('batch')).toBe('批作业')
    expect(vm.typeLabel('stream')).toBe('流作业')
    expect(vm.typeLabel('sql')).toBe('SQL')
    expect(vm.typeLabel('python')).toBe('Python')
    expect(vm.typeLabel('shell')).toBe('Shell')
  })

  it('statusLabel 应正确映射作业状态', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.statusLabel('running')).toBe('运行中')
    expect(vm.statusLabel('success')).toBe('已完成')
    expect(vm.statusLabel('failed')).toBe('失败')
    expect(vm.statusLabel('canceled')).toBe('已取消')
    expect(vm.statusLabel('pending')).toBe('等待中')
    expect(vm.statusLabel('scheduled')).toBe('已调度')
  })

  it('statusTagType 应返回正确的 tag 类型', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.statusTagType('running')).toBe('primary')
    expect(vm.statusTagType('success')).toBe('success')
    expect(vm.statusTagType('failed')).toBe('danger')
    expect(vm.statusTagType('pending')).toBe('info')
    expect(vm.statusTagType('scheduled')).toBe('warning')
  })

  it('canCancel 应判断是否可取消', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.canCancel('running')).toBe(true)
    expect(vm.canCancel('pending')).toBe(true)
    expect(vm.canCancel('scheduled')).toBe(true)
    expect(vm.canCancel('success')).toBe(false)
    expect(vm.canCancel('failed')).toBe(false)
  })

  it('formatDuration 应正确格式化耗时', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.formatDuration(30)).toBe('30s')
    expect(vm.formatDuration(90)).toBe('1m 30s')
    expect(vm.formatDuration(3600)).toBe('1h 0m')
    expect(vm.formatDuration(7380)).toBe('2h 3m')
    expect(vm.formatDuration(undefined)).toBe('--')
    expect(vm.formatDuration(0)).toBe('0s')
  })

  it('handleTabChange 应重置页码', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.currentPage = 3
    vm.handleTabChange()
    expect(vm.currentPage).toBe(1)
  })

  it('openSubmitDialog 应打开弹窗', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.openSubmitDialog()
    expect(vm.submitDialogVisible).toBe(true)
  })

  it('resetSubmitForm 应重置提交表单', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.submitForm.name = '测试作业'
    vm.resetSubmitForm()
    expect(vm.submitForm.name).toBe('')
    expect(vm.submitForm.type).toBe('sql')
    expect(vm.submitForm.engine).toBe('spark')
  })
})
