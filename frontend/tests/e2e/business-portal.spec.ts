/**
 * 业务线门户 E2E（Sprint 3.1.2）
 *
 * 页面：/#/ops-portal（BusinessPortal.vue）
 * 后端：business-portal（Python/FastAPI，nightly 栈宿主机 18093，AUTH_MODE=none）
 * API：/api/v1/business-lines
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase } from './helpers'

test.describe('业务线门户 /ops-portal', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/ops-portal', { waitUntil: 'domcontentloaded' })
  })

  test('业务线门户页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('业务线门户')
    // 主内容卡片
    await expect(page.locator('.card').first()).toBeVisible({ timeout: 10_000 })
  })

  test('业务线列表 API 带租户头返回 200 数组', async ({ request }) => {
    // business-portal 在 AUTH_MODE=none 下从 X-Tenant-Id header 读租户
    const resp = await request.get(`${apiBase}/business-lines`, {
      headers: { 'X-Tenant-Id': 'platform-admin' }
    })
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(Array.isArray(body)).toBe(true)
  })
})
