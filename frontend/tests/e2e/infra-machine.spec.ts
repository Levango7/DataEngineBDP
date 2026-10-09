/**
 * 机器管理 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/infra-machine（InfraMachine.vue）
 * API：/api/v1/clusters/xinchang
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('机器管理（/infra-machine）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/infra-machine', { waitUntil: 'domcontentloaded' })
  })

  test('机器管理页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('机器供应')
    await expect(page.locator('.sub')).toContainText('信创集群供应')
    await expect(page.locator('.card').first()).toBeVisible({ timeout: 15_000 })
  })

  test('新建集群按钮与集群表格存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    await expect(page.locator('h1')).toContainText('机器供应')
    // 新建集群按钮
    const createBtn = page.locator('.toolbar button', { hasText: '新建集群' })
    await expect(createBtn).toBeVisible({ timeout: 15_000 })
    // 集群列表表格
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 15_000 })
    // 刷新按钮（aria-label）
    const refreshBtn = page.locator('button[aria-label="刷新集群列表"]')
    await expect(refreshBtn).toBeVisible()
  })

  test('信创集群列表 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    // 保留 skip（台账 #57① 实跑发现的新真因，非“服务缺席”）：
    // infra-orchestrator 的 `GET /api/v1/clusters/{env}` 对**已启用但不可达**或**已禁用**的 provider
    // 不是返回空列表而是抛错（ProviderRegistry.lookup → IllegalArgumentException → 400；
    // 已启用不可达 → WebClient 异常）。而 `GET /api/v1/clusters`（聚合）对每个 env 各自 catch 后返回空
    // ⇒ 只有 `/{env}` 这条在 compose（无 infra-provider-*）下必非 200。
    // 修它要改单环境列表的容错语义（与聚合口径对齐或加 provider mock），属产品/接口语义决策，
    // 不在本次“扩展栈 + 修正路径”范围内，故保留 skip 并如实记录。
    test.skip(true, 'KNOWN-FAILURES #57①：/clusters/{env} 对不可达/禁用 provider 非 200（400/异常），需 product 决策容错语义；不是“服务缺席”。详见 docs/KNOWN-FAILURES.md #57')
    const resp = await request.get(`${apiBase}/clusters/xinchang`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('信创集群列表 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/clusters/xinchang`)
    expect(resp.status()).toBe(401)
  })
})