/**
 * 开放 API 市场 E2E（Sprint 3.1.2 + Sprint 3.2 补回订阅列表）
 *
 * 页面：/#/ops-api（APIMarket.vue）
 * 后端：open-api-catalog（Python/FastAPI，nightly 栈宿主机 18095，AUTH_MODE=none）
 * API：/api/v1/apis（目录）、/api/v1/subscriptions（订阅审批）
 *
 * Sprint 3.2 修订：Sprint 3.1 移除的订阅列表 spec 补回（open-api-catalog 本就提供
 * GET /subscriptions 根路由，Sprint 3.1 是误删——彼时以为 /subscriptions 仅归 asset-exchange）。
 * Sprint 4.2 修订：asset-exchange 改独立前缀 /asset-subscriptions 后，
 * /subscriptions 唯一归属 open-api-catalog，vite proxy 精确分流无歧义。
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase } from './helpers'

test.describe('开放 API /ops-api', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/ops-api', { waitUntil: 'domcontentloaded' })
  })

  test('开放 API 页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('开放 API')
    await expect(page.locator('input[placeholder*="搜索 API"]')).toBeVisible()
  })

  test('API 目录列表匿名可达返回 200 数组', async ({ request }) => {
    const resp = await request.get(`${apiBase}/apis`)
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(Array.isArray(body)).toBe(true)
  })

  test('订阅审批列表匿名可达返回 200 数组（Sprint 3.2 补回）', async ({ request }) => {
    // Sprint 3.2：open-api-catalog 本就提供 GET /subscriptions 根路由（Sprint 3.1 误删）
    const resp = await request.get(`${apiBase}/subscriptions`)
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(Array.isArray(body)).toBe(true)
  })
})
