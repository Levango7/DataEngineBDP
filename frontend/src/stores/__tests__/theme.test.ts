/**
 * theme store 单测：system（跟随系统）档必须与显式 light/dark 走同一套 CSS 匹配路径
 *
 * 回归背景（2026-09-21）：旧实现里 system 档 `removeAttribute('data-theme')`，
 * 导致 main.css 里 106 条 `:root[data-theme='dark'] .el-*` 组件级覆写全部不匹配
 * （媒体查询兜底块只覆盖变量层）→ system + 系统暗色 的观感与显式 dark 不一致。
 * 现在 applyTheme 一律写入「mode + 系统偏好」的解析结果。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { resolveTheme, useThemeStore } from '../theme'

type Listener = (e: { matches: boolean }) => void

/** 安装可手动切换的 matchMedia 桩（jsdom 不实现 matchMedia） */
function installMatchMedia(initialDark: boolean) {
  const listeners = new Set<Listener>()
  let matches = initialDark
  const mql = {
    get matches() {
      return matches
    },
    media: '(prefers-color-scheme: dark)',
    addEventListener: (_type: string, cb: Listener) => {
      listeners.add(cb)
    },
    removeEventListener: (_type: string, cb: Listener) => {
      listeners.delete(cb)
    },
    addListener: (cb: Listener) => listeners.add(cb),
    removeListener: (cb: Listener) => listeners.delete(cb),
    onchange: null,
    dispatchEvent: () => false
  }
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: vi.fn().mockReturnValue(mql)
  })
  return {
    setDark(next: boolean) {
      matches = next
      listeners.forEach((cb) => cb({ matches: next }))
    }
  }
}

const dataTheme = () => document.documentElement.getAttribute('data-theme')

beforeEach(() => {
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('resolveTheme', () => {
  it('显式 light/dark 直接返回自身（覆盖系统偏好）', () => {
    installMatchMedia(true)
    expect(resolveTheme('light')).toBe('light')
    expect(resolveTheme('dark')).toBe('dark')
  })

  it('system 档返回系统偏好解析结果', () => {
    installMatchMedia(true)
    expect(resolveTheme('system')).toBe('dark')
    installMatchMedia(false)
    expect(resolveTheme('system')).toBe('light')
  })

  it('matchMedia 不可用时 system 档回退亮色', () => {
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      writable: true,
      value: undefined
    })
    expect(resolveTheme('system')).toBe('light')
  })
})

describe('useThemeStore：system 档始终写入 data-theme', () => {
  it('system + 系统暗色 → data-theme="dark"（不再移除属性）', () => {
    installMatchMedia(true)
    localStorage.setItem('sq_theme', 'system')
    const store = useThemeStore()
    expect(store.isDark).toBe(true)
    expect(dataTheme()).toBe('dark')
  })

  it('system + 系统亮色 → data-theme="light"', () => {
    installMatchMedia(false)
    localStorage.setItem('sq_theme', 'system')
    const store = useThemeStore()
    expect(store.isDark).toBe(false)
    expect(dataTheme()).toBe('light')
  })

  it('无存储偏好（默认 system 档）也写入解析结果', () => {
    installMatchMedia(false)
    const store = useThemeStore()
    expect(store.mode).toBe('system')
    expect(dataTheme()).toBe('light')
  })

  it('显式 light 在系统暗色下仍为 light（覆盖系统偏好）', () => {
    installMatchMedia(true)
    localStorage.setItem('sq_theme', 'light')
    useThemeStore()
    expect(dataTheme()).toBe('light')
  })

  it('显式 dark 在系统亮色下仍为 dark', () => {
    installMatchMedia(false)
    localStorage.setItem('sq_theme', 'dark')
    useThemeStore()
    expect(dataTheme()).toBe('dark')
  })

  it('运行中切换系统主题：system 档重绘，显式档锁定', async () => {
    const mm = installMatchMedia(false)
    localStorage.setItem('sq_theme', 'system')
    const store = useThemeStore()
    expect(dataTheme()).toBe('light')

    mm.setDark(true)
    expect(store.isDark).toBe(true)
    expect(dataTheme()).toBe('dark')

    mm.setDark(false)
    expect(dataTheme()).toBe('light')

    // 切到显式 dark 后，系统偏好变化不得改写 data-theme
    // （mode 的 watch 回调是 Vue pre-flush watcher，需等一次 tick）
    store.setMode('dark')
    await nextTick()
    expect(dataTheme()).toBe('dark')
    mm.setDark(true)
    mm.setDark(false)
    expect(dataTheme()).toBe('dark')
  })

  it('setMode 持久化到 sq_theme，重新创建 store 后保持', async () => {
    installMatchMedia(false)
    useThemeStore().setMode('dark')
    await nextTick()
    expect(localStorage.getItem('sq_theme')).toBe('dark')
    expect(dataTheme()).toBe('dark')

    setActivePinia(createPinia())
    document.documentElement.removeAttribute('data-theme')
    const reloaded = useThemeStore()
    expect(reloaded.mode).toBe('dark')
    expect(dataTheme()).toBe('dark')
  })

  it('toggle 在当前主题的相反档之间切换', async () => {
    installMatchMedia(false)
    const store = useThemeStore()
    expect(store.isDark).toBe(false)
    store.toggle()
    await nextTick()
    expect(store.mode).toBe('dark')
    expect(dataTheme()).toBe('dark')
    store.toggle()
    await nextTick()
    expect(store.mode).toBe('light')
    expect(dataTheme()).toBe('light')
  })
})
