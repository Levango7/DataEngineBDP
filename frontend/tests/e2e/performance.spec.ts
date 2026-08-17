/**
 * 前端性能测试 — 验证页面加载时间 < 2s（M-P-03）
 *
 * 测量指标：
 * - navigationStart → DOMContentLoaded（前端渲染完成时间）
 * - 使用 PerformanceNavigation Timing 精确测量
 *
 * 测试页面：Login / Dashboard / 资产管理 / 项目管理 / 检索
 *
 * 说明：
 * - 项目使用 hash 路由（/#/login、/#/dashboard 等）
 * - 使用 waitUntil: 'domcontentloaded' 测量前端渲染速度（不依赖后端 API）
 * - 复用 helpers.ts 的 login 函数完成登录
 */
import { test, expect } from '@playwright/test'
import { login, ensureLoggedIn } from './helpers'

/** M-P-03 验收标准：页面加载 < 2s */
const LOAD_TIME_LIMIT_MS = 2000

/**
 * 测量页面加载时间（毫秒）
 * 使用 Navigation Timing API 获取精确值，回退到 Date.now() 差值
 */
async function measurePageLoad(
  page: import('@playwright/test').Page,
  url: string
): Promise<{ loadTime: number; fcp: number; domContentLoaded: number }> {
  const start = Date.now()
  await page.goto(url, { waitUntil: 'domcontentloaded' })
  const wallTime = Date.now() - start

  // 通过 Performance API 获取精确指标
  const metrics = await page.evaluate(() => {
    const navEntries = performance.getEntriesByType('navigation') as PerformanceNavigationTiming[]
    const nav = navEntries[0]
    const fcpEntry = performance.getEntriesByName('first-contentful-paint')[0]
    return {
      domContentLoaded: nav ? nav.domContentLoadedEventEnd - nav.startTime : 0,
      loadEventEnd: nav ? nav.loadEventEnd - nav.startTime : 0,
      fcp: fcpEntry ? fcpEntry.startTime : 0,
      transferSize: nav ? nav.transferSize : 0
    }
  })

  // 优先使用 Performance API 的 domContentLoaded，回退到 wall time
  const domContentLoaded = metrics.domContentLoaded > 0 ? metrics.domContentLoaded : wallTime
  const fcp = metrics.fcp > 0 ? metrics.fcp : wallTime

  return { loadTime: domContentLoaded, fcp, domContentLoaded }
}

test.describe('前端页面加载性能 @slow', () => {
  test.describe.configure({ timeout: 60_000 })

  test('Login 页面加载 < 2s', async ({ page }) => {
    const { loadTime, fcp, domContentLoaded } = await measurePageLoad(page, '/#/login')
    console.log(`[Login] DOMContentLoaded=${domContentLoaded}ms, FCP=${fcp}ms, loadTime=${loadTime}ms`)
    expect(loadTime, `Login DOMContentLoaded ${loadTime}ms 应 < ${LOAD_TIME_LIMIT_MS}ms`).toBeLessThan(LOAD_TIME_LIMIT_MS)
  })

  test('Dashboard 页面加载 < 2s', async ({ page }) => {
    // 先登录
    await ensureLoggedIn(page)

    // 测量 Dashboard 加载
    const { loadTime, fcp, domContentLoaded } = await measurePageLoad(page, '/#/dashboard')
    console.log(`[Dashboard] DOMContentLoaded=${domContentLoaded}ms, FCP=${fcp}ms, loadTime=${loadTime}ms`)
    expect(loadTime, `Dashboard DOMContentLoaded ${loadTime}ms 应 < ${LOAD_TIME_LIMIT_MS}ms`).toBeLessThan(LOAD_TIME_LIMIT_MS)
  })

  test('资产管理页面加载 < 2s', async ({ page }) => {
    await ensureLoggedIn(page)

    // 资产管理路径为 /#/govern（资产目录）
    const { loadTime, fcp, domContentLoaded } = await measurePageLoad(page, '/#/govern')
    console.log(`[资产管理] DOMContentLoaded=${domContentLoaded}ms, FCP=${fcp}ms, loadTime=${loadTime}ms`)
    expect(loadTime, `资产管理 DOMContentLoaded ${loadTime}ms 应 < ${LOAD_TIME_LIMIT_MS}ms`).toBeLessThan(LOAD_TIME_LIMIT_MS)
  })

  test('项目管理页面加载 < 2s', async ({ page }) => {
    await ensureLoggedIn(page)

    const { loadTime, fcp, domContentLoaded } = await measurePageLoad(page, '/#/projects')
    console.log(`[项目管理] DOMContentLoaded=${domContentLoaded}ms, FCP=${fcp}ms, loadTime=${loadTime}ms`)
    expect(loadTime, `项目管理 DOMContentLoaded ${loadTime}ms 应 < ${LOAD_TIME_LIMIT_MS}ms`).toBeLessThan(LOAD_TIME_LIMIT_MS)
  })

  test('检索页面加载 < 2s', async ({ page }) => {
    await ensureLoggedIn(page)

    const { loadTime, fcp, domContentLoaded } = await measurePageLoad(page, '/#/search')
    console.log(`[检索] DOMContentLoaded=${domContentLoaded}ms, FCP=${fcp}ms, loadTime=${loadTime}ms`)
    expect(loadTime, `检索 DOMContentLoaded ${loadTime}ms 应 < ${LOAD_TIME_LIMIT_MS}ms`).toBeLessThan(LOAD_TIME_LIMIT_MS)
  })
})