/**
 * E2E 测试共享辅助工具
 *
 * 提供：
 * - login(page)        通过 UI 登录（admin/admin），返回主页面
 * - loginByApi(page)   通过 API 直接登录并写入存储，跳过 UI
 * - ensureLoggedIn(page) 智能选择登录方式
 * - apiBase            后端 API 基址
 *
 * Sprint 3.2.4 修复：登录按钮文案由 i18n locale 决定（"Sign In" / "登 录"），
 * 先前硬编码 /登.*录/ 选择器在 locale 回退英文时失败。改用 aria-label
 * （t('login.submit') 本地化值）匹配，规避文案语言差异。
 */
import type { Page, APIRequestContext } from '@playwright/test'

/** 后端 API 基址（与 vite proxy 一致，经 dev server 转发） */
export const apiBase = '/api/v1'

/** 默认管理员账号 */
export const ADMIN = { username: 'admin', password: 'admin' }

/** 存储键（与 stores/auth.ts 保持一致：token → sessionStorage，user → localStorage） */
const TOKEN_KEY = 'sq_token'
const USER_KEY = 'sq_user'

/** 登录提交按钮：优先 aria-label，回退按钮文本（中英文均匹配） */
async function clickLoginButton(page: Page): Promise<void> {
  const submitBtn = page.locator('button[aria-label*="登录"], button[aria-label*="Sign"]').first()
  if (await submitBtn.isVisible().catch(() => false)) {
    await submitBtn.click()
    return
  }
  // 回退：按文本匹配（中文"登 录"/"登录" 或英文"Sign In"）
  await page.getByRole('button', { name: /登.*录|Sign In/i }).click()
}

/**
 * 通过 UI 登录（admin/admin）
 * 前置：page 已打开任意页面（路由守卫会跳到 /login）
 */
/**
 * 通过 UI 登录（admin/admin），返回主页面
 *
 * 登录成功判定：URL 跳转出 /login（dashboard 可能因首屏 API 404 被
 * 守卫再踢回——本地单后端环境常见；token 已写入 sessionStorage 即算成功，
 * 由调用方自行决定是否需要重登）。
 */
export async function login(page: Page, creds: { username: string; password: string } = ADMIN): Promise<void> {
  // 确保在登录页
  if (!page.url().includes('/login')) {
    await page.goto('/#/login')
  }
  await page.waitForSelector('input[autocomplete="username"]', { timeout: 15_000 })

  // 清空并填写用户名
  await page.fill('input[autocomplete="username"]', '')
  await page.fill('input[autocomplete="username"]', creds.username)

  // 清空并填写密码
  await page.fill('input[autocomplete="current-password"]', '')
  await page.fill('input[autocomplete="current-password"]', creds.password)

  // 点击登录按钮（文案由 i18n locale 决定，用 aria-label / 通用匹配）
  await clickLoginButton(page)

  // 等待离开登录页（token 已注入；即使被守卫因后端 404 再踢，登录本身已完成）
  await page.waitForURL(
    (url) => !url.href.includes('/login'),
    { timeout: 20_000 }
  )
}

/**
 * 通过 API 直接登录并写入存储（token 入 sessionStorage，user 入 localStorage；跳过 UI，更快）
 * 用于不需要验证登录页 UI 的测试
 */
export async function loginByApi(page: Page, creds: { username: string; password: string } = ADMIN): Promise<void> {
  // 先打开页面以获得 storage 上下文
  await page.goto('/#/login', { waitUntil: 'domcontentloaded' })

  // 通过浏览器内 fetch 调用登录接口（经 vite proxy 转发到后端）
  const result = await page.evaluate(async ({ url, body }) => {
    const resp = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    })
    return { status: resp.status, json: await resp.json() }
  }, { url: `${apiBase}/auth/login`, body: creds })

  if (result.status !== 200 || result.json.code !== 0) {
    throw new Error(`API 登录失败: status=${result.status}, body=${JSON.stringify(result.json)}`)
  }

  const { token, user } = result.json.data
  await page.evaluate(({ t, u, tk, uk }) => {
    sessionStorage.setItem(tk, t)
    localStorage.setItem(uk, JSON.stringify(u))
  }, { t: token, u: user, tk: TOKEN_KEY, uk: USER_KEY })
}

/**
 * 确保已登录（如果未登录则用 UI 方式登录）
 * 用 UI 登录更稳定（已验证可用；token 注入后守卫的异步验证存在时序竞争，
 * API 注入在部分环境会被误踢——auth.spec 专项覆盖登录 UI）
 */
export async function ensureLoggedIn(page: Page): Promise<void> {
  // 先检查是否已登录（无 token 会被路由守卫重定向到 /login）
  await page.goto('/', { waitUntil: 'domcontentloaded' })
  await page.waitForTimeout(500)
  const url = page.url()
  // 如果被重定向到登录页，则执行 UI 登录
  if (url.includes('/login')) {
    await login(page)
  } else {
    // 已登录，等待 dashboard 渲染
    await page.waitForSelector('aside.side, h1', { timeout: 15_000 }).catch(() => {})
  }
}

/**
 * 直接调用后端 API（不经浏览器，用于验证响应格式）
 */
export async function callApi(
  request: APIRequestContext,
  path: string,
  options: { method?: 'GET' | 'POST' | 'PUT' | 'DELETE'; token?: string; body?: unknown } = {}
): Promise<{ status: number; body: unknown; headers: Record<string, string> }> {
  const method = options.method ?? 'GET'
  const headers: Record<string, string> = { 'Content-Type': 'application/json' }
  if (options.token) headers.Authorization = `Bearer ${options.token}`
  const resp = await request.fetch(`${apiBase}${path}`, {
    method,
    headers,
    data: options.body
  })
  let body: unknown = null
  try {
    body = await resp.json()
  } catch {
    body = await resp.text()
  }
  return { status: resp.status(), body, headers: resp.headers() }
}

/**
 * 通过 API 登录并返回 token（用于 API 格式验证测试）
 */
export async function getApiToken(request: APIRequestContext, creds: { username: string; password: string } = ADMIN): Promise<string> {
  const resp = await request.post(`${apiBase}/auth/login`, { data: creds })
  const json = await resp.json()
  if (json.code !== 0 || !json.data?.token) {
    throw new Error(`获取 token 失败: ${JSON.stringify(json)}`)
  }
  return json.data.token
}