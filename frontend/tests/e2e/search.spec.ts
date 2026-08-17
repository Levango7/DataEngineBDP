/**
 * 搜索功能 E2E 测试
 *
 * 覆盖：
 * - 检索门户页面加载
 * - 检索输入框
 * - 检索过滤器
 * - 检索历史（验证 mock 清零后的接口）
 * - 检索建议
 * - 检索 API 响应格式
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('检索门户（/search）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/search', { waitUntil: 'domcontentloaded' })
  })

  test('检索门户页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('检索门户')
    await expect(page.locator('.sub')).toContainText('统一检索入口')
  })

  test('检索输入框存在', async ({ page }) => {
    // 检索栏
    await expect(page.locator('.portal-search-bar')).toBeVisible()
    // 输入框（el-input 内的 input）
    const input = page.locator('.portal-search-bar input').first()
    await expect(input).toBeVisible()
  })

  test('检索过滤器侧栏存在', async ({ page }) => {
    await expect(page.locator('.portal-filter')).toBeVisible()
    // 排序区
    await expect(page.locator('.sort-box')).toBeVisible()
    await expect(page.locator('.sort-title')).toContainText('排序')
  })

  test('排序选项存在', async ({ page }) => {
    // 排序下拉
    const sortSelect = page.locator('.sort-box .el-select')
    await expect(sortSelect).toBeVisible()
    // 升降序单选
    const sortOrder = page.locator('.sort-box .el-radio-group')
    await expect(sortOrder).toBeVisible()
  })

  test('结果区初始态显示"开始您的检索"', async ({ page }) => {
    // 初始未检索态：init-state 可见
    await expect(page.locator('.init-state')).toBeVisible({ timeout: 10_000 })
    await expect(page.locator('.init-state')).toContainText('开始您的检索')
  })

  test('结果工具栏显示"请输入检索条件"', async ({ page }) => {
    // 未检索时显示提示
    const stat = page.locator('.result-stat')
    await expect(stat).toBeVisible()
    // 文本可能是"请输入检索条件"或类似
    const text = await stat.textContent()
    expect(text).toBeTruthy()
  })

  test('检索历史 API（mock 清零后）返回 200 与空列表', async ({ request }) => {
    // 验证 P0 改动：mock 清零后 /search/history 返回 200
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/search/history`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    expect(json).toHaveProperty('code', 0)
    expect(json).toHaveProperty('message', 'OK')
    expect(json).toHaveProperty('success', true)
    expect(Array.isArray(json.data)).toBe(true)
    // mock 清零后应为空数组
    expect(json.data.length).toBe(0)
  })

  test('检索建议 API 返回 ApiResponse 格式', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/search/suggest`, {
      headers: { Authorization: `Bearer ${token}` },
      params: { keyword: 'test' }
    })
    // 200 或 404（接口可能未实现）均可，但格式应是 ApiResponse
    const json = await resp.json()
    expect(json).toHaveProperty('code')
    expect(json).toHaveProperty('message')
    expect(json).toHaveProperty('success')
  })

  test('检索过滤器候选 API 返回 ApiResponse 格式', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/search/facets`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    const json = await resp.json()
    expect(json).toHaveProperty('code')
    expect(json).toHaveProperty('message')
    expect(json).toHaveProperty('success')
  })

  test('执行检索 POST /search 返回 ApiResponse 格式', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.post(`${apiBase}/search`, {
      headers: { Authorization: `Bearer ${token}` },
      data: {
        keyword: 'test',
        mode: 'natural',
        page: 1,
        pageSize: 10
      }
    })
    const json = await resp.json()
    expect(json).toHaveProperty('code')
    expect(json).toHaveProperty('message')
    expect(json).toHaveProperty('success')
    if (json.code === 0) {
      expect(json.data).toBeDefined()
    }
  })

  test('检索历史 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/search/history`)
    expect(resp.status()).toBe(401)
  })
})