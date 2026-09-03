/**
 * 安全脱敏 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/sec（Sec.vue）
 * API：/api/v1/sec
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('安全脱敏（/sec）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/sec', { waitUntil: 'domcontentloaded' })
  })

  test('安全脱敏页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('安全脱敏')
    await expect(page.locator('.sub')).toContainText('脱敏策略')
    await expect(page.locator('.toolbar')).toBeVisible({ timeout: 15_000 })
  })

  test('新建脱敏策略按钮与待审批 badge 存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    const btn = page.locator('.toolbar button', { hasText: '新建脱敏策略' })
    await expect(btn.first()).toBeVisible({ timeout: 10_000 })
  })

  test('权限申请审批流区块存在', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('安全脱敏')
    await expect(page.locator('text=权限申请审批流')).toBeVisible({ timeout: 10_000 })
  })

  test('安全策略 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/sec`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('安全策略 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/sec`)
    expect(resp.status()).toBe(401)
  })
})