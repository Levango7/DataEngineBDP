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
    // 条件式 skip（台账 #57① 扩栈第一期）：infra-orchestrator 已进栈（compose profile
    // expanded，宿主 18102）；缺席时回落 encaps-layer 得 404 ⇒ 跳过；其余状态码照常断言。
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/clusters/xinchang`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    test.skip(resp.status() === 404, 'KNOWN-FAILURES #57① 栈外 infra-orchestrator 缺席（回落 encaps-layer 404），断言不可达；详见 docs/KNOWN-FAILURES.md #57')
    expect(resp.status()).toBe(200)
  })

  test('信创集群列表 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/clusters/xinchang`)
    expect(resp.status()).toBe(401)
  })
})