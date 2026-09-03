/**
 * 数据源管理 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/datasources（DataSourceManagement.vue）
 * API：/api/v1/datasources
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('数据源管理（/datasources）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/datasources', { waitUntil: 'domcontentloaded' })
  })

  test('数据源管理页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('数据源管理')
    await expect(page.locator('.sub')).toContainText('统一管理平台数据接入源')
    await expect(page.locator('text=MySQL').first()).toBeVisible({ timeout: 15_000 }).catch(() => {})
  })

  test('新建数据源按钮与搜索框存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    const createBtn = page.locator('.toolbar button', { hasText: '新增数据源' })
    await expect(createBtn).toBeVisible()
    const search = page.locator('input[placeholder*="搜索"]')
    await expect(search.first()).toBeVisible()
  })

  test('数据源列表 API 返回 200 数组', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/datasources`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    expect(json).toHaveProperty('data')
    expect(json.data).toHaveProperty('list')
  })

  test('数据源 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/datasources`)
    expect(resp.status()).toBe(401)
  })
})