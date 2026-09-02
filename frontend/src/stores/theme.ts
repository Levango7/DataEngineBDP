import { defineStore } from 'pinia'
import { ref, watch, computed } from 'vue'

/**
 * 主题（亮/暗）全局状态。
 *
 * 机制（与 design-tokens.css 的双选择器暗色机制配合）：
 * - 用户手动选择 > 系统偏好（prefers-color-scheme）
 * - 生效方式：向 document.documentElement 写入/移除 data-theme 属性
 *   （CSS 选择器 :root[data-theme="dark"] 与媒体查询
 *   :root:not([data-theme="light"]) 覆盖暗色令牌）
 * - localStorage 持久化（键 sq_theme），刷新后保持
 *
 * 取值：
 * - "system"：跟随系统（默认，不写属性，完全交给媒体查询）
 * - "light"：强制亮色（data-theme="light"，覆盖系统暗偏好）
 * - "dark"：强制暗色（data-theme="dark"，覆盖系统亮偏好）
 */
export type ThemeMode = 'system' | 'light' | 'dark'

const STORAGE_KEY = 'sq_theme'

function readInitial(): ThemeMode {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return v === 'light' || v === 'dark' || v === 'system' ? v : 'system'
  } catch {
    return 'system'
  }
}

function applyTheme(mode: ThemeMode): void {
  const el = document.documentElement
  if (mode === 'system') {
    el.removeAttribute('data-theme')
  } else {
    el.setAttribute('data-theme', mode)
  }
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(readInitial())

  // 初始应用一次（首渲染前同步属性，避免主题闪变）
  applyTheme(mode.value)

  watch(mode, (m) => {
    applyTheme(m)
    try {
      localStorage.setItem(STORAGE_KEY, m)
    } catch {
      // 私密模式写入失败不影响当前会话主题
    }
  })

  const isDark = computed<boolean>(() => {
    if (mode.value === 'dark') return true
    if (mode.value === 'light') return false
    return window.matchMedia?.('(prefers-color-scheme: dark)').matches ?? false
  })

  function setMode(m: ThemeMode): void {
    mode.value = m
  }

  function toggle(): void {
    mode.value = isDark.value ? 'light' : 'dark'
  }

  return { mode, isDark, setMode, toggle }
})
