<!--
  SearchFilter.vue — 多维度过滤器

  四个维度：
  1. 时间：预设区间（今天/昨天/近7天/近30天/近90天）+ 自定义范围
  2. 来源：数据源多选（候选项来自 facets）
  3. 类型：资产类型多选
  4. 标签：标签多选

  事件：
  - change(filter) 过滤器变化
  - reset 重置
-->
<template>
  <div class="search-filter">
    <div class="filter-header">
      <h3>过滤条件</h3>
      <el-button link type="primary" @click="emitReset">重置</el-button>
    </div>

    <!-- ① 时间维度 -->
    <div class="filter-section">
      <div class="section-title">
        <el-icon><Calendar /></el-icon>
        <span>时间</span>
      </div>
      <el-radio-group v-model="local.time.preset" @change="emitChange">
        <el-radio-button value="today">今天</el-radio-button>
        <el-radio-button value="yesterday">昨天</el-radio-button>
        <el-radio-button value="last7d">近 7 天</el-radio-button>
        <el-radio-button value="last30d">近 30 天</el-radio-button>
        <el-radio-button value="last90d">近 90 天</el-radio-button>
        <el-radio-button value="custom">自定义</el-radio-button>
      </el-radio-group>

      <div v-if="local.time.preset === 'custom'" class="custom-time">
        <el-date-picker
          v-model="customRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          format="YYYY-MM-DD HH:mm"
          value-format="YYYY-MM-DDTHH:mm:ss"
          style="width: 100%; margin-top: 8px"
          @change="onCustomTimeChange"
        />
      </div>
    </div>

    <!-- ② 来源维度 -->
    <div class="filter-section">
      <div class="section-title">
        <el-icon><Connection /></el-icon>
        <span>数据源</span>
      </div>
      <el-select
        v-model="local.sources"
        multiple
        collapse-tags
        collapse-tags-tooltip
        placeholder="选择数据源"
        style="width: 100%"
        @change="emitChange"
      >
        <el-option
          v-for="opt in sourceOptions"
          :key="opt.value"
          :label="opt.count !== undefined ? `${opt.label} (${opt.count})` : opt.label"
          :value="opt.value"
        />
      </el-select>
    </div>

    <!-- ③ 类型维度 -->
    <div class="filter-section">
      <div class="section-title">
        <el-icon><Files /></el-icon>
        <span>类型</span>
      </div>
      <el-checkbox-group v-model="local.types" @change="emitChange">
        <el-checkbox
          v-for="opt in typeOptions"
          :key="opt.value"
          :value="opt.value"
          class="filter-checkbox"
        >
          {{ opt.label }}
          <span v-if="opt.count !== undefined" class="opt-count">{{ opt.count }}</span>
        </el-checkbox>
      </el-checkbox-group>
    </div>

    <!-- ④ 标签维度 -->
    <div class="filter-section">
      <div class="section-title">
        <el-icon><PriceTag /></el-icon>
        <span>标签</span>
      </div>
      <div v-if="tagOptions.length === 0" class="empty-tags">暂无标签候选</div>
      <div v-else class="tag-cloud">
        <span
          v-for="opt in tagOptions"
          :key="opt.value"
          class="tag-chip"
          :class="{ active: local.tags.includes(opt.value) }"
          @click="toggleTag(opt.value)"
        >
          {{ opt.label }}
          <span v-if="opt.count !== undefined" class="chip-count">{{ opt.count }}</span>
        </span>
      </div>
    </div>

    <!-- 已选条件摘要 -->
    <div v-if="hasActiveFilter" class="filter-summary">
      <span class="summary-label">已选：</span>
      <span class="summary-count">{{ activeCount }}</span>
      <span class="summary-text">个条件</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import {
  ElButton,
  ElIcon,
  ElRadioGroup,
  ElRadioButton,
  ElSelect,
  ElOption,
  ElCheckboxGroup,
  ElCheckbox,
  ElDatePicker
} from 'element-plus'
import { Calendar, Connection, Files, PriceTag } from '@element-plus/icons-vue'
import type { SearchFilter, FilterFacets, FilterOption, TimePreset } from '@/types/search'
import { EMPTY_FILTER } from '@/types/search'

/* ------------------------------ Props / Emits ------------------------------ */
interface Props {
  /** 当前过滤器（v-model） */
  modelValue?: SearchFilter
  /** 候选项 */
  facets?: FilterFacets | null
}

const props = withDefaults(defineProps<Props>(), {
  modelValue: () => ({ ...EMPTY_FILTER, time: { ...EMPTY_FILTER.time } }),
  facets: null
})

const emit = defineEmits<{
  (e: 'update:modelValue', filter: SearchFilter): void
  (e: 'change', filter: SearchFilter): void
  (e: 'reset'): void
}>()

/* ------------------------------ 本地状态 ------------------------------ */
const local = reactive<SearchFilter>({
  time: { preset: '', from: undefined, to: undefined },
  sources: [],
  types: [],
  tags: []
})

const customRange = ref<[string, string] | null>(null)

/** 同步 props → local */
watch(
  () => props.modelValue,
  (val) => {
    local.time.preset = val.time.preset
    local.time.from = val.time.from
    local.time.to = val.time.to
    local.sources = [...val.sources]
    local.types = [...val.types]
    local.tags = [...val.tags]
    if (val.time.preset === 'custom' && val.time.from && val.time.to) {
      customRange.value = [val.time.from, val.time.to]
    }
  },
  { immediate: true, deep: true }
)

/* ------------------------------ 候选项 ------------------------------ */
/** 默认数据源候选（facets 未加载时使用） */
const DEFAULT_SOURCE_OPTIONS: FilterOption[] = [
  { value: 'mysql', label: 'MySQL' },
  { value: 'postgresql', label: 'PostgreSQL' },
  { value: 'clickhouse', label: 'ClickHouse' },
  { value: 'hive', label: 'Hive' },
  { value: 'kafka', label: 'Kafka' },
  { value: 'doris', label: 'Doris' }
]

const sourceOptions = computed<FilterOption[]>(() => {
  return props.facets?.sources?.length ? props.facets.sources : DEFAULT_SOURCE_OPTIONS
})

const typeOptions = computed<FilterOption[]>(() => {
  return (
    props.facets?.types ?? [
      { value: 'table', label: '数据集' },
      { value: 'view', label: '视图' },
      { value: 'api', label: '数据服务' },
      { value: 'model', label: '数据模型' },
      { value: 'dashboard', label: '仪表盘' },
      { value: 'stream', label: '实时流' },
      { value: 'job', label: '作业' },
      { value: 'notebook', label: '笔记本' },
      { value: 'metric', label: '指标' },
      { value: 'document', label: '文档' }
    ]
  )
})

const tagOptions = computed<FilterOption[]>(() => props.facets?.tags ?? [])

/* ------------------------------ 事件 ------------------------------ */
function onCustomTimeChange(val: [string, string] | null): void {
  if (val) {
    local.time.from = val[0]
    local.time.to = val[1]
  } else {
    local.time.from = undefined
    local.time.to = undefined
  }
  emitChange()
}

function toggleTag(value: string): void {
  const idx = local.tags.indexOf(value)
  if (idx >= 0) {
    local.tags.splice(idx, 1)
  } else {
    local.tags.push(value)
  }
  emitChange()
}

function emitChange(): void {
  const filter: SearchFilter = {
    time: {
      preset: local.time.preset as TimePreset,
      from: local.time.from,
      to: local.time.to
    },
    sources: [...local.sources],
    types: [...local.types],
    tags: [...local.tags]
  }
  emit('update:modelValue', filter)
  emit('change', filter)
}

function emitReset(): void {
  local.time.preset = ''
  local.time.from = undefined
  local.time.to = undefined
  local.sources = []
  local.types = []
  local.tags = []
  customRange.value = null
  emitChange()
  emit('reset')
}

/* ------------------------------ 摘要 ------------------------------ */
const hasActiveFilter = computed(() => activeCount.value > 0)

const activeCount = computed(() => {
  let n = 0
  if (local.time.preset !== '') n++
  if (local.sources.length > 0) n++
  if (local.types.length > 0) n++
  if (local.tags.length > 0) n++
  return n
})
</script>

<style scoped>
.search-filter {
  background: var(--panel, #fff);
  border: 1px solid var(--line, #e4e8ea);
  border-radius: 10px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.filter-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.filter-header h3 {
  font-size: 14px;
  font-weight: 700;
  margin: 0;
}
.filter-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink, #232a2e);
}
.custom-time {
  margin-top: 4px;
}
.filter-checkbox {
  margin-right: 12px;
  margin-bottom: 4px;
}
.opt-count {
  color: var(--muted, #717a80);
  font-size: 11px;
  margin-left: 4px;
}
.empty-tags {
  color: var(--muted, #717a80);
  font-size: 12px;
}
.tag-cloud {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 3px 10px;
  border: 1px solid var(--line, #e4e8ea);
  border-radius: 14px;
  font-size: 12px;
  cursor: pointer;
  background: #fff;
  transition: all 0.15s;
}
.tag-chip:hover {
  border-color: var(--primary, #2f6f6a);
  color: var(--primary, #2f6f6a);
}
.tag-chip.active {
  background: var(--primary-soft, #e9f1f0);
  border-color: var(--primary, #2f6f6a);
  color: var(--primary, #2f6f6a);
  font-weight: 600;
}
.chip-count {
  color: var(--muted, #717a80);
  font-size: 10px;
}
.filter-summary {
  padding-top: 8px;
  border-top: 1px dashed var(--line, #e4e8ea);
  font-size: 12px;
  color: var(--muted, #717a80);
  display: flex;
  align-items: center;
  gap: 4px;
}
.summary-count {
  color: var(--primary, #2f6f6a);
  font-weight: 700;
}
</style>