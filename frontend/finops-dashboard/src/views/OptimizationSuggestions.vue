<template>
  <div class="page">
    <h2>优化建议</h2>
    <TimeWindowPicker @query="handleQuery" />
    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header>优化建议汇总</template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="建议数量">{{ suggestions.length }}</el-descriptions-item>
        <el-descriptions-item label="估算月度总节约（元）">{{ totalSaving.toFixed(2) }}</el-descriptions-item>
        <el-descriptions-item label="涉及资源数">{{ totalResources }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
    <el-row :gutter="16">
      <el-col v-for="s in suggestions" :key="s.id" :span="12" style="margin-bottom: 16px">
        <el-card shadow="hover">
          <template #header>
            <div class="suggestion-header">
              <span class="title">{{ s.title }}</span>
              <el-tag :type="riskTagType(s.riskLevel)" size="small">{{ s.riskLevel }}</el-tag>
            </div>
          </template>
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="闲置模式">{{ getLabel(s.pattern) }}</el-descriptions-item>
            <el-descriptions-item label="动作类型">{{ s.actionType }}</el-descriptions-item>
            <el-descriptions-item label="涉及资源数">{{ s.resourceCount }}</el-descriptions-item>
            <el-descriptions-item label="月度节约（元）">{{ s.estimatedMonthlySaving.toFixed(2) }}</el-descriptions-item>
          </el-descriptions>
          <p class="description">{{ s.description }}</p>
          <div class="resource-ids">
            <el-tag v-for="rid in s.resourceIds.slice(0, 5)" :key="rid" size="small" style="margin: 2px">
              {{ rid }}
            </el-tag>
            <span v-if="s.resourceIds.length > 5" class="more">等 {{ s.resourceIds.length }} 个</span>
          </div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import TimeWindowPicker from '@/components/TimeWindowPicker.vue'
import { getSuggestions } from '@/api/finops'
import type { OptimizationSuggestion, IdlePattern } from '@/types'
import { IDLE_PATTERN_LABELS } from '@/types'

const suggestions = ref<OptimizationSuggestion[]>([])

const totalSaving = computed(() =>
  suggestions.value.reduce((sum, s) => sum + s.estimatedMonthlySaving, 0)
)

const totalResources = computed(() =>
  suggestions.value.reduce((sum, s) => sum + s.resourceCount, 0)
)

function getLabel(pattern: IdlePattern): string {
  return IDLE_PATTERN_LABELS[pattern]
}

function riskTagType(level: string): 'success' | 'warning' | 'danger' {
  if (level === 'LOW') return 'success'
  if (level === 'MEDIUM') return 'warning'
  return 'danger'
}

async function handleQuery(params: { start: string; end: string; namespace?: string }) {
  try {
    const resp = await getSuggestions(params)
    suggestions.value = resp.items
  } catch (e) {
    console.error('查优化建议失败', e)
  }
}
</script>

<style scoped>
.page h2 {
  margin-top: 0;
}
.suggestion-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.suggestion-header .title {
  font-weight: bold;
}
.description {
  margin: 12px 0 8px;
  color: #606266;
  font-size: 13px;
}
.resource-ids {
  margin-top: 8px;
}
.more {
  color: #909399;
  font-size: 12px;
  margin-left: 4px;
}
</style>