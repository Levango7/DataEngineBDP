/**
 * Doris 引擎管理 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/eng-doris（EngDoris.vue）
 * API：/api/v1/doris/nodes、/api/v1/doris/queries
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('Doris 引擎管理（/eng-doris）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/eng-doris', { waitUntil: 'domcontentloaded' })
  })

  test('Doris 引擎页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('OLAP（Doris）')
    await expect(page.locator('.sub')).toContainText('MPP 引擎')
    await expect(page.locator('.card').first()).toBeVisible({ timeout: 15_000 })
  })

  test('目录树、Tab 切换与节点表格存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    await expect(page.locator('h1')).toContainText('OLAP（Doris）')
    // 左侧目录树卡片
    await expect(page.locator('.tree-card')).toBeVisible({ timeout: 15_000 })
    // 三个 Tab：节点状态 / 查询列表 / SQL 工作台
    await expect(page.locator('.el-tabs__item', { hasText: '节点状态' })).toBeVisible({
      timeout: 15_000
    })
    await expect(page.locator('.el-tabs__item', { hasText: '查询列表' })).toBeVisible()
    await expect(page.locator('.el-tabs__item', { hasText: 'SQL 工作台' })).toBeVisible()
    // 节点表格
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 15_000 })
  })

  test('Doris 节点 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/doris/nodes`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('Doris 节点 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/doris/nodes`)
    expect(resp.status()).toBe(401)
  })
})