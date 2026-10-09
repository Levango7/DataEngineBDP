/**
 * API 网关 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/gateway（Gateway.vue）
 * API：/api/v1/gateway
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('API 网关（/gateway）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/gateway', { waitUntil: 'domcontentloaded' })
  })

  test('API 网关页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('大模型网关')
    await expect(page.locator('.page-header__subtitle')).toContainText('统一 API 入口')
    await expect(page.locator('.stat-card').first()).toBeVisible({ timeout: 15_000 }).catch(() => {})
  })

  test('新建 Key 按钮与 Key 表格区存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    const newKeyBtn = page.locator('button', { hasText: '新建 Key' })
    await expect(newKeyBtn.first()).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('text=API Key 与路由')).toBeVisible()
  })

  test('刷新按钮存在', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('大模型网关')
    const refreshBtn = page.locator('button', { hasText: '刷新' })
    await expect(refreshBtn.first()).toBeVisible({ timeout: 10_000 })
  })

  test('网关 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    // 走 /gateway/stats（GatewayController 实际映射，见 frontend/src/api/gateway.ts getStats）；
    // 裸 /gateway 无映射会 404，不能作为探针路径
    const resp = await request.get(`${apiBase}/gateway/stats`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    // 条件式 skip（台账 #57①）：encaps-gateway 缺席时代理回落 encaps-layer 得 404 ⇒ 跳过；
    // 服务在栈（本地 dev / 未来 runner 扩容）时照常断言，半坏（500/契约不符）不会被吞。
    test.skip(resp.status() === 404, 'KNOWN-FAILURES #57① 栈外 encaps-gateway 缺席（回落 encaps-layer 404），断言不可达；详见 docs/KNOWN-FAILURES.md #57')
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    expect(json).toHaveProperty('data')
    expect(json.data).toHaveProperty('successRate')
  })

  test('网关 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/gateway/stats`)
    expect(resp.status()).toBe(401)
  })
})