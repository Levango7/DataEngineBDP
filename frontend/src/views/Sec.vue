<template>
  <div>
    <PageHeader :title="t('sec.title')" :subtitle="t('sec.subtitle')" />
    <Toolbar
      :show-create="true"
      :create-label="t('sec.newPolicy')"
      :create-aria-label="t('sec.newPolicy')"
      :show-refresh="false"
      @create="modalVisible = true"
    >
      <template #actions>
        <span class="pill r">{{ t('sec.pendingBadge', { count: approvals.length }) }}</span>
      </template>
    </Toolbar>
    <div class="card">
      <div v-if="policiesLoading" class="state-tip state-loading">
        {{ t('common.loading') }}
      </div>
      <div v-else-if="policiesError" class="state-tip state-error">
        {{ policiesError.message }}，
        <a href="javascript:void(0)" @click="loadPolicies">{{ t('common.retry') }}</a>
      </div>
      <!-- 脱敏策略列表：使用 el-table 替换原生 table，统一交互与无障碍语义 -->
      <el-table
        v-else
        :data="policies"
        stripe
        border
        role="table"
        :aria-label="t('sec.title')"
        :empty-text="t('sec.empty')"
      >
        <el-table-column prop="fieldName" :label="t('sec.cols.field')" min-width="160" />
        <el-table-column prop="assetName" :label="t('sec.cols.asset')" min-width="160" />
        <el-table-column :label="t('sec.cols.strategy')" width="140">
          <template #default="{ row }">
            {{ strategyLabel(row.strategy) }}
          </template>
        </el-table-column>
        <el-table-column prop="algorithm" :label="t('sec.cols.algorithm')" width="140" />
        <el-table-column :label="t('sec.cols.status')" width="120">
          <template #default="{ row }">
            <span class="pill" :class="statusPillClass(row.status)">
              {{ statusPillText(row.status) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </div>
    <div class="section-title">{{ t('sec.approvalsTitle') }}</div>
    <div class="card">
      <div v-if="approvalsLoading" class="state-tip state-loading">
        {{ t('common.loading') }}
      </div>
      <!-- 审批列表：使用 el-table 替换原生 table，操作列使用 el-button -->
      <el-table
        v-else
        :data="approvals"
        stripe
        border
        role="table"
        :aria-label="t('sec.approvalsTitle')"
        :empty-text="t('sec.approvalsEmpty')"
      >
        <el-table-column
          prop="applicant"
          :label="t('sec.approvalCols.applicant')"
          min-width="140"
        />
        <el-table-column prop="asset" :label="t('sec.approvalCols.asset')" min-width="160" />
        <el-table-column
          prop="permission"
          :label="t('sec.approvalCols.permission')"
          min-width="140"
        />
        <el-table-column :label="t('sec.approvalCols.action')" width="200" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" @click="handleApprove(row.id)">
              {{ t('sec.approve') }}
            </el-button>
            <el-button size="small" @click="handleReject(row.id)">
              {{ t('sec.reject') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <Modal
      :visible="modalVisible"
      :title="t('sec.createModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('sec.createModal.field') }}</label>
      <el-input v-model="form.fieldName" :placeholder="t('sec.createModal.fieldPlaceholder')" />
      <label>{{ t('sec.createModal.asset') }}</label>
      <el-input v-model="form.assetName" />
      <label>{{ t('sec.createModal.strategy') }}</label>
      <el-select v-model="form.strategy" style="width: 100%">
        <el-option :label="t('sec.strategies.mask')" value="mask" />
        <el-option :label="t('sec.strategies.hash')" value="hash" />
        <el-option :label="t('sec.strategies.authorized_only')" value="authorized_only" />
      </el-select>
      <label>{{ t('sec.createModal.algorithm') }}</label>
      <el-select v-model="form.algorithm" style="width: 100%">
        <el-option :label="t('sec.createModal.sm3')" value="SM3" />
        <el-option label="SHA256" value="SHA256" />
        <el-option label="AES" value="AES" />
      </el-select>
      <template #footer>
        <el-button @click="modalVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ submitting ? t('sec.createModal.submitting') : t('sec.createModal.submit') }}
        </el-button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader, Toolbar } from '@/components/ui'
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

<style scoped>
/* 状态提示：使用 design tokens 替代硬编码颜色 */
.state-tip {
  padding: var(--ds-spacing-4);
}
.state-loading {
  color: var(--ds-text-tertiary);
}
.state-error {
  color: var(--ds-color-error-500);
}
</style>
