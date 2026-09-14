/**
 * 数据分析（BI 分析）E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/analyze（Analyze.vue）
 * API：/api/v1/dashboards、/api/v1/dashboards/realtime
 *
 * 布局：看板列表（ECharts 面板）+ 实时指标卡片 + 新建看板弹窗
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('数据分析·BI 分析（/analyze）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/analyze', { waitUntil: 'domcontentloaded' })
  })

  test('BI 分析页加载', async ({ page }) => {
    // PageHeader 标题
    await expect(page.locator('h1')).toContainText('BI 分析')
    // 子标题
    await expect(page.locator('.page-header__subtitle')).toContainText('ECharts')
    // 统一 SQL 网关标识
    await expect(page.locator('.pill.b', { hasText: '统一 SQL 网关' })).toBeVisible({
      timeout: 15_000
    })
  })

  test('新建看板按钮与实时指标卡片存在', async ({ page }) => {
    // 新建看板按钮
    await expect(page.locator('button', { hasText: '新建看板' })).toBeVisible({
      timeout: 15_000
    })
    // 实时指标卡片标题
    await expect(page.locator('h3', { hasText: '实时指标' })).toBeVisible({ timeout: 15_000 })
  })

  test('看板列表 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/dashboards`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('看板列表 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/dashboards`)
    expect(resp.status()).toBe(401)
  })
})