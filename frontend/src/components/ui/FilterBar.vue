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
      {{ clearLabel || 'Clear' }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
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

const props = defineProps<{
  filters: FilterConfig[]
  modelValue: Record<string, any>
  searchPlaceholder?: string
  showClear?: boolean
  clearLabel?: string
}>()

const emit = defineEmits<{
  'update:modelValue': [value: Record<string, any>]
  search: []
  clear: []
}>()

function onFilterChange(key: string, value: any) {
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
