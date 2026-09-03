/**
 * 集群概览 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/cluster（ClusterOverview.vue）
 * API：/api/v1/cluster/overview、/api/v1/cluster/nodes、/api/v1/cluster/components
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('集群概览（/cluster）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/cluster', { waitUntil: 'domcontentloaded' })
  })

  test('集群概览页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('集群概览')
    await expect(page.locator('.sub')).toContainText('Kubernetes 集群')
    await expect(page.locator('.stat-card').first()).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('text=总节点数')).toBeVisible()
  })

  test('节点列表表格与刷新按钮存在', async ({ page }) => {
    await page.waitForTimeout(2_000)
    await expect(page.locator('h1')).toContainText('集群概览')
    await expect(page.locator('text=节点列表')).toBeVisible({ timeout: 15_000 })
    const refreshBtn = page.locator('button[aria-label="刷新节点列表"]')
    await expect(refreshBtn).toBeVisible()
  })

  test('集群资源配置 tab 与网络配置加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('集群概览')
    await expect(page.locator('text=集群资源配置')).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('text=网络配置')).toBeVisible()
  })

  test('集群概览 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/cluster/overview`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('集群节点 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/cluster/nodes`)
    expect(resp.status()).toBe(401)
  })
})