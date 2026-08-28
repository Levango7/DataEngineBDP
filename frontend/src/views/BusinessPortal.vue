<template>
  <div>
    <h1>业务线门户</h1>
    <div class="sub">
      L5.4 · 以"业务线-团队-项目"组织视图复用平台能力，免计费或内部结算（成本×0.3），不承诺
      SLA，资源受部门预算软约束。
      <span class="pill b">多业务线隔离</span>
      <span class="pill p">数据隔离</span>
      <span class="pill g">权限隔离</span>
    </div>

    <div class="bp-layout">
      <!-- 左侧：业务线选择侧边栏 -->
      <aside class="bp-sidebar">
        <div class="bp-sidebar-header">
          <h3>业务线</h3>
          <button class="btn sm" @click="openCreateModal">+ 新建</button>
        </div>
        <div class="bp-sidebar-list">
          <template v-if="blLoading">
            <div class="bp-sidebar-item" v-for="i in 3" :key="`bl-s-${i}`">
              <b>加载中…</b>
            </div>
          </template>
          <template v-else-if="blError">
            <div class="bp-sidebar-item">
              <span style="color: var(--muted)">{{ blError.message }}</span>
              <a href="javascript:void(0)" @click="reloadBl">重试</a>
            </div>
          </template>
          <template v-else-if="businessLines && businessLines.length === 0">
            <div class="bp-sidebar-item">
              <span style="color: var(--muted)">暂无业务线</span>
            </div>
          </template>
          <template v-else-if="businessLines">
            <div
              v-for="bl in businessLines"
              :key="bl.id"
              class="bp-sidebar-item"
              :class="{ on: bl.id === currentBlId }"
              @click="selectBl(bl.id)"
            >
              <div class="row">
                <b>{{ bl.name }}</b>
                <span class="pill" :class="statusClass(bl.status)">
                  {{ statusText(bl.status) }}
                </span>
              </div>
              <div class="meta">
                预算 {{ bl.budget.used.toFixed(0) }}/{{ bl.budget.total.toFixed(0) }} 元
              </div>
              <div class="bar">
                <i :style="{ width: usageRatio(bl.budget) * 100 + '%' }"></i>
              </div>
            </div>
          </template>
        </div>
      </aside>

      <!-- 右侧：业务线内容区 -->
      <main class="bp-main">
        <template v-if="!currentBlId">
          <div class="card">
            <h3>请选择业务线</h3>
            <div class="meta" style="color: var(--muted)">
              从左侧选择一条业务线，查看数据概览、工作台、数据目录与 BI 报表。
            </div>
          </div>
        </template>
        <template v-else>
          <!-- Tab 切换 -->
          <div class="tabbar">
            <div class="t" :class="{ on: tab === 'dashboard' }" @click="tab = 'dashboard'">
              数据概览
            </div>
            <div class="t" :class="{ on: tab === 'workbench' }" @click="tab = 'workbench'">
              工作台
            </div>
            <div class="t" :class="{ on: tab === 'catalog' }" @click="tab = 'catalog'">
              数据目录
            </div>
            <div class="t" :class="{ on: tab === 'reports' }" @click="tab = 'reports'">BI 报表</div>
          </div>

          <!-- ① 数据概览 -->
          <div v-if="tab === 'dashboard'">
            <template v-if="dashboardLoading">
              <div class="card"><h3>加载中…</h3></div>
            </template>
            <template v-else-if="dashboardError">
              <div class="card">
                <h3>加载失败</h3>
                <div class="meta" style="color: var(--muted)">
                  {{ dashboardError.message }}，
                  <a href="javascript:void(0)" @click="reloadDashboard">重试</a>
                </div>
              </div>
            </template>
            <template v-else-if="dashboard">
              <!-- KPI 卡片 -->
              <div class="grid g4">
                <div class="card" v-for="kpi in dashboard.kpis" :key="kpi.key">
                  <h3>{{ kpi.label }}</h3>
                  <div class="kpi">
                    {{ kpi.value }}
                    <span class="unit">{{ kpi.unit }}</span>
                  </div>
                  <div class="meta">
                    环比
                    <span :style="{ color: kpi.trend >= 0 ? 'var(--ok)' : 'var(--danger)' }">
                      {{ kpi.trend >= 0 ? '+' : '' }}{{ kpi.trend }}%
                    </span>
                  </div>
                </div>
              </div>

              <!-- 趋势图 -->
              <div class="grid g3" style="margin-top: 14px">
                <div class="card" v-for="trend in dashboard.trends" :key="trend.key">
                  <h3>{{ trend.label }}</h3>
                  <div class="mini">
                    <i
                      v-for="(h, idx) in trend.bars"
                      :key="`tr-${idx}`"
                      :style="{ height: h + '%' }"
                    ></i>
                  </div>
                  <div class="meta">近 7 日 · 单位 {{ trend.unit }}</div>
                </div>
              </div>

              <!-- 实时监控 + TopN 项目 -->
              <div class="grid g2" style="margin-top: 14px">
                <div class="card">
                  <h3>实时监控</h3>
                  <table>
                    <thead>
                      <tr>
                        <th>指标</th>
                        <th>当前</th>
                        <th>阈值</th>
                        <th>状态</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="m in dashboard.realtime" :key="m.key">
                        <td>{{ m.label }}</td>
                        <td>{{ m.value }}{{ m.unit }}</td>
                        <td>{{ m.threshold ?? '—' }}</td>
                        <td>
                          <span class="pill" :class="monitorClass(m.status)">
                            {{ monitorText(m.status) }}
                          </span>
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <div class="card">
                  <h3>TopN 项目排行</h3>
                  <table>
                    <thead>
                      <tr>
                        <th>项目</th>
                        <th>成本</th>
                        <th>用量</th>
                        <th>作业数</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="p in dashboard.topProjects" :key="p.projectId">
                        <td>{{ p.projectName }}</td>
                        <td>{{ p.cost.toFixed(0) }} 元</td>
                        <td>{{ (p.usageRatio * 100).toFixed(0) }}%</td>
                        <td>{{ p.jobCount }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </div>
            </template>
          </div>

          <!-- ② 工作台 -->
          <div v-if="tab === 'workbench'">
            <template v-if="workbenchLoading">
              <div class="card"><h3>加载中…</h3></div>
            </template>
            <template v-else-if="workbench">
              <div class="grid g2">
                <div class="card">
                  <h3>
                    待办审批
                    <span class="pill r">{{ workbench.todos.length }}</span>
                  </h3>
                  <table>
                    <thead>
                      <tr>
                        <th>事项</th>
                        <th>申请人</th>
                        <th>优先级</th>
                        <th></th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="t in workbench.todos" :key="t.id">
                        <td>{{ t.title }}</td>
                        <td>{{ t.applicant }}</td>
                        <td>
                          <span class="pill" :class="priorityClass(t.priority)">
                            {{ priorityText(t.priority) }}
                          </span>
                        </td>
                        <td>
                          <button class="btn sm" @click="store.showToast('已批准')">批准</button>
                          <button class="btn ghost sm" @click="store.showToast('已驳回')">
                            驳回
                          </button>
                        </td>
                      </tr>
                      <tr v-if="workbench.todos.length === 0">
                        <td colspan="4" style="text-align: center; color: var(--muted)">
                          暂无待办
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <div class="card">
                  <h3>常用工具</h3>
                  <div class="chips">
                    <span
                      v-for="tool in workbench.tools"
                      :key="tool.key"
                      class="chip"
                      @click="router.push(tool.url)"
                    >
                      {{ tool.label }}
                    </span>
                  </div>
                </div>
              </div>
              <div class="card" style="margin-top: 14px">
                <h3>最近任务</h3>
                <table>
                  <thead>
                    <tr>
                      <th>任务</th>
                      <th>类型</th>
                      <th>状态</th>
                      <th>更新时间</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="t in workbench.recentTasks" :key="t.id">
                      <td>{{ t.name }}</td>
                      <td>{{ kindText(t.kind) }}</td>
                      <td>
                        <span class="pill" :class="recentStatusClass(t.status)">
                          {{ t.status }}
                        </span>
                      </td>
                      <td>{{ t.updatedAt }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>
          </div>

          <!-- ③ 数据目录 -->
          <div v-if="tab === 'catalog'">
            <template v-if="catalogLoading">
              <div class="card"><h3>加载中…</h3></div>
            </template>
            <template v-else-if="catalog">
              <div class="card">
                <h3>
                  数据目录
                  <span class="pill b">{{ catalog.nodes.length }} 节点</span>
                </h3>
                <div class="bp-tree">
                  <div
                    v-for="node in flatNodes"
                    :key="node.id"
                    class="bp-tree-node"
                    :style="{ marginLeft: `${node.depth * 18}px` }"
                  >
                    <span class="bp-tree-ic" :class="`bp-tree-${node.type}`">●</span>
                    <b>{{ node.name }}</b>
                    <span class="pill sm">{{ node.type }}</span>
                    <span v-if="node.assetCount > 0" class="meta">{{ node.assetCount }} 资产</span>
                  </div>
                </div>
              </div>
            </template>
          </div>

          <!-- ④ BI 报表 -->
          <div v-if="tab === 'reports'">
            <div class="toolbar">
              <button class="btn sm" @click="openReportModal">+ 新建报表</button>
              <div class="spacer"></div>
              <span class="pill p">{{ reports.length }} 个报表</span>
            </div>
            <template v-if="reportsLoading">
              <div class="card"><h3>加载中…</h3></div>
            </template>
            <template v-else>
              <div class="grid g3">
                <div class="card" v-for="r in reports" :key="r.id">
                  <div class="row">
                    <b>{{ r.name }}</b>
                    <span class="pill" :class="reportStatusClass(r.status)">
                      {{ reportStatusText(r.status) }}
                    </span>
                  </div>
                  <div class="meta">
                    {{ reportTypeText(r.config.type) }} · {{ r.config.chartType }}
                  </div>
                  <div class="meta">创建人 {{ r.creatorId || '—' }}</div>
                  <button class="btn ghost sm" style="margin-top: 8px" @click="viewReport(r)">
                    查看
                  </button>
                  <button
                    class="btn ghost sm"
                    style="margin-top: 8px; margin-left: 4px"
                    @click="handleDeleteReport(r.id)"
                  >
                    删除
                  </button>
                </div>
                <div class="card" v-if="reports.length === 0">
                  <h3>暂无报表</h3>
                  <div class="meta" style="color: var(--muted)">
                    点击右上角「+ 新建报表」创建第一个。
                  </div>
                </div>
              </div>
            </template>
          </div>
        </template>
      </main>
    </div>

    <!-- 新建业务线 Modal -->
    <Modal :visible="modalVisible" title="新建业务线" @close="modalVisible = false">
      <label>名称</label>
      <input v-model="form.name" placeholder="如 风控线" />
      <label>租户 ID</label>
      <input v-model="form.tenantId" placeholder="如 t-1" />
      <label>描述</label>
      <input v-model="form.description" placeholder="业务线描述" />
      <label>预算总额（元）</label>
      <input v-model.number="form.budgetTotal" type="number" />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" :disabled="creating" @click="handleCreate">
          {{ creating ? '创建中…' : '创建' }}
        </button>
      </template>
    </Modal>

    <!-- 新建报表 Modal -->
    <Modal :visible="reportModalVisible" title="新建 BI 报表" @close="reportModalVisible = false">
      <label>名称</label>
      <input v-model="reportForm.name" placeholder="如 风控日报" />
      <label>描述</label>
      <input v-model="reportForm.description" placeholder="报表描述" />
      <label>类型</label>
      <select v-model="reportForm.type">
        <option value="chart">图表</option>
        <option value="table">明细表</option>
        <option value="dashboard">综合看板</option>
        <option value="pivot">透视表</option>
      </select>
      <label>图表子类型</label>
      <select v-model="reportForm.chartType">
        <option value="line">折线图</option>
        <option value="bar">柱状图</option>
        <option value="pie">饼图</option>
        <option value="area">面积图</option>
      </select>
      <template #footer>
        <button class="btn ghost" @click="reportModalVisible = false">取消</button>
        <button class="btn" :disabled="reportCreating" @click="handleCreateReport">
          {{ reportCreating ? '创建中…' : '创建' }}
        </button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as bpApi from '@/api/businessPortal'
import type {
  BusinessLine,
  BusinessLineStatus,
  Dashboard,
  Workbench,
  CatalogTree,
  CatalogNode,
  Report
} from '@/api/businessPortal'

const router = useRouter()
const store = useAppStore()

/* ------------------------------ 业务线列表 ------------------------------ */
const {
  data: businessLines,
  loading: blLoading,
  error: blError,
  execute: loadBl
} = useApi<BusinessLine[]>(() => bpApi.listBusinessLines())

const currentBlId = ref<string>('')

async function reloadBl(): Promise<void> {
  const list = await loadBl()
  if (list && list.length > 0 && !currentBlId.value) {
    currentBlId.value = list[0].id
  }
}

function selectBl(id: string): void {
  currentBlId.value = id
}

/* ------------------------------ Dashboard ------------------------------ */
const {
  data: dashboard,
  loading: dashboardLoading,
  error: dashboardError,
  execute: loadDashboard
} = useApi<Dashboard>(() => bpApi.getDashboard(currentBlId.value))

async function reloadDashboard(): Promise<void> {
  await loadDashboard()
}

/* ------------------------------ Workbench ------------------------------ */
const {
  data: workbench,
  loading: workbenchLoading,
  execute: loadWorkbench
} = useApi<Workbench>(() => bpApi.getWorkbench(currentBlId.value))

/* ------------------------------ Catalog ------------------------------ */
const {
  data: catalog,
  loading: catalogLoading,
  execute: loadCatalog
} = useApi<CatalogTree>(() => bpApi.getCatalog(currentBlId.value))

// 扁平化目录树（带深度信息），用于渲染
interface FlatNode {
  id: string
  name: string
  type: string
  assetCount: number
  depth: number
}

const flatNodes = computed<FlatNode[]>(() => {
  if (!catalog.value) return []
  const map: Record<string, CatalogNode> = {}
  for (const n of catalog.value.nodes) {
    map[n.id] = n
  }
  const result: FlatNode[] = []
  function walk(id: string, depth: number): void {
    const node = map[id]
    if (!node) return
    result.push({
      id: node.id,
      name: node.name,
      type: node.type,
      assetCount: node.assetCount,
      depth
    })
    for (const childId of node.children) {
      walk(childId, depth + 1)
    }
  }
  for (const rootId of catalog.value.rootIds) {
    walk(rootId, 0)
  }
  return result
})

/* ------------------------------ Reports ------------------------------ */
const reports = ref<Report[]>([])
const { loading: reportsLoading, execute: loadReports } = useApi<Report[]>(() =>
  bpApi.listReports(currentBlId.value)
)

async function reloadReports(): Promise<void> {
  const list = await loadReports()
  reports.value = list ?? []
}

/* ------------------------------ Tab 切换 ------------------------------ */
const tab = ref<'dashboard' | 'workbench' | 'catalog' | 'reports'>('dashboard')

// 业务线切换时重新加载所有数据
watch(currentBlId, async (id) => {
  if (!id) return
  await Promise.all([loadDashboard(), loadWorkbench(), loadCatalog(), reloadReports()])
})

// Tab 切换时按需加载
watch(tab, async (t) => {
  if (!currentBlId.value) return
  if (t === 'dashboard' && !dashboard.value) await loadDashboard()
  if (t === 'workbench' && !workbench.value) await loadWorkbench()
  if (t === 'catalog' && !catalog.value) await loadCatalog()
  if (t === 'reports') await reloadReports()
})

/* ------------------------------ 新建业务线 ------------------------------ */
const modalVisible = ref(false)
const creating = ref(false)
const form = ref({
  name: '',
  tenantId: 't-1',
  description: '',
  budgetTotal: 100000
})

function openCreateModal(): void {
  form.value = { name: '', tenantId: 't-1', description: '', budgetTotal: 100000 }
  modalVisible.value = true
}

async function handleCreate(): Promise<void> {
  if (!form.value.name || !form.value.tenantId) {
    store.showToast('请填写名称和租户 ID')
    return
  }
  creating.value = true
  try {
    const bl = await bpApi.createBusinessLine({
      name: form.value.name,
      tenantId: form.value.tenantId,
      description: form.value.description,
      budget: { total: form.value.budgetTotal, used: 0, cycle: 'monthly', softLimit: true },
      ownerIds: ['current-user'],
      memberIds: ['current-user']
    })
    store.showToast(`业务线 ${bl.name} 已创建`)
    modalVisible.value = false
    await reloadBl()
    currentBlId.value = bl.id
  } catch {
    // 错误已由拦截器提示
  } finally {
    creating.value = false
  }
}

/* ------------------------------ 新建报表 ------------------------------ */
const reportModalVisible = ref(false)
const reportCreating = ref(false)
const reportForm = ref({
  name: '',
  description: '',
  type: 'chart' as const,
  chartType: 'line' as const
})

function openReportModal(): void {
  reportForm.value = { name: '', description: '', type: 'chart', chartType: 'line' }
  reportModalVisible.value = true
}

async function handleCreateReport(): Promise<void> {
  if (!reportForm.value.name) {
    store.showToast('请填写报表名称')
    return
  }
  if (!currentBlId.value) return
  reportCreating.value = true
  try {
    await bpApi.createReport(currentBlId.value, {
      name: reportForm.value.name,
      description: reportForm.value.description,
      config: {
        type: reportForm.value.type,
        chartType: reportForm.value.chartType
      }
    })
    store.showToast('报表已创建')
    reportModalVisible.value = false
    await reloadReports()
  } catch {
    // 错误已由拦截器提示
  } finally {
    reportCreating.value = false
  }
}

async function handleDeleteReport(reportId: string): Promise<void> {
  if (!currentBlId.value) return
  try {
    await bpApi.deleteReport(currentBlId.value, reportId)
    store.showToast('报表已删除')
    await reloadReports()
  } catch {
    // 错误已由拦截器提示
  }
}

function viewReport(r: Report): void {
  store.showToast(`查看报表：${r.name}（即将跳转 BI 工作台）`)
}

/* ------------------------------ 辅助函数 ------------------------------ */
function statusClass(s: BusinessLineStatus): string {
  return s === 'active' ? 'g' : s === 'suspended' ? 'a' : 'r'
}

function statusText(s: BusinessLineStatus): string {
  return s === 'active' ? '活跃' : s === 'suspended' ? '已暂停' : '已归档'
}

function usageRatio(b: { used: number; total: number }): number {
  if (b.total <= 0) return 0
  return Math.min(1, b.used / b.total)
}

function monitorClass(s: string): string {
  return s === 'ok' ? 'g' : s === 'warn' ? 'a' : 'r'
}

function monitorText(s: string): string {
  return s === 'ok' ? '正常' : s === 'warn' ? '告警' : '严重'
}

function priorityClass(p: string): string {
  return p === 'urgent' ? 'r' : p === 'high' ? 'a' : ''
}

function priorityText(p: string): string {
  return p === 'urgent' ? '紧急' : p === 'high' ? '高' : '普通'
}

function kindText(k: string): string {
  const map: Record<string, string> = {
    job: '作业',
    training: '训练',
    deployment: '部署',
    share: '共享'
  }
  return map[k] || k
}

function recentStatusClass(s: string): string {
  if (s === 'succeeded' || s === 'running') return 'g'
  if (s === 'pending') return 'a'
  return 'r'
}

function reportStatusClass(s: string): string {
  return s === 'published' ? 'g' : s === 'draft' ? 'a' : 'r'
}

function reportStatusText(s: string): string {
  return s === 'published' ? '已发布' : s === 'draft' ? '草稿' : '已归档'
}

function reportTypeText(t: string): string {
  const map: Record<string, string> = {
    chart: '图表',
    table: '明细表',
    dashboard: '综合看板',
    pivot: '透视表'
  }
  return map[t] || t
}

/* ------------------------------ 初始化 ------------------------------ */
onMounted(async () => {
  await reloadBl()
})
</script>

<style scoped>
.bp-layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 14px;
  margin-top: 14px;
}

.bp-sidebar {
  background: var(--card-bg, #fff);
  border: 1px solid var(--border, #e5e6eb);
  border-radius: 8px;
  padding: 12px;
  max-height: calc(100vh - 200px);
  overflow-y: auto;
}

.bp-sidebar-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.bp-sidebar-header h3 {
  margin: 0;
}

.bp-sidebar-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.bp-sidebar-item {
  padding: 10px;
  border-radius: 6px;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s;
}

.bp-sidebar-item:hover {
  background: var(--hover-bg, #f5f6f8);
}

.bp-sidebar-item.on {
  background: var(--primary-bg, #e8f3ff);
  border-color: var(--primary, #2f6fed);
}

.bp-main {
  min-width: 0;
}

.bp-tree {
  padding: 4px 0;
}

.bp-tree-node {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 0;
}

.bp-tree-ic {
  font-size: 12px;
  color: var(--muted, #86909c);
}

.bp-tree-database {
  color: #2f6fed;
}
.bp-tree-schema {
  color: #00b42a;
}
.bp-tree-table {
  color: #ff7d00;
}
.bp-tree-view {
  color: #722ed1;
}
.bp-tree-dataset {
  color: #f53f3f;
}
.bp-tree-model {
  color: #14c9c9;
}

.pill.sm {
  font-size: 11px;
  padding: 1px 6px;
}

.unit {
  font-size: 12px;
  color: var(--muted, #86909c);
  margin-left: 4px;
}
</style>
