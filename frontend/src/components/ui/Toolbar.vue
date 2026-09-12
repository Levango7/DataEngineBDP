<template>
  <div class="toolbar" role="toolbar" :aria-label="ariaLabel">
    <el-button
      v-if="showCreate"
      type="primary"
      :aria-label="createAriaLabel || createLabel || t('common.create')"
      @click="$emit('create')"
    >
      {{ createLabel || t('common.create') }}
    </el-button>

    <el-input
      v-if="searchPlaceholder"
      :model-value="searchValue"
      :placeholder="searchPlaceholder"
      clearable
      style="width: 220px"
      :aria-label="searchAriaLabel"
      @update:model-value="$emit('update:searchValue', $event)"
      @keyup.enter="$emit('search')"
      @clear="$emit('search')"
    />

    <slot name="filters" />

    <div class="spacer"></div>

    <slot name="actions" />

    <el-button
      v-if="showRefresh"
      :icon="Refresh"
      circle
      :aria-label="refreshAriaLabel"
      @click="$emit('refresh')"
    />
  </div>
</template>

<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()

withDefaults(
  defineProps<{
    showCreate?: boolean
    createLabel?: string
    createAriaLabel?: string
    searchPlaceholder?: string
    searchValue?: string
    searchAriaLabel?: string
    showRefresh?: boolean
    refreshAriaLabel?: string
    ariaLabel?: string
  }>(),
  {
    showCreate: false,
    createLabel: undefined,
    showRefresh: true
  }
)

defineEmits<{
  create: []
  search: []
  refresh: []
  'update:searchValue': [value: string]
}>()
</script>

<style scoped>
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.toolbar .spacer {
  flex: 1;
}
</style>
