/**
 * Login / Register / Dashboard / Analyze 前端修复验证测试
 *
 * 验证内容（对应任务 B2-1）：
 * 1. emoji 已清除：Login.vue 无 ☀️/🌙，Analyze.vue 无 📊，均替换为内联 SVG
 * 2. 响应式断点：Dashboard.vue / Analyze.vue 含 @media (max-width: 1024px) 与 640px
 * 3. design tokens 使用：Login.vue / Register.vue 亮色 scoped 部分使用 var(--ds-*) 变量
 * 4. Dashboard 组件挂载：能正确挂载并渲染工作台标题与 KPI 卡片
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createI18n } from 'vue-i18n'
import dashboardZh from '@/i18n/locales/modules/dashboard.zh-CN.json'
import dashboardEn from '@/i18n/locales/modules/dashboard.en-US.json'
import Dashboard from '../Dashboard.vue'
import Login from '../Login.vue'
import Analyze from '../Analyze.vue'
// 读取源文件内容（用于静态验证 emoji / 响应式断点 / design tokens）。
// 用 Vite 的 `?raw` 导入代替 node:fs + __dirname：tsconfig 未引入 @types/node，
// `node:fs` / `node:path` / `__dirname` 在 vue-tsc 下没有类型声明（TS2307 / TS2304）
import loginSrc from '../Login.vue?raw'
import registerSrc from '../Register.vue?raw'
import dashboardSrc from '../Dashboard.vue?raw'
import analyzeSrc from '../Analyze.vue?raw'

// i18n 实例（仅加载 dashboard 词条，足够挂载 Dashboard）
const i18n = createI18n({
  legacy: false,
  locale: 'zh-CN',
  messages: {
    'zh-CN': dashboardZh as never,
    'en-US': dashboardEn as never
  }
})

// Mock cluster API：返回稳定的集群概览数据
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
  projectRunning: 6,
  jobCount: 45,
  jobSuccessToday: 38,
  jobFailToday: 2,
  assetCount: 156,
  trendCpu: [55, 60, 58, 62, 65, 63, 62],
  trendMem: [60, 62, 65, 68, 70, 67, 64]
}

vi.mock('@/api/cluster', () => ({
  getClusterOverview: vi.fn(() => Promise.resolve(mockOverview))
}))

// Mock sec API：app store 依赖，返回空审批列表
vi.mock('@/api/sec', () => ({
  listApprovals: vi.fn(() => Promise.resolve([])),
  approveApproval: vi.fn(() => Promise.resolve()),
  rejectApproval: vi.fn(() => Promise.resolve())
}))

// Mock vue-router 的 useRouter（Dashboard 快捷操作用 router.push 跳转）
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {} })
}))

// Mock analyze API：挂载 Analyze 时用于验证空态图标（无看板数据 → 空态）
vi.mock('@/api/analyze', () => ({
  getRealtimeMetrics: vi.fn(() => Promise.resolve([])),
  listDashboards: vi.fn(() => Promise.resolve({ list: [], total: 0, page: 1, pageSize: 20 })),
  createDashboard: vi.fn(() => Promise.resolve({ id: 'b1' })),
  deleteDashboard: vi.fn(() => Promise.resolve())
}))

describe('Login.vue — emoji 清除与 design tokens', () => {
  it('不应包含 ☀️ / 🌙 emoji', () => {
    expect(loginSrc).not.toContain('☀️')
    expect(loginSrc).not.toContain('🌙')
  })

  it('主题切换按钮应使用图标（渲染为 SVG）替代 emoji', async () => {
    // 静态校验：源码里不得出现 emoji 字符，且切换按钮容器仍在
    expect(loginSrc).not.toContain('☀️')
    expect(loginSrc).not.toContain('🌙')
    expect(loginSrc).toContain('tb-pill-ic')

    // 运行时校验：主题切换按钮渲染出真正的 <svg>（Element Plus 图标组件 Sunny / Moon），
    // 而不是 emoji 字符。比"源码里必须出现字面量 <svg>"更贴近用户可见行为
    setActivePinia(createPinia())
    const wrapper = mount(Login)
    await flushPromises()

    const toggleIcon = wrapper.find('.tb-pill-ic')
    expect(toggleIcon.exists()).toBe(true)
    expect(toggleIcon.find('svg').exists()).toBe(true)
    expect(toggleIcon.text()).not.toContain('☀️')
    expect(toggleIcon.text()).not.toContain('🌙')
    wrapper.unmount()
  })

  it('亮色 scoped 部分应使用 --ds-* design token 变量', () => {
    // 主文字色应使用 --ds-text-primary 而非硬编码 #0f172a
    expect(loginSrc).toContain('var(--ds-text-primary)')
    // 次文字色应使用 --ds-text-tertiary 而非硬编码 #64748b
    expect(loginSrc).toContain('var(--ds-text-tertiary)')
    // 品牌主色应使用 --ds-color-primary-500
    expect(loginSrc).toContain('var(--ds-color-primary-500)')
  })
})

describe('Register.vue — design tokens', () => {
  it('亮色 scoped 部分应使用 --ds-* design token 变量', () => {
    expect(registerSrc).toContain('var(--ds-text-primary)')
    expect(registerSrc).toContain('var(--ds-text-tertiary)')
    expect(registerSrc).toContain('var(--ds-color-primary-500)')
    // 错误色应使用 --ds-color-error-* 系列
    expect(registerSrc).toContain('var(--ds-color-error-')
  })
})

describe('Dashboard.vue — 响应式断点', () => {
  it('应包含 @media (max-width: 1024px) 平板断点', () => {
    expect(dashboardSrc).toContain('@media (max-width: 1024px)')
  })

  it('应包含 @media (max-width: 640px) 移动端断点', () => {
    expect(dashboardSrc).toContain('@media (max-width: 640px)')
  })

  it('1024px 断点应将四列网格退化为两列', () => {
    // 提取 1024px 媒体查询块内容
    const mediaBlock = dashboardSrc.match(/@media\s*\(max-width:\s*1024px\)\s*\{([\s\S]*?)\n\s*\}/)
    expect(mediaBlock).not.toBeNull()
    expect(mediaBlock![1]).toContain('repeat(2, 1fr)')
  })

  it('640px 断点应将网格退化为单列', () => {
    const mediaBlock = dashboardSrc.match(/@media\s*\(max-width:\s*640px\)\s*\{([\s\S]*?)\n\s*\}/)
    expect(mediaBlock).not.toBeNull()
    expect(mediaBlock![1]).toContain('1fr')
  })
})

describe('Analyze.vue — emoji 清除与响应式断点', () => {
  it('不应包含 📊 emoji', () => {
    expect(analyzeSrc).not.toContain('📊')
  })

  it('空态应使用图标（渲染为 SVG）替代 emoji', async () => {
    // 静态校验：源码里不得出现 📊 emoji
    expect(analyzeSrc).not.toContain('📊')

    // 运行时校验：无看板数据时进入空态，空态卡片渲染出 <svg> 图标（DataAnalysis）
    setActivePinia(createPinia())
    const wrapper = mount(Analyze)
    await flushPromises()

    const emptyCard = wrapper.find('.card')
    expect(emptyCard.exists()).toBe(true)
    expect(emptyCard.find('svg').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('📊')
    wrapper.unmount()
  })

  it('应包含 @media (max-width: 1024px) 平板断点', () => {
    expect(analyzeSrc).toContain('@media (max-width: 1024px)')
  })

  it('应包含 @media (max-width: 640px) 移动端断点', () => {
    expect(analyzeSrc).toContain('@media (max-width: 640px)')
  })

  it('1024px 断点应将三列面板网格退化为两列', () => {
    const mediaBlock = analyzeSrc.match(/@media\s*\(max-width:\s*1024px\)\s*\{([\s\S]*?)\n\s*\}/)
    expect(mediaBlock).not.toBeNull()
    expect(mediaBlock![1]).toContain('repeat(2, 1fr)')
  })
})

describe('Dashboard.vue — 组件挂载', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  function mountComponent() {
    return mount(Dashboard, { global: { plugins: [i18n] } })
  }

  it('应正确挂载并渲染工作台标题', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    expect(wrapper.find('h1').text()).toBe('工作台')
  })

  it('挂载后应加载集群概览数据', async () => {
    const { getClusterOverview } = await import('@/api/cluster')
    mountComponent()
    await flushPromises()
    expect(getClusterOverview).toHaveBeenCalled()
  })

  it('概览数据加载后应渲染 KPI 卡片', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    // 四个 KPI 卡片
    const cards = wrapper.findAll('.card')
    expect(cards.length).toBeGreaterThanOrEqual(4)
  })

  it('应渲染快捷操作 chips', async () => {
    const wrapper = mountComponent()
    await flushPromises()
    const chips = wrapper.findAll('.chip')
    expect(chips.length).toBeGreaterThan(0)
  })
})
