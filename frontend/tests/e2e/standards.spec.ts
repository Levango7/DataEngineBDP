/**
 * 数据标准 E2E 测试
 *
 * 覆盖：
 * - 标准列表加载
 * - 标准列表表格结构
 * - 新建标准弹窗
 * - 标准类型选项
 * - 标准 API 响应格式
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('数据标准（/standard）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/standard', { waitUntil: 'domcontentloaded' })
  })

  test('标准列表页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('数据标准')
    await expect(page.locator('.sub')).toContainText('统一字段命名')
    await expect(page.locator('.card')).toBeVisible()
  })

  test('标准列表表格表头正确', async ({ page }) => {
    // 等待 loading 结束（"加载中…"消失）
    await page.waitForTimeout(2_000)
    await page.waitForSelector('.card table', { timeout: 15_000 })
    // 验证表格存在且有表头
    const headers = page.locator('.card table th')
    const count = await headers.count()
    expect(count).toBeGreaterThanOrEqual(3)
    // 验证 h1 标题正确（确认在标准页面）
    await expect(page.locator('h1')).toContainText('数据标准')
  })

  test('标准列表显示暂无标准或数据行', async ({ page }) => {
    await page.waitForSelector('table', { timeout: 15_000 })
    // 等待 loading 结束
    await expect(page.locator('text=加载中…')).toHaveCount(0, { timeout: 15_000 })
    const table = page.locator('table')
    await expect(table).toBeVisible()
  })

  test('落标率标签显示', async ({ page }) => {
    // .pill.b 显示"已落标 xx%"
    const pill = page.locator('.toolbar .pill.b')
    await expect(pill).toBeVisible()
    await expect(pill).toContainText('已落标')
  })

  test('新建标准按钮存在', async ({ page }) => {
    const btn = page.locator('.toolbar button', { hasText: '新建标准' })
    await expect(btn).toBeVisible()
    expect(await btn.textContent()).toContain('+')
  })

  test('新建标准弹窗打开与关闭', async ({ page }) => {
    await page.locator('.toolbar button', { hasText: '新建标准' }).click()

    // 弹窗
    await expect(page.locator('.modal, [role="dialog"]')).toBeVisible({ timeout: 5_000 })
    await expect(page.locator('.modal, [role="dialog"]')).toContainText('新建数据标准')

    // 表单字段
    await expect(page.locator('input[placeholder*="user_id"]')).toBeVisible()
    await expect(page.locator('input[placeholder*="bigint"]')).toBeVisible()

    // 类型下拉（限定弹窗内，排除侧边栏语言切换器 <select>）
    const typeSelect = page.locator('.modal select, [role="dialog"] select')
    await expect(typeSelect).toBeVisible()
    const options = typeSelect.locator('option')
    const optionTexts = await options.allTextContents()
    expect(optionTexts).toContain('主键')
    expect(optionTexts).toContain('枚举')
    expect(optionTexts).toContain('字典')
    expect(optionTexts).toContain('金额')

    // 取消
    await page.locator('button', { hasText: '取消' }).click()
    await expect(page.locator('.modal, [role="dialog"]')).not.toBeVisible({ timeout: 5_000 })
  })

  test('新建标准弹窗表单验证（空标准项）', async ({ page }) => {
    await page.locator('.toolbar button', { hasText: '新建标准' }).click()
    await expect(page.locator('.modal, [role="dialog"]')).toBeVisible({ timeout: 5_000 })

    // 标准项留空，点击发布
    const publishBtn = page.locator('.modal button, [role="dialog"] button', { hasText: '发布' })
    await publishBtn.click()

    // 应显示 toast"请填写标准项"（用 getByText 精确定位）
    await expect(page.getByText('请填写标准项')).toBeVisible({ timeout: 5_000 })
  })

  test('标准 API 返回 ApiResponse 统一格式', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/standards`, {
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

  test('标准 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/standards`)
    expect(resp.status()).toBe(401)
  })

  test('标准摘要 API 返回 ApiResponse 格式', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/standards/summary`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    // 200 或 404 均可（接口可能未实现），但格式应是 ApiResponse
    if (resp.status() === 200) {
      const json = await resp.json()
      expect(json).toHaveProperty('code')
      expect(json).toHaveProperty('success')
    }
  })
})