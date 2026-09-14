/**
 * 存储引擎管理 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/eng-storage（EngStorage.vue）
 * API：/api/v1/virtual-tables、/api/v1/materialized-views
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('存储引擎管理（/eng-storage）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/eng-storage', { waitUntil: 'domcontentloaded' })
  })

  test('存储引擎页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('统一存储')
    await expect(page.locator('.sub')).toContainText('虚拟表')
    await expect(page.locator('.card').first()).toBeVisible({ timeout: 15_000 })
  })

  test('Tab 切换、注册虚拟表按钮与表格存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    await expect(page.locator('h1')).toContainText('统一存储')
    // 三个 Tab：虚拟表 / 物化视图 / 缓存统计
    await expect(page.locator('.el-tabs__item', { hasText: '虚拟表' })).toBeVisible({
      timeout: 15_000
    })
    await expect(page.locator('.el-tabs__item', { hasText: '物化视图' })).toBeVisible()
    await expect(page.locator('.el-tabs__item', { hasText: '缓存统计' })).toBeVisible()
    // 注册虚拟表按钮
    const registerBtn = page.locator('.toolbar button', { hasText: '注册虚拟表' })
    await expect(registerBtn).toBeVisible()
    // 虚拟表表格
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 15_000 })
  })

  test('虚拟表列表 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/virtual-tables`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('虚拟表列表 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/virtual-tables`)
    expect(resp.status()).toBe(401)
  })
})