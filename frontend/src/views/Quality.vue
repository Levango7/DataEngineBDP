<template>
  <div>
    <PageHeader :title="t('quality.title')" :subtitle="t('quality.subtitle')" />
    <Toolbar
      :show-create="true"
      :create-label="t('quality.newRule')"
      :create-aria-label="t('quality.newRule')"
      :show-refresh="false"
      @create="modalVisible = true"
    >
      <template #actions>
        <span class="pill g">{{ t('quality.passRate', { rate: summary?.passRate ?? '--' }) }}</span>
      </template>
    </Toolbar>
    <div class="card">
      <div v-if="loading" class="state-tip state-loading">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="state-tip state-error">
        {{ error.message }}，
        <a href="javascript:void(0)" @click="loadRules">{{ t('common.retry') }}</a>
      </div>
      <!-- 质量规则列表：使用 el-table 替换原生 table，统一交互与无障碍语义 -->
      <el-table
        v-else
        :data="rules"
        stripe
        border
        role="table"
        :aria-label="t('quality.title')"
        :empty-text="t('quality.empty')"
      >
        <el-table-column prop="name" :label="t('quality.cols.rule')" min-width="160" />
        <el-table-column prop="targetTable" :label="t('quality.cols.target')" min-width="160" />
        <el-table-column :label="t('quality.cols.check')" width="120">
          <template #default="{ row }">
            {{ checkTypeLabel(row.checkType) }}
          </template>
        </el-table-column>
        <el-table-column prop="threshold" :label="t('quality.cols.threshold')" width="120" />
        <el-table-column prop="lastCheckAt" :label="t('quality.cols.last')" width="180">
          <template #default="{ row }">
            {{ row.lastCheckAt || '--' }}
          </template>
        </el-table-column>
        <el-table-column :label="t('quality.cols.status')" width="120">
          <template #default="{ row }">
            <span class="pill" :class="resultPillClass(row.lastResult)">
              {{ resultPillText(row.lastResult) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 批次质量报告：读取批次执行时 validate 阶段落盘的 quality_summary（/batches/{id}/quality） -->
    <div class="card">
      <div class="batch-head">
        <span class="batch-title">{{ t('quality.batchReport.sectionTitle') }}</span>
        <el-select
          v-model="selectedBatchId"
          :placeholder="t('quality.batchReport.selectPlaceholder')"
          style="width: 280px"
          :aria-label="t('quality.batchReport.selectPlaceholder')"
          @change="loadBatchQuality"
        >
          <el-option
            v-for="b in batches"
            :key="b.batch_id"
            :label="`${b.batch_id} · ${b.status}`"
            :value="b.batch_id"
          />
        </el-select>
        <el-button :aria-label="t('quality.batchReport.refresh')" @click="loadBatches">
          {{ t('quality.batchReport.refresh') }}
        </el-button>
      </div>
      <div v-if="batchLoading" class="state-tip state-loading">{{ t('common.loading') }}</div>
      <div v-else-if="batchError" class="state-tip state-error">
        {{ batchError.message }}，
        <a href="javascript:void(0)" @click="loadBatches">{{ t('common.retry') }}</a>
      </div>
      <template v-else-if="batchQuality">
        <div class="batch-summary">
          <span class="pill g">{{ t('quality.batchReport.dqScore', { score: dqScorePct }) }}</span>
          <span class="pill b">
            {{
              t('quality.batchReport.checks', {
                passed: batchQuality.checks_passed,
                total: batchQuality.checks_total
              })
            }}
          </span>
          <span class="pill r">
            {{ t('quality.batchReport.failedCount', { failed: batchQuality.checks_failed }) }}
          </span>
          <span class="pill b">
            {{ t('quality.batchReport.mode', { mode: batchQuality.mode || 'full' }) }}
          </span>
        </div>
        <el-table
          :data="batchQuality.rules"
          stripe
          border
          role="table"
          :aria-label="t('quality.batchReport.sectionTitle')"
          :empty-text="t('quality.batchReport.empty')"
        >
          <el-table-column
            prop="dataset"
            :label="t('quality.batchReport.cols.dataset')"
            min-width="140"
          />
          <el-table-column
            prop="rule"
            :label="t('quality.batchReport.cols.rule')"
            min-width="160"
          />
          <el-table-column
            prop="checked"
            :label="t('quality.batchReport.cols.checked')"
            width="110"
          />
          <el-table-column
            prop="passed"
            :label="t('quality.batchReport.cols.passed')"
            width="110"
          />
        </el-table>
      </template>
      <div v-else class="state-tip state-loading">{{ t('quality.batchReport.empty') }}</div>
    </div>

    <Modal
      :visible="modalVisible"
      :title="t('quality.createModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('quality.createModal.targetTable') }}</label>
      <el-input
        v-model="form.targetTable"
        :placeholder="t('quality.createModal.targetTablePlaceholder')"
      />
      <label>{{ t('quality.createModal.targetField') }}</label>
      <el-input
        v-model="form.targetField"
        :placeholder="t('quality.createModal.targetFieldPlaceholder')"
      />
      <label>{{ t('quality.createModal.checkType') }}</label>
      <el-select v-model="form.checkType" style="width: 100%">
        <el-option :label="t('quality.checkTypes.not_null')" value="not_null" />
        <el-option :label="t('quality.checkTypes.unique')" value="unique" />
        <el-option :label="t('quality.checkTypes.range')" value="range" />
        <el-option :label="t('quality.checkTypes.fluctuation')" value="fluctuation" />
      </el-select>
      <label>{{ t('quality.createModal.threshold') }}</label>
      <el-input
        v-model="form.threshold"
        :placeholder="t('quality.createModal.thresholdPlaceholder')"
      />
      <label>{{ t('quality.createModal.actionOnFail') }}</label>
      <el-select v-model="form.actionOnFail" style="width: 100%">
        <el-option :label="t('quality.actions.alert')" value="alert" />
        <el-option :label="t('quality.actions.block_downstream')" value="block_downstream" />
      </el-select>
      <template #footer>
        <el-button @click="modalVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ submitting ? t('quality.createModal.creating') : t('common.create') }}
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
import * as qualityApi from '@/api/quality'
import type { QualityRule, QualitySummary, CheckType, ActionOnFail } from '@/api/quality'
import * as batchesApi from '@/api/batches'
import type { BatchRecord, BatchQualityReport } from '@/api/batches'
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

// ---- 批次质量报告：批次列表 + 选中批次的 quality_summary ----
const batches = ref<BatchRecord[]>([])
const selectedBatchId = ref('')
const batchQuality = ref<BatchQualityReport | null>(null)
const batchLoading = ref(false)
const batchError = ref<Error | null>(null)

/** dq_score（0-1）→ 百分比文案 */
const dqScorePct = computed(() =>
  batchQuality.value ? `${Math.round(batchQuality.value.dq_score * 100)}%` : '--'
)

/** 加载批次列表（失败静默：拦截器已统一提示，区块回落空态文案） */
async function loadBatches() {
  try {
    const resp = await batchesApi.listBatches()
    batches.value = resp.batches ?? []
  } catch {
    // 错误提示已由拦截器统一处理
  }
}

/** 加载选中批次的质量报告 */
async function loadBatchQuality(batchId: string) {
  if (!batchId) return
  batchLoading.value = true
  batchError.value = null
  batchQuality.value = null
  try {
    const resp = await batchesApi.getBatchQuality(batchId)
    batchQuality.value = resp.quality
  } catch (e) {
    batchError.value = e instanceof Error ? e : new Error(String(e))
  } finally {
    batchLoading.value = false
  }
}

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
  void loadBatches()
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
/* 批次质量报告区块：头部选择行 + 摘要 pill 行（一律 --ds-* token） */
.batch-head {
  display: flex;
  align-items: center;
  gap: var(--ds-spacing-3);
  margin-bottom: var(--ds-spacing-3);
}
.batch-title {
  font-size: var(--ds-font-size-md);
  font-weight: 600;
  color: var(--ds-text-primary);
}
.batch-summary {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ds-spacing-2);
  margin-bottom: var(--ds-spacing-3);
}
</style>
