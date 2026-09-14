/**
 * 调度运维 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/scheduler-ops（SchedulerOps.vue）
 * API：/api/v1/stream-batch/dags/{dagId}/runs（DAG 运行历史，JWT 保护）
 *
 * 覆盖要点：
 * - 页面加载（h1 标题"任务运维中心"）
 * - 状态筛选 tabs / 运行历史表格 / 查询与补数据按钮
 * - DAG 运行历史 API 认证语义（401 without token）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase } from './helpers'

test.describe('调度运维（/scheduler-ops）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/scheduler-ops', { waitUntil: 'domcontentloaded' })
  })

  test('调度运维页加载', async ({ page }) => {
    // h1 标题为"任务运维中心"
    await expect(page.locator('h1')).toContainText('任务运维中心')
    // PageHeader 副标题可见
    await expect(page.locator('.page-header__subtitle')).toBeVisible({ timeout: 15_000 })
    // 页面根容器 .scheduler-ops-page 存在
    await expect(page.locator('.scheduler-ops-page')).toBeVisible()
  })

  test('状态筛选 tabs 与运行历史表格存在', async ({ page }) => {
    // 状态筛选 tabs（全部/成功/失败/运行中，4 个 tab-pane）
    await expect(page.locator('el-tabs')).toBeVisible({ timeout: 15_000 })
    const tabCount = await page.locator('el-tab-pane').count()
    expect(tabCount).toBeGreaterThanOrEqual(4)
    // 运行历史表格（el-table）
    await expect(page.locator('el-table').first()).toBeVisible({ timeout: 15_000 })
  })

  test('查询与补数据按钮存在', async ({ page }) => {
    // 查询按钮（el-button type="primary"，文案"查询"）
    const queryBtn = page.locator('button', { hasText: '查询' })
    await expect(queryBtn.first()).toBeVisible({ timeout: 15_000 })
    // 补数据按钮（el-button type="success" plain，文案"补数据"）
    const backfillBtn = page.locator('button', { hasText: '补数据' })
    await expect(backfillBtn.first()).toBeVisible({ timeout: 15_000 })
  })

  test('DAG 运行历史 API 未认证返回 401', async ({ request }) => {
    // /api/v1/stream-batch/dags/{dagId}/runs 受 JWT 保护，无 token 应返回 401
    // 使用一个固定的测试 DAG ID（仅验证认证语义，不依赖该 DAG 存在）
    const resp = await request.get(`${apiBase}/stream-batch/dags/test-dag/runs`)
    expect(resp.status()).toBe(401)
  })
})