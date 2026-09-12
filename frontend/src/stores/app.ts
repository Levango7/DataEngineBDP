import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as secApi from '@/api/sec'
import * as tenantApi from '@/api/tenant'
import { i18n } from '@/i18n'

/** store 内使用的 i18n 翻译函数（store 不在组件上下文内，不能用 useI18n()） */
const t = i18n.global.t

/**
 * 应用全局状态：工作空间、环境标签、待办计数、Toast
 */
export const useAppStore = defineStore('app', () => {
  // 是否使用 mock 数据：仅当显式设置 VITE_USE_MOCK='true' 时启用（默认关闭，使用真实 API）
  const USE_MOCK = import.meta.env.VITE_USE_MOCK === 'true'

  // 工作空间（由 fetchTenantInfo 从 API 拉取填充，初始为空）
  const workspace = ref('')
  // 环境标签（由 fetchTenantInfo 从 API 拉取填充，初始为空）
  const envTag = ref('')
  // 套餐版本（由 fetchTenantInfo 从 API 拉取填充，供 Dashboard 展示）
  const plan = ref('')
  // 本月资源消耗百分比字符串（由 fetchTenantInfo 从 API 拉取填充，供 Dashboard 展示）
  const resourceUsage = ref('')

  // 安全审批列表（由后端 API 加载，不预置本地假数据）
  // 说明：原 todos 本地待办列表已无数据源（mock 数据已删除），已移除；
  //       dashboard 与 sec 页面统一使用 secApprovals 作为唯一审批数据源。
  interface SecApproval {
    id: string
    applicant: string
    asset: string
    perm: string
  }
  const secApprovals = ref<SecApproval[]>([])
  const secApprovalsLoaded = ref(false)
  const secApprovalsError = ref<Error | null>(null)

  const todoCount = computed(() => secApprovals.value.length)

  // Toast
  interface ToastItem {
    id: number
    msg: string
    type?: 'success' | 'error' | 'warning' | 'info'
  }
  const toasts = ref<ToastItem[]>([])
  let toastId = 0

  function showToast(msg: string, type: ToastItem['type'] = 'info') {
    const id = ++toastId
    toasts.value.push({ id, msg, type })
    setTimeout(() => {
      toasts.value = toasts.value.filter((toast) => toast.id !== id)
    }, 2200)
  }

  /** 手动关闭指定 toast */
  function dismissToast(id: number) {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  async function approve(id: string) {
    // 非 mock 模式：先调用后端审批 API，失败则中止本地状态变更
    if (!USE_MOCK) {
      try {
        await secApi.approveApproval(id)
      } catch (e) {
        console.error('[app] approveApproval failed:', e)
        showToast(t('app.toast.approveFailed'))
        return
      }
    }
    removeApproval(id)
    showToast(t('app.toast.approved'))
  }

  async function reject(id: string) {
    // 非 mock 模式：先调用后端审批 API，失败则中止本地状态变更
    if (!USE_MOCK) {
      try {
        await secApi.rejectApproval(id)
      } catch (e) {
        console.error('[app] rejectApproval failed:', e)
        showToast(t('app.toast.rejectFailed'))
        return
      }
    }
    removeApproval(id)
    showToast(t('app.toast.rejected'))
  }

  /** 从安全审批列表中移除指定项 */
  function removeApproval(id: string) {
    secApprovals.value = secApprovals.value.filter((s) => s.id !== id)
  }

  function setWorkspace(name: string) {
    workspace.value = name
    showToast(t('app.toast.workspaceSwitched', { name }))
  }

  /**
   * 拉取安全审批列表（非 mock 模式生效）
   * 成功才落数据；失败置 error 态并清空旧数据，不静默保留过期值
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
      // 失败时清空旧数据，避免"先成功后失败"场景下展示过期数据
      secApprovals.value = []
      secApprovalsLoaded.value = false
      secApprovalsError.value = e instanceof Error ? e : new Error(String(e))
    }
  }

  /**
   * 拉取当前租户信息，填充 workspace / envTag / plan / resourceUsage
   * 供 Dashboard、Projects、TopBar 等组件使用，替代原硬编码业务参数。
   * 失败时 fallback 到 i18n 默认值，不抛错（仅影响展示）。
   */
  async function fetchTenantInfo() {
    try {
      const tenants = await tenantApi.listAllTenants()
      if (tenants.length > 0) {
        const current = tenants[0]
        workspace.value = current.name
        plan.value = t(`app.planTiers.${current.plan}`, current.plan)
        resourceUsage.value = `${current.resourceUsage}%`
      } else {
        workspace.value = t('app.defaultWorkspace')
      }
      envTag.value = t('app.defaultEnvTag')
    } catch {
      // API 不可用时使用 i18n 默认值兜底，不阻塞页面渲染
      if (!workspace.value) workspace.value = t('app.defaultWorkspace')
      if (!envTag.value) envTag.value = t('app.defaultEnvTag')
    }
  }

  return {
    workspace,
    envTag,
    plan,
    resourceUsage,

    secApprovals,
    secApprovalsLoaded,
    secApprovalsError,
    todoCount,
    toasts,
    showToast,
    dismissToast,
    approve,
    reject,
    setWorkspace,
    fetchSecApprovals,
    fetchTenantInfo
  }
})
