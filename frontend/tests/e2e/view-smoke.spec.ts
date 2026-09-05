/**
 * 全视图渲染冒烟 E2E（B2）：47 视图遍历，每页断言：
 * 1. h1 渲染了词条（非空、非原始 key）
 * 2. 页面无致命 JS 错误（console error 不含 Vue 渲染错误）
 *
 * 设计取舍：冒烟级而非深交互——每页深表单交互的维护成本远超收益，
 * 渲染回归已能拦住"组件异常白屏/词条缺失/路由挂死"三类最常见回归。
 * 深交互场景由既有 26 个专项 spec 覆盖。
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn } from './helpers'

/** 全部路由（对齐 src/router/index.ts 实际 48 条视图路由） */
const ALL_ROUTES = [
  '/dashboard',
  '/workspaces',
  '/projects',
  '/integrate',
  '/develop',
  '/sql',
  '/govern',
  '/standard',
  '/quality',
  '/lineage',
  '/data-lineage',
  '/sec',
  '/vector',
  '/kb',
  '/llmops',
  '/gateway',
  '/analyze',
  '/ops',
  '/account',
  '/admin',
  '/tenants',
  '/cluster',
  '/datasources',
  '/jobs',
  '/scheduler-ops',
  '/workspace-management',
  '/quota-management',
  '/sql-workbench',
  '/search',
  '/orchestrator/dag',
  '/ai-assistant',
  '/infra-machine',
  '/infra-k8s',
  '/infra-net',
  '/infra-store',
  '/infra-sched',
  '/eng-storage',
  '/eng-spark',
  '/eng-flink',
  '/eng-doris',
  '/eng-kafka',
  '/eng-iotdb',
  '/eng-mmg',
  '/govern-meta',
  '/dev-sched',
  '/dev-tag',
  '/dev-ml',
  '/ops-tpl',
  '/ops-portal',
  '/ops-api',
  '/ops-flow'
]

/** Vue 渲染崩溃的 console error 特征（词条缺失不算致命） */
const FATAL_ERROR = /(TypeError|ReferenceError|Cannot read|is not a function|Unexpected)/

test.describe('全视图渲染冒烟', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
    // 收集 console error
    const errors: string[] = []
    page.on('console', (msg) => {
      if (msg.type() === 'error') errors.push(msg.text())
    })
    page.on('pageerror', (err) => errors.push(String(err)))
    // attach 到 testInfo 供断言读取（每 test 独立）
    test.info().attach('console-errors-source', { body: 'see errors array' })
    // @ts-expect-error 动态挂载供断言
    page.__consoleErrors = errors
  })

  for (const route of ALL_ROUTES) {
    test(`渲染冒烟: ${route}`, async ({ page }) => {
      // 本地单后端环境（缺特定服务 → 首屏 API 404）守卫会踢回登录页。
      // token 是刚注入的，被踢说明该页依赖的后端服务未起——非产品缺陷，
      // skip 注明；CI 全栈（compose 18080-18096）时首跳即通过。
      await page.goto(`/#${route}`, { waitUntil: 'domcontentloaded' })
      if (page.url().includes('/login')) {
        test.skip(true, `本地缺 ${route} 依赖的后端服务（守卫踢回登录页），CI 全栈时执行`)
      }

      // h1 在合理时间内渲染
      const h1 = page.locator('h1').first()
      await expect(h1).toBeVisible({ timeout: 15_000 })

      // h1 渲染的是词条而非 key 占位（含 '.' 通常是 'module.key' 泄漏）
      const text = (await h1.textContent())?.trim() ?? ''
      expect(text.length, `${route} h1 不应为空`).toBeGreaterThan(0)
      expect(
        /^[a-z-]+\.[a-z]/i.test(text),
        `${route} h1 疑似原始 key: "${text}"`
      ).toBe(false)

      // 无致命 JS 错误
      // @ts-expect-error 读取挂载的错误收集
      const errors: string[] = page.__consoleErrors ?? []
      const fatal = errors.filter((e) => FATAL_ERROR.test(e))
      expect(
        fatal,
        `${route} 出现致命 JS 错误: ${fatal.join(' | ')}`
      ).toHaveLength(0)
    })
  }
})
