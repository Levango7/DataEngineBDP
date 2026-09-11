<template>
  <div class="lineage-page">
    <PageHeader :title="t('dataLineage.title')" :subtitle="t('dataLineage.subtitle')" />

    <!-- SQL 输入区 -->
    <section class="card sql-input">
      <div class="card-title">{{ t('dataLineage.input.title') }}</div>
      <div class="input-row">
        <el-input
          v-model="sqlText"
          type="textarea"
          :rows="5"
          :placeholder="t('dataLineage.input.placeholder')"
          class="sql-textarea"
        />
      </div>
      <div class="action-row">
        <el-select v-model="dialect" class="dialect-select">
          <el-option :label="t('dataLineage.input.autoDetect')" value="" />
          <el-option label="ANSI" value="ANSI" />
          <el-option label="Hive" value="HIVE" />
          <el-option label="Doris" value="DORIS" />
          <el-option label="Trino" value="TRINO" />
        </el-select>
        <el-button type="primary" :disabled="analyzing" @click="handleAnalyze">
          {{ analyzing ? t('dataLineage.input.analyzing') : t('dataLineage.input.analyze') }}
        </el-button>
        <el-button @click="loadSample">
          {{ t('dataLineage.input.loadSample') }}
        </el-button>
      </div>
      <div v-if="analyzeError" class="error-tip">{{ analyzeError.message }}</div>
    </section>

    <!-- 血缘图谱可视化：三态 loading / error / data -->
    <section v-if="analyzing" class="card graph-card">
      <div class="card-title">{{ t('dataLineage.graph.title') }}</div>
      <div class="state-tip">{{ t('dataLineage.graph.analyzing') }}</div>
    </section>
    <section v-else-if="analyzeError" class="card graph-card">
      <div class="card-title">{{ t('dataLineage.graph.title') }}</div>
      <div class="state-tip error">
        {{ t('dataLineage.graph.loadFailed', { message: analyzeError.message }) }}，
        <a href="javascript:void(0)" @click="handleAnalyze">{{ t('dataLineage.graph.retry') }}</a>
      </div>
    </section>
    <section v-else-if="graph" class="card graph-card">
      <div class="card-title">
        {{ t('dataLineage.graph.title') }}
        <span class="meta-tag">
          {{
            t('dataLineage.graph.meta', {
              nodes: graph.meta.nodeCount,
              edges: graph.meta.edgeCount,
              time: graph.meta.analyzeTimeMs
            })
          }}
        </span>
      </div>
      <div ref="chartRef" class="chart"></div>
    </section>

    <!-- 表级 + 字段级血缘列表：三态 -->
    <section v-if="analyzing" class="card relation-list">
      <div class="card-title">{{ t('dataLineage.relations.title') }}</div>
      <div class="state-tip">{{ t('dataLineage.relations.loading') }}</div>
    </section>
    <section v-else-if="analyzeError" class="card relation-list">
      <div class="card-title">{{ t('dataLineage.relations.title') }}</div>
      <div class="state-tip error">{{ t('dataLineage.relations.loadFailed') }}</div>
    </section>
    <section v-else-if="graph" class="card relation-list">
      <div class="card-title">{{ t('dataLineage.relations.title') }}</div>
      <div class="relation-tabs">
        <el-button
          :type="activeTab === 'table' ? 'primary' : 'default'"
          size="small"
          @click="activeTab = 'table'"
        >
          {{ t('dataLineage.relations.table', { count: tableEdges.length }) }}
        </el-button>
        <el-button
          :type="activeTab === 'column' ? 'primary' : 'default'"
          size="small"
          @click="activeTab = 'column'"
        >
          {{ t('dataLineage.relations.column', { count: columnEdges.length }) }}
        </el-button>
      </div>
      <el-table :data="activeEdges" stripe class="relation-table">
        <el-table-column :label="t('dataLineage.relations.columns.source')">
          <template #default="{ row }">
            <span class="mono">{{ row.source }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('dataLineage.relations.columns.arrow')" align="center">
          <template #default>
            <span class="arrow">{{ t('dataLineage.relations.columns.arrow') }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('dataLineage.relations.columns.target')">
          <template #default="{ row }">
            <span class="mono">{{ row.target }}</span>
          </template>
        </el-table-column>
        <el-table-column
          v-if="activeTab === 'column'"
          :label="t('dataLineage.relations.columns.expression')"
        >
          <template #default="{ row }">
            <span class="mono expr">{{ row.expression || '-' }}</span>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty">{{ t('dataLineage.relations.empty') }}</div>
        </template>
      </el-table>
    </section>

    <!-- 上下游查询：三态 loading / error / data -->
    <section class="card query-card">
      <div class="card-title">{{ t('dataLineage.query.title') }}</div>
      <div class="query-row">
        <el-input
          v-model="queryTable"
          class="table-input"
          :placeholder="t('dataLineage.query.tablePlaceholder')"
        />
        <el-button type="primary" :disabled="querying" @click="handleQuery('upstream')">
          {{ t('dataLineage.query.upstream') }}
        </el-button>
        <el-button type="primary" :disabled="querying" @click="handleQuery('downstream')">
          {{ t('dataLineage.query.downstream') }}
        </el-button>
        <el-button type="warning" :disabled="querying" @click="handleQuery('impact')">
          {{ t('dataLineage.query.impact') }}
        </el-button>
      </div>
      <!-- 三态：loading -->
      <div v-if="querying" class="query-result">
        <div class="state-tip">{{ t('dataLineage.query.querying') }}</div>
      </div>
      <!-- 三态：error -->
      <div v-else-if="queryError" class="query-result">
        <div class="state-tip error">
          {{ t('dataLineage.query.queryFailed', { message: queryError.message }) }}，
          <a href="javascript:void(0)" @click="retryQuery">{{ t('dataLineage.query.retry') }}</a>
        </div>
      </div>
      <!-- 三态：data -->
      <div v-else-if="queryResult" class="query-result">
        <div class="result-summary">
          <span class="badge" :class="queryResult.direction.toLowerCase()">
            {{ directionLabel(queryResult.direction) }}
          </span>
          {{
            t('dataLineage.query.summary', {
              root: queryResult.rootTable,
              count: queryResult.tables.length,
              time: queryResult.queryTimeMs
            })
          }}
        </div>
        <div v-if="queryResult.tables.length > 0" class="result-paths">
          <div class="paths-title">{{ t('dataLineage.query.pathsTitle') }}</div>
          <div v-for="(p, i) in queryResult.paths" :key="i" class="path-item">{{ p }}</div>
        </div>
        <div v-else class="empty">{{ t('dataLineage.query.pathsEmpty') }}</div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onBeforeUnmount } from 'vue'
import { useI18n } from 'vue-i18n'
import * as echarts from 'echarts'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader } from '@/components/ui'
import {
  analyzeLineage,
  getUpstream,
  getDownstream,
  impactAnalysis,
  type LineageGraph,
  type LineageQueryResult,
  type LineageGraphLink
} from '@/api/lineage'

const { t } = useI18n()
const store = useAppStore()

// ECharts 在 canvas 上绘制，不支持 CSS 变量，因此使用固定颜色常量
const COLOR_TABLE_LINEAGE = '#047857' // 表级血缘边色（对应 --ds-color-success-700）
const COLOR_COLUMN_LINEAGE = '#8b5cf6' // 字段级血缘边色（紫色，无对应 token）

// SQL 输入
const sqlText = ref('')
const dialect = ref('')

// 图谱 ECharts 实例
const chartRef = ref<HTMLDivElement>()
let chartInstance: echarts.ECharts | null = null

// 关系明细 Tab
const activeTab = ref<'table' | 'column'>('table')

// 血缘分析：通过 useApi 包装，自动维护 loading / error / data 三态
const {
  data: graph,
  loading: analyzing,
  error: analyzeError,
  execute: executeAnalyze
} = useApi<LineageGraph>(() => analyzeLineage(sqlText.value, dialect.value || undefined), {
  onSuccess: (result) => {
    activeTab.value = 'table'
    // DOM 更新后渲染 ECharts
    nextTick(() => renderChart(result))
    store.showToast(
      t('dataLineage.messages.analyzeCompleted', {
        nodes: result.meta.nodeCount,
        edges: result.meta.edgeCount
      })
    )
  }
})

const tableEdges = computed<LineageGraphLink[]>(
  () => graph.value?.links.filter((l) => l.relationType === 'TABLE_LINEAGE') ?? []
)
const columnEdges = computed<LineageGraphLink[]>(
  () => graph.value?.links.filter((l) => l.relationType === 'COLUMN_LINEAGE') ?? []
)
const activeEdges = computed(() =>
  activeTab.value === 'table' ? tableEdges.value : columnEdges.value
)

// 上下游/影响查询：通过 useApi 包装，自动维护 loading / error / data 三态
type QueryKind = 'upstream' | 'downstream' | 'impact'
const queryTable = ref('')
let lastQueryKind: QueryKind = 'upstream'
const {
  data: queryResult,
  loading: querying,
  error: queryError,
  execute: executeQuery
} = useApi<LineageQueryResult, [kind: QueryKind]>((kind) => {
  lastQueryKind = kind
  if (kind === 'upstream') return getUpstream(queryTable.value)
  if (kind === 'downstream') return getDownstream(queryTable.value)
  return impactAnalysis(queryTable.value)
})

/** 载入示例 SQL */
function loadSample(): void {
  sqlText.value = [
    'INSERT INTO dwd.order_wide (oid, uid, uname, amount)',
    'SELECT a.id, a.uid, b.name, a.amount',
    'FROM ods.orders a JOIN dim.user b ON a.uid = b.id'
  ].join('\n')
  dialect.value = ''
}

/** 执行血缘分析（触发 useApi execute） */
async function handleAnalyze(): Promise<void> {
  if (!sqlText.value.trim()) {
    // 通过临时 error 状态提示；useApi 的 error 在 execute 时会被清空，这里直接用 store 提示
    store.showToast(t('dataLineage.messages.needSql'))
    return
  }
  await executeAnalyze()
}

/** 执行上下游/影响查询（触发 useApi execute） */
async function handleQuery(kind: QueryKind): Promise<void> {
  if (!queryTable.value.trim()) {
    store.showToast(t('dataLineage.messages.needTable'))
    return
  }
  await executeQuery(kind)
}

/** 重试上一次查询 */
async function retryQuery(): Promise<void> {
  await executeQuery(lastQueryKind)
}

/** 渲染 ECharts 关系图 */
function renderChart(g: LineageGraph): void {
  if (!chartRef.value) return
  if (chartInstance) {
    chartInstance.dispose()
  }
  chartInstance = echarts.init(chartRef.value)
  const option: echarts.EChartsCoreOption = {
    tooltip: {
      formatter: (params: unknown) => {
        const p = params as { data?: { name?: string; expression?: string }; dataType?: string }
        if (p.dataType === 'edge') {
          return `${p.data?.name ?? ''}${p.data?.expression ? '<br/>expr: ' + p.data.expression : ''}`
        }
        return p.data?.name ?? ''
      }
    },
    legend: [
      {
        data: g.categories.map((c) => c.name),
        top: 10
      }
    ],
    series: [
      {
        type: 'graph',
        layout: 'force',
        roam: true,
        draggable: true,
        force: {
          repulsion: 200,
          edgeLength: 120,
          gravity: 0.1
        },
        categories: g.categories.map((c) => ({ name: c.name })),
        data: g.nodes.map((n) => ({
          id: n.id,
          name: n.name,
          category: n.category,
          symbolSize: n.nodeType === 'TABLE' ? 36 : 24
        })),
        links: g.links.map((l) => ({
          source: l.source,
          target: l.target,
          expression: l.expression,
          lineStyle: {
            color: l.relationType === 'TABLE_LINEAGE' ? COLOR_TABLE_LINEAGE : COLOR_COLUMN_LINEAGE,
            width: l.relationType === 'TABLE_LINEAGE' ? 2 : 1.5,
            curveness: 0.1
          }
        })),
        edgeSymbol: ['none', 'arrow'],
        edgeSymbolSize: 8,
        label: {
          show: true,
          position: 'right',
          fontSize: 11
        },
        emphasis: {
          focus: 'adjacency',
          lineStyle: { width: 3 }
        }
      }
    ]
  }
  chartInstance.setOption(option)
}

/** 方向标签词条 */
function directionLabel(d: string): string {
  return t(`dataLineage.direction.${d}`)
}

// 响应式 resize
function handleResize(): void {
  chartInstance?.resize()
}
window.addEventListener('resize', handleResize)

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  chartInstance?.dispose()
  chartInstance = null
})
</script>

<style scoped>
.lineage-page {
  padding: 20px 28px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-width: 1200px;
  margin: 0 auto;
}
.card {
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-subtle);
  border-radius: var(--ds-radius-md);
  padding: 16px 20px;
  box-shadow: var(--ds-shadow-sm);
}
.card-title {
  font-size: var(--ds-font-size-base);
  font-weight: var(--ds-font-weight-semibold);
  color: var(--ds-text-primary);
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.meta-tag {
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-normal);
  color: var(--ds-text-tertiary);
  padding: 2px 8px;
  background: var(--ds-bg-subtle);
  border-radius: var(--ds-radius-sm);
}
/* SQL 输入框：覆盖 Element Plus textarea 样式以保持等宽字体 */
.sql-textarea :deep(.el-textarea__inner) {
  font-family: var(--ds-font-family-mono);
  font-size: var(--ds-font-size-sm);
  resize: vertical;
}
.action-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  align-items: center;
}
.dialect-select {
  width: 140px;
}
.error-tip {
  margin-top: 8px;
  color: var(--ds-color-error-600);
  font-size: var(--ds-font-size-xs);
}
.state-tip {
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-tertiary);
  padding: 16px 0;
}
.state-tip.error {
  color: var(--ds-color-error-600);
}
.state-tip a {
  color: var(--ds-color-primary-600);
  cursor: pointer;
}
.chart {
  width: 100%;
  height: 420px;
  border: 1px solid var(--ds-border-subtle);
  border-radius: var(--ds-radius-md);
  background: var(--ds-bg-subtle);
}
.relation-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 10px;
}
.relation-table .mono {
  font-family: var(--ds-font-family-mono);
}
.relation-table .arrow {
  text-align: center;
  color: var(--ds-color-primary-600);
}
.relation-table .expr {
  color: var(--ds-text-tertiary);
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.query-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.table-input {
  flex: 1;
}
.table-input :deep(.el-input__inner) {
  font-family: var(--ds-font-family-mono);
}
.query-result {
  margin-top: 12px;
  padding: 12px;
  background: var(--ds-bg-subtle);
  border-radius: var(--ds-radius-md);
}
.result-summary {
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-primary);
  margin-bottom: 8px;
}
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: var(--ds-radius-sm);
  font-size: 12px;
  font-weight: var(--ds-font-weight-medium);
  margin-right: 6px;
}
.badge.upstream {
  background: var(--ds-color-info-100);
  color: var(--ds-color-info-700);
}
.badge.downstream {
  background: var(--ds-color-success-100);
  color: var(--ds-color-success-700);
}
.badge.impact {
  background: var(--ds-color-warning-100);
  color: var(--ds-color-warning-700);
}
.result-paths {
  margin-top: 6px;
}
.paths-title {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  margin-bottom: 4px;
}
.path-item {
  font-family: var(--ds-font-family-mono);
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-primary);
  padding: 2px 0;
}
.empty {
  color: var(--ds-text-tertiary);
  font-size: var(--ds-font-size-xs);
  padding: 8px 0;
  text-align: center;
}

/* 响应式断点：中等屏幕收窄页面内边距与最大宽度 */
@media (max-width: 1024px) {
  .lineage-page {
    padding: 16px 20px;
    max-width: 100%;
  }
  .action-row {
    flex-wrap: wrap;
  }
  .query-row {
    flex-wrap: wrap;
  }
  .table-input {
    flex: 1 1 100%;
  }
}

/* 响应式断点：小屏幕单列布局，紧凑间距 */
@media (max-width: 640px) {
  .lineage-page {
    padding: 12px;
    gap: 12px;
  }
  .card {
    padding: 12px;
  }
  .chart {
    height: 300px;
  }
  .dialect-select {
    width: 100%;
  }
  .action-row {
    flex-direction: column;
    align-items: stretch;
  }
  .action-row .el-button {
    width: 100%;
  }
}
</style>
