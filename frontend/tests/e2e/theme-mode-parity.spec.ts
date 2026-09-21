/**
 * 主题模式一致性回归守卫：`system`（跟随系统）≡ 显式 `light` / `dark`
 *
 * 背景（2026-09-21 修复）：
 *   theme.ts 旧实现在 mode === 'system' 时 **移除** data-theme 属性，只有显式
 *   light/dark 才写。而 main.css 有 106 处 `:root[data-theme='dark'] .el-*` 逐组件
 *   覆写（修正 EP 组件在暗色下的底色/文字/边框），媒体查询兜底块只覆盖变量层 ——
 *   于是 system + 系统暗色 的用户「变量是暗的、组件级覆写全部落空」，与显式 dark 观感不一致。
 *   修复后 applyTheme 一律写入「mode + 系统偏好」的解析结果（system 档也写）。
 *
 * 本 spec 断言两者在**计算样式层面逐项一致**，覆盖三层：
 *   ① :root 自定义属性（--ds-* / --el-* / main.css 老变量）—— 变量层；
 *   ② 页面真实元素的 computed style —— 实际渲染；
 *   ③ 注入的 EP 组件夹具 computed style —— 106 条组件级覆写所作用的层。
 *
 * 显式档额外在**相反的系统偏好**下取一次样本（explicit-dark@light / explicit-light@dark）：
 *   此时 @media 兜底块不命中，样本只由 `:root[data-theme='dark']` 决定。
 *   若两份暗色副本漂移（兜底块靠后、同特异性下胜出），system 档就会与它取到不同取值 ——
 *   这一条正是"防漂移"的端到端证据（与 scripts/check-dark-token-parity.py 互补）。
 *
 * UI-only：本地 mock /api/，不涉及真实后端与认证。
 */
import { test, expect, type Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { loginByApi, apiBase } from './helpers'

test.use({ viewport: { width: 1600, height: 1000 }, deviceScaleFactor: 1 })

/** 需要比对的自定义属性（含 2026-09-21 修复中曾漂移/缺失的那几条） */
const ROOT_VARS = [
  // 修复中曾取值不同的 4 条（system 档 vs 显式 dark）
  '--c-surface',
  '--c-surface-alt',
  '--el-table-text-color',
  '--el-table-border-color',
  // 修复中兜底块曾漏掉的（system 档因此回退到亮色取值）
  '--c-track',
  '--el-border-radius-round',
  '--el-input-text-color',
  '--el-table-empty-text-color',
  // 变量层主干
  '--bg',
  '--panel',
  '--ink',
  '--muted',
  '--line',
  '--c-surface-hover',
  '--c-white',
  '--el-text-color-primary',
  '--el-text-color-regular',
  '--el-text-color-3',
  '--el-text-color-8',
  '--el-bg-color',
  '--el-bg-color-page',
  '--el-bg-color-overlay',
  '--el-fill-color',
  '--el-border-color',
  '--el-color-primary',
  '--el-mask-color',
  '--typo-color-h',
  '--ds-bg-base',
  '--ds-canvas',
  '--ds-surface-1',
  '--ds-text-primary',
  '--ds-text-secondary',
  '--ds-border-default',
  '--ds-accent',
  '--ds-glass-chrome-bg',
  '--ds-glass-overlay-bg',
  '--ds-color-gray-800',
  '--ds-color-gray-100',
]

/** 页面真实元素（存在性在同一 spec 内保持一致，缺一即失败） */
const ELEMENT_SELECTORS = [
  'html',
  'body',
  'aside.side',
  '.topbar',
  'aside.side .brand-text',
  'aside.side .nav-item .nav-label',
  'footer.statusbar',
]

/** 注入的 EP 组件夹具：106 条组件级覆写作用的正是这些选择器 */
const EP_FIXTURE = [
  'el-card',
  'el-table',
  'el-input__inner',
  'el-textarea__inner',
  'el-button',
  'el-tag',
  'el-dialog',
  'el-drawer',
  'el-message-box',
  'el-select__popper',
  'el-dropdown__popper',
  'el-popover',
  'el-tooltip__popper',
  'el-pagination',
  'el-notification',
]

/** 采样的 computed style 属性（**kebab-case**：getPropertyValue 不认驼峰名，会返回空串） */
const SAMPLE_PROPS = [
  'color',
  'background-color',
  'background-image',
  'border-top-color',
  'border-top-width',
  'border-radius',
  'box-shadow',
  'backdrop-filter',
  'font-size',
  'font-weight',
] as const

type Condition = { id: string; mode: 'system' | 'light' | 'dark'; os: 'light' | 'dark' }

/** 六个条件：三组"应当完全一致"的等价类 + 两组合法差异（light vs dark） */
const CONDITIONS: Condition[] = [
  { id: 'system@dark', mode: 'system', os: 'dark' },
  { id: 'explicit-dark@dark', mode: 'dark', os: 'dark' },
  { id: 'explicit-dark@light', mode: 'dark', os: 'light' },
  { id: 'system@light', mode: 'system', os: 'light' },
  { id: 'explicit-light@light', mode: 'light', os: 'light' },
  { id: 'explicit-light@dark', mode: 'light', os: 'dark' },
]

async function installUiFixture(page: Page) {
  const token = `${Buffer.from('{"alg":"none","typ":"JWT"}').toString('base64url')}.${Buffer.from(
    JSON.stringify({ sub: 'theme-parity', exp: Math.floor(Date.now() / 1000) + 3600 })
  ).toString('base64url')}.ui-fixture`
  const user = {
    id: 'theme-parity',
    username: 'theme-parity',
    nickname: '主题一致性夹具',
    email: 'parity@example.invalid',
    tenantId: 'theme-parity',
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
          trendCpu: [],
          trendMem: [],
        }
      await route.fulfill({ json: { code: 0, message: 'UI TEST FIXTURE', data } })
    }
  )
  await page.route(/\/actuator\/health/, (route) => route.fulfill({ json: { status: 'UP', components: {} } }))
}

/** 把 sq_theme 写成指定档位（system 档显式写入 'system'，与真实默认档一致） */
async function setStoredMode(page: Page, mode: Condition['mode']) {
  await page.evaluate((m) => localStorage.setItem('sq_theme', m), mode)
}

/**
 * 采集一个条件下的完整样式快照。
 * 注意：所有取值都走 getComputedStyle，不受 CSS 源码写法影响 —— 断言的是**用户看到的结果**。
 */
async function capture(page: Page) {
  return page.evaluate(
    ({ vars, selectors, fixture, props }) => {
      const rootStyle = getComputedStyle(document.documentElement)
      const varValues: Record<string, string> = {}
      for (const v of vars) varValues[v] = rootStyle.getPropertyValue(v).trim()

      const pick = (el: Element): Record<string, string> => {
        const cs = getComputedStyle(el)
        const out: Record<string, string> = {}
        for (const p of props) out[p] = String(cs.getPropertyValue(p)).trim()
        return out
      }

      const elements: Record<string, Record<string, string> | null> = {}
      for (const sel of selectors) {
        const el = document.querySelector(sel)
        elements[sel] = el ? pick(el) : null
      }

      // EP 组件夹具：固定结构 + 固定位置，仅用于让组件级覆写规则命中
      const HOST_ID = 'theme-parity-ep-fixture'
      document.getElementById(HOST_ID)?.remove()
      const host = document.createElement('div')
      host.id = HOST_ID
      host.style.cssText = 'position:fixed;left:-9999px;top:0;width:320px'
      host.innerHTML = fixture.map((c) => `<div class="${c}">fixture</div>`).join('')
      document.body.appendChild(host)

      const epFixture: Record<string, Record<string, string>> = {}
      for (const c of fixture) {
        const el = host.querySelector(`.${c}`)
        if (el) epFixture[c] = pick(el)
      }
      host.remove()

      return {
        dataTheme: document.documentElement.getAttribute('data-theme'),
        prefersDark: window.matchMedia('(prefers-color-scheme: dark)').matches,
        varValues,
        elements,
        epFixture,
      }
    },
    { vars: ROOT_VARS, selectors: ELEMENT_SELECTORS, fixture: EP_FIXTURE, props: SAMPLE_PROPS as unknown as string[] }
  )
}

/** 等有限动画/过渡跑完，避免读到过渡中间值（与同目录其它 spec 的 settled 一致） */
async function settled(page: Page) {
  await page.waitForFunction(
    () =>
      document.getAnimations().every((a) => a.effect?.getTiming().iterations === Infinity || a.playState === 'finished'),
    undefined,
    { timeout: 15_000 }
  )
}

/** 在一个条件下装载页面并采集快照 */
async function snapshotUnder(page: Page, cond: Condition, dir: string) {
  await page.emulateMedia({ colorScheme: cond.os, reducedMotion: 'reduce' })
  await setStoredMode(page, cond.mode)
  await page.reload({ waitUntil: 'domcontentloaded' })
  // 记录"DOMContentLoaded 时 data-theme 是否已就位"（防闪变的诊断证据，不做硬断言）
  const attrAtDcl = await page.evaluate(() => document.documentElement.getAttribute('data-theme'))
  await page.goto('/#/dashboard')
  await expect(page.locator('aside.side')).toBeVisible({ timeout: 30_000 })
  await settled(page)
  await page.mouse.move(1000, 0)
  const snap = await capture(page)
  await page.screenshot({ path: join(dir, `fix3-${cond.id}.png`), fullPage: true, animations: 'disabled' })
  return { cond, attrAtDcl, ...snap }
}

for (const scheme of ['dark', 'light'] as const) {
  test(`system（跟随系统·${scheme}）与显式 ${scheme} 计算样式逐项一致`, async ({ page }, testInfo) => {
    test.setTimeout(180_000)
    const dir = process.env.PARITY_EVIDENCE_DIR || testInfo.outputDir
    mkdirSync(dir, { recursive: true })

    await installUiFixture(page)
    await loginByApi(page, { username: 'theme-parity', password: 'theme-parity' })

    const expected = scheme
    const equivalent = CONDITIONS.filter((c) =>
      scheme === 'dark'
        ? ['system@dark', 'explicit-dark@dark', 'explicit-dark@light'].includes(c.id)
        : ['system@light', 'explicit-light@light', 'explicit-light@dark'].includes(c.id)
    )

    const snaps: Array<Record<string, unknown>> = []
    for (const cond of equivalent) {
      const snap = await snapshotUnder(page, cond, dir)
      // 软断言：让一次运行把"属性不对"与"样式不一致"两类偏差都报出来（便于定位）
      expect.soft(snap.dataTheme, `${cond.id}: data-theme 应为解析结果 ${expected}`).toBe(expected)
      expect(snap.prefersDark, `${cond.id}: 浏览器系统偏好应与 colorScheme=${cond.os} 一致`).toBe(cond.os === 'dark')
      snaps.push(snap as unknown as Record<string, unknown>)
    }

    const [baseline, ...rest] = snaps
    const report = {
      scheme,
      equivalentConditionIds: equivalent.map((c) => c.id),
      baseline: baseline.cond,
      comparisons: rest.map((s) => ({
        condition: (s.cond as Condition).id,
        dataTheme: s.dataTheme,
        attrAtDomContentLoaded: s.attrAtDcl,
      })),
      snapshots: snaps,
    }
    writeFileSync(join(dir, `fix3-parity-${scheme}.json`), JSON.stringify(report, null, 2))

    // ① 变量层
    for (const s of rest) {
      expect
        .soft(
          s.varValues,
          `${(s.cond as Condition).id} 的 :root 自定义属性应与 ${(baseline.cond as Condition).id} 一致`
        )
        .toEqual(baseline.varValues)
    }
    // ② 真实元素
    for (const s of rest) {
      expect
        .soft(
          s.elements,
          `${(s.cond as Condition).id} 的真实元素 computed style 应与 ${(baseline.cond as Condition).id} 一致`
        )
        .toEqual(baseline.elements)
    }
    // ③ EP 组件夹具（106 条组件级覆写所作用的层）
    for (const s of rest) {
      expect
        .soft(
          s.epFixture,
          `${(s.cond as Condition).id} 的 EP 组件 computed style 应与 ${(baseline.cond as Condition).id} 一致`
        )
        .toEqual(baseline.epFixture)
    }

    // 夹具本身必须真的命中（否则"一致"是空集合上的假绿）
    const fixtureKeys = Object.keys(baseline.epFixture as Record<string, unknown>)
    expect(fixtureKeys.length, 'EP 夹具应有元素被解析出 computed style').toBeGreaterThanOrEqual(EP_FIXTURE.length)
    const resolvedElements = Object.entries(baseline.elements as Record<string, unknown>).filter(([, v]) => v !== null)
    expect(resolvedElements.length, '真实元素样本应全部解析成功（缺一即说明选择器漂移）').toBe(
      ELEMENT_SELECTORS.length
    )
  })
}

test('system 档下运行中切换系统主题会实时重绘；显式档不受系统变化影响', async ({ page }, testInfo) => {
  test.setTimeout(120_000)
  const dir = process.env.PARITY_EVIDENCE_DIR || testInfo.outputDir
  mkdirSync(dir, { recursive: true })

  await installUiFixture(page)
  await loginByApi(page, { username: 'theme-parity', password: 'theme-parity' })

  const html = page.locator('html')
  const bg = () => page.evaluate(() => getComputedStyle(document.body).backgroundColor)

  // system 档：系统暗 → 亮，data-theme 必须跟着翻
  await page.emulateMedia({ colorScheme: 'dark' })
  await setStoredMode(page, 'system')
  await page.reload({ waitUntil: 'domcontentloaded' })
  await expect(page.locator('aside.side')).toBeVisible({ timeout: 30_000 })
  await expect(html).toHaveAttribute('data-theme', 'dark')
  const bgDark = await bg()

  await page.emulateMedia({ colorScheme: 'light' })
  await expect(html, 'system 档下系统切到亮色后 data-theme 必须重绘为 light').toHaveAttribute('data-theme', 'light')
  await settled(page)
  const bgLight = await bg()
  expect(bgLight, 'body 背景应随 data-theme 重绘').not.toBe(bgDark)

  await page.emulateMedia({ colorScheme: 'dark' })
  await expect(html, 'system 档下系统切回暗色后 data-theme 必须重绘为 dark').toHaveAttribute('data-theme', 'dark')
  await settled(page)
  expect(await bg()).toBe(bgDark)

  // 显式 dark：系统偏好怎么变都不应影响 data-theme（用户选择优先）
  await page.emulateMedia({ colorScheme: 'light' })
  await setStoredMode(page, 'dark')
  await page.reload({ waitUntil: 'domcontentloaded' })
  await expect(page.locator('aside.side')).toBeVisible({ timeout: 30_000 })
  await expect(html).toHaveAttribute('data-theme', 'dark')
  await page.emulateMedia({ colorScheme: 'dark' })
  await page.waitForTimeout(200)
  await expect(html, '显式 dark 档不应被系统偏好改写').toHaveAttribute('data-theme', 'dark')
  await page.emulateMedia({ colorScheme: 'light' })
  await page.waitForTimeout(200)
  await expect(html, '显式 dark 档不应被系统偏好改写').toHaveAttribute('data-theme', 'dark')
  expect(await bg()).toBe(bgDark)

  writeFileSync(
    join(dir, 'fix3-parity-runtime-switch.json'),
    JSON.stringify({ bgDark, bgLight, note: 'system 档随系统切换重绘；显式档锁定' }, null, 2)
  )
})
