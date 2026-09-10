/**
 * Integrate.vue 单元测试
 *
 * 测试数据集成页面的组件挂载、连接器列表、任务列表、辅助函数、Element Plus 组件替换后的交互
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import integrateZh from '@/i18n/locales/modules/integrate.zh-CN.json'
import integrateEn from '@/i18n/locales/modules/integrate.en-US.json'
import Integrate from '../Integrate.vue'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': integrateZh as never,
    'en-US': integrateEn as never
  }
})

// Mock integrate API
const mockConnectors = [
  {
    name: 'MySQL',
    logo: 'M',
    status: 'connected' as const,
    type: 'jdbc',
    category: 'source' as const
  },
  {
    name: 'Iceberg',
    logo: 'I',
    status: 'connected' as const,
    type: 'iceberg',
    category: 'sink' as const
  },
  {
    name: 'Kafka',
    logo: 'K',
    status: 'pending_config' as const,
    type: 'kafka',
    category: 'source' as const
  }
]

const mockTaskList = {
  list: [
    {
      id: 'task1',
      name: '订单全量同步',
      sourceType: 'MySQL',
      targetType: 'Iceberg',
      sourceToTarget: 'MySQL → Iceberg',
      mode: 'batch' as const,
      status: 'success' as const,
      schedule: '0 4 * * *',
      lastRunAt: '2024-01-15 04:00',
      lastRunDuration: '12s',
      createdAt: '2024-01-15',
      updatedAt: '2024-01-15'
    },
    {
      id: 'task2',
      name: '用户CDC',
      sourceType: 'MySQL',
      targetType: 'Iceberg',
      sourceToTarget: 'MySQL → Iceberg',
      mode: 'stream_cdc' as const,
      status: 'running' as const,
      createdAt: '2024-02-01',
      updatedAt: '2024-02-01'
    }
  ],
  total: 2,
  page: 1,
  pageSize: 100
}

vi.mock('@/api/integrate', () => ({
  listConnectors: vi.fn(() => Promise.resolve(mockConnectors)),
  listSyncTasks: vi.fn(() => Promise.resolve(mockTaskList)),
  createSyncTask: vi.fn(() =>
    Promise.resolve({ id: 'task3', name: '新任务', sourceType: '', targetType: '', mode: 'batch' })
  ),
  updateSyncTask: vi.fn(() => Promise.resolve({ id: 'task1', name: '更新任务' })),
  deleteSyncTask: vi.fn(() => Promise.resolve()),
  getSyncTask: vi.fn(() => Promise.resolve(mockTaskList.list[0])),
  runSyncTask: vi.fn(() => Promise.resolve()),
  stopSyncTask: vi.fn(() => Promise.resolve())
}))

// Mock ElMessageBox
vi.mock('element-plus', () => ({
  ElMessageBox: {
    confirm: vi.fn(() => Promise.resolve())
  }
}))

describe('Integrate.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountComponent(): VueWrapper {
    return mount(Integrate, { global: { plugins: [i18n] } })
  }

  it('应正确挂载组件', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.exists()).toBe(true)
  })

  it('挂载后应自动加载连接器列表', async () => {
    const { listConnectors } = await import('@/api/integrate')
    mountComponent()
    await flushPromises()
    expect(listConnectors).toHaveBeenCalled()
  })

  it('挂载后应自动加载同步任务列表', async () => {
    const { listSyncTasks } = await import('@/api/integrate')
    mountComponent()
    await flushPromises()
    expect(listSyncTasks).toHaveBeenCalled()
  })

  it('应使用 el-table 渲染任务列表（而非原生 table）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.el-table').exists()).toBe(true)
    expect(wrapper.find('select').exists()).toBe(false)
  })

  it('应包含连接器网格', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.conn-grid').exists()).toBe(true)
  })

  it('应包含卡片容器', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.card').exists()).toBe(true)
  })

  it('modeLabel 应正确映射同步模式', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.modeLabel('batch')).toBe('批')
    expect(vm.modeLabel('stream_cdc')).toBe('流')
  })

  it('connectorPillClass 应返回正确的 pill 样式类', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.connectorPillClass('connected')).toBe('g')
    expect(vm.connectorPillClass('pending_config')).toBe('a')
    expect(vm.connectorPillClass('pending_auth')).toBe('a')
    expect(vm.connectorPillClass('disconnected')).toBe('b')
  })

  it('connectorPillText 应返回正确的连接器状态文案', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.connectorPillText('connected')).toBe('已连通')
    expect(vm.connectorPillText('pending_config')).toBe('待配置')
    expect(vm.connectorPillText('pending_auth')).toBe('待授权')
    expect(vm.connectorPillText('disconnected')).toBe('未连通')
  })

  it('statusPillClass 应返回正确的任务状态 pill 样式类', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.statusPillClass('success')).toBe('g')
    expect(vm.statusPillClass('running')).toBe('a')
    expect(vm.statusPillClass('failed')).toBe('r')
    expect(vm.statusPillClass('pending')).toBe('b')
  })

  it('statusPillText 应返回正确的任务状态文案', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.statusPillText('success')).toBe('成功')
    expect(vm.statusPillText('running')).toBe('运行中')
    expect(vm.statusPillText('failed')).toBe('失败')
    expect(vm.statusPillText('pending')).toBe('等待中')
    expect(vm.statusPillText('stopped')).toBe('已停止')
  })

  it('sourceConnectors 应过滤出 source 类型的连接器', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.sourceConnectors.length).toBe(2) // MySQL + Kafka
    expect(vm.sourceConnectors.some((c: any) => c.name === 'MySQL')).toBe(true)
    expect(vm.sourceConnectors.some((c: any) => c.name === 'Kafka')).toBe(true)
  })

  it('sinkConnectors 应过滤出 sink 类型的连接器', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.sinkConnectors.length).toBe(1) // Iceberg
    expect(vm.sinkConnectors[0].name).toBe('Iceberg')
  })

  it('isConnectorSelected 应正确判断连接器选中状态', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.isConnectorSelected(mockConnectors[0])).toBe(false)
    vm.selectedConnectorName = 'MySQL'
    expect(vm.isConnectorSelected(mockConnectors[0])).toBe(true)
  })

  it('任务操作列应使用 el-button（而非原生 button）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.el-button').exists()).toBe(true)
  })

  it('新建同步任务弹窗应使用 el-input 和 el-select（而非原生 input/select）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.syncModal = true
    await flushPromises()
    // Modal 使用 Teleport to body，需在 document.body 中查找
    expect(document.body.querySelector('.el-input')).not.toBeNull()
    expect(document.body.querySelector('.el-select')).not.toBeNull()
    expect(document.body.querySelector('select')).toBeNull()
    vm.syncModal = false
    await flushPromises()
  })

  it('新增数据源弹窗应使用 el-input 和 el-select（而非原生 input/select）', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.srcModal = true
    await flushPromises()
    expect(document.body.querySelector('.el-input')).not.toBeNull()
    expect(document.body.querySelector('.el-select')).not.toBeNull()
    expect(document.body.querySelector('select')).toBeNull()
    vm.srcModal = false
    await flushPromises()
  })

  it('handleRunTask 应调用 runSyncTask API', async () => {
    const { runSyncTask } = await import('@/api/integrate')
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    await vm.handleRunTask(mockTaskList.list[0])
    expect(runSyncTask).toHaveBeenCalledWith('task1')
  })

  it('openSyncModal 应重置表单并打开弹窗', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.openSyncModal()
    expect(vm.syncModal).toBe(true)
    expect(vm.syncForm.name).toBe('')
    expect(vm.syncForm.sourceTable).toBe('')
    expect(vm.syncForm.targetTable).toBe('')
    expect(vm.syncFormError).toBe('')
  })

  it('handleCreateSyncTask 缺少任务名应设置错误提示', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.syncForm.name = ''
    await vm.handleCreateSyncTask()
    expect(vm.syncFormError).toBe('请输入任务名')
  })

  it('handleCreateSyncTask 缺少源表应设置错误提示', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.syncForm.name = '测试任务'
    vm.syncForm.sourceTable = ''
    await vm.handleCreateSyncTask()
    expect(vm.syncFormError).toBe('请输入源表')
  })

  it('handleCreateSyncTask 缺少目标表应设置错误提示', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    vm.syncForm.name = '测试任务'
    vm.syncForm.sourceTable = 'orders'
    vm.syncForm.targetTable = ''
    await vm.handleCreateSyncTask()
    expect(vm.syncFormError).toBe('请输入目标表')
  })
})
