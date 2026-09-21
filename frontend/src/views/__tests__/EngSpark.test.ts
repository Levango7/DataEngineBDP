/**
 * EngSpark.vue 单元测试
 *
 * 重点覆盖：工作空间切换后应重置页码并重载作业列表
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import enginesZh from '@/i18n/locales/modules/engines.zh-CN.json'
import enginesEn from '@/i18n/locales/modules/engines.en-US.json'

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

// 工作空间不再硬编码：app store 的 workspace 初始为空，由 fetchTenantInfo()
// 从租户 API 拉取填充（生产由 TopBar/App 初始化时调用）。测试按生产链路注入，
// 否则 appStore.workspace 为空 → 请求参数 workspaceId 为 undefined。
vi.mock('@/api/tenant', () => ({
  listAllTenants: vi.fn(() =>
    Promise.resolve([
      { id: 'tenant-1', name: '华东生产集群', plan: 'enterprise', resourceUsage: 42 }
    ])
  ),
  listTenants: vi.fn(),
  getTenant: vi.fn(),
  createTenant: vi.fn(),
  updateTenant: vi.fn(),
  deleteTenant: vi.fn()
}))

import EngSpark from '../engine/EngSpark.vue'
import { useAppStore } from '@/stores/app'

const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': enginesZh as never,
    'en-US': enginesEn as never
  }
})

describe('views/engine/EngSpark.vue 工作空间切换', () => {
  beforeEach(async () => {
    vi.clearAllMocks()
    setActivePinia(createPinia())
    getSparkJobsMock.mockResolvedValue({ list: [], total: 0, page: 1, pageSize: 20 })
    // 与生产一致：先由租户接口填充当前工作空间，再挂载页面
    await useAppStore().fetchTenantInfo()
  })

  async function mountPage() {
    const wrapper = mount(EngSpark, { global: { plugins: [i18n] } })
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
