<template>
  <div>
    <h1>账户与配额</h1>
    <div class="sub">套餐即容量边界；超额自动扩容或升级套餐，费用清晰可核算。</div>
    <div v-if="loading" class="card" style="text-align: center; padding: 24px; color: #888">
      正在加载账户信息...
    </div>
    <div v-else-if="error" class="card" style="text-align: center; padding: 24px; color: #d4380d">
      加载失败：{{ error.message }}
      <button class="btn ghost sm" style="margin-left: 8px" @click="loadAll">重试</button>
    </div>
    <template v-else>
      <div class="card">
        <h3>当前套餐：{{ plan?.planName ?? '—' }}</h3>
        <template v-if="plan">
          <div v-for="(q, idx) in plan.quotas" :key="q.name">
            <div class="row" :style="idx > 0 ? 'margin-top: 12px' : ''">
              <span>{{ q.name }}</span>
              <span>{{ q.total }} / 已用 {{ q.used }}</span>
            </div>
            <div class="bar">
              <i :class="idx === 1 ? 'a' : ''" :style="{ width: q.usagePercent + '%' }"></i>
            </div>
          </div>
        </template>
        <button class="btn ghost sm" style="margin-top: 10px" @click="modalVisible = true">
          升级套餐
        </button>
      </div>
      <div class="card" style="margin-top: 14px">
        <h3>本月计费明细</h3>
        <div v-if="billingLoading" style="text-align: center; padding: 24px; color: #888">
          正在加载计费明细...
        </div>
        <table v-else-if="billing">
          <thead>
            <tr>
              <th>项</th>
              <th>用量</th>
              <th>费用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="item in billing.items" :key="item.id">
              <td>{{ item.name }}</td>
              <td>{{ item.usage }}</td>
              <td>¥ {{ item.cost.toLocaleString() }}</td>
            </tr>
            <tr>
              <td><b>合计</b></td>
              <td></td>
              <td>
                <b>¥ {{ billing.totalCost.toLocaleString() }}</b>
              </td>
            </tr>
          </tbody>
        </table>
        <div class="note">
          套餐由 ResourceQuota + 节点池租约实现，与 SKE 发行版解耦，客户仅见套餐概念。
        </div>
      </div>
    </template>

    <Modal :visible="modalVisible" title="升级套餐" @close="modalVisible = false">
      <label>目标套餐</label>
      <select v-model="upgradeForm.targetPlan">
        <option value="flagship">旗舰版</option>
        <option value="enterprise">企业版+扩容包</option>
      </select>
      <label>预计月费</label>
      <input :value="estimatedFee" disabled />
      <div class="note">升级后经 NodePoolLease 自动扩容，客户无感知停机。</div>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="submitUpgrade">确认升级</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as accountApi from '@/api/account'
import type { AccountPlan, BillingDetail, PlanTier } from '@/api/account'

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
      store.showToast('套餐升级已提交')
      await loadAll()
    } else {
      store.showToast('套餐升级失败')
    }
  } catch (e) {
    store.showToast(`升级失败：${(e as Error).message}`)
  }
}

onMounted(() => {
  void loadAll()
})
</script>
