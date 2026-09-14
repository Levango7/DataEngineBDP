/**
 * Spark 引擎管理 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/eng-spark（EngSpark.vue）
 * API：/api/v1/jobs（type=spark）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('Spark 引擎管理（/eng-spark）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/eng-spark', { waitUntil: 'domcontentloaded' })
  })

  test('Spark 引擎页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('批计算（Spark）')
    await expect(page.locator('.sub')).toContainText('Spark 引擎监控')
    await expect(page.locator('.card').first()).toBeVisible({ timeout: 15_000 })
  })

  test('提交作业按钮、状态筛选与作业表格存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    await expect(page.locator('h1')).toContainText('批计算（Spark）')
    // 提交作业按钮
    const submitBtn = page.locator('.toolbar button', { hasText: '提交作业' })
    await expect(submitBtn).toBeVisible({ timeout: 15_000 })
    // 状态筛选下拉
    await expect(page.locator('.toolbar .el-select').first()).toBeVisible()
    // 作业列表表格
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 15_000 })
    // 分页器
    await expect(page.locator('.el-pagination')).toBeVisible({ timeout: 15_000 })
  })

  test('Spark 作业 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/jobs`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { type: 'spark' }
    })
    expect(resp.status()).toBe(200)
  })

  test('Spark 作业 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/jobs`, {
      data: { type: 'spark' }
    })
    expect(resp.status()).toBe(401)
  })
})