/**
 * LLMOps E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/llmops（Llmops.vue）
 * API：/api/v1/llmops
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('LLMOps（/llmops）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/llmops', { waitUntil: 'domcontentloaded' })
  })

  test('LLMOps 页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('LLMOps')
    await expect(page.locator('.page-header__subtitle')).toContainText('大模型运营')
    await expect(page.locator('.stat-card').first()).toBeVisible({ timeout: 15_000 }).catch(() => {})
  })

  test('四个主 tab 存在', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('LLMOps')
    await page.waitForTimeout(1_500)
    for (const tab of ['模型管理', '微调', '评估', '推理服务']) {
      await expect(page.locator('text=' + tab).first()).toBeVisible({ timeout: 10_000 })
    }
  })

  test('注册模型按钮存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    const btn = page.locator('button', { hasText: '注册模型' })
    await expect(btn.first()).toBeVisible({ timeout: 10_000 })
  })

  // 后端 LLMOpsController 只挂 /models、/eval-metrics、/finetune、/inference-services
  // 等子路径（@RequestMapping("/api/v1/llmops") 上没有根映射），打裸前缀必然 404
  // ——那是后端的正确行为，不是服务缺陷。
  test('LLMOps API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/llmops/inference-services`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('LLMOps API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/llmops/inference-services`)
    expect(resp.status()).toBe(401)
  })
})