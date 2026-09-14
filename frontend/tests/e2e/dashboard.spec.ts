/**
 * 仪表盘首页 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/dashboard（Dashboard.vue）
 * API：/api/v1/cluster/overview（KPI 卡片数据源，复用集群概览接口）
 *
 * 覆盖要点：
 * - 页面加载（h1 标题"工作台" + 副标题 .sub）
 * - KPI 卡片 / 资源趋势 / 待办审批表格 / 快捷入口 chips
 * - 集群概览 API 认证语义（200 with Bearer / 401 without）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('仪表盘首页（/dashboard）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/dashboard', { waitUntil: 'domcontentloaded' })
  })

  test('仪表盘页加载', async ({ page }) => {
    // h1 标题为"工作台"
    await expect(page.locator('h1')).toContainText('工作台')
    // 副标题 .sub 可见（含租户/套餐/资源消耗信息）
    await expect(page.locator('.sub')).toBeVisible({ timeout: 15_000 })
    // 主区域 role=main 存在
    await expect(page.locator('[role="main"]')).toBeVisible()
  })

  test('KPI 卡片与快捷入口 chips 存在', async ({ page }) => {
    // KPI 四卡片容器（.grid.g4）可见
    await expect(page.locator('.grid.g4')).toBeVisible({ timeout: 15_000 })
    // 至少渲染一个 .card（loading/data 态均会渲染）
    await expect(page.locator('.grid.g4 .card').first()).toBeVisible()
    // 快捷入口区域存在（5 个 chip：新建作业/配置同步/注册资产/训练模型/创建看板）
    await expect(page.locator('.chips')).toBeVisible({ timeout: 15_000 })
    const chipCount = await page.locator('.chips .chip').count()
    expect(chipCount).toBeGreaterThanOrEqual(5)
  })

  test('资源趋势与待办审批区域存在', async ({ page }) => {
    // 资源趋势卡片（含 CPU/内存进度条 .bar）
    await expect(page.locator('.grid.g2 .card').first()).toBeVisible({ timeout: 15_000 })
    // 待办审批表格（el-table 渲染）
    await expect(page.locator('.grid.g2 el-table').first()).toBeVisible()
    // 至少存在一个进度条 .bar（CPU 或内存）
    await expect(page.locator('.bar').first()).toBeVisible()
  })

  test('集群概览 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/cluster/overview`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('集群概览 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/cluster/overview`)
    expect(resp.status()).toBe(401)
  })
})