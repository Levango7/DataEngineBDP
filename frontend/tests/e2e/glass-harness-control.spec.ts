/**
 * 玻璃换肤的**正面控制**（positive control）
 *
 * 目的：证明 v2 的结论不是"假绿"。三条控制，缺一不可：
 *
 *  C1  harness 能观测 backdrop-filter
 *      —— 若 headless / 软件光栅化丢弃了模糊，所有基于像素的玻璃结论都无效。
 *  C2  采样 harness 能报出**必然不达标**的样本
 *      —— 若这个样本也被判为达标，说明采样链路坏了（永远绿）。
 *  C3  玻璃浮层的计算样式确实是「半透明 + 模糊」，而不是被降级成实色
 *      —— 直接对 EP 的 (0,2,0) 复合选择器求值，锁死 2026-09-16 返工的那个 bug：
 *         `--el-bg-color-overlay: transparent` 曾让 .el-popover.el-popper 等
 *         复合选择器的 background 解析成 transparent（"幽灵面板"）。
 *
 * 与本目录其他 spec 一样：UI-only，本地 mock /api/，不涉及真实后端与认证。
 */
import { test, expect } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { loginByApi, apiBase } from './helpers'
import { sample, alphaOf } from './helpers/contrast'

test.use({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 })

const CONTROL_ID = 'contrast-positive-control'

async function installUiFixture(page: import('@playwright/test').Page) {
  const token = `${Buffer.from('{"alg":"none","typ":"JWT"}').toString('base64url')}.${Buffer.from(
    JSON.stringify({ sub: 'glass-control', exp: Math.floor(Date.now() / 1000) + 3600 })
  ).toString('base64url')}.ui-fixture`
  const user = {
    id: 'glass-control',
    username: 'glass-control',
    nickname: 'Glass 控制组',
    email: 'control@example.invalid',
    tenantId: 'glass-control',
    roles: ['admin'],
    status: 'active',
  }
  await page.route(
    (url) => url.pathname.startsWith('/api/'),
    async (route) => {
      const path = new URL(route.request().url()).pathname
      let data: unknown = []
      if (path === `${apiBase}/auth/login`) data = { token, user, expiresIn: 3600 }
      if (path === `${apiBase}/auth/me`) data = user
      if (path.endsWith('/cluster/overview'))
        data = {
          clusterName: 'UI fixture',
          version: 'test',
          projectCount: 0,
          projectRunning: 0,
          jobCount: 0,
          jobSuccessToday: 0,
          jobFailToday: 0,
          assetCount: 0,
          cpuUsed: 0,
          cpuCapacity: 1,
          memUsed: 0,
          memCapacity: 1,
          storageUsed: 0,
          nodeTotal: 0,
          nodeReady: 0,
          podTotal: 0,
          podRunning: 0,
        }
      await route.fulfill({ json: { code: 0, message: 'UI TEST FIXTURE', data } })
    }
  )
  await page.route(/\/actuator\/health/, (route) =>
    route.fulfill({ json: { status: 'UP', components: {} } })
  )
}

// ---------------------------------------------------------------- C1
test('C1 harness 能观测 backdrop-filter（否则所有玻璃结论无效）', async ({ page }, testInfo) => {
  test.setTimeout(60_000)
  const dir = process.env.CONTROL_EVIDENCE_DIR || testInfo.outputDir
  mkdirSync(dir, { recursive: true })

  await page.setContent(`
    <style>html,body{margin:0;height:100%}</style>
    <div id="bg" style="position:fixed;inset:0;
      background:repeating-linear-gradient(90deg,#000 0 4px,#fff 4px 8px)"></div>
    <div id="fg" style="position:fixed;inset:0;
      background:rgba(255,255,255,0.5);backdrop-filter:blur(20px)"></div>
  `)
  await page.waitForTimeout(300)

  const png = await page.locator('#fg').screenshot({ animations: 'disabled' })
  writeFileSync(join(dir, 'c1-backdrop-filter-stripes.png'), png)

  // 统计"中间灰"像素占比：若 backdrop-filter 真的被栅格化，黑白条纹会被模糊成大量灰阶。
  // 若模糊被丢弃，条纹仍是纯黑/纯白，中间灰占比接近 0。
  const stats = await page.evaluate(async (image) => {
    const img = new Image()
    img.src = `data:image/png;base64,${image}`
    await img.decode()
    const canvas = document.createElement('canvas')
    canvas.width = img.width
    canvas.height = img.height
    const ctx = canvas.getContext('2d')!
    ctx.drawImage(img, 0, 0)
    const d = ctx.getImageData(0, 0, canvas.width, canvas.height).data
    let mid = 0
    let total = 0
    for (let i = 0; i < d.length; i += 4) {
      const v = d[i]
      total++
      if (v > 40 && v < 215) mid++ // 既非近黑也非近白
    }
    return { midGreyRatio: mid / total, total }
  }, png.toString('base64'))

  writeFileSync(
    join(dir, 'c1-backdrop-filter-probe.json'),
    JSON.stringify(stats, null, 2)
  )

  expect(
    stats.midGreyRatio,
    `harness 观测不到 backdrop-filter（中间灰占比仅 ${(stats.midGreyRatio * 100).toFixed(1)}%）——
     后续所有基于像素的玻璃结论都不可信，请先修 harness 或换渲染后端`
  ).toBeGreaterThan(0.3)
})

// ---------------------------------------------------------------- C2
test('C2 采样 harness 能报出必然不达标的样本（防"永远绿"）', async ({ page }, testInfo) => {
  test.setTimeout(120_000)
  const dir = process.env.CONTROL_EVIDENCE_DIR || testInfo.outputDir
  mkdirSync(dir, { recursive: true })

  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' })
  await installUiFixture(page)
  await loginByApi(page, { username: 'glass-control', password: 'glass-control' })
  await page.reload({ waitUntil: 'domcontentloaded' })
  await page.goto('/#/dashboard')
  await expect(page.locator('aside.side')).toBeVisible()

  // 刻意构造一个必然不达标的样本：#767676 文字压在同色 #767676 底上 → 对比度恒为 1:1。
  // 这是"已知答案"的样本：harness 必须报出 < 4.5。若它报达标，说明采样链路是坏的。
  await page.evaluate((id) => {
    const d = document.createElement('div')
    d.id = id
    d.textContent = 'CONTROL-MUST-FAIL'
    d.setAttribute(
      'style',
      'position:fixed;left:520px;top:380px;z-index:99999;' +
        'width:220px;height:40px;line-height:40px;' +
        'background:#767676;color:#767676;font-size:14px;font-family:sans-serif'
    )
    document.body.appendChild(d)
  }, CONTROL_ID)

  const row = await sample(
    page,
    page.locator(`#${CONTROL_ID}`),
    'control-must-fail',
    join(dir, 'c2-control-must-fail.png')
  )
  writeFileSync(join(dir, 'c2-control-probe.json'), JSON.stringify(row, null, 2))

  expect(row.color, '控制组前景色应为 #767676').toBe('rgb(118, 118, 118)')
  expect(
    row.minContrast,
    `harness 是假绿：一个 1:1 的必然不达标样本被判为 ${row.minContrast.toFixed(2)}:1`
  ).toBeLessThan(1.5)
})

// ---------------------------------------------------------------- C3
const GLASS_MATRIX = [
  // [类名字符串, 期望的 glass 层]
  ['el-popover el-popper', 'popover'],
  ['el-select__popper el-popper', 'popover'],
  ['el-dropdown__popper el-popper', 'popover'],
  ['el-cascader__dropdown el-popper', 'popover'],
  ['el-picker__popper el-popper', 'popover'],
  ['el-tooltip__popper el-popper', 'popover'],
  ['el-notification', 'popover'],
  ['el-dialog', 'overlay'],
  ['el-drawer', 'overlay'],
  ['el-message-box', 'overlay'],
]

for (const theme of ['light', 'dark'] as const) {
  test(`C3 玻璃浮层真的是半透明 + 模糊（${theme}）`, async ({ page }, testInfo) => {
    test.setTimeout(120_000)
    const dir = process.env.CONTROL_EVIDENCE_DIR || testInfo.outputDir
    mkdirSync(dir, { recursive: true })

    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'reduce' })
    await page.addInitScript((mode) => {
      if (mode === 'dark') localStorage.setItem('sq_theme', 'dark')
      else localStorage.removeItem('sq_theme')
    }, theme)
    await installUiFixture(page)
    await loginByApi(page, { username: 'glass-control', password: 'glass-control' })
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.goto('/#/dashboard')
    await expect(page.locator('aside.side')).toBeVisible()

    // ① 真实外壳：.side / .topbar
    for (const sel of ['aside.side', '.topbar']) {
      const m = await page.locator(sel).first().evaluate((el) => {
        const cs = getComputedStyle(el)
        return {
          background: cs.backgroundColor,
          backdropFilter: cs.backdropFilter || (cs as CSSStyleDeclaration & { webkitBackdropFilter?: string }).webkitBackdropFilter || 'none',
        }
      })
      expect.soft(m.backdropFilter, `${sel} backdrop-filter（${theme}）`).not.toBe('none')
      const a = alphaOf(m.background)
      expect.soft(a, `${sel} 背景应为半透明（${theme}）`).toBeGreaterThan(0)
      expect.soft(a, `${sel} 背景应为半透明（${theme}）`).toBeLessThan(1)
    }

    // ② EP 浮层：直接对 EP 的复合选择器求值，锁死 "--el-*-bg-color 被清零"这类回归
    const rows = await page.evaluate((matrix) => {
      const host = document.createElement('div')
      host.style.cssText = 'position:fixed;left:-9999px;top:0'
      document.body.appendChild(host)
      const out: Array<{
        cls: string
        layer: string
        background: string
        backdropFilter: string
        resolvedVar: string
      }> = []
      for (const [cls, layer] of matrix) {
        const d = document.createElement('div')
        d.className = cls
        host.appendChild(d)
        const cs = getComputedStyle(d)
        out.push({
          cls,
          layer,
          background: cs.backgroundColor,
          backdropFilter:
            cs.backdropFilter ||
            (cs as CSSStyleDeclaration & { webkitBackdropFilter?: string }).webkitBackdropFilter ||
            'none',
          resolvedVar: cs.getPropertyValue('--el-bg-color-overlay').trim(),
        })
        d.remove()
      }
      host.remove()
      return out
    }, GLASS_MATRIX)

    writeFileSync(
      join(dir, `c3-glass-probe-${theme}.json`),
      JSON.stringify({ theme, rows }, null, 2)
    )

    for (const r of rows) {
      const a = alphaOf(r.background)
      expect.soft(
        a,
        `${r.cls}：背景不应完全透明（若 alpha=0，说明 EP 的组件级 bg 变量被清零了）`
      ).toBeGreaterThan(0)
      expect.soft(a, `${r.cls}：背景应保持半透明（玻璃材质）`).toBeLessThan(1)
      expect.soft(r.backdropFilter, `${r.cls}：backdrop-filter 不应为 none`).not.toBe('none')
    }
  })
}
