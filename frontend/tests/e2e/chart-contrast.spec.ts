/**
 * 尾栏 + 图表文字对比度回归（UI-only，mock 本地 /api/，不涉及真实后端与认证）
 *
 * 本 spec 是对 `sidebar-contrast.spec.ts` 的**采样点扩展**：原有 12 个点只覆盖侧栏，
 * 因此上一轮"全绿"却漏掉了两个真实问题：
 *   ① 尾栏暗色被 main.css 的 !important 覆写盖成蓝黑渐变 + 靛蓝分割线（亮暗不同源）；
 *   ② ECharts 把 `'var(--ds-text-secondary)'` 这类字符串塞进 option，canvas 不解析
 *      CSS 变量 → 颜色解析失败回退出厂浅灰 → 图表轴标签 / 图例几乎不可见。
 *
 * 采样点（每主题 9 个，亮暗共 18 个）：
 *   尾栏 DOM 5 点：sb-label / sb-service / sb-item(工作区) / sb-clock / sb-env
 *   图表 canvas 3 点：x 轴标签 / y 轴标签 / 图例文字（用 sampleCanvasText 量真实位图）
 *   图表 tooltip 1 点：悬停展开后量 DOM 计算样式（tooltip 是不透明 HTML 层）
 *
 * 配套：`glass-harness-control.spec.ts` 的 C1/C2/C3 正面控制必须同时通过，
 * 否则本 spec 的"全绿"不成立（见 helpers/contrast.ts 顶部说明）。
 */
import { test, expect, type Page, type Locator } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { loginByApi, apiBase } from './helpers'
import { sample, sampleCanvasText, contrastRatio, parseRgb, toHex, type SampleOptions } from './helpers/contrast'
import { CHART_PALETTE_LIGHT, CHART_PALETTE_DARK } from '../../src/composables/chartPalette'

test.use({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 })

const CHART_SEL = '.trend-chart'

/** 图表文字区域（canvas 比例坐标）。区域只需"包含"目标文字，多含空白不影响众数底色。 */
const REGIONS = {
  'chart-axis-x': { x0: 0.05, y0: 0.9, x1: 0.98, y1: 1.0 },
  'chart-axis-y': { x0: 0.0, y0: 0.1, x1: 0.06, y1: 0.95 },
  'chart-legend': { x0: 0.6, y0: 0.0, x1: 1.0, y1: 0.1 }
} as const

async function installUiFixture(page: Page) {
  const token = `${Buffer.from('{"alg":"none","typ":"JWT"}').toString('base64url')}.${Buffer.from(
    JSON.stringify({ sub: 'chart-contrast', exp: Math.floor(Date.now() / 1000) + 3600 })
  ).toString('base64url')}.ui-fixture`
  const user = {
    id: 'chart-contrast',
    username: 'chart-contrast',
    nickname: '图表对比度夹具',
    email: 'chart@example.invalid',
    tenantId: 'chart-contrast',
    roles: ['admin'],
    status: 'active'
  }
  const requests: string[] = []
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      requests.push(path)
      let data: unknown = []
      if (path === `${apiBase}/auth/login`) data = { token, user, expiresIn: 3600 }
      else if (path === `${apiBase}/auth/me`) data = user
      else if (path.endsWith('/cluster/overview'))
        data = {
          clusterName: 'UI fixture',
          version: 'test',
          nodeTotal: 6,
          nodeReady: 6,
          podTotal: 220,
          podRunning: 214,
          cpuCapacity: 384,
          cpuUsed: 108,
          memCapacity: 1536,
          memUsed: 612,
          storageUsed: 48,
          projectCount: 12,
          projectRunning: 11,
          jobCount: 320,
          jobSuccessToday: 298,
          jobFailToday: 4,
          assetCount: 512,
          // 真实趋势数据：让折线 / 图例 / tooltip 都真正渲染出来
          trendCpu: [42, 51, 47, 63, 58, 72, 66],
          trendMem: [55, 58, 61, 57, 64, 69, 62]
        }
      else if (path.endsWith('/cluster/nodes'))
        data = [
          {
            name: 'bdp-master-01',
            role: 'master',
            status: 'ready',
            cpuUsed: 18,
            cpuCapacity: 64,
            memUsed: 96,
            memCapacity: 256,
            podCount: 42,
            podCapacity: 110,
            os: 'Rocky Linux 9.3'
          }
        ]
      else if (path.endsWith('/cluster/components'))
        data = [{ name: 'Spark', status: 'healthy', meta: '3.5.0 · 4 nodes' }]
      await route.fulfill({ json: { code: 0, message: 'UI TEST FIXTURE', data } })
    }
  )
  await page.route(/\/actuator\/health/, (route) => route.fulfill({ json: { status: 'UP', components: {} } }))
  return requests
}

/** tooltip 是不透明 HTML 层：读它真实解析后的 color / background-color 直接算对比度 */
async function sampleTooltipDom(page: Page, state: string, chartSel: string) {
  const box = await page.locator(chartSel).boundingBox()
  if (!box) throw new Error(`${chartSel} 不可见`)
  // 悬停到网格内，触发 trigger:'axis' 的 tooltip
  await page.mouse.move(box.x + box.width * 0.5, box.y + box.height * 0.5)
  await page.waitForTimeout(500)
  const probe = await page.evaluate((sel) => {
    const c = document.querySelector(sel)
    if (!c) return null
    for (const d of Array.from(c.querySelectorAll('div'))) {
      const cs = getComputedStyle(d)
      const text = (d.textContent || '').trim()
      if (
        cs.position === 'absolute' &&
        text.length > 0 &&
        cs.visibility !== 'hidden' &&
        cs.display !== 'none' &&
        Number(cs.opacity) > 0
      ) {
        const m = (v: string) => (v.match(/[\d.]+/g) || []).map(Number)
        return {
          text: text.slice(0, 80),
          color: cs.color,
          background: cs.backgroundColor,
          borderColor: cs.borderColor,
          rgba: m(cs.backgroundColor),
          fg: m(cs.color)
        }
      }
    }
    return null
  }, chartSel)
  if (!probe) throw new Error('tooltip 未出现（ECharts 未渲染 tip）')
  return {
    state,
    text: probe.text,
    color: probe.color,
    background: probe.background,
    borderColor: probe.borderColor,
    contrast: contrastRatio(probe.fg.slice(0, 3), probe.rgba.slice(0, 3)),
    threshold: 4.5
  }
}

for (const theme of ['light', 'dark'] as const) {
  test(`尾栏 + 图表文字对比度（${theme}）`, async ({ page }, testInfo) => {
    test.setTimeout(180_000)
    const dir = process.env.CHART_EVIDENCE_DIR || testInfo.outputDir
    mkdirSync(dir, { recursive: true })
    const prefix = `fix2-${theme}`
    const palette = theme === 'dark' ? CHART_PALETTE_DARK : CHART_PALETTE_LIGHT

    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' })
    await page.addInitScript((mode) => {
      if (mode === 'dark') localStorage.setItem('sq_theme', 'dark')
      else localStorage.removeItem('sq_theme')
    }, theme)

    const errors: string[] = []
    page.on('pageerror', (e) => errors.push(e.message))
    const requests = await installUiFixture(page)
    await loginByApi(page, { username: 'chart-contrast', password: 'chart-contrast' })
    await page.reload({ waitUntil: 'domcontentloaded' })
    // 先等登录后的默认重定向落地。否则直接 goto('#/cluster') 会和守卫的
    // '#/dashboard' 重定向赛跑，偶尔被盖回工作台（本轮首跑就踩到了）。
    await expect(page.locator('aside.side')).toBeVisible({ timeout: 30_000 })
    await expect(page).toHaveURL(/#\/dashboard/)
    // 走真实用户路径：点侧栏"集群总览"，避免 reload 后 hash 导航的竞态
    await page.locator('aside.side a[href="#/cluster"]').first().click()
    await expect(page).toHaveURL(/#\/cluster/, { timeout: 30_000 })
    // 集群总览是懒加载路由：冷启动 dev server 首次要现编译 chunk，给足时间
    await expect(page.locator(CHART_SEL)).toBeVisible({ timeout: 60_000 })
    await expect(page.locator(`${CHART_SEL} canvas`)).toBeVisible({ timeout: 60_000 })
    // 主题契约（2026-09-21 修复）：data-theme 恒为解析结果 'light' | 'dark'，
    // system 档（无存储偏好 + 系统亮色）也写入（旧契约是 light 档不带属性）。
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    // 等 ECharts 画完
    await page.waitForTimeout(600)

    /* ---------------- 尾栏（DOM） ---------------- */
    const footerRows: Array<Record<string, unknown>> = []
    const footer = page.locator('footer.statusbar')
    await expect(footer).toBeVisible()
    const capture = async (name: string, locator: Locator, threshold = 4.5, opts?: SampleOptions) => {
      const row = await sample(page, locator, name, join(dir, `${prefix}-${name}.png`), opts)
      footerRows.push({ ...row, threshold })
    }
    await page.mouse.move(1000, 0)
    await capture('statusbar-label', footer.locator('.sb-label'))
    // .sb-service 胶囊内含彩色状态点（.sb-dot），它不是文字却落在包围盒里，
    // 会把"最小对比度"拉到 1.2 —— 采样时临时隐藏，量文字真正的底色。
    await capture('statusbar-service', footer.locator('.sb-service').first(), 4.5, { hide: '.sb-dot' })
    await capture('statusbar-ws', footer.locator('.sb-item.sb-ws'))
    // .sb-clock 是圆角描边胶囊：描边弧线侵入默认 2px 内缩区，加大到 4px。
    await capture('statusbar-clock', footer.locator('.sb-clock'), 4.5, { inset: 4 })
    await capture('statusbar-env', footer.locator('.sb-env'))

    /* ---------------- 图表 canvas 文字 ---------------- */
    const canvasRows: Array<Record<string, unknown>> = []
    const canvas = page.locator(`${CHART_SEL} canvas`).first()
    for (const [state, region] of Object.entries(REGIONS)) {
      const expected = state === 'chart-legend' ? palette.legendText : palette.axisText
      const row = await sampleCanvasText(page, canvas, region, expected, state)
      canvasRows.push({ ...row, expectedRole: state === 'chart-legend' ? 'legendText' : 'axisText' })
    }

    /* ---------------- 图表 tooltip（DOM 计算样式） ---------------- */
    const tooltip = await sampleTooltipDom(page, 'chart-tooltip', CHART_SEL)
    await page.mouse.move(1000, 0)
    await page.waitForTimeout(200)

    /* ---------------- 截图：整页 + 尾栏特写 + 图表特写 ---------------- */
    await page.screenshot({ path: join(dir, `${prefix}-full.png`), animations: 'disabled' })
    await footer.screenshot({ path: join(dir, `${prefix}-statusbar-closeup.png`), animations: 'disabled' })
    await page.locator(CHART_SEL).screenshot({ path: join(dir, `${prefix}-chart-closeup.png`), animations: 'disabled' })

    const report = {
      theme,
      fixture: 'UI-only: mocked local /api/; not real backend/auth acceptance',
      url: testInfo.project.use.baseURL,
      palette,
      footerRows,
      canvasRows,
      tooltip,
      pageErrors: errors,
      mockedApiPaths: [...new Set(requests)]
    }
    const json = join(dir, `${prefix}-validation.json`)
    writeFileSync(json, JSON.stringify(report, null, 2))
    await testInfo.attach(`${prefix}-validation`, { path: json, contentType: 'application/json' })

    /* ---------------- 断言 ---------------- */
    for (const row of footerRows) {
      expect
        .soft(row.minContrast, `${theme} 尾栏 ${row.state}: 前景 ${row.color}`)
        .toBeGreaterThanOrEqual(row.threshold as number)
    }
    for (const row of canvasRows) {
      expect
        .soft(
          row.inkPresence,
          `${theme} ${row.state}: 区域内找不到预期文字色 ${row.expectedHex}（inkPresence=${row.inkPresence}）——` +
            `要么区域取错，要么 ECharts 没按配置颜色绘制（回退出厂色）`
        )
        .toBeGreaterThan(0)
      expect
        .soft(
          row.contrast,
          `${theme} ${row.state}: ${row.expectedHex} 压在实测底色 ${row.bgHex} 上仅 ${(row.contrast as number).toFixed(2)}:1`
        )
        .toBeGreaterThanOrEqual(row.threshold as number)
    }
    expect
      .soft(
        tooltip.contrast,
        `${theme} chart-tooltip: ${tooltip.color} 压在 ${tooltip.background} 上仅 ${tooltip.contrast.toFixed(2)}:1`
      )
      .toBeGreaterThanOrEqual(tooltip.threshold)
    expect(errors).toEqual([])
  })
}

/** 尾栏亮暗同源：两套主题下尾栏的**结构色**必须来自同一组语义 token（值不同、来源相同）。
 *  这里做一条轻量静态断言：尾栏相关 CSS 不得再出现被禁用的旧暗色硬编码。 */
test('尾栏亮暗同源：无蓝黑渐变 / 靛蓝分割线残留', async () => {
  const { readFileSync } = await import('node:fs')
  const css = readFileSync(join(process.cwd(), 'src/styles/main.css'), 'utf8')
  const sbBlocks = css.split(/\n(?=\S)/).filter((b) => /\.statusbar|\.sb-/.test(b))
  const offending = sbBlocks.filter((b) =>
    /--ds-color-dark-bg-1|--ds-color-dark-bg-2|--ds-color-gray-900|rgba\(99,\s*102,\s*241/.test(b)
  )
  expect(offending, `尾栏规则仍引用被禁用的暗色硬编码:\n${offending.join('\n')}`).toEqual([])
})

/** 图表颜色表与 design-tokens 的锚点一致性：防止有人只改一处。
 *  只断言"亮暗不同"（证明确实随主题切换）+ 关键锚点值，避免脆弱的全表比对。 */
test('图表色板亮暗分离且锚点正确', () => {
  expect(CHART_PALETTE_LIGHT.axisText).not.toBe(CHART_PALETTE_DARK.axisText)
  expect(toHex(parseRgb(CHART_PALETTE_LIGHT.axisText))).toBe('#454d5a')
  expect(toHex(parseRgb(CHART_PALETTE_DARK.axisText))).toBe('#adb5c2')
  // 暗色文字在暗色画布上的对比度必须达标
  expect(contrastRatio(parseRgb(CHART_PALETTE_DARK.axisText), parseRgb('#101317'))).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(parseRgb(CHART_PALETTE_DARK.axisText), parseRgb('#171a20'))).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(parseRgb(CHART_PALETTE_LIGHT.axisText), parseRgb('#ffffff'))).toBeGreaterThanOrEqual(4.5)
  expect(contrastRatio(parseRgb(CHART_PALETTE_LIGHT.axisText), parseRgb('#f3f5f9'))).toBeGreaterThanOrEqual(4.5)
})
