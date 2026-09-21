import { defineStore } from 'pinia'
import { ref, watch, computed } from 'vue'

/**
 * 主题（亮/暗）全局状态。
 *
 * 机制：
 * - 用户手动选择 > 系统偏好（prefers-color-scheme）
 * - 生效方式：把 mode + 系统偏好**解析**成最终主题，并**始终**写入
 *   document.documentElement 的 data-theme 属性（取值恒为 "light" | "dark"）。
 *   —— 包括 "system" 档：它不再"移除属性"，而是解析后写入解析结果。
 * - localStorage 持久化（键 sq_theme），刷新后保持
 *
 * 为什么 system 档也必须写 data-theme（2026-09-21 修复）：
 *   main.css 里有 106 处 `:root[data-theme='dark'] .el-*` 逐组件覆写，用来把
 *   Element Plus 组件在暗色下的底色 / 文字色 / 边框修正成 v2 取值。
 *   而 design-tokens.css 与 main.css 里的 `@media (prefers-color-scheme: dark)`
 *   块只覆盖**变量层**（--ds-* / --el-*），覆盖不到那 106 条组件级规则。
 *   旧实现里 system 档 removeAttribute → 106 条覆写全部不匹配 → system + 系统暗色
 *   的用户看到"变量是暗的、组件级覆写全部落空"的错色，与显式 dark 观感不一致。
 *   改为"始终写入解析结果"后，system 档与显式 light/dark 走同一套 CSS 匹配路径。
 *
 * 取值：
 * - "system"：跟随系统（解析后写入 light / dark）
 * - "light"：强制亮色（data-theme="light"，覆盖系统暗偏好）
 * - "dark"：强制暗色（data-theme="dark"，覆盖系统亮偏好）
 */
export type ThemeMode = 'system' | 'light' | 'dark'
export type ResolvedTheme = 'light' | 'dark'

const STORAGE_KEY = 'sq_theme'
const DARK_QUERY = '(prefers-color-scheme: dark)'

/** 当前系统是否偏好暗色（matchMedia 不可用时按亮色处理） */
function systemPrefersDark(): boolean {
  return window.matchMedia?.(DARK_QUERY)?.matches ?? false
}

/**
 * 把用户选择的 mode 与系统偏好解析为最终生效的主题。
 * 这是 data-theme 属性值的**唯一**来源：显式 light/dark 直接返回自身
 * （从而覆盖系统偏好），system 档返回系统偏好解析结果。
 */
export function resolveTheme(mode: ThemeMode): ResolvedTheme {
  if (mode === 'light' || mode === 'dark') return mode
  return systemPrefersDark() ? 'dark' : 'light'
}

function readInitial(): ThemeMode {
  try {
    const v = localStorage.getItem(STORAGE_KEY)
    return v === 'light' || v === 'dark' || v === 'system' ? v : 'system'
  } catch {
    return 'system'
  }
}

function applyTheme(mode: ThemeMode): void {
  // 恒写入解析结果（不再在 system 档 removeAttribute）——见文件头注释。
  document.documentElement.setAttribute('data-theme', resolveTheme(mode))
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

  // 系统暗色偏好（响应式）：监听 matchMedia change 事件，使 isDark 在 system 模式下随系统主题实时变化
  const systemDark = ref<boolean>(systemPrefersDark())
  // 监听系统主题变化（仅当 matchMedia 可用时）；pinia setup store 单例，监听器随页面生命周期存在
  const mql = window.matchMedia?.(DARK_QUERY)
  if (mql) {
    const updateSystemDark = (e: MediaQueryListEvent): void => {
      systemDark.value = e.matches
      // system 档必须重新解析并写回 data-theme：否则运行中切换系统主题时只有
      // 媒体查询里的变量层跟着变，那 106 条 `:root[data-theme='dark']` 组件级覆写
      // 不会重绘（旧实现的缺陷）。显式 light/dark 档由用户选择锁定，不受影响。
      if (mode.value === 'system') applyTheme(mode.value)
    }
    mql.addEventListener('change', updateSystemDark)
  }

  const isDark = computed<boolean>(() => {
    if (mode.value === 'dark') return true
    if (mode.value === 'light') return false
    return systemDark.value
  })

  function setMode(m: ThemeMode): void {
    mode.value = m
  }

  function toggle(): void {
    mode.value = isDark.value ? 'light' : 'dark'
  }

  return { mode, isDark, setMode, toggle }
})
