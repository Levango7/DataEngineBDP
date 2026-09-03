/**
 * 数据资产流通市场 E2E（Sprint 3.1.2 + Sprint 3.2 补回订阅列表）
 *
 * 页面：/#/ops-flow（AssetMarket.vue）
 * 后端：asset-exchange（Python/FastAPI，nightly 栈宿主机 18094，AUTH_MODE=none 匿名放行）
 * API：/api/v1/assets、/api/v1/asset-subscriptions（Sprint 4.2 前缀隔离）
 *
 * Sprint 4.2 修订：asset-exchange 的订阅路由改独立前缀 /asset-subscriptions——
 * 此前与 open-api-catalog 的 /subscriptions 共享前缀，vite proxy 只能固定指向
 * 一个服务，语义分流实为错路由。Sprint 2.2 的前缀"对齐"是本冲突的历史根源，
 * 本次按域隔离修正。
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase } from './helpers'

test.describe('数据资产流通 /ops-flow', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/ops-flow', { waitUntil: 'domcontentloaded' })
  })

  test('资产市场页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('数据资产流通')
    // 搜索框
    await expect(page.locator('input[placeholder*="搜索资产"]')).toBeVisible()
  })

  test('资产列表 API 匿名可达返回 200 数组', async ({ request }) => {
    const resp = await request.get(`${apiBase}/assets`)
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(Array.isArray(body)).toBe(true)
  })

  test('资产订阅列表 API 匿名可达返回 200 数组（Sprint 4.2 前缀隔离）', async ({ request }) => {
    // Sprint 4.2：asset-exchange 改独立前缀 /asset-subscriptions（与 open-api-catalog
    // 的 /subscriptions 解耦），vite proxy 按 URL 前缀精确分流
    const resp = await request.get(`${apiBase}/asset-subscriptions`)
    expect(resp.status()).toBe(200)
    const body = await resp.json()
    expect(Array.isArray(body)).toBe(true)
  })
})
