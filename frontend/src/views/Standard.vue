<template>
  <div>
    <h1>数据标准</h1>
    <div class="sub">统一字段命名、类型、码值，治理前置，避免"同义不同名"。</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建标准</button>
      <div class="spacer"></div>
      <span class="pill b">已落标 {{ summary?.applyRate ?? '--' }}%</span>
    </div>
    <div class="card">
      <div v-if="loading" style="padding: 16px; color: var(--muted)">加载中…</div>
      <div v-else-if="error" style="padding: 16px; color: var(--red)">
        {{ error.message }}，
        <a href="javascript:void(0)" @click="loadStandards">重试</a>
      </div>
      <table v-else>
        <tr>
          <th>标准项</th>
          <th>类型</th>
          <th>码值/规则</th>
          <th>引用资产</th>
        </tr>
        <tr v-for="s in standards" :key="s.id">
          <td>{{ s.name }}</td>
          <td>{{ typeLabel(s.type) }}</td>
          <td>{{ s.rule }}</td>
          <td>{{ s.refAssetCount }}</td>
        </tr>
        <tr v-if="standards.length === 0">
          <td colspan="4" style="text-align: center; color: var(--muted)">暂无标准</td>
        </tr>
      </table>
    </div>

    <Modal :visible="modalVisible" title="新建数据标准" @close="modalVisible = false">
      <label>标准项</label>
      <input v-model="form.name" placeholder="如 user_id" />
      <label>类型</label>
      <select v-model="form.type">
        <option value="primary_key">主键</option>
        <option value="enum">枚举</option>
        <option value="dict">字典</option>
        <option value="amount">金额</option>
      </select>
      <label>规则/码值</label>
      <input v-model="form.rule" placeholder="如 bigint,非空" />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" :disabled="submitting" @click="handleSubmit">
          {{ submitting ? '发布中…' : '发布' }}
        </button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as standardApi from '@/api/standard'
import type { Standard, StandardSummary, StandardType } from '@/api/standard'
import type { PagedResult } from '@/api/types'

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

/** 类型 → 中文 */
function typeLabel(t: StandardType): string {
  const map: Record<StandardType, string> = {
    primary_key: '主键',
    enum: '枚举',
    dict: '字典',
    amount: '金额',
    date: '日期',
    string: '字符串'
  }
  return map[t] || t
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
    store.showToast('请填写标准项')
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
    store.showToast('标准已发布')
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
