/**
 * i18n 词条结构与切换器契约测试（2026-09-02 框架级双语）。
 *
 * 保障：
 * - zh-CN / en-US 词条文件结构镜像（同 key 集合，防漏译）
 * - Sidebar 导航 35 项 + 7 组 key 全部存在
 * - 常用词条有值（防空翻译）
 */
import { describe, it, expect } from 'vitest'
import zhCN from '@/i18n/locales/zh-CN.json'
import enUS from '@/i18n/locales/en-US.json'

function flattenKeys(obj: Record<string, unknown>, prefix = ''): string[] {
  return Object.entries(obj).flatMap(([k, v]) =>
    typeof v === 'object' && v !== null
      ? flattenKeys(v as Record<string, unknown>, `${prefix}${k}.`)
      : [`${prefix}${k}`]
  )
}

describe('i18n locales structure', () => {
  const zhKeys = flattenKeys(zhCN as Record<string, unknown>).sort()
  const enKeys = flattenKeys(enUS as Record<string, unknown>).sort()

  it('zh-CN 与 en-US 的 key 集合完全一致（无漏译）', () => {
    expect(enKeys).toEqual(zhKeys)
  })

  it('导航 51 项 + 7 分组全部有中英词条', () => {
    const navItems = Object.keys((zhCN as any).nav.items)
    const navGroups = Object.keys((zhCN as any).nav.groups)
    // 实数：Sidebar groups 共 51 个导航项（7 分组）
    expect(navItems.length).toBe(51)
    expect(navGroups.length).toBe(7)
    for (const k of navItems) {
      expect((enUS as any).nav.items[k]).toBeTruthy()
    }
    for (const g of navGroups) {
      expect((enUS as any).nav.groups[g]).toBeTruthy()
    }
  })

  it('login / common / app 词条均有值（非空字符串）', () => {
    for (const domain of ['login', 'common', 'app']) {
      const entries = Object.entries((zhCN as any)[domain])
      for (const [k, v] of entries) {
        expect(v, `${domain}.${k}`).toBeTruthy()
        expect((enUS as any)[domain][k], `en:${domain}.${k}`).toBeTruthy()
      }
    }
  })

  it('zh-CN 导航项含中文，en-US 导航项为英文', () => {
    expect((zhCN as any).nav.items.quality).toBe('数据质量')
    expect((enUS as any).nav.items.quality).toBe('Data Quality')
    expect((zhCN as any).nav.brand).toContain('数擎')
    expect((enUS as any).nav.brand).toContain('Shuqing')
  })
})
