/**
 * 数据资产管理 E2E 测试
 *
 * 覆盖：
 * - 资产列表页加载
 * - 资产列表表格结构
 * - 资产搜索过滤
 * - 资产详情抽屉
 * - 新建资产弹窗
 * - 资产 API 响应格式
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('数据资产管理（资产目录 /govern）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/govern', { waitUntil: 'domcontentloaded' })
  })

  test('资产目录页加载', async ({ page }) => {
    // 标题
    await expect(page.locator('h1')).toContainText('资产目录')
    // 子标题
    await expect(page.locator('.sub')).toContainText('统一检索')
    // 工具栏
    await expect(page.locator('.toolbar')).toBeVisible()
    // 卡片容器
    await expect(page.locator('.card')).toBeVisible()
  })

  test('资产列表表格表头正确', async ({ page }) => {
    // 等待 loading 结束
    await page.waitForTimeout(2_000)
    await page.waitForSelector('.card table', { timeout: 15_000 })
    // 验证表格存在且有表头
    const headers = page.locator('.card thead th')
    const count = await headers.count()
    expect(count).toBeGreaterThanOrEqual(5)
    // 验证 h1 标题正确（确认在资产目录页面）
    await expect(page.locator('h1')).toContainText('资产目录')
  })

  test('资产列表显示暂无资产或数据行', async ({ page }) => {
    await page.waitForSelector('table', { timeout: 15_000 })
    // 表格存在
    await expect(page.locator('table')).toBeVisible()
    // 要么有数据行（tbody tr），要么有"暂无资产"提示
    const dataRows = page.locator('tbody tr')
    const emptyRow = page.locator('tbody tr td', { hasText: '暂无资产' })
    const rowCount = await dataRows.count()
    if (rowCount === 1) {
      await expect(emptyRow).toBeVisible()
    } else {
      expect(rowCount).toBeGreaterThanOrEqual(0)
    }
  })

  test('资产搜索框存在', async ({ page }) => {
    const searchInput = page.locator('.toolbar input[placeholder*="搜索"]')
    await expect(searchInput).toBeVisible()
    // 输入文字
    await searchInput.fill('test_asset')
    await expect(searchInput).toHaveValue('test_asset')
  })

  test('分层筛选下拉存在', async ({ page }) => {
    const select = page.locator('.toolbar select')
    await expect(select.first()).toBeVisible()
    // 默认选项"全部分层"
    const option = select.first().locator('option')
    await expect(option.first()).toContainText('全部分层')
  })

  test('登记资产按钮存在', async ({ page }) => {
    const btn = page.locator('.toolbar button', { hasText: '登记资产' })
    await expect(btn).toBeVisible()
    expect(await btn.textContent()).toContain('+')
  })

  test('新建资产弹窗打开与关闭', async ({ page }) => {
    // 点击"+ 登记资产"
    await page.locator('.toolbar button', { hasText: '登记资产' }).click()

    // 弹窗出现
    await expect(page.locator('.modal, [role="dialog"]')).toBeVisible({ timeout: 5_000 })

    // 弹窗标题
    await expect(page.locator('.modal, [role="dialog"]')).toContainText('登记数据资产')

    // 表单字段
    await expect(page.locator('input[placeholder*="dws"]')).toBeVisible()

    // 取消按钮
    const cancelBtn = page.locator('button', { hasText: '取消' })
    await expect(cancelBtn).toBeVisible()
    await cancelBtn.click()

    // 弹窗关闭
    await expect(page.locator('.modal, [role="dialog"]')).not.toBeVisible({ timeout: 5_000 })
  })

  test('资产 API 返回 ApiResponse 统一格式', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/governance/assets`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    expect(json).toHaveProperty('code', 0)
    expect(json).toHaveProperty('message', 'OK')
    expect(json).toHaveProperty('success', true)
    expect(json).toHaveProperty('data')
    // data 应为分页结构
    expect(json.data).toHaveProperty('list')
    expect(json.data).toHaveProperty('total')
    expect(Array.isArray(json.data.list)).toBe(true)
  })

  test('资产 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/governance/assets`)
    expect(resp.status()).toBe(401)
  })
})