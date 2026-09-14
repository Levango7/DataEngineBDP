/**
 * AI 数据助手 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/ai-assistant（ai-assistant/AiAssistant.vue）
 * API：/api/v1/ai-assistant/sessions、/api/v1/ai-assistant/superset/datasources
 *
 * 布局：左侧会话列表 + 中部聊天面板 + 右侧分析面板（SQL 预览 / 图表推荐 / 数据解读 / Superset 仪表盘）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('AI 数据助手（/ai-assistant）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/ai-assistant', { waitUntil: 'domcontentloaded' })
  })

  test('AI 助手页加载', async ({ page }) => {
    // 顶部标题
    await expect(page.locator('h1')).toContainText('AI 数据助手')
    // 副标题
    await expect(page.locator('.ai-sub')).toContainText('自然语言')
    // 主体三栏容器渲染
    await expect(page.locator('.ai-assistant')).toBeVisible({ timeout: 15_000 })
    await expect(page.locator('.ai-body')).toBeVisible()
  })

  test('会话列表与新建对话按钮存在', async ({ page }) => {
    // 左侧会话列表区
    await expect(page.locator('.ai-sessions')).toBeVisible({ timeout: 15_000 })
    // 会话列表标题“历史会话”
    await expect(page.locator('.sessions-header')).toContainText('历史会话')
    // 顶部“新对话”按钮
    const newChatBtn = page.locator('button', { hasText: '新对话' })
    await expect(newChatBtn).toBeVisible()
    // 数据源选择下拉存在
    await expect(page.locator('.ai-actions select, .ai-actions .el-select').first()).toBeVisible()
  })

  test('右侧分析面板四个分区存在', async ({ page }) => {
    // 右侧分析面板
    await expect(page.locator('.ai-side')).toBeVisible({ timeout: 15_000 })
    // SQL 预览
    await expect(page.locator('.side-section-title', { hasText: 'SQL 预览' })).toBeVisible()
    // 图表推荐
    await expect(page.locator('.side-section-title', { hasText: '图表推荐' })).toBeVisible()
    // 数据解读
    await expect(page.locator('.side-section-title', { hasText: '数据解读' })).toBeVisible()
    // Superset 仪表盘
    await expect(page.locator('.side-section-title', { hasText: 'Superset 仪表盘' })).toBeVisible()
    // 一键创建仪表盘按钮
    await expect(page.locator('button', { hasText: '一键创建仪表盘' })).toBeVisible()
  })

  test('AI 助手历史会话 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/ai-assistant/sessions`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('AI 助手会话 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/ai-assistant/sessions`)
    expect(resp.status()).toBe(401)
  })
})