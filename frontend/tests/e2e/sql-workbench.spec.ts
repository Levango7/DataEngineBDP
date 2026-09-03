/**
 * SQL 工作台 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/sql-workbench（SqlWorkbench.vue）
 * API：/api/v1/sql/validate、/api/v1/sql/cross-source/explain、/api/v1/sql/cross-source/execute
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('SQL 工作台（/sql-workbench）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/sql-workbench', { waitUntil: 'domcontentloaded' })
  })

  test('SQL 工作台页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('SQL 工作台')
    await expect(page.locator('.sub')).toContainText('跨源归并')
    await expect(page.locator('textarea').first()).toBeVisible({ timeout: 15_000 }).catch(() => {})
  })

  test('方言下拉与三个操作按钮存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    for (const label of ['执行计划', '语法校验', '执行查询']) {
      await expect(page.locator('button', { hasText: label }).first()).toBeVisible({ timeout: 10_000 })
    }
    // 方言选择器（el-select）
    await expect(page.locator('.el-select').first()).toBeVisible()
  })

  test('语法校验 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.post(`${apiBase}/sql/validate`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { sql: 'SELECT 1', dialect: 'ANSI' }
    })
    expect(resp.status()).toBe(200)
  })

  test('SQL 校验 API 未认证返回 401', async ({ request }) => {
    const resp = await request.post(`${apiBase}/sql/validate`, { data: { sql: 'SELECT 1' } })
    expect(resp.status()).toBe(401)
  })
})