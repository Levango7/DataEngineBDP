<!--
  DataSummary.vue — 数据解读摘要组件（T011）

  功能：
  - 渲染自然语言摘要正文
  - 渲染关键洞察列表（要点）
  - 渲染关键指标卡片（含同比 / 趋势）
  - 显示生成耗时与命中行/列数

  说明：
  - 简化模式：仅展示 summary 文本与 meta（用于卡片内嵌）
  - 完整模式：展示 insights + metrics（由父组件传入完整 SummarizeResponse）
-->
<template>
  <div class="data-summary">
    <!-- 头部 -->
    <div class="summary-header">
      <div class="summary-title">
        <el-icon><DocumentChecked /></el-icon>
        <span>{{ t.title }}</span>
      </div>
      <div v-if="meta" class="summary-meta">
        <span>{{ meta.rowCount }} {{ t.rows }}</span>
        <span>·</span>
        <span>{{ meta.columnCount }} {{ t.cols }}</span>
        <span>·</span>
        <span>{{ meta.durationMs }} ms</span>
      </div>
    </div>

    <!-- 摘要正文 -->
    <div v-if="summary" class="summary-text">
      <el-icon class="quote-icon"><ChatLineSquare /></el-icon>
      <span>{{ summary }}</span>
    </div>

    <!-- 关键洞察 -->
    <div v-if="insights.length > 0" class="summary-insights">
      <div class="insights-title">{{ t.insights }}</div>
      <ul class="insights-list">
        <li v-for="(ins, idx) in insights" :key="idx" class="insight-item">
          <el-icon class="insight-bullet"><Right /></el-icon>
          <span>{{ pickBilingual(ins) }}</span>
        </li>
      </ul>
    </div>

    <!-- 关键指标 -->
    <div v-if="metrics.length > 0" class="summary-metrics">
      <div
        v-for="(m, idx) in metrics"
        :key="idx"
        class="metric-card"
      >
        <div class="metric-label">{{ pickBilingual(m.label) }}</div>
        <div class="metric-value">
          {{ formatMetric(m.value) }}<span v-if="m.unit" class="metric-unit">{{ m.unit }}</span>
        </div>
        <div v-if="m.change !== undefined" class="metric-change" :class="changeClass(m)">
          <el-icon><component :is="changeIcon(m)" /></el-icon>
          <span>{{ Math.abs(m.change).toFixed(1) }}%</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ElIcon } from 'element-plus'
import {
  DocumentChecked,
  ChatLineSquare,
  Right,
  ArrowUp,
  ArrowDown,
  Minus
} from '@element-plus/icons-vue'
import type {
  SummaryMeta,
  SummaryMetric,
  Bilingual,
  Locale
} from '@/types/ai-assistant'

interface Props {
  /** 摘要正文（已按语言取过） */
  summary?: string
  /** 元信息 */
  meta?: SummaryMeta
  /** 关键洞察（双语） */
  insights?: Bilingual[]
  /** 关键指标 */
  metrics?: SummaryMetric[]
  /** 语言 */
  locale: Locale
}
const props = withDefaults(defineProps<Props>(), {
  summary: '',
  insights: () => [],
  metrics: () => []
})

const t = computed(() =>
  props.locale === 'zh'
    ? { title: '数据解读', rows: '行', cols: '列', insights: '关键洞察' }
    : { title: 'Data Insights', rows: 'rows', cols: 'cols', insights: 'Key Insights' }
)

function pickBilingual(b: Bilingual): string {
  return props.locale === 'zh' ? b.zh : b.en
}

function formatMetric(value: number): string {
  if (Math.abs(value) >= 1_000_000) return (value / 1_000_000).toFixed(2) + 'M'
  if (Math.abs(value) >= 1_000) return (value / 1_000).toFixed(2) + 'K'
  return value.toLocaleString(props.locale === 'zh' ? 'zh-CN' : 'en-US')
}

function changeClass(m: SummaryMetric): string {
  if (m.trend === 'up') return 'change-up'
  if (m.trend === 'down') return 'change-down'
  if (m.change === undefined) return ''
  return m.change > 0 ? 'change-up' : m.change < 0 ? 'change-down' : 'change-flat'
}

function changeIcon(m: SummaryMetric) {
  if (m.trend === 'up' || (m.change ?? 0) > 0) return ArrowUp
  if (m.trend === 'down' || (m.change ?? 0) < 0) return ArrowDown
  return Minus
}
</script>

<style scoped>
.data-summary {
  width: 100%;
  background: var(--c-white);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px 14px;
}
.summary-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.summary-title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
}
.summary-meta {
  font-size: 11px;
  color: var(--muted);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.summary-text {
  display: flex;
  gap: 8px;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--c-slate-700);
  background: var(--c-surface-hover);
  border-radius: 8px;
  padding: 12px 14px;
  margin-bottom: 10px;
}
.quote-icon {
  color: var(--primary);
  flex: none;
  margin-top: 3px;
}
.summary-insights {
  margin-bottom: 10px;
}
.insights-title {
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 6px;
  font-weight: 600;
}
.insights-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.insight-item {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  font-size: 12.5px;
  color: var(--c-slate-700);
  line-height: 1.6;
}
.insight-bullet {
  color: var(--green);
  flex: none;
  margin-top: 3px;
}
.summary-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(140px, 1fr));
  gap: 8px;
}
.metric-card {
  background: var(--c-surface-hover);
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.metric-label {
  font-size: 11px;
  color: var(--muted);
}
.metric-value {
  font-size: 18px;
  font-weight: 700;
  color: var(--ink);
  font-variant-numeric: tabular-nums;
}
.metric-unit {
  font-size: 11px;
  font-weight: 500;
  color: var(--muted);
  margin-left: 2px;
}
.metric-change {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 11px;
  font-weight: 600;
}
.change-up {
  color: var(--green);
}
.change-down {
  color: var(--red);
}
.change-flat {
  color: var(--muted);
}
</style>