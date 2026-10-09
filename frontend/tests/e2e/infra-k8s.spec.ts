/**
 * K8s 基础设施管理 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/infra-k8s（InfraK8s.vue）
 * API：/api/v1/clusters
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('K8s 基础设施管理（/infra-k8s）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/infra-k8s', { waitUntil: 'domcontentloaded' })
  })

  test('K8s 基础设施页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('K8s 集群')
    await expect(page.locator('.sub')).toContainText('跨环境统一集群管理')
    await expect(page.locator('.card').first()).toBeVisible({ timeout: 15_000 })
  })

  test('新建集群按钮、环境 Tab 与集群表格存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    await expect(page.locator('h1')).toContainText('K8s 集群')
    // 新建集群按钮
    const createBtn = page.locator('.toolbar button', { hasText: '新建集群' })
    await expect(createBtn).toBeVisible({ timeout: 15_000 })
    // 四个环境 Tab：全部 / 私有云 / 公有云 / 信创
    await expect(page.locator('.el-tabs__item', { hasText: '全部' })).toBeVisible({
      timeout: 15_000
    })
    await expect(page.locator('.el-tabs__item', { hasText: '私有云' })).toBeVisible()
    await expect(page.locator('.el-tabs__item', { hasText: '公有云' })).toBeVisible()
    await expect(page.locator('.el-tabs__item', { hasText: '信创' })).toBeVisible()
    // 集群列表表格
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 15_000 })
  })

  test('K8s 集群列表 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/clusters`, {
      headers: { Authorization: `Bearer ${token}` }
    })
    expect(resp.status()).toBe(200)
  })

  test('K8s 集群列表 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/clusters`)
    expect(resp.status()).toBe(401)
  })
})