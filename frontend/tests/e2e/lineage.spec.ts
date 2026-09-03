/**
 * 数据血缘 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/lineage（Lineage.vue）
 * API：/lineage/api/v1/lineage/upstream/{table} 等（独立服务 :8089，经 vite proxy 转发）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, getApiToken } from './helpers'

test.describe('数据血缘（/lineage）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/lineage', { waitUntil: 'domcontentloaded' })
  })

  test('数据血缘页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('血缘分析')
    await expect(page.locator('.legend')).toBeVisible()
    await expect(page.locator('text=■ 上游')).toBeVisible()
  })

  test('上下游与影响四列渲染（空态或数据）', async ({ page }) => {
    await page.waitForTimeout(2_000)
    await expect(page.locator('h1')).toContainText('血缘分析')
    const lvls = page.locator('.lineage .lvl')
    const count = await lvls.count()
    expect(count).toBeGreaterThanOrEqual(4)
  })

  test('血缘 API 返回 200（Bearer 认证，独立前缀 /lineage）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get('/lineage/api/v1/lineage/upstream/dwd.order_wide', {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('血缘 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get('/lineage/api/v1/lineage/upstream/dwd.order_wide')
    expect(resp.status()).toBe(401)
  })
})