<template>
  <div>
    <h1>{{ t('account.title') }}</h1>
    <div class="sub">{{ t('account.subtitle') }}</div>
    <div v-if="loading" class="card" style="text-align: center; padding: 24px; color: #888">
      {{ t('account.loading') }}
    </div>
    <div v-else-if="error" class="card" style="text-align: center; padding: 24px; color: #d4380d">
      {{ t('account.loadFailed', { msg: error.message }) }}
      <button class="btn ghost sm" style="margin-left: 8px" @click="loadAll">
        {{ t('common.retry') }}
      </button>
    </div>
    <template v-else>
      <div class="card">
        <h3>{{ t('account.currentPlan', { name: plan?.planName ?? '—' }) }}</h3>
        <template v-if="plan">
          <div v-for="(q, idx) in plan.quotas" :key="q.name">
            <div class="row" :style="idx > 0 ? 'margin-top: 12px' : ''">
              <span>{{ q.name }}</span>
              <span>{{ t('account.quotaUsed', { total: q.total, used: q.used }) }}</span>
            </div>
            <div class="bar">
              <i :class="idx === 1 ? 'a' : ''" :style="{ width: q.usagePercent + '%' }"></i>
            </div>
          </div>
        </template>
        <button class="btn ghost sm" style="margin-top: 10px" @click="modalVisible = true">
          {{ t('account.upgrade') }}
        </button>
      </div>
      <div class="card" style="margin-top: 14px">
        <h3>{{ t('account.billingTitle') }}</h3>
        <div v-if="billingLoading" style="text-align: center; padding: 24px; color: #888">
          {{ t('account.billingLoading') }}
        </div>
        <table v-else-if="billing">
          <thead>
            <tr>
              <th>{{ t('account.cols.item') }}</th>
              <th>{{ t('account.cols.usage') }}</th>
              <th>{{ t('account.cols.cost') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in billing.items" :key="item.id">
              <td>{{ item.name }}</td>
              <td>{{ item.usage }}</td>
              <td>¥ {{ item.cost.toLocaleString() }}</td>
            </tr>
            <tr>
              <td>
                <b>{{ t('account.total') }}</b>
              </td>
              <td></td>
              <td>
                <b>¥ {{ billing.totalCost.toLocaleString() }}</b>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="note">{{ t('account.billingNote') }}</div>
      </div>
    </template>

    <Modal
      :visible="modalVisible"
      :title="t('account.upgradeModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('account.upgradeModal.targetPlan') }}</label>
      <select v-model="upgradeForm.targetPlan">
        <option value="flagship">{{ t('account.upgradeModal.flagship') }}</option>
        <option value="enterprise">{{ t('account.upgradeModal.enterprisePlus') }}</option>
      </select>
      <label>{{ t('account.upgradeModal.estimatedFee') }}</label>
      <input :value="estimatedFee" disabled />
      <div class="note">{{ t('account.upgradeModal.note') }}</div>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">{{ t('common.cancel') }}</button>
        <button class="btn" @click="submitUpgrade">{{ t('account.upgradeModal.confirm') }}</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as accountApi from '@/api/account'
import type { AccountPlan, BillingDetail, PlanTier } from '@/api/account'

const { t } = useI18n()
const store = useAppStore()
const modalVisible = ref(false)

// 账户套餐：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: plan,
  loading,
  error,
  execute: loadPlan
} = useApi<AccountPlan>(() => accountApi.getAccountPlan())

// 计费明细：通过 useApi 包装，失败时不阻塞页面
const {
  data: billing,
  loading: billingLoading,
  execute: loadBilling
} = useApi<BillingDetail>(() => accountApi.getBillingDetail())

const upgradeForm = ref<{ targetPlan: PlanTier }>({
  targetPlan: 'flagship'
})

const estimatedFee = computed(() => {
  return upgradeForm.value.targetPlan === 'flagship' ? '¥ 58,000' : '¥ 35,000'
})

async function loadAll() {
  await Promise.all([void loadPlan(), void loadBilling()])
}

async function submitUpgrade() {
  try {
    const result = await accountApi.upgradePlan({
      targetPlan: upgradeForm.value.targetPlan
    })
    modalVisible.value = false
    if (result.status === 'success' || result.status === 'submitted') {
      store.showToast(t('account.upgradeModal.submitted'))
      await loadAll()
    } else {
      store.showToast(t('account.upgradeModal.failed'))
    }
  } catch (e) {
    store.showToast(t('account.upgradeModal.failedWithMsg', { msg: (e as Error).message }))
  }
}

onMounted(() => {
  void loadAll()
})
</script>
