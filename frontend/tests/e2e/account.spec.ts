/**
 * 账户与配额 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/account（Account.vue）
 * API：/api/v1/account/plan、/api/v1/account/billing（encaps-tenant，JWT 保护）
 *
 * 覆盖要点：
 * - 页面加载（h1 标题"账户与配额"）
 * - 套餐卡片 / 配额进度条 / 升级按钮 / 计费明细表格
 * - 账户 API 认证语义（200 with Bearer / 401 without）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('账户与配额（/account）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/account', { waitUntil: 'domcontentloaded' })
  })

  test('账户页加载', async ({ page }) => {
    // h1 标题为"账户与配额"
    await expect(page.locator('h1')).toContainText('账户与配额')
    // PageHeader 副标题可见
    await expect(page.locator('.page-header__subtitle')).toBeVisible({ timeout: 15_000 })
  })

  test('套餐卡片与升级按钮存在', async ({ page }) => {
    // 套餐卡片（含"当前套餐"标题）
    await expect(page.locator('.card').first()).toBeVisible({ timeout: 15_000 })
    // 升级套餐按钮（.btn.ghost.sm，文案"升级套餐"）
    const upgradeBtn = page.locator('button.btn.ghost.sm', { hasText: '升级套餐' })
    await expect(upgradeBtn).toBeVisible({ timeout: 15_000 })
  })

  test('计费明细表格存在', async ({ page }) => {
    // 计费明细卡片（含 h3"计费明细"）
    await expect(page.locator('h3', { hasText: '计费明细' })).toBeVisible({ timeout: 15_000 })
    // el-table 渲染（计费明细表格）
    await expect(page.locator('el-table').first()).toBeVisible({ timeout: 15_000 })
  })

  test('账户套餐 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/account/plan`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('账户套餐 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/account/plan`)
    expect(resp.status()).toBe(401)
  })
})