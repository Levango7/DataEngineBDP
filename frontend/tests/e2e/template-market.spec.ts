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
    // 页面存在两个"搜索"输入（顶栏全局搜索 gs-input + 本页 toolbar el-input），
    // 未限定祖先会触发 strict mode violation；限定到本页 toolbar
    await expect(page.locator('.toolbar input[placeholder*="搜索"]')).toBeVisible()
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
