/**
 * 登录流程 E2E 测试
 *
 * 覆盖：
 * - 正确账号登录成功
 * - 错误密码登录失败
 * - 登录后跳转到首页
 * - 登出后跳转到登录页
 * - Token 持久化（刷新页面保持登录）
 * - 未登录访问受保护路由跳转登录页
 * - 登录页 UI 元素正确
 */
import { test, expect } from '@playwright/test'
import { login, ADMIN, apiBase } from './helpers'

test.describe('登录流程', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/#/login', { waitUntil: 'domcontentloaded' })
  })

  test('登录页 UI 元素正确显示', async ({ page }) => {
    // 品牌标识
    await expect(page.locator('.brand')).toContainText('数擎')
    // 标题
    await expect(page.locator('h2')).toContainText('登录')
    // 用户名输入框
    await expect(page.locator('input[autocomplete="username"]')).toBeVisible()
    // 密码输入框
    await expect(page.locator('input[autocomplete="current-password"]')).toBeVisible()
    // 登录按钮
    await expect(page.getByRole('button', { name: /登.*录/ })).toBeVisible()
    // 提示信息
    await expect(page.locator('.tip')).toContainText('admin')
  })

  test('正确账号登录成功', async ({ page }) => {
    await login(page, ADMIN)

    // 验证跳转到 dashboard
    await expect(page).toHaveURL(/#\/dashboard/)
    // 验证 dashboard 标题
    await expect(page.locator('h1')).toContainText('工作台')
  })

  test('错误密码登录失败显示错误信息', async ({ page }) => {
    // 填写错误密码
    await page.fill('input[autocomplete="username"]', 'admin')
    await page.fill('input[autocomplete="current-password"]', 'wrong_password_xyz')
    await page.getByRole('button', { name: /登.*录/ }).click()

    // 等待错误提示出现（.error 元素）
    await page.waitForSelector('.error', { timeout: 15_000 })
    const errorText = await page.locator('.error').textContent()
    expect(errorText).toMatch(/登录失败/)

    // 验证仍在登录页
    await expect(page).toHaveURL(/#\/login/)
  })

  test('空用户名显示验证提示', async ({ page }) => {
    // 清空用户名
    await page.fill('input[autocomplete="username"]', '')
    await page.fill('input[autocomplete="current-password"]', 'admin')
    await page.getByRole('button', { name: /登.*录/ }).click()

    // 应显示"请输入用户名和密码"
    await page.waitForSelector('.error', { timeout: 5_000 })
    const errorText = await page.locator('.error').textContent()
    expect(errorText).toMatch(/请输入用户名和密码/)
  })

  test('登录后跳转到首页 dashboard', async ({ page }) => {
    await login(page, ADMIN)
    await expect(page).toHaveURL(/#\/dashboard/)
    // 验证侧边栏出现
    await expect(page.locator('aside.side')).toBeVisible()
    // 验证顶栏出现
    await expect(page.locator('.topbar')).toBeVisible()
  })

  test('登出后跳转到登录页', async ({ page }) => {
    await login(page, ADMIN)
    await expect(page).toHaveURL(/#\/dashboard/)

    // 点击头像打开用户菜单
    await page.locator('.avatar').click()
    // 点击退出登录
    await page.getByRole('button', { name: '退出登录' }).click()

    // 验证跳转到登录页
    await expect(page).toHaveURL(/#\/login/, { timeout: 10_000 })
    await expect(page.locator('h2')).toContainText('登录')
  })

  test('Token 持久化：刷新页面保持登录', async ({ page }) => {
    await login(page, ADMIN)
    await expect(page).toHaveURL(/#\/dashboard/)

    // 验证 sessionStorage 有 token
    const token = await page.evaluate(() => sessionStorage.getItem('sq_token'))
    expect(token).toBeTruthy()
    expect(token?.length).toBeGreaterThan(10)

    // 刷新页面
    await page.reload({ waitUntil: 'domcontentloaded' })

    // 应仍在 dashboard（未跳回登录页）
    await expect(page).toHaveURL(/#\/dashboard/, { timeout: 15_000 })
    await expect(page.locator('h1')).toContainText('工作台')
  })

  test('未登录访问受保护路由跳转登录页', async ({ page }) => {
    // 直接访问 dashboard
    await page.goto('/#/dashboard', { waitUntil: 'domcontentloaded' })

    // 应被路由守卫重定向到登录页
    await expect(page).toHaveURL(/#\/login/, { timeout: 10_000 })
  })

  test('未登录访问受保护路由携带 redirect 参数', async ({ page }) => {
    // 直接访问 projects
    await page.goto('/#/projects', { waitUntil: 'domcontentloaded' })

    // 应跳转到登录页并携带 redirect 参数
    await expect(page).toHaveURL(/#\/login/, { timeout: 10_000 })
    const url = page.url()
    expect(url).toMatch(/redirect=/)
  })

  test('登录后按 redirect 参数跳转回原页面', async ({ page }) => {
    // 直接访问 projects（会跳到 login?redirect=...）
    await page.goto('/#/projects', { waitUntil: 'domcontentloaded' })
    await expect(page).toHaveURL(/#\/login/)

    // 在登录页填写账号
    await page.fill('input[autocomplete="username"]', 'admin')
    await page.fill('input[autocomplete="current-password"]', 'admin')
    await page.getByRole('button', { name: /登.*录/ }).click()

    // 应跳回 projects 而非 dashboard
    await expect(page).toHaveURL(/#\/projects/, { timeout: 15_000 })
    await expect(page.locator('h1')).toContainText('数据项目')
  })

  test('API 登录返回 ApiResponse 统一格式', async ({ request }) => {
    const resp = await request.post(`${apiBase}/auth/login`, { data: ADMIN })
    expect(resp.status()).toBe(200)
    const json = await resp.json()

    // 验证 ApiResponse 字段
    expect(json).toHaveProperty('code', 0)
    expect(json).toHaveProperty('message', 'OK')
    expect(json).toHaveProperty('data')
    expect(json).toHaveProperty('success', true)
    // data 内含 token 与 user
    expect(json.data).toHaveProperty('token')
    expect(json.data).toHaveProperty('user')
    expect(json.data.user).toHaveProperty('username', 'admin')
  })

  test('已登录访问登录页跳转到 dashboard', async ({ page }) => {
    // 先用 UI 登录
    await login(page, ADMIN)
    // 访问登录页
    await page.goto('/#/login', { waitUntil: 'domcontentloaded' })
    // 应跳转到 dashboard
    await expect(page).toHaveURL(/#\/dashboard/, { timeout: 10_000 })
  })
})
