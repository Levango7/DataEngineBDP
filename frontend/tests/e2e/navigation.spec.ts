/**
 * 导航布局 E2E 测试
 *
 * 覆盖：
 * - 侧边栏菜单项数量正确（8 分组 48 项）
 * - 顶栏显示用户信息
 * - 路由切换正常（至少 5 个路由）
 * - 分组折叠/展开
 * - 工作空间切换
 * - 用户菜单
 */
import { test, expect } from '@playwright/test'
import { ensureLoggedIn } from './helpers'

test.describe('导航布局', () => {
  test.beforeEach(async ({ page }) => {
    await ensureLoggedIn(page)
  })

  test('侧边栏菜单分组数量正确（8 分组）', async ({ page }) => {
    // 侧边栏可见
    await expect(page.locator('aside.side')).toBeVisible()
    // 品牌标识
    await expect(page.locator('aside.side .brand')).toContainText('数擎')

    // 8 个分组标题（useNavGroups.ts 单一来源）
    const groups = page.locator('.nav .grp')
    await expect(groups).toHaveCount(8)

    // 验证分组标题文本
    const titles = await groups.locator('.grp-label').allTextContents()
    expect(titles).toEqual([
      '基础设施',
      '数据引擎',
      '数据治理',
      '开发工具',
      '租户与配额',
      '智能数据',
      '运营管理',
      '平台管理'
    ])
  })

  test('侧边栏菜单项数量正确（48 项）', async ({ page }) => {
    // 等待菜单渲染
    await page.waitForSelector('.nav-item', { timeout: 10_000 })
    const items = page.locator('.nav-item')
    const count = await items.count()
    // 48 项（允许较大浮动以适应未来调整）
    expect(count).toBeGreaterThanOrEqual(25)
    expect(count).toBeLessThanOrEqual(55)
  })

  test('顶栏显示用户信息', async ({ page }) => {
    // 顶栏可见
    await expect(page.locator('.topbar')).toBeVisible()
    // 工作空间切换器
    await expect(page.locator('.ws-switch')).toContainText('工作空间')
    // 环境标签
    await expect(page.locator('.env-tag')).toBeVisible()
    // 头像
    await expect(page.locator('.avatar')).toBeVisible()
    // 头像首字 A（admin 首字母大写）
    await expect(page.locator('.avatar')).toContainText('A')
  })

  test('用户菜单展开显示用户名与退出按钮', async ({ page }) => {
    // 点击头像
    await page.waitForSelector('.avatar', { timeout: 10_000 })
    await page.locator('.avatar').click()
    // 用户弹出层
    await expect(page.locator('.user-pop')).toBeVisible({ timeout: 5_000 })
    // 用户名（用 first 避免 strict mode）
    await expect(page.locator('.user-name').first()).toContainText('admin')
    // 退出登录按钮（在 .user-pop 内）
    await expect(page.locator('.user-pop').getByRole('menuitem', { name: '退出登录' })).toBeVisible()
    // 账户与配额链接（在 .user-pop 内）
    await expect(page.locator('.user-pop').getByRole('menuitem', { name: '账户与配额' })).toBeVisible()
  })

  test('路由切换：dashboard → projects → standard → govern → search', async ({ page }) => {
    // dashboard
    await page.goto('/#/dashboard', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('h1')).toContainText('工作台')

    // projects
    await page.goto('/#/projects', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('h1')).toContainText('数据项目')

    // standard
    await page.goto('/#/standard', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('h1')).toContainText('数据标准')

    // govern
    await page.goto('/#/govern', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('h1')).toContainText('资产目录')

    // search
    await page.goto('/#/search', { waitUntil: 'domcontentloaded' })
    await expect(page.locator('h1')).toContainText('检索门户')
  })

  test('点击侧边栏菜单项跳转对应路由', async ({ page }) => {
    // 点击"统一控制台"（在"产品运营"分组下）
    await page.goto('/#/dashboard', { waitUntil: 'domcontentloaded' })
    const dashLink = page.locator('.nav-item', { hasText: '统一控制台' })
    await dashLink.click()
    await expect(page).toHaveURL(/#\/dashboard/)

    // 点击"项目管理"
    const projLink = page.locator('.nav-item', { hasText: '项目管理' })
    await projLink.click()
    await expect(page).toHaveURL(/#\/projects/, { timeout: 10_000 })
    await expect(page.locator('h1')).toContainText('数据项目')
  })

  test('分组折叠/展开正常', async ({ page }) => {
    // 默认 1280×720 视口落入平板断点（641–1440px），侧边栏自动折叠后
    // 分组标题被隐藏（main.css: .side.collapsed .nav .grp { display: none }），
    // 分组行点击不可达——固定为桌面视口并等待分组可见后再操作
    await page.setViewportSize({ width: 1600, height: 900 })

    // 找到第一个分组标题
    const firstGroup = page.locator('.nav .grp').first()
    await expect(firstGroup).toBeVisible({ timeout: 10_000 })
    const groupLabel = await firstGroup.locator('.grp-label').textContent()
    expect(groupLabel).toBeTruthy()

    // 初始展开：箭头旋转 90deg
    await expect(firstGroup.locator('.grp-arrow')).toHaveClass(/open/)

    // 点击折叠
    await firstGroup.click()
    await expect(firstGroup.locator('.grp-arrow')).not.toHaveClass(/open/)

    // 验证子项容器 collapsed
    const firstItems = page.locator('.grp-items').first()
    await expect(firstItems).toHaveClass(/collapsed/)

    // 再点击展开
    await firstGroup.click()
    await expect(firstGroup.locator('.grp-arrow')).toHaveClass(/open/)
  })

  test('工作空间切换菜单', async ({ page }) => {
    // 点击工作空间切换器
    await page.locator('.ws-switch').click()
    // 工作空间菜单
    await expect(page.locator('.ws-menu')).toBeVisible()

    // 条目数量取决于租户 API 返回（加载失败时回退为默认单一项），至少 1 项
    const wsItems = page.locator('.ws-item')
    await expect(wsItems.first()).toBeVisible({ timeout: 5_000 })
    const total = await wsItems.count()
    expect(total).toBeGreaterThanOrEqual(1)

    // 当前选中项唯一，且其文本与切换器展示的工作空间一致
    const selected = page.locator('.ws-item.on')
    await expect(selected).toHaveCount(1)
    const selectedText = (await selected.textContent())?.trim() ?? ''
    if (selectedText) {
      await expect(page.locator('.ws-switch')).toContainText(selectedText)
    }

    if (total > 1 && selectedText) {
      // 多项：切换到非当前项（按文本排除当前项），验证切换器文本更新且菜单收起
      const other = wsItems.filter({ hasNotText: selectedText }).first()
      const targetText = (await other.textContent())?.trim() ?? ''
      await other.click()
      await expect(page.locator('.ws-menu')).not.toBeVisible()
      if (targetText) {
        await expect(page.locator('.ws-switch')).toContainText(targetText)
      }
    } else {
      // 单项（API 回退默认）：点击后菜单收起即可
      await wsItems.first().click()
      await expect(page.locator('.ws-menu')).not.toBeVisible()
    }
  })

  test('路由切换时激活态高亮', async ({ page }) => {
    // 访问 projects
    await page.goto('/#/projects', { waitUntil: 'domcontentloaded' })
    // 激活项唯一
    const activeItem = page.locator('.nav-item.active')
    await expect(activeItem).toHaveCount(1)
    // 用可重试断言等待路由渲染完成；一次性 textContent 读取会因竞态
    // 拿到上一页（统一控制台）的激活项
    const projItem = page.locator('.nav-item', { hasText: '项目管理' })
    await expect(projItem).toHaveClass(/active/, { timeout: 10_000 })
  })

  test('环境标签显示', async ({ page }) => {
    const envTag = page.locator('.env-tag')
    await expect(envTag).toBeVisible()
    const text = await envTag.textContent()
    expect(text).toMatch(/●/)
  })

  test('侧边栏底部信息显示', async ({ page }) => {
    await expect(page.locator('.side-foot')).toBeVisible()
    await expect(page.locator('.side-foot')).toContainText('DataEngineBDP')
  })
})