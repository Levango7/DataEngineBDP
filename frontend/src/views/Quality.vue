<template>
  <div>
    <h1>数据质量</h1>
    <div class="sub">规则配置即校验，异常自动阻断下游并告警，保障湖仓集数据可信。</div>
    <div class="toolbar">
      <button class="btn sm" @click="modalVisible = true">+ 新建规则</button>
      <div class="spacer"></div>
      <span class="pill g">通过率 {{ summary?.passRate ?? '--' }}%</span>
    </div>
    <div class="card">
      <div v-if="loading" style="padding: 16px; color: var(--muted)">加载中…</div>
      <div v-else-if="error" style="padding: 16px; color: var(--red)">
        {{ error.message }}，
        <a href="javascript:void(0)" @click="loadRules">重试</a>
      </div>
      <table v-else>
        <tr>
          <th>规则</th>
          <th>对象</th>
          <th>校验</th>
          <th>阈值</th>
          <th>最近</th>
          <th>状态</th>
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
          <td colspan="6" style="text-align: center; color: var(--muted)">暂无规则</td>
        </tr>
      </table>
    </div>

    <Modal :visible="modalVisible" title="新建质量规则" @close="modalVisible = false">
      <label>对象表</label>
      <input v-model="form.targetTable" placeholder="如 dwd.order_wide" />
      <label>字段</label>
      <input v-model="form.targetField" placeholder="如 order_id" />
      <label>校验类型</label>
      <select v-model="form.checkType">
        <option value="not_null">非空</option>
        <option value="unique">唯一</option>
        <option value="range">范围</option>
        <option value="fluctuation">波动</option>
      </select>
      <label>阈值</label>
      <input v-model="form.threshold" placeholder="如 100%" />
      <label>异常动作</label>
      <select v-model="form.actionOnFail">
        <option value="alert">告警</option>
        <option value="block_downstream">阻断下游</option>
      </select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" :disabled="submitting" @click="handleSubmit">
          {{ submitting ? '创建中…' : '创建' }}
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
import * as qualityApi from '@/api/quality'
import type { QualityRule, QualitySummary, CheckType, ActionOnFail } from '@/api/quality'
import type { PagedResult } from '@/api/types'

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

/** 校验类型 → 中文 */
function checkTypeLabel(t: CheckType): string {
  const map: Record<CheckType, string> = {
    not_null: '非空',
    unique: '唯一',
    range: '范围',
    fluctuation: '波动',
    regex: '正则',
    sql: 'SQL'
  }
  return map[t] || t
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
      return '通过'
    case 'warn':
      return '告警'
    case 'fail':
      return '失败'
    default:
      return '未运行'
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
    store.showToast('请填写对象表')
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
    store.showToast('规则已创建')
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
