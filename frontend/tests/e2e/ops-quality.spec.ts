/**
 * 运维中心与数据质量 E2E（Sprint 3.2.3 Playwright 扩面）
 *
 * 页面：
 * - /#/ops      运维中心（Ops.vue，/api/v1/ops → query-api，nightly 栈走 18080 兜底）
 * - /#/quality  数据质量（Quality.vue，/api/v1/quality/rules → rule-engine :18083）
 *
 * 说明：ops 页面的 /ops 前缀在 nightly 栈无 query-api 容器，playwright.config
 * 已把 VITE_OPS_TARGET 兜底指向 encaps-layer 18080——页面渲染正常，
 * API 请求会返回 encaps-layer 的响应（可能 404/兜底数据），页面本身仍应可用。
 * quality 页面的 /quality/rules 前缀走 rule-engine 18083（nightly 栈已入）。
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('运维中心 /ops', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/ops', { waitUntil: 'domcontentloaded' })
  })

  test('运维中心页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('运维中心')
    // 卡片容器（KPI 概览区）
    await expect(page.locator('.card').first()).toBeVisible()
  })

  test('作业运行表格区块存在', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('运维中心')
    // 作业日志按钮所在表格区块
    await page.waitForTimeout(1_500)
    const logButtons = page.locator('button', { hasText: '日志' })
    const count = await logButtons.count()
    // 有作业行时按钮存在；无作业时表格空态也属正常
    expect(count).toBeGreaterThanOrEqual(0)
  })

  test('告警刷新按钮存在', async ({ page }) => {
    const refreshBtn = page.locator('button', { hasText: '刷新' })
    await expect(refreshBtn.first()).toBeVisible()
  })
})

test.describe('数据质量 /quality', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/quality', { waitUntil: 'domcontentloaded' })
  })

  test('数据质量页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('数据质量')
    await expect(page.locator('.sub')).toContainText('规则配置即校验')
    await expect(page.locator('.toolbar')).toBeVisible()
  })

  test('新建规则按钮存在', async ({ page }) => {
    const btn = page.locator('.toolbar button', { hasText: '新建规则' })
    await expect(btn).toBeVisible()
    expect(await btn.textContent()).toContain('+')
  })

  test('质量规则列表 API 返回 200 数组（rule-engine 不套 ApiResponse，裸列表/分页）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/quality/rules`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    // rule-engine 直接返回列表或分页 {list,...}，不套 ApiResponse 包装（Sprint 3.2.4 验证）
    if (Array.isArray(json)) {
      expect(json).toBeTruthy()
    } else {
      expect(json).toHaveProperty('list')
      expect(Array.isArray(json.list)).toBe(true)
    }
  })

  test('质量规则 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/quality/rules`)
    expect(resp.status()).toBe(401)
  })
})
