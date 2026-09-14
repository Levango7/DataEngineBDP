/**
 * 数据集成 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/integrate（Integrate.vue）
 * API：/api/v1/integrate/connectors、/api/v1/integrate/tasks
 *
 * 布局：连接器网格 + 同步任务表格（el-table）+ 新建同步任务弹窗
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('数据集成（/integrate）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/integrate', { waitUntil: 'domcontentloaded' })
  })

  test('数据集成页加载', async ({ page }) => {
    // PageHeader 标题
    await expect(page.locator('h1')).toContainText('数据集成')
    // 子标题
    await expect(page.locator('.page-header__subtitle')).toContainText('SeaTunnel')
    // 连接器区标题
    await expect(page.locator('.section-title', { hasText: '数据源连接器' })).toBeVisible({
      timeout: 15_000
    })
  })

  test('连接器网格与同步任务表格存在', async ({ page }) => {
    // 连接器网格容器
    await expect(page.locator('.conn-grid')).toBeVisible({ timeout: 15_000 })
    // 批流一体标识
    await expect(page.locator('.pill.b', { hasText: '批流一体' })).toBeVisible()
    // 新建同步任务按钮
    await expect(page.locator('button', { hasText: '新建同步任务' })).toBeVisible()
    // 同步任务表格（el-table 渲染为 table）
    await expect(page.locator('.card table')).toBeVisible({ timeout: 15_000 })
    // 表头列：任务 / 源→目标 / 模式 / 状态 / 最近运行 / 操作
    await expect(page.locator('.card thead')).toContainText('任务')
    await expect(page.locator('.card thead')).toContainText('操作')
  })

  test('连接器 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/integrate/connectors`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('连接器 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/integrate/connectors`)
    expect(resp.status()).toBe(401)
  })
})