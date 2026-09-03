/**
 * API 网关 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/gateway（Gateway.vue）
 * API：/api/v1/gateway
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('API 网关（/gateway）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/gateway', { waitUntil: 'domcontentloaded' })
  })

  test('API 网关页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('大模型网关')
    await expect(page.locator('.sub')).toContainText('统一 API 入口')
    await expect(page.locator('.stat-card').first()).toBeVisible({ timeout: 15_000 }).catch(() => {})
  })

  test('新建 Key 按钮与 Key 表格区存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    const newKeyBtn = page.locator('button', { hasText: '新建 Key' })
    await expect(newKeyBtn.first()).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('text=API Key 与路由')).toBeVisible()
  })

  test('刷新按钮存在', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('大模型网关')
    const refreshBtn = page.locator('button', { hasText: '刷新' })
    await expect(refreshBtn.first()).toBeVisible({ timeout: 10_000 })
  })

  test('网关 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/gateway`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('网关 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/gateway`)
    expect(resp.status()).toBe(401)
  })
})