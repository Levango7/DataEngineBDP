/**
 * 运营后台 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/admin（Admin.vue）
 * API：/api/v1/admin/kpi、/api/v1/admin/env-matrix（encaps-tenant，JWT 保护）
 *
 * 覆盖要点：
 * - 页面加载（h1 标题"运营后台（平台侧）"）
 * - KPI 四卡片（租户/集群/营收/告警）/ 环境矩阵表格
 * - 运营后台 API 认证语义（200 with Bearer / 401 without）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('运营后台（/admin）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/admin', { waitUntil: 'domcontentloaded' })
  })

  test('运营后台页加载', async ({ page }) => {
    // h1 标题为"运营后台（平台侧）"
    await expect(page.locator('h1')).toContainText('运营后台')
    // PageHeader 副标题可见
    await expect(page.locator('.page-header__subtitle')).toBeVisible({ timeout: 15_000 })
  })

  test('KPI 四卡片存在', async ({ page }) => {
    // KPI 四卡片容器 .grid.g4 可见
    await expect(page.locator('.grid.g4')).toBeVisible({ timeout: 15_000 })
    // 四个 .card（租户总数/集群总数/月度营收/告警数）
    const cardCount = await page.locator('.grid.g4 .card').count()
    expect(cardCount).toBeGreaterThanOrEqual(4)
    // KPI 数值 .kpi 至少渲染一个
    await expect(page.locator('.kpi').first()).toBeVisible()
  })

  test('环境矩阵表格存在', async ({ page }) => {
    // 环境矩阵卡片（含 h3"环境矩阵"）
    await expect(page.locator('h3', { hasText: '环境矩阵' })).toBeVisible({ timeout: 15_000 })
    // el-table 渲染（环境矩阵表格）
    await expect(page.locator('el-table').first()).toBeVisible({ timeout: 15_000 })
  })

  test('运营 KPI API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/admin/kpi`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('运营 KPI API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/admin/kpi`)
    expect(resp.status()).toBe(401)
  })
})