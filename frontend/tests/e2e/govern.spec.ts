/**
 * 治理中台（资产目录）E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/govern（Govern.vue）
 * API：/api/v1/governance/assets
 *
 * 布局：资产目录表格（el-table）+ 资产详情抽屉（元数据 / Schema / 质量 / 权限 Tab）+ 登记资产弹窗
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('治理中台·资产目录（/govern）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/govern', { waitUntil: 'domcontentloaded' })
  })

  test('资产目录页加载', async ({ page }) => {
    // PageHeader 标题
    await expect(page.locator('h1')).toContainText('资产目录')
    // 子标题
    await expect(page.locator('.page-header__subtitle')).toContainText('统一检索')
    // 卡片容器渲染
    await expect(page.locator('.card')).toBeVisible({ timeout: 15_000 })
  })

  test('资产表格与登记资产按钮存在', async ({ page }) => {
    // 登记资产按钮
    await expect(page.locator('button', { hasText: '登记资产' })).toBeVisible({
      timeout: 15_000
    })
    // 搜索框
    await expect(page.locator('.toolbar input[placeholder*="搜索"]')).toBeVisible()
    // 资产表格（el-table 渲染为 table）
    await expect(page.locator('.card table')).toBeVisible({ timeout: 15_000 })
    // 表头列：资产名 / 分层 / 负责人 / 质量分 / 敏感 / 详情
    const headers = page.locator('.card thead th')
    const count = await headers.count()
    expect(count).toBeGreaterThanOrEqual(5)
    await expect(page.locator('.card thead')).toContainText('资产名')
    await expect(page.locator('.card thead')).toContainText('详情')
  })

  test('资产列表 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/governance/assets`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('资产列表 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/governance/assets`)
    expect(resp.status()).toBe(401)
  })
})