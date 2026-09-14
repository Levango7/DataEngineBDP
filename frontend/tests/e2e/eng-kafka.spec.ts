/**
 * Kafka 引擎管理 E2E（P1 · 前端全量 E2E 页面覆盖）
 *
 * 页面：/#/eng-kafka（EngKafka.vue）
 * API：/api/v1/datasources（type=kafka）、/api/v1/kafka/{clusterId}/brokers
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn, apiBase, getApiToken } from './helpers'

test.describe('Kafka 引擎管理（/eng-kafka）', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    await page.goto('/#/eng-kafka', { waitUntil: 'domcontentloaded' })
  })

  test('Kafka 引擎页加载', async ({ page }) => {
    await expect(page.locator('h1')).toContainText('OLAP/消息（Kafka）')
    await expect(page.locator('.sub')).toContainText('消息队列')
    await expect(page.locator('.card').first()).toBeVisible({ timeout: 15_000 })
  })

  test('集群选择、Tab 切换与 Broker 表格存在', async ({ page }) => {
    await page.waitForTimeout(1_500)
    await expect(page.locator('h1')).toContainText('OLAP/消息（Kafka）')
    // 集群选择下拉
    await expect(page.locator('.toolbar .el-select').first()).toBeVisible({ timeout: 15_000 })
    // 三个 Tab：Broker / Topic / 消费组
    await expect(page.locator('.el-tabs__item', { hasText: 'Broker' })).toBeVisible({
      timeout: 15_000
    })
    await expect(page.locator('.el-tabs__item', { hasText: 'Topic' })).toBeVisible()
    await expect(page.locator('.el-tabs__item', { hasText: '消费组' })).toBeVisible()
    // Broker 表格
    await expect(page.locator('.el-table').first()).toBeVisible({ timeout: 15_000 })
  })

  test('Kafka 集群列表 API 返回 200（Bearer 认证）', async ({ request }) => {
    const token = await getApiToken(request)
    const resp = await request.get(`${apiBase}/datasources`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { type: 'kafka' }
    })
    expect(resp.status()).toBe(200)
  })

  test('Kafka 集群列表 API 未认证返回 401', async ({ request }) => {
    const resp = await request.get(`${apiBase}/datasources`, {
      data: { type: 'kafka' }
    })
    expect(resp.status()).toBe(401)
  })
})