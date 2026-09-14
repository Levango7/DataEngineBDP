/**
 * 审批中心 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/approvals（Approvals.vue）
 * 后端：/api/v1/registrations（注册审批，JWT 保护；前端经 tenantAdmin store 调用）
 *
 * 覆盖要点：
 * - 页面加载（h1 标题"审批中心"）
 * - 统计卡片 / 状态筛选下拉 / 注册申请表格
 * - 注册审批 API 认证语义（401 without token）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase } from './helpers'

test.describe('审批中心（/approvals）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/approvals', { waitUntil: 'domcontentloaded' })
  })

  test('审批中心页加载', async ({ page }) => {
    // h1 标题为"审批中心"
    await expect(page.locator('h1')).toContainText('审批中心')
    // PageHeader 副标题可见
    await expect(page.locator('.page-header__subtitle')).toBeVisible({ timeout: 15_000 })
    // 主区域 role=main 存在
    await expect(page.locator('.appr-page[role="main"]')).toBeVisible()
  })

  test('统计卡片与状态筛选控件存在', async ({ page }) => {
    // 统计卡片容器 .appr-stats 可见（4 个 .stat：全部/处理中/已通过/已拒绝）
    await expect(page.locator('.appr-stats')).toBeVisible({ timeout: 15_000 })
    const statCount = await page.locator('.appr-stats .stat').count()
    expect(statCount).toBeGreaterThanOrEqual(4)
    // 状态筛选下拉（el-select，placeholder 含"状态"）
    await expect(page.locator('.toolbar el-select').first()).toBeVisible({ timeout: 15_000 })
  })

  test('注册申请表格存在', async ({ page }) => {
    // el-table 渲染（注册申请列表，含 ID/用户名/邮箱/状态/操作等列）
    await expect(page.locator('el-table').first()).toBeVisible({ timeout: 15_000 })
    // 表格至少存在表头列（el-table-column 渲染为 th）
    await expect(page.locator('el-table th').first()).toBeVisible({ timeout: 15_000 })
  })

  test('注册审批 API 未认证返回 401', async ({ request }) => {
    // /api/v1/registrations 受 JWT 保护，无 token 应返回 401
    const resp = await request.get(`${apiBase}/registrations`)
    expect(resp.status()).toBe(401)
  })
})