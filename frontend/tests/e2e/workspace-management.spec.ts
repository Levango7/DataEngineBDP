/**
 * 工作空间管理 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/workspace-management（WorkspaceManagement.vue）
 * API：/api/v1/workspaces
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('工作空间管理（/workspace-management）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/workspace-management', { waitUntil: 'domcontentloaded' })
  })

  test('工作空间管理页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('工作空间管理')
    await expect(page.locator('.sub')).toContainText('K8s Namespace')
    await expect(page.locator('.toolbar')).toBeVisible({ timeout: 15_000 })
  })

  test('新建工作空间按钮与筛选框存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    const btn = page.locator('.toolbar button', { hasText: '新建工作空间' })
    await expect(btn.first()).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('input[placeholder*="搜索"]').first()).toBeVisible()
  })

  test('工作空间列表表格与刷新按钮存在', async ({ page }) => {
    await page.waitForTimeout(2_000)
    await expect(page.locator('h1')).toContainText('工作空间管理')
    await page.waitForSelector('.el-table, table', { timeout: 15_000 })
    const refreshBtn = page.locator('button[aria-label="刷新工作空间列表"]')
    await expect(refreshBtn).toBeVisible()
  })

  test('工作空间 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/workspaces`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('工作空间 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/workspaces`)
    expect(resp.status()).toBe(401)
  })
})