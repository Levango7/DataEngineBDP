<template>
  <div>
    <PageHeader :title="t('account.title')" :subtitle="t('account.subtitle')" />
    <div
      v-if="loading"
      class="card"
      style="text-align: center; padding: 24px; color: var(--ds-text-tertiary)"
    >
      {{ t('account.loading') }}
    </div>
    <div
      v-else-if="error"
      class="card"
      style="text-align: center; padding: 24px; color: var(--ds-color-error-500)"
    >
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
        <div
          v-if="billingLoading"
          style="text-align: center; padding: 24px; color: var(--ds-text-tertiary)"
        >
          {{ t('account.billingLoading') }}
        </div>
        <el-table v-else-if="billing" :data="billingRows" stripe border style="width: 100%">
          <el-table-column prop="name" :label="t('account.cols.item')" />
          <el-table-column prop="usage" :label="t('account.cols.usage')" />
          <el-table-column :label="t('account.cols.cost')">
            <template #default="{ row }">
              <span v-if="row.isTotal" style="font-weight: 700">
                {{ t('common.currency') }} {{ row.cost.toLocaleString() }}
              </span>
              <span v-else>{{ t('common.currency') }} {{ row.cost.toLocaleString() }}</span>
            </template>
          </el-table-column>
        </el-table>
        <div class="note">{{ t('account.billingNote') }}</div>
      </div>
    </template>

    <Modal
      :visible="modalVisible"
      :title="t('account.upgradeModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('account.upgradeModal.targetPlan') }}</label>
      <el-select v-model="upgradeForm.targetPlan" style="width: 100%">
        <el-option value="flagship" :label="t('account.upgradeModal.flagship')" />
        <el-option value="enterprise" :label="t('account.upgradeModal.enterprisePlus')" />
      </el-select>
      <label>{{ t('account.upgradeModal.estimatedFee') }}</label>
      <el-input :model-value="estimatedFee" disabled />
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
import { PageHeader } from '@/components/ui'
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

/** 计费明细表格数据（含合计行，用于 el-table 渲染） */
interface BillingRow {
  id: string | number
  name: string
  usage: string | number
  cost: number
  isTotal?: boolean
}

const billingRows = computed<BillingRow[]>(() => {
  if (!billing.value) return []
  const items: BillingRow[] = billing.value.items.map((item) => ({
    id: item.id,
    name: item.name,
    usage: item.usage,
    cost: item.cost
  }))
  items.push({
    id: 'total',
    name: t('account.total'),
    usage: '',
    cost: billing.value.totalCost,
    isTotal: true
  })
  return items
})

const estimatedFee = computed(() => {
  const fee = upgradeForm.value.targetPlan === 'flagship' ? '58,000' : '35,000'
  return `${t('common.currency')} ${fee}`
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
    const msg = e instanceof Error ? e.message : String(e)
    store.showToast(t('account.upgradeModal.failedWithMsg', { msg }))
  }
}

onMounted(() => {
  void loadAll()
})
</script>
