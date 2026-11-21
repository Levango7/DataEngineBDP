/**
 * 几何门禁·布局断言（/exchange-overview）
 *
 * 背景（外部审查报告核实结论）：前端设计契约门禁只管颜色/对比度
 * （scripts/check-design-contrast.py、check-design-sync.py、check-hardcoded-colors.py），
 * 无任何几何/布局自动化断言（无等分/等高/居中/溢出检测）。本 spec 补上这一门禁盲区。
 *
 * 判据来源：frontend-page-design.md 强制「4 个等分 KPI 卡」；ExchangeOverview 用
 * grid + repeat(auto-fit, minmax(160px,1fr)) 等分 ⇒ 等宽/同行顶边对齐/无横向滚动
 * 是可被 boundingBox 可靠测量的布局不变量（检出「grid 被改坏 / 元素溢出」类回归）。
 *
 * mock 后端（page.route）：不依赖 docker-compose 栈，本机与 CI 均可跑。
 */
import { test, expect } from '@playwright/test'
import { login } from './helpers'

/** 伪造一个不过期的 JWT（前端仅读 payload.exp，不验签）。 */
function fakeJwt(): string {
  const b64 = (o: object) => Buffer.from(JSON.stringify(o)).toString('base64url')
  return `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ sub: 'admin', exp: 9999999999 })}.fakesig`
}

test.describe('几何门禁·布局断言（/exchange-overview）', () => {
  test.beforeEach(async ({ page }) => {
    // mock 登录（loginByApi 走浏览器内 fetch，被 page.route 拦截）
    await page.route('**/api/v1/auth/login', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          data: {
            token: fakeJwt(),
            user: { username: 'admin', tenantId: 't1', roles: ['admin'] }
          }
        })
      })
    )
    // mock 结算列表（非空 ⇒ KPI 卡片渲染；empty 态无卡片）
    await page.route('**/api/v1/settlements**', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          tenant: 't1',
          count: 3,
          settlements: [
            { id: 's1', assetId: 'a1', period: '2026-11', status: 'SETTLED', totalAmount: 1000, providerRevenue: 800, platformRevenue: 200, settledAt: '2026-11-01T00:00:00Z' },
            { id: 's2', assetId: 'a2', period: '2026-11', status: 'SETTLED', totalAmount: 2000, providerRevenue: 1600, platformRevenue: 400, settledAt: '2026-11-02T00:00:00Z' },
            { id: 's3', assetId: 'a1', period: '2026-10', status: 'SETTLED', totalAmount: 1500, providerRevenue: 1200, platformRevenue: 300, settledAt: '2026-10-01T00:00:00Z' }
          ]
        })
      })
    )
    // mock /auth/me（登录后 authStore 拉用户信息；失败有兜底但显式 mock 更稳）
    await page.route('**/api/v1/auth/me', (route) =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 0,
          data: { username: 'admin', tenantId: 't1', roles: ['admin'] }
        })
      })
    )
    // 先访问目标页：未登录会被守卫踢到 /login?redirect=…
    await page.goto('/#/exchange-overview', { waitUntil: 'domcontentloaded' })
    // 用 UI 登录（走 authStore.login action 真正更新 token.value）。不能用 loginByApi 纯注入：
    // hash 路由跳转不刷新页面，authStore 的 token 只在加载时读一次 sessionStorage，
    // 注入后 isAuthenticated 恒 false 被守卫踢回（helpers 注释亦警告注入会被误踢）。
    await login(page)
    // 已登录，确保落在目标页
    await page.goto('/#/exchange-overview', { waitUntil: 'domcontentloaded' })
    // 等 KPI 卡片渲染（rows 非空 ⇒ ex-kpis 出现）
    await expect(page.locator('.ex-kpis .ex-kpi').first()).toBeVisible({ timeout: 15_000 })
  })

  test('KPI 卡片等宽（grid 等分布局）', async ({ page }) => {
    const cards = page.locator('.ex-kpis .ex-kpi')
    const n = await cards.count()
    expect(n).toBeGreaterThanOrEqual(4) // 设计规范「4 个等分 KPI 卡」
    const widths: number[] = []
    for (let i = 0; i < n; i++) {
      const b = await cards.nth(i).boundingBox()
      expect(b).not.toBeNull()
      widths.push(b!.width)
    }
    for (const w of widths) {
      expect(Math.abs(w - widths[0])).toBeLessThanOrEqual(2) // 等宽，容差 ±2px
    }
  })

  test('KPI 卡片同行顶边对齐（不错位）', async ({ page }) => {
    // 等字体加载完成，避免加载期 reflow 造成测量噪声
    await page.evaluate(() => document.fonts.ready)
    // 一次批量测量（同一帧内取所有 rect），消除逐次 boundingBox 之间的 reflow 噪声
    const rects = await page.evaluate(() =>
      Array.from(document.querySelectorAll('.ex-kpis .ex-kpi')).map((el) => {
        const r = el.getBoundingClientRect()
        return { y: r.y }
      })
    )
    expect(rects.length).toBeGreaterThanOrEqual(4)
    const firstY = rects[0].y
    for (const r of rects) {
      // 同一行内（y 差 < 40px 视为同行，换行属 auto-fit 正常行为）顶边必须一致
      if (Math.abs(r.y - firstY) < 40) {
        expect(Math.abs(r.y - firstY)).toBeLessThanOrEqual(2)
      }
    }
  })

  test('KPI 网格与页面均不得横向溢出视口', async ({ page }) => {
    // 判据 1：页面级无横向滚动（documentElement 口径）。
    const pageOverflow = await page.evaluate(
      () => document.documentElement.scrollWidth > window.innerWidth
    )
    expect(pageOverflow).toBe(false)
    // 判据 2：被测容器自身必须完整落在视口内。只看 documentElement 会被
    // 祖先 overflow-x 裁剪吞掉溢出（变异电池 m3 实证：min-width:3000px 撑爆
    // .ex-kpis 后页面级 scrollWidth 不变、内容被静默截断）——容器级 rect 是
    // 能抓住这种「被裁剪的截断」的最小判据。
    const kpiWidth = await page.evaluate(() => {
      const el = document.querySelector('.ex-kpis')
      return el ? el.getBoundingClientRect().width : 0
    })
    expect(kpiWidth).toBeGreaterThan(0)
    expect(kpiWidth).toBeLessThanOrEqual(await page.evaluate(() => window.innerWidth))
  })
})
