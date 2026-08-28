<!--
  ChartRecommendation.vue — 图表推荐组件（T011）

  功能：
  - 展示后端返回的图表推荐列表（柱/线/饼/散点/地图/面积/雷达）
  - 每项显示：类型图标、推荐理由、得分、维度×度量
  - 点击推荐项触发 select 事件，由父组件切换图表
  - 显示数据特征描述

  事件：
  - select(recommendation) 选中某个推荐
-->
<template>
  <div class="chart-recommendation">
    <div class="rec-header">
      <div class="rec-title">
        <el-icon><DataAnalysis /></el-icon>
        <span>{{ t.title }}</span>
      </div>
      <div v-if="dataProfile" class="data-profile">
        {{ dataProfile }}
      </div>
    </div>

    <div class="rec-list">
      <div
        v-for="rec in recommendations"
        :key="rec.id"
        class="rec-item"
        :class="{ primary: rec.primary, active: selectedId === rec.id }"
        @click="onSelect(rec)"
      >
        <!-- 类型图标 -->
        <div class="rec-icon" :class="`type-${rec.type}`">
          <el-icon :size="22">
            <component :is="chartIcon(rec.type)" />
          </el-icon>
        </div>

        <!-- 信息 -->
        <div class="rec-info">
          <div class="rec-name">
            <span class="rec-type-label">{{ typeLabel(rec.type) }}</span>
            <el-tag v-if="rec.primary" size="small" type="success" effect="light">
              {{ t.recommended }}
            </el-tag>
          </div>
          <div class="rec-reason">{{ pickReason(rec.reason) }}</div>
          <div class="rec-fields">
            <span class="field-group">
              <span class="field-label">{{ t.dimension }}:</span>
              <span class="field-value">{{ rec.dimensions.join(' · ') }}</span>
            </span>
            <span class="field-group">
              <span class="field-label">{{ t.metric }}:</span>
              <span class="field-value">{{ rec.metrics.join(' · ') }}</span>
            </span>
          </div>
        </div>

        <!-- 得分 -->
        <div class="rec-score">
          <el-progress
            type="dashboard"
            :percentage="Math.round(rec.score * 100)"
            :width="48"
            :stroke-width="4"
          />
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElIcon, ElTag, ElProgress } from 'element-plus'
import {
  DataAnalysis,
  Histogram,
  TrendCharts,
  PieChart,
  Aim,
  MapLocation,
  DataLine,
  Compass
} from '@element-plus/icons-vue'
import type { ChartRecommendation, ChartType, Locale, Bilingual } from '@/types/ai-assistant'
import { CHART_TYPE_LABELS } from '@/types/ai-assistant'

interface Props {
  /** 推荐列表 */
  recommendations: ChartRecommendation[]
  /** 数据特征描述 */
  dataProfile?: Bilingual
  /** 语言 */
  locale: Locale
  /** 当前选中 ID */
  selectedId?: string
}
const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'select', recommendation: ChartRecommendation): void
}>()

/* ------------------------------ 文案 ------------------------------ */
const t = computed(() =>
  props.locale === 'zh'
    ? {
        title: '图表推荐',
        recommended: '推荐',
        dimension: '维度',
        metric: '度量'
      }
    : {
        title: 'Chart Recommendation',
        recommended: 'Recommended',
        dimension: 'Dimension',
        metric: 'Metric'
      }
)

const dataProfile = computed(() => {
  if (!props.dataProfile) return ''
  return props.locale === 'zh' ? props.dataProfile.zh : props.dataProfile.en
})

function typeLabel(type: ChartType): string {
  const label = CHART_TYPE_LABELS[type]
  return props.locale === 'zh' ? label.zh : label.en
}

function pickReason(b: Bilingual): string {
  return props.locale === 'zh' ? b.zh : b.en
}

/* ------------------------------ 图标 ------------------------------ */
function chartIcon(type: ChartType) {
  switch (type) {
    case 'bar':
      return Histogram
    case 'line':
      return TrendCharts
    case 'pie':
      return PieChart
    case 'scatter':
      return Compass
    case 'map':
      return MapLocation
    case 'area':
      return DataLine
    case 'radar':
      return Aim
    default:
      return DataAnalysis
  }
}

/* ------------------------------ 事件 ------------------------------ */
function onSelect(rec: ChartRecommendation): void {
  emit('select', rec)
}
</script>

<style scoped>
.chart-recommendation {
  width: 100%;
  background: var(--c-white);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px;
}
.rec-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.rec-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
}
.data-profile {
  font-size: 11px;
  color: var(--muted);
  max-width: 60%;
  text-align: right;
}
.rec-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.rec-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 8px;
  cursor: pointer;
  transition: all 0.15s;
  background: var(--c-white);
}
.rec-item:hover {
  border-color: var(--primary);
  background: var(--primary-soft);
}
.rec-item.primary {
  border-color: var(--green);
  background: var(--c-green-50);
}
.rec-item.active {
  border-color: var(--primary);
  background: var(--primary-soft);
  box-shadow: 0 0 0 2px var(--primary-soft);
}
.rec-icon {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--primary-soft);
  color: var(--primary);
  flex: none;
}
.rec-icon.type-bar {
  background: #eef2ff;
  color: #4f46e5;
}
.rec-icon.type-line {
  background: #ecfdf5;
  color: #16a34a;
}
.rec-icon.type-pie {
  background: #fff7ed;
  color: #ea580c;
}
.rec-icon.type-scatter {
  background: #f0f9ff;
  color: #0284c7;
}
.rec-icon.type-map {
  background: #fef2f2;
  color: #dc2626;
}
.rec-icon.type-area {
  background: #f5f3ff;
  color: #7c3aed;
}
.rec-icon.type-radar {
  background: #fefce8;
  color: #ca8a04;
}

.rec-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}
.rec-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
}
.rec-type-label {
  color: var(--ink);
}
.rec-reason {
  font-size: 12px;
  color: var(--muted);
  line-height: 1.5;
}
.rec-fields {
  display: flex;
  gap: 14px;
  font-size: 11px;
  flex-wrap: wrap;
}
.field-group {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.field-label {
  color: var(--muted);
}
.field-value {
  color: var(--c-slate-700);
  font-weight: 500;
}
.rec-score {
  flex: none;
}
</style>
