import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as secApi from '@/api/sec'

/**
 * 应用全局状态：工作空间、环境标签、待办计数、Toast
 */
export const useAppStore = defineStore('app', () => {
  // 是否使用 mock 数据：仅当显式设置 VITE_USE_MOCK='true' 时启用（默认关闭，使用真实 API）
  const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

  // 工作空间
  const workspace = ref('华东生产集群')
  const envTag = ref('信创环境 · 健康')

  // 待办审批列表（dashboard 与 sec 页面共用）
  interface Todo {
    id: string
    text: string
    applicant: string
  }
  const todos = ref<Todo[]>([])

  // 安全审批列表（由后端 API 加载，不预置本地假数据）
  interface SecApproval {
    id: string
    applicant: string
    asset: string
    perm: string
  }
  const secApprovals = ref<SecApproval[]>([])
  const secApprovalsLoaded = ref(false)
  const secApprovalsError = ref<Error | null>(null)

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

  async function approve(id: string) {
    // 非 mock 模式：先调用后端审批 API，失败则中止本地状态变更
    if (!USE_MOCK) {
      try {
        await secApi.approveApproval(id)
      } catch (e) {
        showToast('审批失败')
        return
      }
    }
    removeApproval(id)
    showToast('已批准')
  }

  async function reject(id: string) {
    // 非 mock 模式：先调用后端审批 API，失败则中止本地状态变更
    if (!USE_MOCK) {
      try {
        await secApi.rejectApproval(id)
      } catch (e) {
        showToast('驳回失败')
        return
      }
    }
    removeApproval(id)
    showToast('已驳回')
  }

  /** 从待办或安全审批列表中移除指定项 */
  function removeApproval(id: string) {
    if (todos.value.some((t) => t.id === id)) {
      todos.value = todos.value.filter((t) => t.id !== id)
    } else {
      secApprovals.value = secApprovals.value.filter((s) => s.id !== id)
    }
  }

  function setWorkspace(name: string) {
    workspace.value = name
    showToast(`已切换工作空间：${name}`)
  }

  /**
   * 拉取安全审批列表（非 mock 模式生效）
   * 成功才落数据；失败置 error 态，不静默保留旧值
   */
  async function fetchSecApprovals() {
    if (USE_MOCK) return
    try {
      const approvals = await secApi.listApprovals('pending')
      secApprovals.value = approvals.map((a) => ({
        id: a.id,
        applicant: a.applicant,
        asset: a.asset,
        perm: a.permission
      }))
      secApprovalsLoaded.value = true
      secApprovalsError.value = null
    } catch (e) {
      secApprovalsError.value = e instanceof Error ? e : new Error(String(e))
    }
  }

  return {
    workspace,
    envTag,
    todos,
    secApprovals,
    secApprovalsLoaded,
    secApprovalsError,
    todoCount,
    toasts,
    showToast,
    approve,
    reject,
    setWorkspace,
    fetchSecApprovals
  }
})
