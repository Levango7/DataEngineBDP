<template>
  <el-tag :type="tagType" :effect="effect" :size="size">
    {{ displayLabel }}
  </el-tag>
</template>

<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(
  defineProps<{
    status: string
    label?: string
    statusMap?: Record<string, 'primary' | 'success' | 'info' | 'warning' | 'danger'>
    effect?: 'dark' | 'light' | 'plain'
    size?: 'large' | 'default' | 'small'
  }>(),
  {
    statusMap: () => ({}),
    effect: 'light',
    size: 'default'
  }
)

const tagType = computed(() => {
  return props.statusMap[props.status] ?? 'info'
})

const displayLabel = computed(() => {
  return props.label ?? props.status
})
</script>
