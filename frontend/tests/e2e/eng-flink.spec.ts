/**
 * Flink 引擎管理 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/eng-flink（EngFlink.vue）
 * API：/api/v1/jobs（type=flink）
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('Flink 引擎管理（/eng-flink）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/eng-flink', { waitUntil: 'domcontentloaded' })
  })

  test('Flink 引擎页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('流计算（Flink）')
    await expect(page.locator('.sub')).toContainText('Flink 流作业')
    await expect(page.locator('.card').first()).toBeVisible({ timeout: 15_000 })
  })

  test('提交流作业按钮、状态筛选与作业表格存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    await expect(page.locator('h1')).toContainText('流计算（Flink）')
    // 提交流作业按钮
    const submitBtn = page.locator('.toolbar button', { hasText: '提交流作业' })
    await expect(submitBtn).toBeVisible({ timeout: 15_000 })
    // 状态筛选下拉
    await expect(page.locator('.toolbar .el-select').first()).toBeVisible()
    // 作业列表表格
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 15_000 })
    // 分页器
    await expect(page.locator('.el-pagination')).toBeVisible({ timeout: 15_000 })
  })

  test('Flink 作业 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/jobs`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { type: 'flink' }
    })
        // 条件式 skip（台账 #57①）：stream-batch-scheduler 缺席时代理回落 encaps-layer 得 404 ⇒ 跳过；
    // 服务在栈（本地 dev / 未来 runner 扩容）时照常断言，半坏（500/契约不符）不会被吞。
    test.skip(resp.status() === 404, 'KNOWN-FAILURES #57① 栈外 stream-batch-scheduler 缺席（回落 encaps-layer 404），断言不可达；详见 docs/KNOWN-FAILURES.md #57')
expect(resp.status()).toBe(200)
  })

  test('Flink 作业 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/jobs`, {
      data: { type: 'flink' }
    })
    expect(resp.status()).toBe(401)
  })
})