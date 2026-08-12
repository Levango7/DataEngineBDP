<!--
  SearchInput.vue — 检索输入框（自然语言 + 结构化）

  功能：
  - 双模式切换：自然语言 / 结构化查询
  - 自然语言模式：单行输入 + 实时建议下拉
  - 结构化模式：字段 / 操作符 / 值 三段式条件构建器
  - 支持回车检索、清空、防抖
  - 历史记录快查（可选）

  事件：
  - search(text, conditions, mode) 触发检索
  - clear 清空
-->
<template>
  <div class="search-input-wrap">
    <!-- 模式切换 -->
    <div class="mode-switch">
      <button
        :class="['btn', 'sm', mode === 'natural' ? '' : 'ghost']"
        @click="switchMode('natural')"
      >
        自然语言
      </button>
      <button
        :class="['btn', 'sm', mode === 'structured' ? '' : 'ghost']"
        @click="switchMode('structured')"
      >
        结构化查询
      </button>
    </div>

    <!-- 自然语言输入 -->
    <div v-if="mode === 'natural'" class="natural-input">
      <el-input
        v-model="naturalText"
        placeholder="用自然语言描述要找的数据，如：最近 7 天风控线用户行为日志"
        size="large"
        clearable
        @keyup.enter="emitSearch"
        @input="onNaturalInput"
        @clear="emitClear"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
        <template #append>
          <el-button :loading="loading" type="primary" @click="emitSearch">检索</el-button>
        </template>
      </el-input>

      <!-- 实时建议 -->
      <div v-if="suggestions.length > 0" class="suggestions">
        <div
          v-for="s in suggestions"
          :key="s"
          class="suggestion-item"
          @mousedown.prevent="applySuggestion(s)"
        >
          <el-icon><MagicStick /></el-icon>
          <span>{{ s }}</span>
        </div>
      </div>
    </div>

    <!-- 结构化查询构建器 -->
    <div v-else class="structured-input">
      <div
        v-for="(cond, idx) in conditions"
        :key="idx"
        class="condition-row"
      >
        <el-select v-model="cond.field" placeholder="字段" style="width: 160px" @change="emitSearch">
          <el-option
            v-for="f in fieldOptions"
            :key="f.value"
            :label="f.label"
            :value="f.value"
          />
        </el-select>

        <el-select v-model="cond.op" placeholder="操作" style="width: 110px" @change="emitSearch">
          <el-option
            v-for="o in opOptions"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>

        <el-input
          v-model="cond.valueText"
          placeholder="值（多个用逗号分隔）"
          style="flex: 1"
          @keyup.enter="emitSearch"
        />

        <el-button :icon="Delete" circle text @click="removeCondition(idx)" />
      </div>

      <div class="condition-actions">
        <el-button size="small" :icon="Plus" @click="addCondition">添加条件</el-button>
        <el-button
          size="small"
          type="primary"
          :loading="loading"
          :disabled="conditions.length === 0"
          @click="emitSearch"
        >
          检索
        </el-button>
        <el-button size="small" @click="emitClear">清空</el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onUnmounted } from 'vue'
import { ElInput, ElSelect, ElOption, ElButton, ElIcon } from 'element-plus'
import { Search, MagicStick, Plus, Delete } from '@element-plus/icons-vue'
import type { SearchMode, StructuredCondition } from '@/types/search'
import * as searchApi from '@/api/search'

/* ------------------------------ Props / Emits ------------------------------ */
interface Props {
  /** 当前模式 */
  modelMode?: SearchMode
  /** 加载状态 */
  loading?: boolean
  /** 是否启用建议 */
  enableSuggest?: boolean
}

const props = withDefaults(defineProps<Props>(), {
  modelMode: 'natural',
  loading: false,
  enableSuggest: true
})

const emit = defineEmits<{
  /** 触发检索 */
  (e: 'search', payload: { text: string; conditions: StructuredCondition[]; mode: SearchMode }): void
  /** 清空 */
  (e: 'clear'): void
  /** 模式切换 */
  (e: 'mode-change', mode: SearchMode): void
}>()

/* ------------------------------ 状态 ------------------------------ */
const mode = ref<SearchMode>(props.modelMode)
const naturalText = ref('')
const suggestions = ref<string[]>([])

/** 字段选项（与后端 schema 对齐） */
const fieldOptions = [
  { label: '名称', value: 'name' },
  { label: '描述', value: 'description' },
  { label: '数据源', value: 'sourceId' },
  { label: '类型', value: 'type' },
  { label: '负责人', value: 'owner' },
  { label: '标签', value: 'tags' },
  { label: '创建时间', value: 'createdAt' },
  { label: '更新时间', value: 'updatedAt' }
]

/** 操作符选项 */
const opOptions = [
  { label: '等于', value: 'eq' },
  { label: '不等于', value: 'ne' },
  { label: '包含于', value: 'in' },
  { label: '不包含于', value: 'not_in' },
  { label: '大于', value: 'gt' },
  { label: '大于等于', value: 'gte' },
  { label: '小于', value: 'lt' },
  { label: '小于等于', value: 'lte' },
  { label: '包含', value: 'contains' },
  { label: '存在', value: 'exists' }
]

/** 结构化条件（带文本值，emit 时转换为 StructuredCondition） */
interface CondRow {
  field: string
  op: StructuredCondition['op']
  valueText: string
}
const conditions = ref<CondRow[]>([{ field: 'name', op: 'contains', valueText: '' }])

/* ------------------------------ 建议防抖 ------------------------------ */
let suggestTimer: ReturnType<typeof setTimeout> | null = null

async function fetchSuggestions(keyword: string): Promise<void> {
  if (!props.enableSuggest || keyword.trim().length < 2) {
    suggestions.value = []
    return
  }
  try {
    suggestions.value = await searchApi.suggest(keyword)
  } catch {
    suggestions.value = []
  }
}

function onNaturalInput(val: string): void {
  if (suggestTimer !== null) clearTimeout(suggestTimer)
  suggestTimer = setTimeout(() => {
    void fetchSuggestions(val)
  }, 250)
}

function applySuggestion(s: string): void {
  naturalText.value = s
  suggestions.value = []
  emitSearch()
}

/* ------------------------------ 结构化条件操作 ------------------------------ */
function addCondition(): void {
  conditions.value.push({ field: 'name', op: 'contains', valueText: '' })
}

function removeCondition(idx: number): void {
  if (conditions.value.length <= 1) {
    conditions.value[0].valueText = ''
    return
  }
  conditions.value.splice(idx, 1)
  emitSearch()
}

/** 将 CondRow[] 转换为 StructuredCondition[] */
function toStructuredConditions(): StructuredCondition[] {
  return conditions.value
    .filter((c) => c.valueText.trim().length > 0 || c.op === 'exists')
    .map((c) => {
      const isMulti = c.op === 'in' || c.op === 'not_in'
      const value: string | string[] = isMulti
        ? c.valueText.split(',').map((s) => s.trim()).filter(Boolean)
        : c.valueText
      return {
        field: c.field,
        op: c.op,
        value
      }
    })
}

/* ------------------------------ 事件 ------------------------------ */
function emitSearch(): void {
  suggestions.value = []
  if (mode.value === 'natural') {
    emit('search', { text: naturalText.value, conditions: [], mode: 'natural' })
  } else {
    emit('search', { text: '', conditions: toStructuredConditions(), mode: 'structured' })
  }
}

function emitClear(): void {
  naturalText.value = ''
  conditions.value = [{ field: 'name', op: 'contains', valueText: '' }]
  suggestions.value = []
  emit('clear')
}

function switchMode(m: SearchMode): void {
  if (m === mode.value) return
  mode.value = m
  emit('mode-change', m)
}

/* ------------------------------ 同步外部 props ------------------------------ */
watch(
  () => props.modelMode,
  (m) => {
    mode.value = m
  }
)

onUnmounted(() => {
  if (suggestTimer !== null) clearTimeout(suggestTimer)
})
</script>

<style scoped>
.search-input-wrap {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.mode-switch {
  display: flex;
  gap: 8px;
}
.natural-input {
  position: relative;
}
.suggestions {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin-top: 4px;
  background: #fff;
  border: 1px solid var(--line);
  border-radius: 8px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  z-index: 10;
  max-height: 240px;
  overflow-y: auto;
}
.suggestion-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  cursor: pointer;
  font-size: 13px;
}
.suggestion-item:hover {
  background: var(--c-surface-hover);
}
.structured-input {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.condition-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.condition-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
</style>