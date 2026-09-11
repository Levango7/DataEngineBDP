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
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import dashboardZh from '@/i18n/locales/modules/dashboard.zh-CN.json'
import dashboardEn from '@/i18n/locales/modules/dashboard.en-US.json'
import Dashboard from '../Dashboard.vue'

// 读取源文件内容（用于静态验证 emoji / 响应式断点 / design tokens）
const viewsDir = resolve(__dirname, '..')
const loginSrc = readFileSync(resolve(viewsDir, 'Login.vue'), 'utf-8')
const registerSrc = readFileSync(resolve(viewsDir, 'Register.vue'), 'utf-8')
const dashboardSrc = readFileSync(resolve(viewsDir, 'Dashboard.vue'), 'utf-8')
const analyzeSrc = readFileSync(resolve(viewsDir, 'Analyze.vue'), 'utf-8')

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

describe('Login.vue — emoji 清除与 design tokens', () => {
  it('不应包含 ☀️ / 🌙 emoji', () => {
    expect(loginSrc).not.toContain('☀️')
    expect(loginSrc).not.toContain('🌙')
  })

  it('主题切换按钮应使用内联 SVG 替代 emoji', () => {
    expect(loginSrc).toContain('<svg')
    // tb-pill-ic 容器内应有 SVG 图标
    expect(loginSrc).toContain('tb-pill-ic')
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
    const mediaBlock = dashboardSrc.match(
      /@media\s*\(max-width:\s*1024px\)\s*\{([\s\S]*?)\n\s*\}/
    )
    expect(mediaBlock).not.toBeNull()
    expect(mediaBlock![1]).toContain('repeat(2, 1fr)')
  })

  it('640px 断点应将网格退化为单列', () => {
    const mediaBlock = dashboardSrc.match(
      /@media\s*\(max-width:\s*640px\)\s*\{([\s\S]*?)\n\s*\}/
    )
    expect(mediaBlock).not.toBeNull()
    expect(mediaBlock![1]).toContain('1fr')
  })
})

describe('Analyze.vue — emoji 清除与响应式断点', () => {
  it('不应包含 📊 emoji', () => {
    expect(analyzeSrc).not.toContain('📊')
  })

  it('空态应使用内联 SVG 替代 emoji', () => {
    expect(analyzeSrc).toContain('<svg')
  })

  it('应包含 @media (max-width: 1024px) 平板断点', () => {
    expect(analyzeSrc).toContain('@media (max-width: 1024px)')
  })

  it('应包含 @media (max-width: 640px) 移动端断点', () => {
    expect(analyzeSrc).toContain('@media (max-width: 640px)')
  })

  it('1024px 断点应将三列面板网格退化为两列', () => {
    const mediaBlock = analyzeSrc.match(
      /@media\s*\(max-width:\s*1024px\)\s*\{([\s\S]*?)\n\s*\}/
    )
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