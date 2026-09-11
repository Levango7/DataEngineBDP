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
      <h3>{{ t('searchPortal.search.filter.title') }}</h3>
      <el-button link type="primary" @click="emitReset">
        {{ t('searchPortal.search.filter.reset') }}
      </el-button>
    </div>

    <!-- ① 时间维度 -->
    <div class="filter-section">
      <div class="section-title">
        <el-icon><Calendar /></el-icon>
        <span>{{ t('searchPortal.search.filter.time') }}</span>
      </div>
      <el-radio-group v-model="local.time.preset" @change="emitChange">
        <el-radio-button value="today">{{ t('searchPortal.search.filter.today') }}</el-radio-button>
        <el-radio-button value="yesterday">
          {{ t('searchPortal.search.filter.yesterday') }}
        </el-radio-button>
        <el-radio-button value="last7d">
          {{ t('searchPortal.search.filter.last7d') }}
        </el-radio-button>
        <el-radio-button value="last30d">
          {{ t('searchPortal.search.filter.last30d') }}
        </el-radio-button>
        <el-radio-button value="last90d">
          {{ t('searchPortal.search.filter.last90d') }}
        </el-radio-button>
        <el-radio-button value="custom">
          {{ t('searchPortal.search.filter.custom') }}
        </el-radio-button>
      </el-radio-group>

      <div v-if="local.time.preset === 'custom'" class="custom-time">
        <el-date-picker
          v-model="customRange"
          type="datetimerange"
          :range-separator="t('searchPortal.search.filter.rangeSep')"
          :start-placeholder="t('searchPortal.search.filter.startPh')"
          :end-placeholder="t('searchPortal.search.filter.endPh')"
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
        <span>{{ t('searchPortal.search.filter.source') }}</span>
      </div>
      <el-select
        v-model="local.sources"
        multiple
        collapse-tags
        collapse-tags-tooltip
        :placeholder="t('searchPortal.search.filter.sourcePh')"
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
        <span>{{ t('searchPortal.search.filter.type') }}</span>
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
        <span>{{ t('searchPortal.search.filter.tag') }}</span>
      </div>
      <div v-if="tagOptions.length === 0" class="empty-tags">
        {{ t('searchPortal.search.filter.noTags') }}
      </div>
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
      <span class="summary-label">{{ t('searchPortal.search.filter.selectedLabel') }}</span>
      <span class="summary-count">{{ activeCount }}</span>
      <span class="summary-text">{{ t('searchPortal.search.filter.conditionsFmt') }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch } from 'vue'
import { useI18n } from 'vue-i18n'
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

const { t } = useI18n()

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
      { value: 'table', label: t('searchPortal.search.typeOptions.table') },
      { value: 'view', label: t('searchPortal.search.typeOptions.view') },
      { value: 'api', label: t('searchPortal.search.typeOptions.api') },
      { value: 'model', label: t('searchPortal.search.typeOptions.model') },
      { value: 'dashboard', label: t('searchPortal.search.typeOptions.dashboard') },
      { value: 'stream', label: t('searchPortal.search.typeOptions.stream') },
      { value: 'job', label: t('searchPortal.search.typeOptions.job') },
      { value: 'notebook', label: t('searchPortal.search.typeOptions.notebook') },
      { value: 'metric', label: t('searchPortal.search.typeOptions.metric') },
      { value: 'document', label: t('searchPortal.search.typeOptions.document') }
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
const activeCount = computed(() => {
  let n = 0
  if (local.time.preset !== '') n++
  if (local.sources.length > 0) n++
  if (local.types.length > 0) n++
  if (local.tags.length > 0) n++
  return n
})

const hasActiveFilter = computed(() => activeCount.value > 0)
</script>

<style scoped>
.search-filter {
  background: var(--panel, #fff);
  border: 1px solid var(--line, var(--ds-border-default));
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
  font-size: 14px;
  font-weight: 600;
  color: var(--ink, var(--ds-text-primary));
}
.custom-time {
  margin-top: 4px;
}
.filter-checkbox {
  margin-right: 12px;
  margin-bottom: 4px;
}
.opt-count {
  color: var(--muted, var(--ds-text-secondary));
  font-size: 12px;
  margin-left: 4px;
}
.empty-tags {
  color: var(--muted, var(--ds-text-secondary));
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
  border: 1px solid var(--line, var(--ds-border-default));
  border-radius: 14px;
  font-size: 12px;
  cursor: pointer;
  background: var(--ds-bg-surface);
  transition: all 0.15s;
}
.tag-chip:hover {
  border-color: var(--primary, var(--ds-color-success-700));
  color: var(--primary, var(--ds-color-success-700));
}
.tag-chip.active {
  background: var(--primary-soft, #e9f1f0);
  border-color: var(--primary, var(--ds-color-success-700));
  color: var(--primary, var(--ds-color-success-700));
  font-weight: 600;
}
.chip-count {
  color: var(--muted, var(--ds-text-secondary));
  font-size: 12px;
}
.filter-summary {
  padding-top: 8px;
  border-top: 1px dashed var(--line, var(--ds-border-default));
  font-size: 12px;
  color: var(--muted, var(--ds-text-secondary));
  display: flex;
  align-items: center;
  gap: 4px;
}
.summary-count {
  color: var(--primary, var(--ds-color-success-700));
  font-weight: 700;
}
</style>
