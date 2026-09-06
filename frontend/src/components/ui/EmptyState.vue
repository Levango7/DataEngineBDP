<template>
  <div class="empty-state">
    <component :is="icon" v-if="icon" class="empty-state__icon" />
    <p class="empty-state__message">{{ message || 'No data available' }}</p>
    <el-button v-if="actionLabel" type="primary" @click="$emit('action')">
      {{ actionLabel }}
    </el-button>
  </div>
</template>

<script setup lang="ts">
import type { Component } from 'vue'

defineProps<{
  message?: string
  icon?: Component
  actionLabel?: string
}>()

defineEmits<{
  action: []
}>()
</script>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 48px 16px;
  text-align: center;
}

.empty-state__icon {
  width: 48px;
  height: 48px;
  margin-bottom: 12px;
  color: var(--el-text-color-placeholder);
}

.empty-state__message {
  margin: 0 0 16px;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
</style>
