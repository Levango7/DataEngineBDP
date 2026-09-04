<template>
  <div>
    <h1>{{ t('quality.title') }}</h1>
    <div class="sub">{{ t('quality.subtitle') }}</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">{{ t('quality.newRule') }}</button>
      <div class="spacer"></div>
      <span class="pill g">{{ t('quality.passRate', { rate: summary?.passRate ?? '--' }) }}</span>
    </div>
    <div class="card">
      <div v-if="loading" style="padding: 16px; color: var(--muted)">{{ t('common.loading') }}</div>
      <div v-else-if="error" style="padding: 16px; color: var(--red)">
        {{ error.message }}，
        <a href="javascript:void(0)" @click="loadRules">{{ t('common.retry') }}</a>
      </div>
      <table v-else>
        <tr>
          <th>{{ t('quality.cols.rule') }}</th>
          <th>{{ t('quality.cols.target') }}</th>
          <th>{{ t('quality.cols.check') }}</th>
          <th>{{ t('quality.cols.threshold') }}</th>
          <th>{{ t('quality.cols.last') }}</th>
          <th>{{ t('quality.cols.status') }}</th>
        </tr>
        <tr v-for="r in rules" :key="r.id">
          <td>{{ r.name }}</td>
          <td>{{ r.targetTable }}</td>
          <td>{{ checkTypeLabel(r.checkType) }}</td>
          <td>{{ r.threshold }}</td>
          <td>{{ r.lastCheckAt || '--' }}</td>
          <td>
            <span class="pill" :class="resultPillClass(r.lastResult)">
              {{ resultPillText(r.lastResult) }}
            </span>
          </td>
        </tr>
        <tr v-if="rules.length === 0">
          <td colspan="6" style="text-align: center; color: var(--muted)">
            {{ t('quality.empty') }}
          </td>
        </tr>
      </table>
    </div>

    <Modal
      :visible="modalVisible"
      :title="t('quality.createModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('quality.createModal.targetTable') }}</label>
      <input
        v-model="form.targetTable"
        :placeholder="t('quality.createModal.targetTablePlaceholder')"
      />
      <label>{{ t('quality.createModal.targetField') }}</label>
      <input
        v-model="form.targetField"
        :placeholder="t('quality.createModal.targetFieldPlaceholder')"
      />
      <label>{{ t('quality.createModal.checkType') }}</label>
      <select v-model="form.checkType">
        <option value="not_null">{{ t('quality.checkTypes.not_null') }}</option>
        <option value="unique">{{ t('quality.checkTypes.unique') }}</option>
        <option value="range">{{ t('quality.checkTypes.range') }}</option>
        <option value="fluctuation">{{ t('quality.checkTypes.fluctuation') }}</option>
      </select>
      <label>{{ t('quality.createModal.threshold') }}</label>
      <input
        v-model="form.threshold"
        :placeholder="t('quality.createModal.thresholdPlaceholder')"
      />
      <label>{{ t('quality.createModal.actionOnFail') }}</label>
      <select v-model="form.actionOnFail">
        <option value="alert">{{ t('quality.actions.alert') }}</option>
        <option value="block_downstream">{{ t('quality.actions.block_downstream') }}</option>
      </select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">{{ t('common.cancel') }}</button>
        <button class="btn" :disabled="submitting" @click="handleSubmit">
          {{ submitting ? t('quality.createModal.creating') : t('common.create') }}
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
import * as qualityApi from '@/api/quality'
import type { QualityRule, QualitySummary, CheckType, ActionOnFail } from '@/api/quality'
import type { PagedResult } from '@/api/types'

const { t } = useI18n()
const store = useAppStore()
const modalVisible = ref(false)
const submitting = ref(false)

// 规则列表 + 通过率：通过 useApi 包装并行加载，自动维护 loading / error / data 三态
const {
  data: rulesData,
  loading,
  error,
  execute: loadRules
} = useApi<[PagedResult<QualityRule>, QualitySummary | null]>(() =>
  Promise.all([
    qualityApi.listRules({ page: 1, pageSize: 100 }),
    qualityApi.getSummary().catch(() => null)
  ])
)

// 规则列表
const rules = computed<QualityRule[]>(() => rulesData.value?.[0]?.list ?? [])
// 通过率
const summary = computed<QualitySummary | null>(() => rulesData.value?.[1] ?? null)

/** 校验类型 → 词条 */
const CHECK_TYPES: CheckType[] = ['not_null', 'unique', 'range', 'fluctuation', 'regex', 'sql']

function checkTypeLabel(ct: CheckType): string {
  return CHECK_TYPES.includes(ct) ? t(`quality.checkTypes.${ct}`) : ct
}

/** 校验结果 → pill 样式 */
function resultPillClass(result?: string): string {
  switch (result) {
    case 'pass':
      return 'g'
    case 'warn':
      return 'a'
    case 'fail':
      return 'r'
    default:
      return 'b'
  }
}

/** 校验结果 → pill 文案 */
function resultPillText(result?: string): string {
  switch (result) {
    case 'pass':
      return t('quality.results.pass')
    case 'warn':
      return t('quality.results.warn')
    case 'fail':
      return t('quality.results.fail')
    default:
      return t('quality.results.none')
  }
}

// 新建表单
const form = reactive<{
  targetTable: string
  targetField: string
  checkType: CheckType
  threshold: string
  actionOnFail: ActionOnFail
}>({
  targetTable: '',
  targetField: '',
  checkType: 'not_null',
  threshold: '100%',
  actionOnFail: 'alert'
})

/** 提交创建规则 */
async function handleSubmit() {
  if (!form.targetTable.trim()) {
    store.showToast(t('quality.createModal.tableRequired'))
    return
  }
  submitting.value = true
  try {
    await qualityApi.createRule({
      name: `${form.targetField || form.targetTable}_${form.checkType}`,
      targetTable: form.targetTable,
      targetField: form.targetField || undefined,
      checkType: form.checkType,
      threshold: form.threshold,
      actionOnFail: form.actionOnFail
    })
    modalVisible.value = false
    store.showToast(t('quality.createModal.created'))
    await loadRules()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadRules()
})
</script>
