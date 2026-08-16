<template>
  <div class="eng-doris-page">
    <h1>OLAP（Doris）</h1>
    <div class="sub">MPP 引擎 · FE/BE 节点 · 查询负载 · 15 秒自动刷新</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
    <div class="grid g4">
      <template v-if="nodesLoading">
        <div class="card" v-for="i in 4" :key="i">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="nodesError">
        <div class="card" style="grid-column: span 4">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            {{ nodesError.message }}，<a href="javascript:void(0)" @click="reloadNodes">重试</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>FE 节点</h3>
          <div class="kpi">{{ kpi.feCount }}</div>
          <div class="meta">存活 {{ kpi.feAlive }} · 异常 {{ kpi.feDead }}</div>
        </div>
        <div class="card">
          <h3>BE 节点</h3>
          <div class="kpi">{{ kpi.beCount }}</div>
          <div class="meta">存活 {{ kpi.beAlive }} · 异常 {{ kpi.beDead }}</div>
        </div>
        <div class="card">
          <h3>今日查询数</h3>
          <div class="kpi s">{{ queries?.length ?? 0 }}</div>
          <div class="meta">查询记录</div>
        </div>
        <div class="card">
          <h3>平均查询时长</h3>
          <div class="kpi">{{ formatDuration(kpi.avgDurationMs) }}</div>
          <div class="meta">基于全部查询</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：左右分栏 -->
    <div class="split-layout">
      <!-- 左：数据库/表目录树 -->
      <el-card shadow="never" class="page-card tree-card">
        <template #header>
          <span>数据库 / 表目录</span>
        </template>
        <el-tree
          :data="catalogTree"
          :props="{ label: 'label', children: 'children' }"
          :load="loadCatalogNode"
          lazy
          node-key="id"
          @node-click="handleNodeClick"
        />
      </el-card>

      <!-- 右：Tab [节点状态 | 查询列表 | SQL 工作台] -->
      <el-card shadow="never" class="page-card main-card">
        <div class="toolbar">
          <el-tabs v-model="activeTab" type="card" class="main-tabs">
            <el-tab-pane label="节点状态" name="nodes" />
            <el-tab-pane label="查询列表" name="queries" />
            <el-tab-pane label="SQL 工作台" name="sql" />
          </el-tabs>
          <div class="spacer"></div>
          <el-button :icon="Refresh" circle @click="reloadAll" />
        </div>

        <!-- Tab1 节点状态 -->
        <template v-if="activeTab === 'nodes'">
          <el-table
            v-loading="nodesLoading"
            :data="nodes ?? []"
            stripe
            border
            style="width: 100%"
            :empty-text="nodesError ? '加载失败，请重试' : '暂无节点'"
          >
            <el-table-column label="节点" min-width="180">
              <template #default="{ row }">{{ row.host }}:{{ row.port }}</template>
            </el-table-column>
            <el-table-column label="角色" width="100">
              <template #default="{ row }">
                <el-tag :type="row.role === 'FE' ? 'primary' : 'success'" effect="light" size="small">
                  {{ row.role }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="nodeStatusTagType(row.status)" effect="light" size="small">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="CPU" width="160">
              <template #default="{ row }">
                <el-progress
                  :percentage="Math.min(row.cpuUsage, 100)"
                  :stroke-width="12"
                  :text-inside="true"
                />
              </template>
            </el-table-column>
            <el-table-column label="内存" width="160">
              <template #default="{ row }">
                <el-progress
                  :percentage="Math.min(row.memUsage, 100)"
                  :stroke-width="12"
                  :text-inside="true"
                />
              </template>
            </el-table-column>
            <el-table-column label="磁盘" width="160">
              <template #default="{ row }">
                <el-progress
                  v-if="row.diskUsage != null"
                  :percentage="Math.min(row.diskUsage, 100)"
                  :stroke-width="12"
                  :text-inside="true"
                />
                <span v-else>--</span>
              </template>
            </el-table-column>
          </el-table>
        </template>

        <!-- Tab2 查询列表 -->
        <template v-else-if="activeTab === 'queries'">
          <el-table
            v-loading="queriesLoading"
            :data="queries ?? []"
            stripe
            border
            style="width: 100%"
            :empty-text="queriesError ? '加载失败，请重试' : '暂无查询记录'"
          >
            <el-table-column prop="queryId" label="QueryId" min-width="180" />
            <el-table-column prop="sqlSummary" label="SQL 摘要" min-width="240" show-overflow-tooltip />
            <el-table-column prop="user" label="用户" width="120" />
            <el-table-column prop="database" label="数据库" width="140" />
            <el-table-column label="时长" width="120">
              <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
            </el-table-column>
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="queryStatusTagType(row.status)" effect="light" size="small">
                  {{ row.status }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="startTime" label="开始时间" width="180" />
          </el-table>
        </template>

        <!-- Tab3 SQL 工作台 -->
        <template v-else>
          <div class="sql-workbench">
            <el-input
              v-model="sqlText"
              type="textarea"
              :rows="8"
              placeholder="输入 Doris SQL，如 SELECT * FROM db.table LIMIT 100"
              style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
            />
            <div class="sql-actions">
              <el-button type="primary" :loading="executing" @click="handleExecuteSql">执行</el-button>
              <el-button :loading="explaining" @click="handleExplainSql">执行计划</el-button>
              <el-button @click="sqlText = ''">清空</el-button>
            </div>
            <div v-if="sqlResult" class="sql-result">
              <div class="result-meta">
                共 {{ sqlResult.rowCount }} 行，耗时 {{ sqlResult.durationMs }} ms
              </div>
              <el-table
                :data="sqlResult.rows"
                stripe
                border
                size="small"
                style="width: 100%"
                max-height="360"
              >
                <el-table-column
                  v-for="(col, idx) in sqlResult.columns"
                  :key="idx"
                  :label="col"
                  min-width="120"
                >
                  <template #default="{ row }">{{ row[idx] }}</template>
                </el-table-column>
              </el-table>
            </div>
            <div v-if="explainResult" class="sql-result">
              <div class="result-meta">执行计划</div>
              <pre class="explain-content">{{ explainResult.plan ?? explainResult.error ?? '无' }}</pre>
            </div>
          </div>
        </template>
      </el-card>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as engineApi from '@/api/engine'
import type { DorisNode, DorisQuery, SqlExecuteResponse, SqlExplainResponse } from '@/api/engine'

/* ------------------------------ 数据加载 ------------------------------ */

const activeTab = ref<'nodes' | 'queries' | 'sql'>('nodes')

const {
  data: nodes,
  loading: nodesLoading,
  error: nodesError,
  execute: reloadNodes
} = useApi<DorisNode[]>(() => engineApi.getDorisNodes())

const {
  data: queries,
  loading: queriesLoading,
  error: queriesError,
  execute: reloadQueries
} = useApi<DorisQuery[]>(() => engineApi.getDorisQueries())

const { data: databases, execute: loadDatabases } = useApi<string[]>(() =>
  engineApi.getDorisDatabases()
)

/** KPI 聚合 */
const kpi = computed(() => {
  const list = nodes.value ?? []
  const feList = list.filter((n) => n.role === 'FE')
  const beList = list.filter((n) => n.role === 'BE')
  const qList = queries.value ?? []
  const avgDurationMs = qList.length
    ? qList.reduce((s, q) => s + q.durationMs, 0) / qList.length
    : 0
  return {
    feCount: feList.length,
    feAlive: feList.filter((n) => n.status === 'alive').length,
    feDead: feList.filter((n) => n.status !== 'alive').length,
    beCount: beList.length,
    beAlive: beList.filter((n) => n.status === 'alive').length,
    beDead: beList.filter((n) => n.status !== 'alive').length,
    avgDurationMs
  }
})

/** 重新加载所有 */
async function reloadAll() {
  await Promise.all([reloadNodes(), reloadQueries(), loadDatabases()])
}

/* ------------------------------ 目录树 ------------------------------ */

interface CatalogNode {
  id: string
  label: string
  type: 'db' | 'table'
  children?: CatalogNode[]
  leaf?: boolean
}

/** 目录树根节点 */
const catalogTree = computed<CatalogNode[]>(() => {
  return (databases.value ?? []).map((db) => ({
    id: `db:${db}`,
    label: db,
    type: 'db' as const,
    leaf: false
  }))
})

/** 懒加载子节点 */
async function loadCatalogNode(
  node: { data: CatalogNode },
  resolve: (data: CatalogNode[]) => void
) {
  if (node.data.type === 'db') {
    const dbName = node.data.label
    try {
      const tables = await engineApi.getDorisTables(dbName)
      resolve(
        tables.map((t) => ({
          id: `table:${dbName}:${t}`,
          label: t,
          type: 'table' as const,
          leaf: true
        }))
      )
    } catch {
      resolve([])
    }
  } else {
    resolve([])
  }
}

/** 节点点击 */
function handleNodeClick(node: CatalogNode) {
  if (node.type === 'table') {
    // 切到 SQL 工作台并预填 SELECT
    const parts = node.id.split(':')
    const dbName = parts[1]
    const tableName = parts[2]
    activeTab.value = 'sql'
    sqlText.value = `SELECT * FROM ${dbName}.${tableName} LIMIT 100;`
  }
}

/* ------------------------------ SQL 工作台 ------------------------------ */

const sqlText = ref<string>('SELECT 1;')
const executing = ref(false)
const explaining = ref(false)
const sqlResult = ref<SqlExecuteResponse | null>(null)
const explainResult = ref<SqlExplainResponse | null>(null)

/** 执行 SQL */
async function handleExecuteSql() {
  if (!sqlText.value.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  executing.value = true
  sqlResult.value = null
  explainResult.value = null
  try {
    sqlResult.value = await engineApi.executeDorisSql(sqlText.value)
  } catch {
    // 拦截器已提示
  } finally {
    executing.value = false
  }
}

/** 执行计划 */
async function handleExplainSql() {
  if (!sqlText.value.trim()) {
    ElMessage.warning('请输入 SQL')
    return
  }
  explaining.value = true
  sqlResult.value = null
  explainResult.value = null
  try {
    explainResult.value = await engineApi.explainDorisSql(sqlText.value)
  } catch {
    // 拦截器已提示
  } finally {
    explaining.value = false
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 节点状态 → tag 类型 */
function nodeStatusTagType(status: string): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 'alive') return 'success'
  if (status === 'dead') return 'danger'
  if (status === 'decommission') return 'warning'
  return 'info'
}

/** 查询状态 → tag 类型 */
function queryStatusTagType(status: string): 'success' | 'danger' | 'warning' | 'info' | 'primary' {
  const s = status.toUpperCase()
  if (s === 'FINISHED') return 'success'
  if (s === 'FAILED') return 'danger'
  if (s === 'RUNNING') return 'primary'
  if (s === 'CANCELED') return 'info'
  return 'info'
}

/** 耗时格式化（毫秒） */
function formatDuration(ms?: number): string {
  if (!ms && ms !== 0) return '--'
  const seconds = Math.floor(ms / 1000)
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return `${h}h ${m}m`
}

/* ------------------------------ 生命周期 ------------------------------ */

let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void reloadAll()
  // 15s 轮询刷新
  timer = setInterval(() => void reloadAll(), 15000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.eng-doris-page {
  padding: 0;
}
.sub {
  color: #717a80;
  font-size: 13px;
  margin-bottom: 16px;
}
.grid {
  display: grid;
  gap: 14px;
}
.grid.g4 {
  grid-template-columns: repeat(4, 1fr);
}
@media (max-width: 1100px) {
  .grid.g4 {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 720px) {
  .grid.g4 {
    grid-template-columns: 1fr;
  }
}
.card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
  padding: 16px;
  background: #fff;
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: #717a80;
  margin: 0 0 8px;
}
.kpi {
  font-size: 28px;
  font-weight: 700;
  color: #232a2e;
  line-height: 1.2;
}
.kpi.s {
  color: #2f9e6f;
}
.kpi.d {
  color: #c0504d;
}
.meta {
  font-size: 12px;
  color: #717a80;
  margin-top: 6px;
}
.page-card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
}
.split-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 14px;
  margin-top: 16px;
}
@media (max-width: 1100px) {
  .split-layout {
    grid-template-columns: 1fr;
  }
}
.tree-card {
  height: fit-content;
}
.main-card {
  min-width: 0;
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.toolbar .spacer {
  flex: 1;
}
.main-tabs {
  margin-left: 0;
}
.sql-workbench {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sql-actions {
  display: flex;
  gap: 8px;
}
.sql-result {
  border: 1px solid #e4e8ea;
  border-radius: 8px;
  padding: 12px;
  background: #fff;
}
.result-meta {
  color: #717a80;
  font-size: 12px;
  margin-bottom: 8px;
}
.explain-content {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  color: #232a2e;
}
</style>