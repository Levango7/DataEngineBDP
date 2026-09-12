/**
 * 布局 UI 状态（2026-09-07 布局重构）
 *
 * 左侧边栏可折叠（232px ⇄ 64px 图标模式），
 * 右侧信息面板可开合（320px，通知/动态）。
 * 状态持久化到 localStorage，刷新后还原用户偏好。
 *
 * 移动端（≤640px）侧边栏改为抽屉模式，由 sidebarOpen 控制滑入/移出。
 * 平板（641–1440px）通过 matchMedia 自动折叠侧边栏（sidebarAutoCollapsed），
 * 该状态不持久化，仅由视口尺寸驱动；用户手动 toggleSidebar 时清除自动折叠，
 * 让用户偏好优先。
 */
import { defineStore } from 'pinia'

const SIDEBAR_KEY = 'sq_sidebar_collapsed'
const RIGHTPANEL_KEY = 'sq_rightpanel_open'
const SIDEBAR_OPEN_KEY = 'sq_sidebar_open'

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
    /** 左侧边栏折叠（图标模式）—— 用户偏好，持久化 */
    sidebarCollapsed: readBool(SIDEBAR_KEY, false),
    /** 侧边栏自动折叠——由平板断点 matchMedia 驱动，不持久化。
     *  最终折叠态 = sidebarCollapsed || sidebarAutoCollapsed */
    sidebarAutoCollapsed: false,
    /** 右侧信息面板展开（通知/动态） */
    rightPanelOpen: readBool(RIGHTPANEL_KEY, false),
    /** 移动端侧边栏抽屉展开——仅 ≤640px 生效，控制 .side.is-open */
    sidebarOpen: readBool(SIDEBAR_OPEN_KEY, false)
  }),
  getters: {
    /** 侧边栏是否处于折叠态（用户偏好 OR 平板自动折叠） */
    sidebarFolded(): boolean {
      return this.sidebarCollapsed || this.sidebarAutoCollapsed
    }
  },
  actions: {
    toggleSidebar() {
      // 用户手动切换时，清除平板自动折叠标记，让用户偏好生效
      this.sidebarAutoCollapsed = false
      this.sidebarCollapsed = !this.sidebarCollapsed
      try {
        localStorage.setItem(SIDEBAR_KEY, this.sidebarCollapsed ? '1' : '0')
      } catch {
        /* 忽略持久化失败 */
      }
    },
    /** 由 matchMedia 调用：设置平板自动折叠态（不持久化） */
    setSidebarAutoCollapsed(value: boolean) {
      this.sidebarAutoCollapsed = value
    },
    toggleRightPanel() {
      this.rightPanelOpen = !this.rightPanelOpen
      try {
        localStorage.setItem(RIGHTPANEL_KEY, this.rightPanelOpen ? '1' : '0')
      } catch {
        /* 忽略持久化失败 */
      }
    },
    /** 移动端切换侧边栏抽屉开合 */
    toggleSidebarOpen() {
      this.sidebarOpen = !this.sidebarOpen
      try {
        localStorage.setItem(SIDEBAR_OPEN_KEY, this.sidebarOpen ? '1' : '0')
      } catch {
        /* 忽略持久化失败 */
      }
    },
    /** 关闭移动端侧边栏抽屉（路由跳转后调用） */
    closeSidebarOpen() {
      this.sidebarOpen = false
      try {
        localStorage.setItem(SIDEBAR_OPEN_KEY, '0')
      } catch {
        /* 忽略持久化失败 */
      }
    }
  }
})
