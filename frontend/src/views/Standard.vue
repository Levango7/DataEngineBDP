<template>
  <div>
    <PageHeader :title="t('standard.title')" :subtitle="t('standard.subtitle')" />
    <Toolbar
      :show-create="true"
      :create-label="t('standard.newStandard')"
      :create-aria-label="t('standard.newStandard')"
      :show-refresh="false"
      @create="modalVisible = true"
    >
      <template #actions>
        <span class="pill b">
          {{ t('standard.applyRate', { rate: summary?.applyRate ?? '--' }) }}
        </span>
      </template>
    </Toolbar>
    <div class="card">
      <div v-if="loading" class="state-tip state-loading">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="state-tip state-error">
        {{ error.message }}，
        <button type="button" class="link-btn" @click="loadStandards">{{ t('common.retry') }}</button>
      </div>
      <!-- 数据标准列表：使用 el-table 替换原生 table，统一交互与无障碍语义 -->
      <el-table
        v-else
        :data="standards"
        stripe
        border
        role="table"
        :aria-label="t('standard.title')"
        :empty-text="t('standard.empty')"
      >
        <el-table-column prop="name" :label="t('standard.cols.item')" min-width="160" />
        <el-table-column :label="t('standard.cols.type')" width="120">
          <template #default="{ row }">
            {{ typeLabel(row.type) }}
          </template>
        </el-table-column>
        <el-table-column prop="rule" :label="t('standard.cols.rule')" min-width="180" />
        <el-table-column prop="refAssetCount" :label="t('standard.cols.refAssets')" width="120" />
      </el-table>
    </div>

    <Modal
      :visible="modalVisible"
      :title="t('standard.createModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('standard.createModal.item') }}</label>
      <el-input v-model="form.name" :placeholder="t('standard.createModal.itemPlaceholder')" />
      <label>{{ t('standard.createModal.type') }}</label>
      <el-select v-model="form.type" style="width: 100%">
        <el-option :label="t('standard.types.primary_key')" value="primary_key" />
        <el-option :label="t('standard.types.enum')" value="enum" />
        <el-option :label="t('standard.types.dict')" value="dict" />
        <el-option :label="t('standard.types.amount')" value="amount" />
      </el-select>
      <label>{{ t('standard.createModal.rule') }}</label>
      <el-input v-model="form.rule" :placeholder="t('standard.createModal.rulePlaceholder')" />
      <template #footer>
        <el-button @click="modalVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ submitting ? t('standard.createModal.publishing') : t('standard.createModal.publish') }}
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
import * as standardApi from '@/api/standard'
import type { Standard, StandardSummary, StandardType } from '@/api/standard'
import type { PagedResult } from '@/api/types'

const { t } = useI18n()
const store = useAppStore()
const modalVisible = ref(false)
const submitting = ref(false)

// 标准列表 + 落标率：通过 useApi 包装并行加载，自动维护 loading / error / data 三态
const {
  data: standardsData,
  loading,
  error,
  execute: loadStandards
} = useApi<[PagedResult<Standard>, StandardSummary | null]>(() =>
  Promise.all([
    standardApi.listStandards({ page: 1, pageSize: 100 }),
    standardApi.getSummary().catch(() => null)
  ])
)

// 标准列表
const standards = computed<Standard[]>(() => standardsData.value?.[0]?.list ?? [])
// 落标率
const summary = computed<StandardSummary | null>(() => standardsData.value?.[1] ?? null)

/** 类型 → 词条 */
const STANDARD_TYPES: StandardType[] = ['primary_key', 'enum', 'dict', 'amount', 'date', 'string']

function typeLabel(st: StandardType): string {
  return STANDARD_TYPES.includes(st) ? t(`standard.types.${st}`) : st
}

// 新建表单
const form = reactive<{
  name: string
  type: StandardType
  rule: string
}>({
  name: '',
  type: 'primary_key',
  rule: ''
})

/** 提交创建标准 */
async function handleSubmit() {
  if (!form.name.trim()) {
    store.showToast(t('standard.createModal.nameRequired'))
    return
  }
  submitting.value = true
  try {
    await standardApi.createStandard({
      name: form.name,
      type: form.type,
      rule: form.rule
    })
    modalVisible.value = false
    store.showToast(t('standard.createModal.published'))
    await loadStandards()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  void loadStandards()
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
.link-btn {
  background: none;
  border: none;
  padding: 0;
  color: var(--ds-color-primary-500);
  text-decoration: underline;
  cursor: pointer;
  font: inherit;
}
</style>
