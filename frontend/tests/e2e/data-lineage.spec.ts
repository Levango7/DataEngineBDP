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
    // 页面已迁到 PageHeader，副标题在 .page-header__subtitle（旧 .sub 在本页已不存在）
    await expect(page.locator('.page-header__subtitle')).toContainText('SQL AST')
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
    // el-input type="textarea" 把 class 落在外层 div（实测 tagName=div），可 inputValue() 的
    // 节点是内层 textarea；直接对外层取值得到的是 "Node is not an <input>" 一类错误。
    const val = await page.locator('.sql-textarea textarea').inputValue()
    expect(val.length).toBeGreaterThan(0)
  })

  test('血缘分析 API 返回 200（Bearer 认证，独立前缀 /lineage）', async ({ request }) => {
    // 无条件 skip（台账 #57 裁决②）：/lineage 属栈外服务，nightly compose 未起，代理回落 encaps-layer 必 404
    test.skip(true, 'KNOWN-FAILURES #57 栈外服务未进 nightly compose（/lineage → lineage-analyzer），回落 encaps-layer:18080 必 404；详见 docs/KNOWN-FAILURES.md #57')
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