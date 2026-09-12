<template>
  <el-card shadow="never" class="page-card">
    <template v-if="title || $slots.header" #header>
      <div class="page-card__header">
        <div class="page-card__header-text">
          <h2 v-if="title" class="page-card__title">{{ title }}</h2>
          <p v-if="subtitle" class="page-card__subtitle">{{ subtitle }}</p>
        </div>
        <div v-if="$slots['header-actions']" class="page-card__header-actions">
          <slot name="header-actions" />
        </div>
      </div>
    </template>
    <slot />
  </el-card>
</template>

<script setup lang="ts">
defineProps<{
  title?: string
  subtitle?: string
}>()
</script>

<style scoped>
.page-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: var(--ds-radius-md);
}

.page-card__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ds-spacing-3);
}

.page-card__title {
  margin: 0;
  font-size: var(--ds-font-size-lg);
  font-weight: var(--ds-font-weight-semibold);
  color: var(--el-text-color-primary);
}

.page-card__subtitle {
  margin: 2px 0 0;
  font-size: var(--ds-font-size-sm);
  color: var(--el-text-color-secondary);
}

.page-card__header-actions {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: var(--ds-spacing-2);
}
</style>
