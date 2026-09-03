<template>
  <div class="lineage-page">
    <header class="page-header">
      <h1>{{ t('dataLineage.title') }}</h1>
      <p class="sub">{{ t('dataLineage.subtitle') }}</p>
    </header>

    <!-- SQL 输入区 -->
    <section class="card sql-input">
      <div class="card-title">{{ t('dataLineage.input.title') }}</div>
      <div class="input-row">
        <textarea
          v-model="sqlText"
          class="sql-textarea"
          :placeholder="t('dataLineage.input.placeholder')"
          rows="5"
        ></textarea>
      </div>
      <div class="action-row">
        <select v-model="dialect" class="dialect-select">
          <option value="">{{ t('dataLineage.input.autoDetect') }}</option>
          <option value="ANSI">ANSI</option>
          <option value="HIVE">Hive</option>
          <option value="DORIS">Doris</option>
          <option value="TRINO">Trino</option>
        </select>
        <button class="btn-primary" :disabled="analyzing" @click="handleAnalyze">
          {{ analyzing ? t('dataLineage.input.analyzing') : t('dataLineage.input.analyze') }}
        </button>
        <button class="btn-ghost" @click="loadSample">{{ t('dataLineage.input.loadSample') }}</button>
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
          {{ t('dataLineage.graph.meta', { nodes: graph.meta.nodeCount, edges: graph.meta.edgeCount, time: graph.meta.analyzeTimeMs }) }}
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
        <button :class="['tab', { active: activeTab === 'table' }]" @click="activeTab = 'table'">
          {{ t('dataLineage.relations.table', { count: tableEdges.length }) }}
        </button>
        <button :class="['tab', { active: activeTab === 'column' }]" @click="activeTab = 'column'">
          {{ t('dataLineage.relations.column', { count: columnEdges.length }) }}
        </button>
      </div>
      <table class="relation-table">
        <thead>
          <tr>
            <th>{{ t('dataLineage.relations.columns.source') }}</th>
            <th>{{ t('dataLineage.relations.columns.arrow') }}</th>
            <th>{{ t('dataLineage.relations.columns.target') }}</th>
            <th v-if="activeTab === 'column'">{{ t('dataLineage.relations.columns.expression') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(edge, i) in activeEdges" :key="i">
            <td class="mono">{{ edge.source }}</td>
            <td class="arrow">{{ t('dataLineage.relations.columns.arrow') }}</td>
            <td class="mono">{{ edge.target }}</td>
            <td v-if="activeTab === 'column'" class="mono expr">{{ edge.expression || '-' }}</td>
          </tr>
          <tr v-if="activeEdges.length === 0">
            <td :colspan="activeTab === 'column' ? 4 : 3" class="empty">{{ t('dataLineage.relations.empty') }}</td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 上下游查询：三态 loading / error / data -->
    <section class="card query-card">
      <div class="card-title">{{ t('dataLineage.query.title') }}</div>
      <div class="query-row">
        <input v-model="queryTable" class="table-input" :placeholder="t('dataLineage.query.tablePlaceholder')" />
        <button class="btn-secondary" :disabled="querying" @click="handleQuery('upstream')">
          {{ t('dataLineage.query.upstream') }}
        </button>
        <button class="btn-secondary" :disabled="querying" @click="handleQuery('downstream')">
          {{ t('dataLineage.query.downstream') }}
        </button>
        <button class="btn-warn" :disabled="querying" @click="handleQuery('impact')">
          {{ t('dataLineage.query.impact') }}
        </button>
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
          {{ t('dataLineage.query.summary', { root: queryResult.rootTable, count: queryResult.tables.length, time: queryResult.queryTimeMs }) }}
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
    store.showToast(t('dataLineage.messages.analyzeCompleted', { nodes: result.meta.nodeCount, edges: result.meta.edgeCount }))
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
            color: l.relationType === 'TABLE_LINEAGE' ? 'var(--ds-color-success-700)' : '#8b5cf6',
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
.page-header h1 {
  font-size: 20px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 4px;
}
.page-header .sub {
  font-size: 13px;
  color: var(--muted);
}
.card {
  background: var(--c-white);
  border: 1px solid var(--line);
  border-radius: var(--radius);
  padding: 16px 20px;
  box-shadow: var(--shadow);
}
.card-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--ink);
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.meta-tag {
  font-size: 12px;
  font-weight: 400;
  color: var(--muted);
  padding: 2px 8px;
  background: var(--c-surface-alt);
  border-radius: 4px;
}
.sql-textarea {
  width: 100%;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  resize: vertical;
  outline: none;
  color: var(--ink);
  background: var(--c-white);
}
.sql-textarea:focus {
  border-color: var(--primary);
}
.action-row {
  display: flex;
  gap: 8px;
  margin-top: 10px;
  align-items: center;
}
.dialect-select {
  padding: 6px 10px;
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 13px;
  background: var(--c-white);
  color: var(--ink);
  outline: none;
}
.btn-primary,
.btn-secondary,
.btn-warn,
.btn-ghost {
  padding: 6px 16px;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s;
}
.btn-primary {
  background: var(--primary);
  color: var(--c-white);
}
.btn-primary:hover:not(:disabled) {
  background: #265a55;
}
.btn-secondary {
  background: var(--primary-soft);
  color: var(--primary);
  border-color: var(--primary);
}
.btn-secondary:hover:not(:disabled) {
  background: var(--primary);
  color: var(--c-white);
}
.btn-warn {
  background: #fff5e9;
  color: var(--amber);
  border-color: var(--amber);
}
.btn-warn:hover:not(:disabled) {
  background: var(--amber);
  color: var(--c-white);
}
.btn-ghost {
  background: transparent;
  color: var(--muted);
  border-color: var(--line);
}
.btn-ghost:hover {
  background: var(--c-surface-alt);
}
.btn-primary:disabled,
.btn-secondary:disabled,
.btn-warn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.error-tip {
  margin-top: 8px;
  color: var(--red);
  font-size: 12px;
}
.state-tip {
  font-size: 13px;
  color: var(--muted);
  padding: 16px 0;
}
.state-tip.error {
  color: var(--red);
}
.state-tip a {
  color: var(--primary);
  cursor: pointer;
}
.chart {
  width: 100%;
  height: 420px;
  border: 1px solid var(--line);
  border-radius: 6px;
  background: #fafbfc;
}
.relation-tabs {
  display: flex;
  gap: 4px;
  margin-bottom: 10px;
}
.tab {
  padding: 4px 12px;
  border: 1px solid var(--line);
  background: var(--c-white);
  border-radius: 6px;
  font-size: 12px;
  cursor: pointer;
  color: var(--muted);
}
.tab.active {
  background: var(--primary);
  color: var(--c-white);
  border-color: var(--primary);
}
.relation-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.relation-table th {
  text-align: left;
  padding: 6px 10px;
  background: var(--c-surface-alt);
  color: var(--muted);
  font-weight: 500;
  border-bottom: 1px solid var(--line);
}
.relation-table td {
  padding: 6px 10px;
  border-bottom: 1px solid var(--line);
  color: var(--ink);
}
.relation-table .mono {
  font-family: 'Consolas', 'Monaco', monospace;
}
.relation-table .arrow {
  text-align: center;
  color: var(--primary);
}
.relation-table .expr {
  color: var(--muted);
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.relation-table .empty {
  text-align: center;
  color: var(--muted);
  padding: 16px;
}
.query-row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.table-input {
  flex: 1;
  padding: 6px 12px;
  border: 1px solid var(--line);
  border-radius: 6px;
  font-size: 13px;
  font-family: 'Consolas', 'Monaco', monospace;
  outline: none;
}
.table-input:focus {
  border-color: var(--primary);
}
.query-result {
  margin-top: 12px;
  padding: 12px;
  background: var(--c-surface-alt);
  border-radius: 6px;
}
.result-summary {
  font-size: 13px;
  color: var(--ink);
  margin-bottom: 8px;
}
.badge {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  margin-right: 6px;
}
.badge.upstream {
  background: #e3ebee;
  color: #4a6a72;
}
.badge.downstream {
  background: var(--c-green-50);
  color: var(--green);
}
.badge.impact {
  background: var(--c-amber-50);
  color: var(--amber);
}
.result-paths {
  margin-top: 6px;
}
.paths-title {
  font-size: 12px;
  color: var(--muted);
  margin-bottom: 4px;
}
.path-item {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: var(--ink);
  padding: 2px 0;
}
.empty {
  color: var(--muted);
  font-size: 12px;
  padding: 8px 0;
}
</style>
