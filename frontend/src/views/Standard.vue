<template>
  <div>
    <h1>{{ t('standard.title') }}</h1>
    <div class="sub">{{ t('standard.subtitle') }}</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">{{ t('standard.newStandard') }}</button>
      <div class="spacer"></div>
      <span class="pill b">
        {{ t('standard.applyRate', { rate: summary?.applyRate ?? '--' }) }}
      </span>
    </div>
    <div class="card">
      <div v-if="loading" style="padding: 16px; color: var(--muted)">{{ t('common.loading') }}</div>
      <div v-else-if="error" style="padding: 16px; color: var(--red)">
        {{ error.message }}，
        <a href="javascript:void(0)" @click="loadStandards">{{ t('common.retry') }}</a>
      </div>
      <table v-else>
        <tr>
          <th>{{ t('standard.cols.item') }}</th>
          <th>{{ t('standard.cols.type') }}</th>
          <th>{{ t('standard.cols.rule') }}</th>
          <th>{{ t('standard.cols.refAssets') }}</th>
        </tr>
        <tr v-for="s in standards" :key="s.id">
          <td>{{ s.name }}</td>
          <td>{{ typeLabel(s.type) }}</td>
          <td>{{ s.rule }}</td>
          <td>{{ s.refAssetCount }}</td>
        </tr>
        <tr v-if="standards.length === 0">
          <td colspan="4" style="text-align: center; color: var(--muted)">
            {{ t('standard.empty') }}
          </td>
        </tr>
      </table>
    </div>

    <Modal
      :visible="modalVisible"
      :title="t('standard.createModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('standard.createModal.item') }}</label>
      <input v-model="form.name" :placeholder="t('standard.createModal.itemPlaceholder')" />
      <label>{{ t('standard.createModal.type') }}</label>
      <select v-model="form.type">
        <option value="primary_key">{{ t('standard.types.primary_key') }}</option>
        <option value="enum">{{ t('standard.types.enum') }}</option>
        <option value="dict">{{ t('standard.types.dict') }}</option>
        <option value="amount">{{ t('standard.types.amount') }}</option>
      </select>
      <label>{{ t('standard.createModal.rule') }}</label>
      <input v-model="form.rule" :placeholder="t('standard.createModal.rulePlaceholder')" />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">{{ t('common.cancel') }}</button>
        <button class="btn" :disabled="submitting" @click="handleSubmit">
          {{
            submitting ? t('standard.createModal.publishing') : t('standard.createModal.publish')
          }}
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
