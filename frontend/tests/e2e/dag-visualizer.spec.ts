/**
 * 编排 DAG 可视化 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/orchestrator/dag（orchestrator/DagVisualizer.vue）
 * API：/api/v1/orchestrator/dags
 *
 * 布局：顶部工具栏（DAG 选择 / 运行 / 停止 / 刷新 / 自动轮询）+ 左侧 DAG 画布（SVG）+ 右侧详情 Tab（节点详情 / 思考链 / 工具调用 / 回放）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('编排 DAG 可视化（/orchestrator/dag）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/orchestrator/dag', { waitUntil: 'domcontentloaded' })
  })

  test('DAG 可视化页加载', async ({ page }) => {
    // 顶部标题
    await expect(page.locator('h1')).toContainText('编排 DAG 可视化')
    // 副标题
    await expect(page.locator('.sub')).toContainText('可视化')
    // 工具栏渲染
    await expect(page.locator('.toolbar')).toBeVisible({ timeout: 15_000 })
  })

  test('工具栏操作按钮与 DAG 选择下拉存在', async ({ page }) => {
    // DAG 选择下拉
    await expect(page.locator('.toolbar .el-select').first()).toBeVisible({ timeout: 15_000 })
    // 运行 / 停止 / 刷新按钮
    await expect(page.locator('.toolbar button', { hasText: '运行' })).toBeVisible()
    await expect(page.locator('.toolbar button', { hasText: '停止' })).toBeVisible()
    await expect(page.locator('.toolbar button', { hasText: '刷新' })).toBeVisible()
    // 自动刷新复选框
    await expect(page.locator('.toolbar', { hasText: '自动刷新' })).toBeVisible()
  })

  test('未选择 DAG 时显示空态或已选时显示画布与详情 Tab', async ({ page }) => {
    await page.waitForTimeout(2_000)
    // 二者必有其一：空态提示 或 DAG 画布 + 详情 Tab
    const emptyState = page.locator('.empty-state')
    const vizBody = page.locator('.viz-body')
    const hasEmpty = await emptyState.isVisible().catch(() => false)
    const hasViz = await vizBody.isVisible().catch(() => false)
    expect(hasEmpty || hasViz).toBe(true)
    // 若已加载 DAG，验证画布与详情 Tab 结构
    if (hasViz) {
      await expect(page.locator('.canvas-wrap')).toBeVisible()
      await expect(page.locator('.detail-wrap')).toBeVisible()
      // Tab：节点详情 / 思考链 / 工具调用 / 回放
      await expect(page.locator('.tabbar')).toContainText('节点详情')
      await expect(page.locator('.tabbar')).toContainText('回放')
    } else {
      // 空态文案
      await expect(emptyState).toContainText('未选择 DAG')
    }
  })

  test('DAG 列表 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/orchestrator/dags`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('DAG 列表 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/orchestrator/dags`)
    expect(resp.status()).toBe(401)
  })
})