<template>
  <div class="filter-bar">
    <template v-for="filter in filters" :key="filter.key">
      <el-select
        :model-value="modelValue[filter.key]"
        :placeholder="filter.placeholder"
        clearable
        :style="{ width: filter.width || '160px' }"
        @update:model-value="onFilterChange(filter.key, $event)"
      >
        <el-option
          v-for="opt in filter.options"
          :key="opt.value"
          :label="opt.label"
          :value="opt.value"
        />
      </el-select>
    </template>

    <el-input
      v-if="searchPlaceholder"
      :model-value="modelValue._search"
      :placeholder="searchPlaceholder"
      clearable
      style="width: 220px"
      @update:model-value="onFilterChange('_search', $event)"
      @keyup.enter="$emit('search')"
      @clear="$emit('search')"
    />

    <el-button v-if="showClear" link type="primary" @click="$emit('clear')">
      {{ clearLabel || t('common.clear') }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

export interface FilterOption {
  label: string
  value: string | number
}

export interface FilterConfig {
  key: string
  placeholder: string
  options: FilterOption[]
  width?: string
}

/** 过滤器值类型：支持字符串、数字、以及 undefined（未设置/清空） */
export type FilterValue = string | number | undefined

/** 过滤器模型：键为过滤器 key 或 '_search'，值为当前选中值 */
export type FilterModel = Record<string, FilterValue>

const props = defineProps<{
  filters: FilterConfig[]
  modelValue: FilterModel
  searchPlaceholder?: string
  showClear?: boolean
  clearLabel?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: FilterModel]
  search: []
  clear: []
}>()

function onFilterChange(key: string, value: FilterValue): void {
  emit('update:modelValue', { ...props.modelValue, [key]: value })
}
</script>

<style scoped>
.filter-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
</style>
