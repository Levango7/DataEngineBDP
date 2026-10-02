/**
 * 配额管理 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/quota-management（QuotaManagement.vue）
 * API：/api/v1/quotas
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('配额管理（/quota-management）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/quota-management', { waitUntil: 'domcontentloaded' })
  })

  test('配额管理页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('配额管理')
    await expect(page.locator('.page-header__subtitle')).toContainText('ResourceQuota')
    await expect(page.locator('.toolbar')).toBeVisible({ timeout: 15_000 })
  })

  test('设置配额按钮与筛选框存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    const btn = page.locator('.toolbar button', { hasText: '设置配额' })
    await expect(btn.first()).toBeVisible({ timeout: 10_000 })
    // 筛选框为 Element Plus <el-select>（渲染为 .el-select，placeholder 文本而非 input 属性）
    await expect(page.locator('.toolbar .el-select').first()).toBeVisible()
  })

  test('配额列表表格与刷新按钮存在', async ({ page }) => {
    await page.waitForTimeout(2_000)
    await expect(page.locator('h1')).toContainText('配额管理')
    await page.waitForSelector('.el-table, table', { timeout: 15_000 })
    const refreshBtn = page.locator('button[aria-label="刷新配额列表"]')
    await expect(refreshBtn).toBeVisible()
  })

  test('配额 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/quotas`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('配额 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/quotas`)
    expect(resp.status()).toBe(401)
  })
})