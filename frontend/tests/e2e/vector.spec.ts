/**
 * 向量引擎 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/vector（Vector.vue）
 * API：/api/v1/vector（list）、/api/v1/vector/search
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('向量引擎（/vector）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/vector', { waitUntil: 'domcontentloaded' })
  })

  test('向量引擎页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('向量库')
    await expect(page.locator('.page-header__subtitle')).toContainText('Milvus')
    await expect(page.locator('.card')).toBeVisible({ timeout: 15_000 })
  })

  test('新建集合按钮与检索框存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    const createBtn = page.locator('.toolbar button', { hasText: '新建集合' })
    await expect(createBtn).toBeVisible()
    const search = page.locator('input[placeholder*="检索"]')
    await expect(search).toBeVisible()
  })

  test('集合表格表头正确', async ({ page }) => {
    // 先等本页渲染完成：/vector 的 h1 唯一（上一页 Dashboard 的 h1 是"工作台"）。
    // 否则 waitForSelector('table') 会命中切页过渡期残留的 Dashboard 待办表，
    // 表头被拼成"申请申请人操作"（实测），随后对"集合/维度/条数"的断言必失败。
    // 注意：本页表只在向量 API 成功时渲染；栈外服务（vector-engine）缺席时仍会按
    // 正确原因超时（属台账 #57①，不是本处的竞态）。
    await expect(page.locator('h1')).toContainText('向量库', { timeout: 15_000 })
    await page.waitForSelector('table', { timeout: 15_000 })
    const headers = page.locator('table th')
    const texts = await headers.allTextContents()
    expect(texts.join('')).toContain('集合')
    expect(texts.join('')).toContain('维度')
    expect(texts.join('')).toContain('条数')
  })

  test('向量集合 API 返回 200 数组', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/vector`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
    const json = await resp.json()
    // 契约：vector-engine（Go）返回**裸数组**（frontend/src/api/vector.ts listCollections(): VectorCollection[]）。
    // 非 Java 的 ApiResponse 包装（无 {code,data}）⇒ 断言直接看顶层，不是 json.data。
    expect(Array.isArray(json)).toBe(true)
  })

  test('向量集合 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/vector`)
    expect(resp.status()).toBe(401)
  })
})