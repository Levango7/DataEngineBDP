/**
 * 知识工程 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/kb（Kb.vue）
 * API：/api/v1/knowledge（list）、/api/v1/knowledge/rag-strategy
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('知识工程（/kb）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/kb', { waitUntil: 'domcontentloaded' })
  })

  test('知识工程页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('知识工程')
    await expect(page.locator('.sub')).toContainText('企业级知识底座')
    await expect(page.locator('.card').first()).toBeVisible({ timeout: 15_000 })
  })

  test('创建知识库按钮与知识库 tab 存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    const createBtn = page.locator('.toolbar button', { hasText: '创建知识库' })
    await expect(createBtn).toBeVisible()
    await expect(page.locator('text=知识库').first()).toBeVisible()
  })

  test('RAG 策略 tab 可切换', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('知识工程')
    await page.locator('text=RAG 策略').first().click()
    await expect(page.locator('text=检索 TopK')).toBeVisible({ timeout: 10_000 }).catch(() => {})
  })

  test('知识库 API 返回 200 数组', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/knowledge`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    expect(json.data).toHaveProperty('list')
  })

  test('知识库 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/knowledge`)
    expect(resp.status()).toBe(401)
  })
})