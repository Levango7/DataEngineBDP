/**
 * 租户状态 store
 *
 * 职责：
 * - 维护租户列表与当前选中租户
 * - 提供 fetchTenants / selectTenant 动作
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as tenantApi from '@/api/tenant'
import type { Tenant } from '@/api/types'

/** localStorage 持久化键 */
const CURRENT_TENANT_KEY = 'sq_current_tenant'

/** 读取本地存储的当前租户 ID */
function loadCurrentTenantId(): string | null {
  return localStorage.getItem(CURRENT_TENANT_KEY)
}

export const useTenantStore = defineStore('tenant', () => {
  // 租户列表
  const tenants = ref<Tenant[]>([])
  // 当前选中租户
  const currentTenant = ref<Tenant | null>(null)
  // 加载状态
  const loading = ref(false)
  // 错误信息
  const error = ref<string | null>(null)

  /**
   * 拉取租户列表
   * @param force 是否强制刷新
   */
  async function fetchTenants(force = false): Promise<void> {
    if (!force && tenants.value.length > 0) return
    loading.value = true
    error.value = null
    try {
      const result = await tenantApi.listAllTenants()
      tenants.value = result
      // 若未选中租户，尝试从本地存储恢复或取第一项
      if (!currentTenant.value) {
        const savedId = loadCurrentTenantId()
        const matched = savedId ? result.find((t) => t.id === savedId) : undefined
        currentTenant.value = matched ?? result[0] ?? null
      }
    } catch (e) {
      error.value = (e as Error).message || '加载租户列表失败'
    } finally {
      loading.value = false
    }
  }

  /**
   * 切换当前租户
   * @param id 租户 ID
   */
  function selectTenant(id: string): void {
    const target = tenants.value.find((t) => t.id === id) ?? null
    if (target) {
      currentTenant.value = target
      localStorage.setItem(CURRENT_TENANT_KEY, id)
    }
  }

  /** 重置状态 */
  function reset(): void {
    tenants.value = []
    currentTenant.value = null
    error.value = null
    localStorage.removeItem(CURRENT_TENANT_KEY)
  }

  return {
    tenants,
    currentTenant,
    loading,
    error,
    fetchTenants,
    selectTenant,
    reset
  }
})
