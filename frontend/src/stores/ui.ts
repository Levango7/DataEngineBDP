/**
 * 布局 UI 状态（2026-09-07 布局重构）
 *
 * 左侧边栏可折叠（232px ⇄ 64px 图标模式），
 * 右侧信息面板可开合（320px，通知/动态）。
 * 状态持久化到 localStorage，刷新后还原用户偏好。
 */
import { defineStore } from 'pinia'

const SIDEBAR_KEY = 'sq_sidebar_collapsed'
const RIGHTPANEL_KEY = 'sq_rightpanel_open'

function readBool(key: string, fallback: boolean): boolean {
  try {
    const v = localStorage.getItem(key)
    if (v === '1') return true
    if (v === '0') return false
  } catch {
    // 私密模式读不到
  }
  return fallback
}

export const useUiStore = defineStore('ui', {
  state: () => ({
    /** 左侧边栏折叠（图标模式） */
    sidebarCollapsed: readBool(SIDEBAR_KEY, false),
    /** 右侧信息面板展开（通知/动态） */
    rightPanelOpen: readBool(RIGHTPANEL_KEY, false)
  }),
  actions: {
    toggleSidebar() {
      this.sidebarCollapsed = !this.sidebarCollapsed
      try {
        localStorage.setItem(SIDEBAR_KEY, this.sidebarCollapsed ? '1' : '0')
      } catch {
        /* 忽略持久化失败 */
      }
    },
    toggleRightPanel() {
      this.rightPanelOpen = !this.rightPanelOpen
      try {
        localStorage.setItem(RIGHTPANEL_KEY, this.rightPanelOpen ? '1' : '0')
      } catch {
        /* 忽略持久化失败 */
      }
    }
  }
})
