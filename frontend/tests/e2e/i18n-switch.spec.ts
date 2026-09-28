/**
 * i18n 语言切换回归 E2E（B2，47 视图词条化的关键闸门）
 *
 * 覆盖：
 * - 侧边栏品牌/分组在英文模式变英文
 * - 代表性页面 h1 在英文模式渲染英文词条（Dashboard/Projects/Quality/Govern/Search）
 * - 语言偏好持久化（刷新后仍英文）
 * - 切回中文恢复
 * - 英文模式下无"词条未翻译泄漏"（h1 不含 key 占位符 'xxx.' 前缀）
 *
 * 定位策略：语言切换器在顶栏右侧 `.tb-locale` 二态按钮
 * （中文态显 EN / 英文态显 中，TopBar.vue toggleLocale）；
 * 偏好存储 localStorage `sq_locale`（i18n/index.ts 约定）。
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn } from './helpers'

/** localStorage 键（与 src/i18n/index.ts 的 STORAGE_KEY 一致） */
const LOCALE_KEY = 'sq_locale'

/** 通过顶栏语言切换器（真实用户路径）切换到目标语言。 */
async function switchLocale(
  page: import('@playwright/test').Page,
  to: 'zh-CN' | 'en-US'
): Promise<void> {
  const btn = page.locator('.tb-locale')
  await btn.waitFor({ state: 'visible', timeout: 10_000 })
  // 二态开关：按钮文案是"点击后将切换到的语言"（中文态显 EN / 英文态显 中），
  // 即当前语言已是目标语言时，按钮显示的是另一种语言的文案
  const achievedLabel = to === 'en-US' ? '中' : 'EN'
  if ((await btn.textContent())?.trim() !== achievedLabel) {
    await btn.click()
    await expect(btn).toHaveText(achievedLabel, { timeout: 5_000 })
  }
  // 与 toggleLocale 的持久化行为对齐（persistLocale 写 sq_locale）
  await expect
    .poll(() => page.evaluate((key) => localStorage.getItem(key), LOCALE_KEY), { timeout: 5_000 })
    .toBe(to)
}

test.describe('i18n 语言切换回归', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
  })

  test('切英文后侧边栏品牌与分组变英文', async ({ page }) => {
    // 前置：中文模式品牌为"数擎"
    await expect(page.locator('aside.side .brand')).toContainText('数擎')

    await switchLocale(page, 'en-US')

    // 品牌变英文（Shuqing）
    await expect(page.locator('aside.side .brand')).toContainText(/Shuqing/i)

    // 分组标题至少一个变英文（Infrastructure / Data Engine / ...）
    const groupTexts = await page.locator('.nav .grp-label').allTextContents()
    const hasEnglishGroup = groupTexts.some((t) =>
      /Infrastructure|Data Engine|Governance|Dev Tools|Tenant|Intelligence|Operations/i.test(t)
    )
    expect(hasEnglishGroup, `分组应含英文，实际: ${groupTexts.join(',')}`).toBe(true)
  })

  test('英文模式下代表性页面 h1 渲染英文词条', async ({ page }) => {
    await switchLocale(page, 'en-US')

    // h1 词条值对齐 locales/modules/*.en-US.json 实际词条
    // 注意 /quality 已在 C-2 导航合并中重定向到 /standard（router/index.ts），
    // 故质量不再有独立 h1；此处用 /standard 的 "Data Standards"
    const cases: Array<[string, RegExp]> = [
      ['/dashboard', /Workspace/i],
      ['/projects', /Projects/i],
      ['/standard', /Data Standards/i],
      ['/govern', /Asset Catalog/i],
      ['/search', /Search Portal/i]
    ]

    for (const [route, pattern] of cases) {
      await page.goto(`/#${route}`, { waitUntil: 'domcontentloaded' })
      // 用自动重试断言：路由懒加载完成前旧页面 h1 仍短暂可见，
      // toContainText 轮询等待新词条渲染，避免抓到上一页残留
      await expect(page.locator('h1').first()).toContainText(pattern, { timeout: 15_000 })
    }
  })

  test('英文模式无词条 key 泄漏（h1 不含模块前缀占位）', async ({ page }) => {
    await switchLocale(page, 'en-US')

    const routes = [
      '/dashboard', '/projects', '/quality', '/standard', '/govern',
      '/integrate', '/develop', '/sql', '/lineage', '/sec', '/ops',
      '/jobs', '/scheduler-ops', '/vector', '/kb', '/llmops',
      '/gateway', '/account', '/admin', '/search', '/sql-workbench',
      '/datasources', '/tenants', '/quota-management', '/cluster',
      '/eng-spark', '/eng-doris', '/dev-ml', '/dev-sched', '/dev-tag',
      '/infra-k8s', '/infra-machine', '/ops-tpl', '/ops-portal', '/ops-api', '/ops-flow'
    ]

    for (const route of routes) {
      await page.goto(`/#${route}`, { waitUntil: 'domcontentloaded' })
      // h1 若渲染 key 本身（如 "dashboard.title"），说明词条缺失
      const h1 = await page.locator('h1').first().textContent().catch(() => '')
      if (h1) {
        expect(
          h1.includes('.'),
          `${route} h1 疑似渲染原始 key: "${h1}"`
        ).toBe(false)
      }
    }
  })

  test('语言偏好刷新后持久', async ({ page }) => {
    await switchLocale(page, 'en-US')
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.waitForSelector('aside.side .brand', { timeout: 10_000 })

    // 刷新后仍英文
    await expect(page.locator('aside.side .brand')).toContainText(/Shuqing/i)

    // 恢复中文，避免污染后续测试
    await switchLocale(page, 'zh-CN')
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.waitForSelector('aside.side .brand', { timeout: 10_000 })
    await expect(page.locator('aside.side .brand')).toContainText('数擎')
  })

  test('切回中文模式恢复中文词条', async ({ page }) => {
    // 英文 → 中文
    await switchLocale(page, 'en-US')
    await page.reload({ waitUntil: 'domcontentloaded' })
    await page.waitForSelector('aside.side .brand', { timeout: 10_000 })

    await switchLocale(page, 'zh-CN')
    // /quality 重定向到 /standard（C-2 导航合并），h1 为"数据标准"
    await page.goto('/#/standard', { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('h1', { timeout: 10_000 })
    await expect(page.locator('h1').first()).toContainText('数据标准')
  })
})
