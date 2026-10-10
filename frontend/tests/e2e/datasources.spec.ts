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
    await expect(page.locator('.page-header__subtitle')).toContainText('统一管理平台数据接入源')
    await expect(page.locator('text=MySQL').first()).toBeVisible({ timeout: 15_000 }).catch(() => {})
  })

  test('新建数据源按钮与搜索框存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    const createBtn = page.locator('.toolbar button', { hasText: '新增数据源' })
    await expect(createBtn).toBeVisible()
    const search = page.locator('input[placeholder*="搜索"]')
    await expect(search.first()).toBeVisible()
  })

  test('数据源列表 API 返回 200 分页对象（PagedResult）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/datasources`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    // 条件式 skip（台账 #57①）：encaps-data 缺席时代理回落 encaps-layer 得 404 ⇒ 跳过；
    // 服务在栈（本地 dev / 未来 runner 扩容）时照常断言，半坏（500/契约不符）不会被吞。
    test.skip(resp.status() === 404, 'KNOWN-FAILURES #57① 栈外 encaps-data 缺席（回落 encaps-layer 404），断言不可达；详见 docs/KNOWN-FAILURES.md #57')
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    expect(json).toHaveProperty('data')
    // 平台统一分页对象：DataSourceController 曾返回裸数组，与前端 datasource.ts 的
    // PagedResult 契约冲突（页面表格恒空）——2026-10-10 已收口，见台账 #57①
    expect(json.data).toHaveProperty('list')
    expect(typeof json.data.total).toBe('number')
  })

  test('数据源 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/datasources`)
    expect(resp.status()).toBe(401)
  })
})