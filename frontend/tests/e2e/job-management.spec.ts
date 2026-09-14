/**
 * 作业管理 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/jobs（JobManagement.vue）
 * API：/api/v1/jobs（作业列表/提交/取消/日志，JWT 保护）
 *
 * 覆盖要点：
 * - 页面加载（h1 标题"作业管理"）
 * - 状态筛选 tabs / 作业列表表格 / 分页 / 提交作业按钮
 * - 作业 API 认证语义（200 with Bearer / 401 without）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('作业管理（/jobs）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/jobs', { waitUntil: 'domcontentloaded' })
  })

  test('作业管理页加载', async ({ page }) => {
    // h1 标题为"作业管理"
    await expect(page.locator('h1')).toContainText('作业管理')
    // PageHeader 副标题可见
    await expect(page.locator('.page-header__subtitle')).toBeVisible({ timeout: 15_000 })
    // 主区域 role=main 存在
    await expect(page.locator('.job-page[role="main"]')).toBeVisible()
  })

  test('状态筛选 tabs 与作业列表表格存在', async ({ page }) => {
    // 状态筛选 tabs（全部/运行中/成功/失败/等待，5 个 tab-pane）
    await expect(page.locator('el-tabs')).toBeVisible({ timeout: 15_000 })
    const tabCount = await page.locator('el-tab-pane').count()
    expect(tabCount).toBeGreaterThanOrEqual(5)
    // 作业列表表格（el-table，aria-label 含"作业列表"）
    await expect(page.locator('el-table').first()).toBeVisible({ timeout: 15_000 })
  })

  test('分页与提交作业按钮存在', async ({ page }) => {
    // 分页控件 el-pagination 存在
    await expect(page.locator('el-pagination')).toBeVisible({ timeout: 15_000 })
    // 提交作业按钮（Toolbar create 按钮，文案"+ 提交作业"）
    const submitBtn = page.locator('button', { hasText: '提交作业' })
    await expect(submitBtn.first()).toBeVisible({ timeout: 15_000 })
  })

  test('作业列表 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/jobs`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('作业列表 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/jobs`)
    expect(resp.status()).toBe(401)
  })
})