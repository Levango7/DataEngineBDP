/**
 * 封装层租户域 E2E 测试（Sprint 3.1.2）
 *
 * 覆盖 encaps-tenant 独立微服务支撑的 3 个页面（Java/Spring Boot，JWT 保护）：
 * - /#/tenants    租户管理（/api/v1/tenants）
 * - /#/account    账户与配额（/api/v1/account）
 * - /#/admin      运营后台（/api/v1/admin）
 *
 * 认证语义：encaps-tenant 挂统一 JwtAuthFilter，无 token 访问受保护端点必须 401。
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('租户管理 /tenants', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/tenants', { waitUntil: 'domcontentloaded' })
  })

  test('租户管理页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('租户管理')
    await expect(page.locator('.toolbar')).toBeVisible()
    // 租户列表表格（el-table 渲染 header+body 两个 table，用 aria-label 定位主表）
    await expect(page.getByRole('table', { name: '租户列表表格' })).toBeVisible()
  })

  test('搜索与状态筛选控件存在', async ({ page }) => {
    const searchInput = page.locator('.toolbar input[placeholder*="搜索"]')
    await expect(searchInput).toBeVisible()
    await searchInput.fill('测试')
    await expect(searchInput).toHaveValue('测试')
  })

  test('租户列表 API 返回 ApiResponse 统一格式', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/tenants`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    expect(json).toHaveProperty('code', 0)
    expect(json).toHaveProperty('message', 'OK')
    expect(json).toHaveProperty('data')
    expect(Array.isArray(json.data)).toBe(true)
  })

  test('租户列表 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/tenants`)
    expect(resp.status()).toBe(401)
  })
})

test.describe('账户与配额 /account', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/account', { waitUntil: 'domcontentloaded' })
  })

  test('账户页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('账户与配额')
  })

  test('账户套餐 API 返回 200 且含 plan 字段', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/account/plan`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    expect(json).toHaveProperty('code', 0)
    expect(json.data).toHaveProperty('plan')
  })

  test('账户 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/account/plan`)
    expect(resp.status()).toBe(401)
  })
})

test.describe('运营后台 /admin', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/admin', { waitUntil: 'domcontentloaded' })
  })

  test('运营后台页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('运营后台')
  })

  test('KPI API 返回 200 且含核心指标', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/admin/kpi`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    expect(json).toHaveProperty('code', 0)
    expect(json.data).toHaveProperty('tenantTotal')
    expect(json.data).toHaveProperty('workspaceTotal')
  })

  test('运营后台 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/admin/kpi`)
    expect(resp.status()).toBe(401)
  })
})
