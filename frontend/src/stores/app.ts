import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

/**
 * 应用全局状态：工作空间、环境标签、待办计数、Toast
 */
export const useAppStore = defineStore('app', () => {
  // 工作空间
  const workspace = ref('华东生产集群')
  const envTag = ref('信创环境 · 健康')

  // 待办审批列表（dashboard 与 sec 页面共用）
  interface Todo {
    id: string
    text: string
    applicant: string
  }
  const todos = ref<Todo[]>([
    { id: 't1', text: '资产 dwd.order_wide 读权限', applicant: '李工' },
    { id: 't2', text: '项目「风控域」成员新增', applicant: '王工' },
    { id: 't3', text: '工作空间配额升级', applicant: '赵工' }
  ])

  // 安全审批列表
  interface SecApproval {
    id: string
    applicant: string
    asset: string
    perm: string
  }
  const secApprovals = ref<SecApproval[]>([
    { id: 's1', applicant: '李工', asset: 'dwd.order_wide', perm: '读' },
    { id: 's2', applicant: '王工', asset: '风控域', perm: '写' }
  ])

  const todoCount = computed(() => todos.value.length + secApprovals.value.length)

  // Toast
  interface ToastItem {
    id: number
    msg: string
  }
  const toasts = ref<ToastItem[]>([])
  let toastId = 0

  function showToast(msg: string) {
    const id = ++toastId
    toasts.value.push({ id, msg })
    setTimeout(() => {
      toasts.value = toasts.value.filter((t) => t.id !== id)
    }, 2200)
  }

  function approve(id: string) {
    // 先尝试 dashboard 待办
    const inTodos = todos.value.some((t) => t.id === id)
    if (inTodos) {
      todos.value = todos.value.filter((t) => t.id !== id)
    } else {
      secApprovals.value = secApprovals.value.filter((s) => s.id !== id)
    }
    showToast('已批准')
  }

  function reject(id: string) {
    const inTodos = todos.value.some((t) => t.id === id)
    if (inTodos) {
      todos.value = todos.value.filter((t) => t.id !== id)
    } else {
      secApprovals.value = secApprovals.value.filter((s) => s.id !== id)
    }
    showToast('已驳回')
  }

  function setWorkspace(name: string) {
    workspace.value = name
    showToast(`已切换工作空间：${name}`)
  }

  return {
    workspace,
    envTag,
    todos,
    secApprovals,
    todoCount,
    toasts,
    showToast,
    approve,
    reject,
    setWorkspace
  }
})
