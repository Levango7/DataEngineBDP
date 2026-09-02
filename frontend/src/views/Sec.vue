<template>
  <div>
    <h1>{{ t('sec.title') }}</h1>
    <div class="sub">{{ t('sec.subtitle') }}</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">{{ t('sec.newPolicy') }}</button>
      <div class="spacer"></div>
      <span class="pill r">{{ t('sec.pendingBadge', { count: approvals.length }) }}</span>
    </div>
    <div class="card">
      <div v-if="policiesLoading" style="padding: 16px; color: var(--muted)">
        {{ t('common.loading') }}
      </div>
      <div v-else-if="policiesError" style="padding: 16px; color: var(--red)">
        {{ policiesError.message }}，
        <a href="javascript:void(0)" @click="loadPolicies">{{ t('common.retry') }}</a>
      </div>
      <table v-else>
        <tr>
          <th>{{ t('sec.cols.field') }}</th>
          <th>{{ t('sec.cols.asset') }}</th>
          <th>{{ t('sec.cols.strategy') }}</th>
          <th>{{ t('sec.cols.algorithm') }}</th>
          <th>{{ t('sec.cols.status') }}</th>
        </tr>
        <tr v-for="p in policies" :key="p.id">
          <td>{{ p.fieldName }}</td>
          <td>{{ p.assetName }}</td>
          <td>{{ strategyLabel(p.strategy) }}</td>
          <td>{{ p.algorithm }}</td>
          <td>
            <span class="pill" :class="statusPillClass(p.status)">
              {{ statusPillText(p.status) }}
            </span>
          </td>
        </tr>
        <tr v-if="policies.length === 0">
          <td colspan="5" style="text-align: center; color: var(--muted)">{{ t('sec.empty') }}</td>
        </tr>
      </table>
    </div>
    <div class="section-title">{{ t('sec.approvalsTitle') }}</div>
    <div class="card">
      <div v-if="approvalsLoading" style="padding: 16px; color: var(--muted)">
        {{ t('common.loading') }}
      </div>
      <table v-else>
        <tr>
          <th>{{ t('sec.approvalCols.applicant') }}</th>
          <th>{{ t('sec.approvalCols.asset') }}</th>
          <th>{{ t('sec.approvalCols.permission') }}</th>
          <th></th>
        </tr>
        <tr v-for="a in approvals" :key="a.id">
          <td>{{ a.applicant }}</td>
          <td>{{ a.asset }}</td>
          <td>{{ a.permission }}</td>
          <td>
            <button class="btn sm" @click="handleApprove(a.id)">{{ t('sec.approve') }}</button>
            <button class="btn ghost sm" @click="handleReject(a.id)">{{ t('sec.reject') }}</button>
          </td>
        </tr>
        <tr v-if="approvals.length === 0">
          <td colspan="4" style="text-align: center; color: var(--muted)">
            {{ t('sec.approvalsEmpty') }}
          </td>
        </tr>
      </table>
    </div>

    <Modal :visible="modalVisible" :title="t('sec.createModal.title')" @close="modalVisible = false">
      <label>{{ t('sec.createModal.field') }}</label>
      <input v-model="form.fieldName" :placeholder="t('sec.createModal.fieldPlaceholder')" />
      <label>{{ t('sec.createModal.asset') }}</label>
      <input v-model="form.assetName" />
      <label>{{ t('sec.createModal.strategy') }}</label>
      <select v-model="form.strategy">
        <option value="mask">{{ t('sec.strategies.mask') }}</option>
        <option value="hash">{{ t('sec.strategies.hash') }}</option>
        <option value="authorized_only">{{ t('sec.strategies.authorized_only') }}</option>
      </select>
      <label>{{ t('sec.createModal.algorithm') }}</label>
      <select v-model="form.algorithm">
        <option value="SM3">{{ t('sec.createModal.sm3') }}</option>
        <option value="SHA256">SHA256</option>
        <option value="AES">AES</option>
      </select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">{{ t('common.cancel') }}</button>
        <button class="btn" :disabled="submitting" @click="handleSubmit">
          {{ submitting ? t('sec.createModal.submitting') : t('sec.createModal.submit') }}
        </button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as secApi from '@/api/sec'
import type {
  MaskPolicy,
  PermissionApproval,
  MaskStrategy,
  MaskAlgorithm,
  StrategyStatus
} from '@/api/sec'

const { t } = useI18n()
const store = useAppStore()
const modalVisible = ref(false)
const submitting = ref(false)

// 脱敏策略列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: policiesData,
  loading: policiesLoading,
  error: policiesError,
  execute: loadPolicies
} = useApi<MaskPolicy[]>(() => secApi.listMaskPolicies(), { initialData: [] })
const policies = computed<MaskPolicy[]>(() => policiesData.value ?? [])

// 审批列表：通过 useApi 包装，失败时不阻塞页面
const {
  data: approvalsData,
  loading: approvalsLoading,
  execute: loadApprovals
} = useApi<PermissionApproval[]>(() => secApi.listApprovals('pending'), { initialData: [] })
const approvals = computed<PermissionApproval[]>(() => approvalsData.value ?? [])

/** 批准申请 */
async function handleApprove(id: string) {
  try {
    await secApi.approveApproval(id)
    store.showToast(t('sec.toast.approved'))
    await loadApprovals()
  } catch {
    // 错误提示已由拦截器统一处理
  }
}

/** 驳回申请 */
async function handleReject(id: string) {
  try {
    await secApi.rejectApproval(id)
    store.showToast(t('sec.toast.rejected'))
    await loadApprovals()
  } catch {
    // 错误提示已由拦截器统一处理
  }
}

/** 策略 → 词条 */
const MASK_STRATEGIES: MaskStrategy[] = ['mask', 'hash', 'authorized_only', 'plain']

function strategyLabel(s: MaskStrategy): string {
  return MASK_STRATEGIES.includes(s) ? t(`sec.strategies.${s}`) : s
}

/** 状态 → pill 样式 */
function statusPillClass(s: StrategyStatus): string {
  switch (s) {
    case 'active':
      return 'g'
    case 'pending':
      return 'a'
    default:
      return 'b'
  }
}

/** 状态 → pill 文案 */
function statusPillText(s: StrategyStatus): string {
  switch (s) {
    case 'active':
      return t('sec.status.active')
    case 'pending':
      return t('sec.status.pending')
    case 'disabled':
      return t('sec.status.disabled')
    default:
      return s
  }
}

// 新建表单
const form = reactive<{
  fieldName: string
  assetName: string
  strategy: MaskStrategy
  algorithm: MaskAlgorithm
}>({
  fieldName: '',
  assetName: '',
  strategy: 'mask',
  algorithm: 'SM3'
})

/** 提交创建策略 */
async function handleSubmit() {
  if (!form.fieldName.trim()) {
    store.showToast(t('sec.createModal.fieldRequired'))
    return
  }
  submitting.value = true
  try {
    await secApi.createMaskPolicy({
      fieldName: form.fieldName,
      assetName: form.assetName,
      strategy: form.strategy,
      algorithm: form.algorithm
    })
    modalVisible.value = false
    store.showToast(t('sec.createModal.created'))
    await loadPolicies()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadPolicies()
  void loadApprovals()
})
</script>
