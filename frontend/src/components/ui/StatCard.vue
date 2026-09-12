<template>
  <div class="stat-card">
    <div class="stat-card__label">{{ label }}</div>
    <div class="stat-card__value">{{ value }}</div>
    <div v-if="trend" class="stat-card__trend" :class="`stat-card__trend--${trend}`">
      <span class="stat-card__trend-icon">
        {{ trend === 'up' ? '↑' : trend === 'down' ? '↓' : '→' }}
      </span>
      <span v-if="trendValue" class="stat-card__trend-value">{{ trendValue }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  value: string | number
  label: string
  trend?: 'up' | 'down' | 'flat'
  trendValue?: string
}>()
</script>

<style scoped>
.stat-card {
  padding: 16px;
  border-radius: var(--ds-radius-md);
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-subtle);
}

.stat-card__label {
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
  margin-bottom: 4px;
}

.stat-card__value {
  font-size: var(--ds-font-size-4xl);
  font-weight: var(--ds-font-weight-extrabold);
  color: var(--ds-text-primary);
  line-height: 1.2;
}

.stat-card__trend {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-top: 6px;
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-medium);
}

.stat-card__trend--up {
  color: var(--ds-color-success-500);
}

.stat-card__trend--down {
  color: var(--ds-color-error-500);
}

.stat-card__trend--flat {
  color: var(--ds-text-secondary);
}
</style>
