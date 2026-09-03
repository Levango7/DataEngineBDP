/**
 * 行业应用模板 E2E（Sprint 3.1.2）
 *
 * 页面：/#/ops-tpl（TemplateMarket.vue）
 * 后端：industry-templates（Python/FastAPI，nightly 栈宿主机 18096，AUTH_MODE=none）
 * API：/api/v1/templates
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase } from './helpers'

test.describe('行业应用模板 /ops-tpl', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/ops-tpl', { waitUntil: 'domcontentloaded' })
  })

  test('模板市场页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('行业应用模板')
    await expect(page.locator('input[placeholder*="搜索"]')).toBeVisible()
  })

  test('模板列表 API 匿名可达返回 200 数组', async ({ request }) => {
    const resp = await request.get(`${apiBase}/templates`)
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(Array.isArray(body)).toBe(true)
  })

  test('模板分类 API 匿名可达返回 200', async ({ request }) => {
    const resp = await request.get(`${apiBase}/templates/categories`)
    expect(resp.status()).toBe(200)
  })
})
