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
  border-radius: 8px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
}

.stat-card__label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.stat-card__value {
  font-size: 28px;
  font-weight: 700;
  color: var(--el-text-color-primary);
  line-height: 1.2;
}

.stat-card__trend {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  margin-top: 6px;
  font-size: 12px;
  font-weight: 500;
}

.stat-card__trend--up {
  color: var(--el-color-success);
}

.stat-card__trend--down {
  color: var(--el-color-danger);
}

.stat-card__trend--flat {
  color: var(--el-text-color-secondary);
}
</style>
