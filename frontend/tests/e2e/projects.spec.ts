/**
 * 项目管理 E2E 测试
 *
 * 覆盖：
 * - 项目列表加载
 * - 项目详情抽屉
 * - 项目数据集列表（验证 mock 清零后的接口）
 * - 新建项目弹窗
 * - 项目 API 响应格式
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('项目管理（/projects）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/projects', { waitUntil: 'domcontentloaded' })
  })

  test('项目列表页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('数据项目')
    await expect(page.locator('.sub')).toContainText('工作空间')
    await expect(page.locator('.card')).toBeVisible()
  })

  test('项目列表表格表头正确', async ({ page }) => {
    // 等待 loading 结束
    await page.waitForTimeout(2_000)
    await page.waitForSelector('.card table', { timeout: 15_000 })
    // 验证表格存在且有表头
    const headers = page.locator('.card table th')
    const count = await headers.count()
    expect(count).toBeGreaterThanOrEqual(5)
    // 验证 h1 标题正确（确认在项目页面）
    await expect(page.locator('h1')).toContainText('数据项目')
  })

  test('项目列表显示暂无项目或数据行', async ({ page }) => {
    await page.waitForSelector('table', { timeout: 15_000 })
    const table = page.locator('table')
    await expect(table).toBeVisible()
    // 等待 loading 结束（"加载中…"消失）
    await expect(page.locator('text=加载中…')).toHaveCount(0, { timeout: 15_000 })

    const rows = page.locator('table tr')
    const rowCount = await rows.count()
    // 至少有表头行
    expect(rowCount).toBeGreaterThanOrEqual(1)
  })

  test('新建项目按钮存在', async ({ page }) => {
    const btn = page.locator('.toolbar button', { hasText: '新建项目' })
    await expect(btn).toBeVisible()
    expect(await btn.textContent()).toContain('+')
  })

  test('项目搜索框存在', async ({ page }) => {
    const searchInput = page.locator('.toolbar input[placeholder*="搜索项目"]')
    await expect(searchInput).toBeVisible()
    await searchInput.fill('test_project')
    await expect(searchInput).toHaveValue('test_project')
  })

  test('状态筛选下拉存在', async ({ page }) => {
    const select = page.locator('.toolbar select')
    await expect(select.first()).toBeVisible()
    const option = select.first().locator('option')
    await expect(option.first()).toContainText('全部状态')
  })

  test('新建项目弹窗打开与关闭', async ({ page }) => {
    // 点击"+ 新建项目"
    await page.locator('.toolbar button', { hasText: '新建项目' }).click()

    // 弹窗出现
    await expect(page.locator('.modal, [role="dialog"]')).toBeVisible({ timeout: 5_000 })
    await expect(page.locator('.modal, [role="dialog"]')).toContainText('新建数据项目')

    // 表单字段：项目名、业务域、描述
    await expect(page.locator('input[placeholder*="供应链"]')).toBeVisible()
    await expect(page.locator('input[placeholder="运营"]')).toBeVisible()

    // 取消按钮
    const cancelBtn = page.locator('button', { hasText: '取消' })
    await cancelBtn.click()
    await expect(page.locator('.modal, [role="dialog"]')).not.toBeVisible({ timeout: 5_000 })
  })

  test('新建项目弹窗表单验证（空项目名）', async ({ page }) => {
    await page.locator('.toolbar button', { hasText: '新建项目' }).click()
    await expect(page.locator('.modal, [role="dialog"]')).toBeVisible({ timeout: 5_000 })

    // 项目名留空，点击创建
    const createBtn = page.locator('.modal button, [role="dialog"] button', { hasText: '新建' })
    await createBtn.click()

    // 应显示 toast 提示"请填写项目名"（用 getByText 精确定位）
    await expect(page.getByText('请填写项目名')).toBeVisible({ timeout: 5_000 })
  })

  test('项目 API 返回 ApiResponse 统一格式', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/projects`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    expect(json).toHaveProperty('code', 0)
    expect(json).toHaveProperty('message', 'OK')
    expect(json).toHaveProperty('success', true)
    expect(json.data).toHaveProperty('list')
    expect(json.data).toHaveProperty('total')
  })

  test('项目数据集 API（mock 清零后）返回 200 与空列表', async ({ request }) => {
    // 验证 P0 改动：mock 清零后 /projects/{id}/datasets 返回 200
    const token = await getApiToken(request)

    // 先获取项目列表
    const listResp = await request.get(`${apiBase}/projects`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const listJson = await listResp.json()
    expect(listJson.code).toBe(0)

    if (listJson.data.list.length > 0) {
      // 取第一个项目 id
      const projectId = listJson.data.list[0].id
      const dsResp = await request.get(`${apiBase}/projects/${projectId}/datasets`, {
        headers: { Authorization: `Bearer ${token}` }
      })
      expect(dsResp.status()).toBe(200)
      const dsJson = await dsResp.json()
      expect(dsJson).toHaveProperty('code', 0)
      expect(dsJson).toHaveProperty('success', true)
      expect(Array.isArray(dsJson.data)).toBe(true)
    } else {
      // 无项目时跳过（mock 清零验证已通过冒烟）
      test.skip(true, '项目列表为空，跳过数据集接口验证')
    }
  })

  test('项目 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/projects`)
    expect(resp.status()).toBe(401)
  })

  test('项目详情抽屉打开后显示 tab 切换', async ({ page }) => {
    // 等待列表加载
    await page.waitForSelector('table', { timeout: 15_000 })
    await expect(page.locator('text=加载中…')).toHaveCount(0, { timeout: 15_000 })

    // 检查是否有可点击的项目行
    const clickableRows = page.locator('tr.click')
    const rowCount = await clickableRows.count()

    if (rowCount > 0) {
      // 点击第一行
      await clickableRows.first().click()
      // 抽屉出现
      await expect(page.locator('.drawer, [role="dialog"]')).toBeVisible({ timeout: 5_000 })
      // tab 栏
      const tabs = page.locator('.tabbar .t')
      await expect(tabs).toHaveCount(5)
      const tabTexts = await tabs.allTextContents()
      expect(tabTexts).toEqual(['概览', '数据集', '作业', '成员', '设置'])
    } else {
      test.skip(true, '无项目数据，跳过详情抽屉验证')
    }
  })
})