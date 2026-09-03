/**
 * 血缘分析（SQL AST）E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/data-lineage（DataLineage.vue）
 * API：/lineage/api/v1/lineage/analyze（独立服务 :8089，经 vite proxy 转发）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, getApiToken } from './helpers'

test.describe('血缘分析（/data-lineage）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/data-lineage', { waitUntil: 'domcontentloaded' })
  })

  test('血缘分析页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('数据血缘分析')
    await expect(page.locator('.sub')).toContainText('SQL AST')
    await expect(page.locator('.sql-input')).toBeVisible({ timeout: 15_000 })
  })

  test('SQL 输入区与操作按钮存在', async ({ page }) => {
    await expect(page.locator('.sql-textarea')).toBeVisible()
    const analyzeBtn = page.locator('button', { hasText: '分析血缘' })
    await expect(analyzeBtn).toBeVisible()
    const sampleBtn = page.locator('button', { hasText: '载入示例' })
    await expect(sampleBtn).toBeVisible()
  })

  test('载入示例填入 SQL', async ({ page }) => {
    await page.locator('button', { hasText: '载入示例' }).click()
    const val = await page.locator('.sql-textarea').inputValue()
    expect(val.length).toBeGreaterThan(0)
  })

  test('血缘分析 API 返回 200（Bearer 认证，独立前缀 /lineage）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.post('/lineage/api/v1/lineage/analyze', {
      headers: { Authorization: `Bearer ${token}` },
      data: { sql: 'SELECT a.id FROM ods.orders a JOIN dim.user b ON a.uid=b.id', dialect: 'ANSI' }
    })
    expect(resp.status()).toBe(200)
  })

  test('血缘分析 API 未认证返回 401', async ({ request }) => {
    const resp = await request.post('/lineage/api/v1/lineage/analyze', {
      data: { sql: 'SELECT 1' }
    })
    expect(resp.status()).toBe(401)
  })
})