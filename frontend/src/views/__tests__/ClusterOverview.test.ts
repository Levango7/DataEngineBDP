/**
 * ClusterOverview.vue 单元测试
 *
 * 测试集群概览页面的组件挂载、概览数据加载、计算属性、辅助函数
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises, VueWrapper } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ClusterOverview from '../ClusterOverview.vue'

// Mock cluster API
const mockOverview = {
  clusterName: 'prod-cluster',
  version: '1.28.3',
  nodeTotal: 6,
  nodeReady: 5,
  podTotal: 120,
  podRunning: 110,
  cpuCapacity: 96,
  cpuUsed: 62,
  memCapacity: 384,
  memUsed: 245,
  storageUsed: 12.5,
  projectCount: 8,
  jobCount: 45,
  jobSuccessToday: 38,
  jobFailToday: 2,
  assetCount: 156,
  trendCpu: [55, 60, 58, 62, 65, 63, 62],
  trendMem: [60, 62, 65, 68, 70, 67, 64]
}

const mockNodes = [
  {
    id: 'n1',
    name: 'node-1',
    roles: ['master'],
    status: 'ready' as const,
    cpuCapacity: 32,
    cpuUsed: 20,
    memCapacity: 128,
    memUsed: 80,
    podCount: 30,
    podCapacity: 110,
    osImage: 'Ubuntu 22.04',
    createdAt: '2024-01-01'
  },
  {
    id: 'n2',
    name: 'node-2',
    roles: ['worker'],
    status: 'ready' as const,
    cpuCapacity: 32,
    cpuUsed: 25,
    memCapacity: 128,
    memUsed: 90,
    podCount: 35,
    podCapacity: 110,
    osImage: 'Ubuntu 22.04',
    createdAt: '2024-01-01'
  }
]

vi.mock('@/api/cluster', () => ({
  getClusterOverview: vi.fn(() => Promise.resolve(mockOverview)),
  listNodes: vi.fn(() => Promise.resolve(mockNodes)),
  listPods: vi.fn(() => Promise.resolve([])),
  listComponentStatuses: vi.fn(() =>
    Promise.resolve([
      { name: 'Spark', status: 'healthy', meta: '3/3 运行' },
      { name: 'Flink', status: 'warning', meta: '2/3 运行' },
      { name: 'Doris', status: 'error', meta: '1/3 运行' }
    ])
  )
}))

describe('ClusterOverview.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountComponent(): VueWrapper {
    return mount(ClusterOverview)
  }

  it('应正确挂载组件并渲染页面标题', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('h1').text()).toBe('集群概览')
  })

  it('挂载后应加载集群概览和节点列表', async () => {
    const { getClusterOverview, listNodes } = await import('@/api/cluster')
    mountComponent()
    await flushPromises()
    expect(getClusterOverview).toHaveBeenCalled()
    expect(listNodes).toHaveBeenCalled()
  })

  it('应渲染统计卡片行', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('.stat-row').exists()).toBe(true)
  })

  it('概览数据加载后应更新内部状态', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.overview).not.toBeNull()
    expect(vm.overview.nodeTotal).toBe(6)
    expect(vm.overview.nodeReady).toBe(5)
  })

  it('节点列表加载后应更新 nodeList', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.nodeList).toHaveLength(2)
    expect(vm.nodesLoading).toBe(false)
  })

  it('应包含大数据组件状态卡片', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const compCards = wrapper.findAll('.comp-card')
    expect(compCards.length).toBeGreaterThan(0)
  })

  it('组件状态应包含 healthy/warning/error 类型', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const healthyCards = wrapper.findAll('.comp-card.healthy')
    const warningCards = wrapper.findAll('.comp-card.warning')
    const errorCards = wrapper.findAll('.comp-card.error')
    expect(healthyCards.length).toBeGreaterThan(0)
    expect(warningCards.length + errorCards.length).toBeGreaterThan(0)
  })

  it('nodeStatusLabel 应正确映射节点状态', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.nodeStatusLabel('ready')).toBe('就绪')
    expect(vm.nodeStatusLabel('not-ready')).toBe('未就绪')
    expect(vm.nodeStatusLabel('unknown')).toBe('未知')
  })

  it('nodeStatusType 应返回正确的 tag 类型', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.nodeStatusType('ready')).toBe('success')
    expect(vm.nodeStatusType('not-ready')).toBe('danger')
    expect(vm.nodeStatusType('unknown')).toBe('info')
  })

  it('usageLevel 应根据百分比返回等级', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.usageLevel(95)).toBe('danger')
    expect(vm.usageLevel(75)).toBe('warning')
    expect(vm.usageLevel(50)).toBe('healthy')
  })

  it('usageColor 应根据百分比返回颜色', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.usageColor(95)).toBe('#c0504d')
    expect(vm.usageColor(75)).toBe('#c08a2e')
    expect(vm.usageColor(50)).toBe('#2f9e6f')
  })

  it('compStatusLabel 应正确映射组件状态', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.compStatusLabel('healthy')).toBe('健康')
    expect(vm.compStatusLabel('warning')).toBe('警告')
    expect(vm.compStatusLabel('error')).toBe('故障')
  })

  it('nodeCpuPercent 应正确计算 CPU 使用率', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.nodeCpuPercent({ cpuCapacity: 32, cpuUsed: 20 })).toBe(63)
    expect(vm.nodeCpuPercent({ cpuCapacity: 0, cpuUsed: 0 })).toBe(0)
  })

  it('nodeMemPercent 应正确计算内存使用率', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const vm = wrapper.vm as any
    expect(vm.nodeMemPercent({ memCapacity: 128, memUsed: 90 })).toBe(70)
    expect(vm.nodeMemPercent({ memCapacity: 0, memUsed: 0 })).toBe(0)
  })
})
