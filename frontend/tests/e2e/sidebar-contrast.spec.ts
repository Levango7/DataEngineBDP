/** UI-only regression: mocked local APIs, not real authentication/backend acceptance. */
import { test, expect, type Page, type Locator } from '@playwright/test'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { loginByApi, apiBase } from './helpers'

const baselineCss = process.env.SIDEBAR_BASELINE_CSS
  ? readFileSync(process.env.SIDEBAR_BASELINE_CSS, 'utf8') : null
const beforeOnly = process.env.SIDEBAR_BEFORE_ONLY === '1'
// <=1440px intentionally auto-collapses Sidebar; use a desktop width to inspect labels.
test.use({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 })

async function installUiFixture(page: Page) {
  // Use the project's storage helper. The token is deliberately not a real signed credential.
  const token = `${Buffer.from('{"alg":"none","typ":"JWT"}').toString('base64url')}.${Buffer.from(JSON.stringify({ sub: 'sidebar-ui-fixture', exp: Math.floor(Date.now() / 1000) + 3600 })).toString('base64url')}.ui-fixture`
  const user = { id: 'sidebar-ui-fixture', username: 'ui-fixture', nickname: 'UI 测试', email: 'ui@example.invalid', tenantId: 'ui-fixture', roles: ['admin'], status: 'active' }
  const requests: string[] = []
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const path = new URL(route.request().url()).pathname
    requests.push(path)
    let data: unknown = []
    if (path === `${apiBase}/auth/login`) data = { token, user, expiresIn: 3600 }
    if (path === `${apiBase}/auth/me`) data = user
    if (path.endsWith('/cluster/overview')) data = { clusterName: 'UI fixture', version: 'test', projectCount: 0, projectRunning: 0, jobCount: 0, jobSuccessToday: 0, jobFailToday: 0, assetCount: 0, cpuUsed: 0, cpuCapacity: 1, memUsed: 0, memCapacity: 1, storageUsed: 0, nodeTotal: 0, nodeReady: 0, podTotal: 0, podRunning: 0, trendCpu: [], trendMem: [] }
    await route.fulfill({ json: { code: 0, message: 'UI TEST FIXTURE', data } })
  })
  await page.route(/\/actuator\/health/, route => route.fulfill({ json: { status: 'UP', components: {} } }))
  return requests
}

async function mainStyle(page: Page, css?: string) {
  return page.evaluate(value => {
    const el = [...document.querySelectorAll<HTMLStyleElement>('style[data-vite-dev-id]')]
      .find(s => s.dataset.viteDevId?.replaceAll('\\', '/').endsWith('/src/styles/index.css'))
    if (!el) throw new Error('Vite index.css style not found; baseline comparison requires dev server')
    // index.css imports theme.css then main.css; Vite inlines both into this one style tag.
    const full = el.textContent || ''
    const marker = full.indexOf('数据引擎大数据平台 控制台 - 全局样式')
    if (marker < 0) throw new Error('main.css source boundary not found')
    const start = full.lastIndexOf('/*', marker)
    const previous = full.slice(start)
    // A UTF-8 BOM is legal at file start, not in the middle of this combined stylesheet.
    if (value !== undefined) el.textContent = full.slice(0, start) + value.replace(/^\uFEFF/, '')
    return previous
  }, css)
}

async function settled(page: Page) {
  // Wait for finite CSS transitions (including hover) without changing production styles.
  await page.waitForFunction(() => document.getAnimations().every(a => a.effect?.getTiming().iterations === Infinity || a.playState === 'finished'))
}

async function sample(page: Page, target: Locator, state: string, file: string) {
  await target.scrollIntoViewIfNeeded()
  await settled(page)
  const result = await target.evaluate(el => {
    const cs = getComputedStyle(el)
    const r = el.getBoundingClientRect()
    let opacity = 1
    for (let p: Element | null = el; p; p = p.parentElement) opacity *= Number(getComputedStyle(p).opacity)
    const parent = getComputedStyle(el.parentElement!)
    return { state: '', text: el.textContent?.trim(), color: cs.color, opacity, background: cs.backgroundColor, backgroundImage: cs.backgroundImage, parentBackground: parent.backgroundColor, parentBackgroundImage: parent.backgroundImage, fontSize: cs.fontSize, rect: { x: r.x, y: r.y, width: r.width, height: r.height } }
  })
  result.state = state
  await page.screenshot({ path: file, animations: 'disabled' })
  // Capture the actual raster background, including gradients, pseudo-element glow and opacity.
  // Only this target's foreground is hidden temporarily, never a production source edit.
  const previous = await target.getAttribute('style')
  await target.evaluate(el => {
    const h = el as HTMLElement
    h.style.setProperty('color', 'transparent', 'important')
    h.style.setProperty('-webkit-text-fill-color', 'transparent', 'important')
    h.style.setProperty('transition', 'none', 'important')
  })
  let png: Buffer
  try { png = await page.screenshot({ animations: 'disabled' }) }
  finally { await target.evaluate((el, old) => old === null ? el.removeAttribute('style') : el.setAttribute('style', old), previous) }
  const contrast = await page.evaluate(async ({ image, row }) => {
    const img = new Image()
    img.src = `data:image/png;base64,${image}`
    await img.decode()
    const canvas = document.createElement('canvas')
    canvas.width = img.width; canvas.height = img.height
    const ctx = canvas.getContext('2d')!
    ctx.drawImage(img, 0, 0)
    const pixels = ctx.getImageData(0, 0, canvas.width, canvas.height).data
    const fg = row.color.match(/[\d.]+/g)!.map(Number)
    const alpha = (fg[3] ?? 1) * row.opacity
    const lum = (rgb: number[]) => rgb.slice(0, 3).reduce((sum, c, i) => {
      const s = c / 255
      return sum + (s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4) * [0.2126, 0.7152, 0.0722][i]
    }, 0)
    let min = Infinity
    let worstBackground: number[] = []
    const r = row.rect
    // Inset excludes borders/rounded corners; sample every raster pixel below the text box.
    for (let y = Math.max(0, Math.ceil(r.y + 2)); y < Math.min(canvas.height, r.y + r.height - 2); y++) {
      for (let x = Math.max(0, Math.ceil(r.x + 2)); x < Math.min(canvas.width, r.x + r.width - 2); x++) {
        const pos = (y * canvas.width + x) * 4
        const bg = [pixels[pos], pixels[pos + 1], pixels[pos + 2]]
        const ink = fg.slice(0, 3).map((c, i) => c * alpha + bg[i] * (1 - alpha))
        const a = lum(ink), b = lum(bg)
        const ratio = (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05)
        if (ratio < min) { min = ratio; worstBackground = bg }
      }
    }
    return { minContrast: min, worstBackground }
  }, { image: png!.toString('base64'), row: result })
  return { ...result, ...contrast }
}

for (const theme of ['light', 'dark'] as const) {
  test(`sidebar contrast and login isolation (${theme})`, async ({ page }, testInfo) => {
    test.setTimeout(120_000)
    const dir = process.env.SIDEBAR_EVIDENCE_DIR || testInfo.outputDir
    mkdirSync(dir, { recursive: true })
    const prefix = `${beforeOnly ? 'before' : 'after'}-${theme}`
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' })
    await page.addInitScript(mode => {
      // light 档 = 无存储偏好 → "system" 档；系统偏好由 emulateMedia 固定为亮色。
      // 2026-09-21 起 theme.ts 把 mode + 系统偏好解析后**始终**写入 data-theme
      // （system 档不再移除属性），因此 light 档现在带 data-theme='light'。
      if (mode === 'dark') localStorage.setItem('sq_theme', 'dark')
      else localStorage.removeItem('sq_theme')
    }, theme)
    const errors: string[] = []
    page.on('pageerror', e => errors.push(e.message))
    const requests = await installUiFixture(page)
    await loginByApi(page, { username: 'ui-fixture', password: 'ui-fixture' })
    await page.reload({ waitUntil: 'domcontentloaded' }) // hash navigation alone does not rehydrate Pinia
    await page.goto('/#/dashboard')
    const side = page.locator('aside.side')
    await expect(side).toBeVisible()
    await expect(page).toHaveURL(/#\/dashboard/)
    // 主题契约（2026-09-21 修复）：data-theme 恒为解析结果 'light' | 'dark'，
    // system 档也写入（旧契约是 light/system 档不带属性）。
    await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
    if (beforeOnly) {
      if (!baselineCss) throw new Error('SIDEBAR_BASELINE_CSS required for negative control')
      await mainStyle(page, baselineCss)
    }
    const rows = []
    const capture = async (name: string, locator: Locator, threshold = 4.5) => {
      const row = await sample(page, locator, name, join(dir, `${prefix}-${name}.png`))
      rows.push({ ...row, threshold })
    }
    await page.mouse.move(1000, 0)
    await capture('brand', side.locator('.brand-text'))
    const normal = side.locator('.nav-item:not(.active)').first()
    await normal.scrollIntoViewIfNeeded()
    await page.mouse.move(1000, 0)
    await capture('normal', normal.locator('.nav-label'))
    await capture('normal-icon', normal.locator('.nav-ic'), 3)
    await normal.hover()
    await capture('hover', normal.locator('.nav-label'))
    await capture('hover-icon', normal.locator('.nav-ic'), 3)
    const active = side.locator('.nav-item.active').first()
    await active.scrollIntoViewIfNeeded()
    await page.mouse.move(1000, 0)
    await capture('active', active.locator('.nav-label'))
    await capture('active-icon', active.locator('.nav-ic'), 3)
    const group = side.locator('.grp').first()
    await group.scrollIntoViewIfNeeded()
    await page.mouse.move(1000, 0)
    await capture('group', group.locator('.grp-label'))
    await capture('group-count', group.locator('.grp-count'))
    await group.hover()
    await capture('group-hover-count', group.locator('.grp-count'))
    await capture('group-hover-arrow', group.locator('.grp-arrow'), 3)
    await page.mouse.move(1000, 0)
    await capture('footer', side.locator('.side-foot-text'))
    await side.evaluate(el => el.scrollTop = 0)
    await side.screenshot({ path: join(dir, `${prefix}-sidebar.png`), animations: 'disabled' })

    // Remove only fixture auth state, then visit real Login.vue in the same browser.
    await page.evaluate(() => { sessionStorage.removeItem('sq_token'); localStorage.removeItem('sq_user') })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.goto('/#/login')
    await expect(page.locator('.login-page')).toBeVisible()
    await expect(page.locator('input[autocomplete="username"]')).toBeVisible()
    await expect(page.locator('input[autocomplete="current-password"]')).toBeVisible()
    const loginStyles = () => page.locator('.login-page .brand, .login-page .brand-text, .login-page h2, .login-page input, .login-page .login-btn').evaluateAll(els => els.map(el => {
      const c = getComputedStyle(el)
      return { tag: el.tagName, className: el.className, color: c.color, background: c.background, borderColor: c.borderColor, height: c.height, width: c.width, padding: c.padding, fontSize: c.fontSize }
    }))
    await settled(page)
    const loginAfter = await loginStyles()
    await page.screenshot({ path: join(dir, `${prefix}-login.png`), animations: 'disabled' })
    let loginBefore = null
    if (baselineCss) {
      const current = await mainStyle(page, baselineCss)
      await settled(page)
      loginBefore = await loginStyles()
      await page.screenshot({ path: join(dir, `before-${theme}-login.png`), animations: 'disabled' })
      await mainStyle(page, current)
    }
    const report = { theme, defaultLight: theme === 'light', fixture: 'UI-only: project loginByApi helper + mocked local APIs; not real login/backend acceptance', url: testInfo.project.use.baseURL, rows, loginAfter, loginBefore, pageErrors: errors, mockedApiPaths: [...new Set(requests)] }
    const json = join(dir, `${prefix}-validation.json`)
    writeFileSync(json, JSON.stringify(report, null, 2))
    await testInfo.attach(`${prefix}-validation`, { path: json, contentType: 'application/json' })
    if (!beforeOnly) {
      for (const row of rows) expect.soft(row.minContrast, `${theme} ${row.state}: ${row.color}`).toBeGreaterThanOrEqual(row.threshold)
      if (loginBefore) expect(loginAfter).toEqual(loginBefore)
      expect(errors).toEqual([])
    } else {
      // Negative control: confirm the pre-fix stylesheet exposes the regression.
      expect(rows.some(r => r.minContrast < r.threshold)).toBe(true)
    }
  })
}
