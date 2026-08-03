import { ref } from 'vue'

/**
 * 通用 tab 切换逻辑
 */
export function useTabs(initial: number = 0) {
  const activeTab = ref(initial)
  function setTab(i: number) {
    activeTab.value = i
  }
  return { activeTab, setTab }
}