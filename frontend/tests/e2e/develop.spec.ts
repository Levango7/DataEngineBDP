/**
 * 数据开发 IDE E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/develop（Develop.vue）
 * API：/api/v1/develop/files、/api/v1/develop/dag
 *
 * 布局：文件树 + 代码编辑器（tabs + textarea + runlog）+ 运行参数面板 + 任务 DAG 卡片
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('数据开发 IDE（/develop）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/develop', { waitUntil: 'domcontentloaded' })
  })

  test('数据开发页加载', async ({ page }) => {
    // PageHeader 标题
    await expect(page.locator('h1')).toContainText('数据开发')
    // 子标题
    await expect(page.locator('.page-header__subtitle')).toContainText('Web IDE')
    // IDE 主体三栏（文件树 / 代码区 / 参数面板）
    await expect(page.locator('.ide')).toBeVisible({ timeout: 15_000 })
  })

  test('环境切换 chip 与运行参数面板存在', async ({ page }) => {
    // 环境切换 chip：开发环境（高亮）/ 生产环境
    await expect(page.locator('.chip.on', { hasText: '开发环境' })).toBeVisible({
      timeout: 15_000
    })
    await expect(page.locator('.chip', { hasText: '生产环境' })).toBeVisible()
    // 环境隔离标识
    await expect(page.locator('.pill.b', { hasText: '隔离' })).toBeVisible()
    // 运行参数面板
    await expect(page.locator('.params')).toBeVisible()
    // 引擎下拉（Spark SQL 等选项）
    await expect(page.locator('.params label', { hasText: '引擎' })).toBeVisible()
    // 运行 / 提交调度按钮
    await expect(page.locator('.params button', { hasText: '运行' })).toBeVisible()
    await expect(page.locator('.params button', { hasText: '提交调度' })).toBeVisible()
  })

  test('文件树与代码编辑器结构存在', async ({ page }) => {
    // 文件树容器
    await expect(page.locator('.tree')).toBeVisible({ timeout: 15_000 })
    // 代码区：tabs + 编辑器 + 运行日志
    await expect(page.locator('.code-wrap')).toBeVisible()
    await expect(page.locator('.tabs')).toBeVisible()
    await expect(page.locator('.runlog')).toBeVisible()
    // 任务 DAG 卡片标题
    await expect(page.locator('h3', { hasText: '任务 DAG' })).toBeVisible()
  })

  test('文件树 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/develop/files`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('文件树 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/develop/files`)
    expect(resp.status()).toBe(401)
  })
})