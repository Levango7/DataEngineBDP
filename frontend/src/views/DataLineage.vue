<template>
  <div class="lineage-page">
    <header class="page-header">
      <h1>数据血缘分析</h1>
      <p class="sub">基于 SQL AST 提取表级 + 字段级血缘，支持上下游查询与影响分析</p>
    </header>

    <!-- SQL 输入区 -->
    <section class="card sql-input">
      <div class="card-title">SQL 血缘分析</div>
      <div class="input-row">
        <textarea
          v-model="sqlText"
          class="sql-textarea"
          placeholder="输入 SQL，例如：INSERT INTO dwd.wide (oid, uname) SELECT a.id, b.name FROM ods.orders a JOIN dim.user b ON a.uid = b.id"
          rows="5"
        ></textarea>
      </div>
      <div class="action-row">
        <select v-model="dialect" class="dialect-select">
          <option value="">自动检测</option>
          <option value="ANSI">ANSI</option>
          <option value="HIVE">Hive</option>
          <option value="DORIS">Doris</option>
          <option value="TRINO">Trino</option>
        </select>
        <button class="btn-primary" :disabled="analyzing" @click="handleAnalyze">
          {{ analyzing ? '分析中…' : '分析血缘' }}
        </button>
        <button class="btn-ghost" @click="loadSample">载入示例</button>
      </div>
      <div v-if="analyzeError" class="error-tip">{{ analyzeError }}</div>
    </section>

    <!-- 血缘图谱可视化 -->
    <section v-if="graph" class="card graph-card">
      <div class="card-title">
        血缘图谱
        <span class="meta-tag">{{ graph.meta.nodeCount }} 节点 · {{ graph.meta.edgeCount }} 边 · {{ graph.meta.analyzeTimeMs }}ms</span>
      </div>
      <div ref="chartRef" class="chart"></div>
    </section>

    <!-- 表级 + 字段级血缘列表 -->
    <section v-if="graph" class="card relation-list">
      <div class="card-title">血缘关系明细</div>
      <div class="relation-tabs">
        <button
          :class="['tab', { active: activeTab === 'table' }]"
          @click="activeTab = 'table'"
        >表级 ({{ tableEdges.length }})</button>
        <button
          :class="['tab', { active: activeTab === 'column' }]"
          @click="activeTab = 'column'"
        >字段级 ({{ columnEdges.length }})</button>
      </div>
      <table class="relation-table">
        <thead>
          <tr>
            <th>源</th>
            <th>→</th>
            <th>目标</th>
            <th v-if="activeTab === 'column'">表达式</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(edge, i) in activeEdges" :key="i">
            <td class="mono">{{ edge.source }}</td>
            <td class="arrow">→</td>
            <td class="mono">{{ edge.target }}</td>
            <td v-if="activeTab === 'column'" class="mono expr">{{ edge.expression || '-' }}</td>
          </tr>
          <tr v-if="activeEdges.length === 0">
            <td :colspan="activeTab === 'column' ? 4 : 3" class="empty">无血缘关系</td>
          </tr>
        </tbody>
      </table>
    </section>

    <!-- 上下游查询 -->
    <section class="card query-card">
      <div class="card-title">上下游查询 & 影响分析</div>
      <div class="query-row">
        <input
          v-model="queryTable"
          class="table-input"
          placeholder="输入表全名，例如 dwd.wide"
        />
        <button class="btn-secondary" :disabled="querying" @click="handleQuery('upstream')">上游</button>
        <button class="btn-secondary" :disabled="querying" @click="handleQuery('downstream')">下游</button>
        <button class="btn-warn" :disabled="querying" @click="handleQuery('impact')">影响分析</button>
      </div>
      <div v-if="queryResult" class="query-result">
        <div class="result-summary">
          <span class="badge" :class="queryResult.direction.toLowerCase()">
            {{ directionLabel(queryResult.direction) }}
          </span>
          从 <strong>{{ queryResult.rootTable }}</strong> 出发，命中
          <strong>{{ queryResult.tables.length }}</strong> 张表，耗时 {{ queryResult.queryTimeMs }}ms
        </div>
        <div v-if="queryResult.tables.length > 0" class="result-paths">
          <div class="paths-title">路径：</div>
          <div v-for="(p, i) in queryResult.paths" :key="i" class="path-item">{{ p }}</div>
        </div>
        <div v-else class="empty">无相关表</div>
      </div>
    </section>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import { useAppStore } from '@/stores/app'
import {
  analyzeLineage,
  getUpstream,
  getDownstream,
  impactAnalysis,
  type LineageGraph,
  type LineageQueryResult,
  type LineageGraphLink
} from '@/api/lineage'

const store = useAppStore()

// SQL 输入
const sqlText = ref('')
const dialect = ref('')
const analyzing = ref(false)
const analyzeError = ref('')
const graph = ref<LineageGraph | null>(null)

// 图谱 ECharts 实例
const chartRef = ref<HTMLDivElement>()
let chartInstance: echarts.ECharts | null = null

// 关系明细 Tab
const activeTab = ref<'table' | 'column'>('table')
const tableEdges = computed<LineageGraphLink[]>(() =>
  graph.value?.links.filter((l) => l.relationType === 'TABLE_LINEAGE') ?? []
)
const columnEdges = computed<LineageGraphLink[]>(() =>
  graph.value?.links.filter((l) => l.relationType === 'COLUMN_LINEAGE') ?? []
)
const activeEdges = computed(() => (activeTab.value === 'table' ? tableEdges.value : columnEdges.value))

// 查询
const queryTable = ref('')
const querying = ref(false)
const queryResult = ref<LineageQueryResult | null>(null)

/** 载入示例 SQL */
function loadSample(): void {
  sqlText.value = [
    'INSERT INTO dwd.order_wide (oid, uid, uname, amount)',
    'SELECT a.id, a.uid, b.name, a.amount',
    'FROM ods.orders a JOIN dim.user b ON a.uid = b.id'
  ].join('\n')
  dialect.value = ''
}

/** 执行血缘分析 */
async function handleAnalyze(): Promise<void> {
  if (!sqlText.value.trim()) {
    analyzeError.value = '请输入 SQL'
    return
  }
  analyzing.value = true
  analyzeError.value = ''
  try {
    const result = await analyzeLineage(sqlText.value, dialect.value || undefined)
    graph.value = result
    activeTab.value = 'table'
    await nextTick()
    renderChart(result)
    store.showToast(`血缘分析完成：${result.meta.nodeCount} 节点 / ${result.meta.edgeCount} 边`)
  } catch (e: unknown) {
    analyzeError.value = (e as Error).message || '分析失败'
  } finally {
    analyzing.value = false
  }
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
            color: l.relationType === 'TABLE_LINEAGE' ? '#2f6f6a' : '#8b5cf6',
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

/** 执行上下游/影响查询 */
async function handleQuery(kind: 'upstream' | 'downstream' | 'impact'): Promise<void> {
  if (!queryTable.value.trim()) {
    store.showToast('请输入表名')
    return
  }
  querying.value = true
  queryResult.value = null
  try {
    if (kind === 'upstream') {
      queryResult.value = await getUpstream(queryTable.value)
    } else if (kind === 'downstream') {
      queryResult.value = await getDownstream(queryTable.value)
    } else {
      queryResult.value = await impactAnalysis(queryTable.value)
    }
  } catch (e: unknown) {
    store.showToast((e as Error).message || '查询失败')
  } finally {
    querying.value = false
  }
}

/** 方向标签中文 */
function directionLabel(d: string): string {
  return { UPSTREAM: '上游', DOWNSTREAM: '下游', IMPACT: '影响分析' }[d] ?? d
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