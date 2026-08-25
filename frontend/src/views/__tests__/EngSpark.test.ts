/**
 * EngSpark.vue 单元测试
 *
 * 重点覆盖：工作空间切换后应重置页码并重载作业列表
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'

const { getSparkJobsMock } = vi.hoisted(() => ({
  getSparkJobsMock: vi.fn()
}))

vi.mock('@/api/engine', () => ({
  getSparkJobs: getSparkJobsMock,
  submitSparkJob: vi.fn(),
  runSparkJob: vi.fn(),
  cancelSparkJob: vi.fn(),
  deleteSparkJob: vi.fn(),
  getSparkJobLogs: vi.fn(() => Promise.resolve([]))
}))

import EngSpark from '../engine/EngSpark.vue'
import { useAppStore } from '@/stores/app'

describe('views/engine/EngSpark.vue 工作空间切换', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    getSparkJobsMock.mockResolvedValue({ list: [], total: 0, page: 1, pageSize: 20 })
  })

  async function mountPage() {
    const wrapper = mount(EngSpark)
    await flushPromises()
    return wrapper
  }

  it('挂载时应以当前工作空间加载作业列表', async () => {
    const wrapper = await mountPage()
    expect(getSparkJobsMock).toHaveBeenCalledTimes(1)
    expect(getSparkJobsMock.mock.calls[0][0]).toEqual(
      expect.objectContaining({ workspaceId: '华东生产集群' })
    )
    wrapper.unmount()
  })

  it('切换工作空间后应触发列表重载并携带新工作空间 ID', async () => {
    const wrapper = await mountPage()
    const appStore = useAppStore()

    appStore.setWorkspace('测试空间')
    await flushPromises()

    expect(getSparkJobsMock).toHaveBeenCalledTimes(2)
    expect(getSparkJobsMock.mock.calls[1][0]).toEqual(
      expect.objectContaining({ workspaceId: '测试空间' })
    )
    wrapper.unmount()
  })

  it('工作空间未变化时不应重复加载', async () => {
    const wrapper = await mountPage()
    await flushPromises()
    expect(getSparkJobsMock).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })
})
