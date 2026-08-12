import { defineStore } from 'pinia'
import { ref } from 'vue'
import * as subApi from '@/api/subscription'

// 订阅 store
export const useSubscriptionStore = defineStore('subscription', () => {
  const subscriptions = ref([])
  const loading = ref(false)

  async function loadSubscriptions(params) {
    loading.value = true
    try {
      const data = await subApi.listSubscriptions(params)
      subscriptions.value = Array.isArray(data) ? data : (data.items || [])
    } finally {
      loading.value = false
    }
  }

  async function subscribe(apiId, data) {
    return await subApi.subscribe(apiId, data)
  }

  async function approve(id, data) {
    return await subApi.approveSubscription(id, data)
  }

  async function issueKey(id, data) {
    return await subApi.issueKey(id, data)
  }

  async function configureRateLimit(id, data) {
    return await subApi.configureRateLimit(id, data)
  }

  return {
    subscriptions,
    loading,
    loadSubscriptions,
    subscribe,
    approve,
    issueKey,
    configureRateLimit,
  }
})