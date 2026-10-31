import { test, expect } from '@playwright/test'
import { login } from './helpers'

/**
 * 流通看板（/exchange-overview）的外壳级 e2e。
 *
 * 只钉三件在 CI 栈里必然可判定的事：路由可达、页面不带裸 i18n key、
 * "有数据 / 空态 / 错误态"三者必居其一——**不断言具体金额**，因为 nightly 栈里
 * 该租户是否已有结算单取决于测试顺序，把顺序敏感的数字写进断言会变成长期 flake。
 * 数字口径的正确性由 src/views/exchange/__tests__/overview.test.ts 在纯函数层钉。
 */

/** client.ts / stores/auth.ts 用的 token 存储键（helpers 未导出，这里按现查值写死并注释来源） */
const TOKEN_KEY = 'sq_token'

test.describe('流通看板', () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
  })

  test('路由可达且外壳完整', async ({ page }) => {
    await page.goto('/#/exchange-overview')
    const page$ = page.locator('.ex-page')
    await expect(page$).toBeVisible()
    // IT 栈不播种结算单 ⇒ 空态是合法终态；只有拿到数据时才应渲染两张图。
    // 写死 canvas=2 等于把"该租户恰好有结算"当成门禁条件（10-24 起连红四晚的成因）。
    // 错误态必须判红：后端不通时页面只剩错误框，这里不接受。
    const kpi = page$.locator('[data-test="kpi-settlement-count"]')
    const empty = page$.locator('[data-test="overview-empty"]')
    await expect(kpi.or(empty)).toBeVisible()
    await expect(page$.locator('canvas')).toHaveCount((await kpi.isVisible()) ? 2 : 0)
    // 裸 key（exchange.overview.xxx）出现在页面上 = 词条没注册进 ROUTE_MODULE_MAP
    await expect(page$).not.toContainText(/exchange\.overview\.[a-zA-Z]/)
  })

  test('状态可判定：KPI / 空态 / 错误态三者必居其一，不能静默空白', async ({ page }) => {
    await page.goto('/#/exchange-overview')
    const kpi = page.locator('[data-test="kpi-settlement-count"]')
    const empty = page.locator('[data-test="overview-empty"]')
    const err = page.locator('[data-test="overview-error"]')
    await expect(kpi.or(empty).or(err)).toBeVisible()
  })

  test('侧边栏有该页入口（Sidebar 的 router-link 形状）', async ({ page }) => {
    await page.goto('/#/dashboard')
    const link = page.locator('a.nav-item[href*="exchange-overview"]')
    await expect(link).toBeVisible()
    expect(await page.evaluate((k) => sessionStorage.getItem(k), TOKEN_KEY)).toBeTruthy()
  })
})
