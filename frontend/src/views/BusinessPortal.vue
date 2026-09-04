<template>
  <div>
    <h1>{{ t('businessPortal.title') }}</h1>
    <div class="sub">
      {{ t('businessPortal.subtitle') }}
      <span class="pill b">{{ t('businessPortal.pills.isolateLines') }}</span>
      <span class="pill p">{{ t('businessPortal.pills.isolateData') }}</span>
      <span class="pill g">{{ t('businessPortal.pills.isolatePerms') }}</span>
    </div>

    <div class="bp-layout">
      <!-- 左侧：业务线选择侧边栏 -->
      <aside class="bp-sidebar">
        <div class="bp-sidebar-header">
          <h3>{{ t('businessPortal.sidebar.title') }}</h3>
          <button class="btn sm" @click="openCreateModal">
            {{ t('businessPortal.sidebar.new') }}
          </button>
        </div>
        <div class="bp-sidebar-list">
          <template v-if="blLoading">
            <div v-for="i in 3" :key="`bl-s-${i}`" class="bp-sidebar-item">
              <b>{{ t('businessPortal.sidebar.loading') }}</b>
            </div>
          </template>
          <template v-else-if="blError">
            <div class="bp-sidebar-item">
              <span style="color: var(--muted)">{{ blError.message }}</span>
              <a href="javascript:void(0)" @click="reloadBl">
                {{ t('businessPortal.sidebar.retry') }}
              </a>
            </div>
          </template>
          <template v-else-if="businessLines && businessLines.length === 0">
            <div class="bp-sidebar-item">
              <span style="color: var(--muted)">{{ t('businessPortal.sidebar.empty') }}</span>
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
                {{
                  t('businessPortal.budget.usedOverTotal', {
                    used: bl.budget.used.toFixed(0),
                    total: bl.budget.total.toFixed(0)
                  })
                }}
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
            <h3>{{ t('businessPortal.empty.selectBl') }}</h3>
            <div class="meta" style="color: var(--muted)">
              {{ t('businessPortal.empty.selectBlHint') }}
            </div>
          </div>
        </template>
        <template v-else>
          <!-- Tab 切换 -->
          <div class="tabbar">
            <div class="t" :class="{ on: tab === 'dashboard' }" @click="tab = 'dashboard'">
              {{ t('businessPortal.tabs.dashboard') }}
            </div>
            <div class="t" :class="{ on: tab === 'workbench' }" @click="tab = 'workbench'">
              {{ t('businessPortal.tabs.workbench') }}
            </div>
            <div class="t" :class="{ on: tab === 'catalog' }" @click="tab = 'catalog'">
              {{ t('businessPortal.tabs.catalog') }}
            </div>
            <div class="t" :class="{ on: tab === 'reports' }" @click="tab = 'reports'">
              {{ t('businessPortal.tabs.reports') }}
            </div>
          </div>

          <!-- ① 数据概览 -->
          <div v-if="tab === 'dashboard'">
            <template v-if="dashboardLoading">
              <div class="card">
                <h3>{{ t('businessPortal.dashboard.loading') }}</h3>
              </div>
            </template>
            <template v-else-if="dashboardError">
              <div class="card">
                <h3>{{ t('businessPortal.dashboard.loadFailed') }}</h3>
                <div class="meta" style="color: var(--muted)">
                  {{ t('businessPortal.dashboard.loadFailedHint') }}
                  <a href="javascript:void(0)" @click="reloadDashboard">
                    {{ t('businessPortal.dashboard.retry') }}
                  </a>
                </div>
              </div>
            </template>
            <template v-else-if="dashboard">
              <!-- KPI 卡片 -->
              <div class="grid g4">
                <div v-for="kpi in dashboard.kpis" :key="kpi.key" class="card">
                  <h3>{{ kpi.label }}</h3>
                  <div class="kpi">
                    {{ kpi.value }}
                    <span class="unit">{{ kpi.unit }}</span>
                  </div>
                  <div class="meta">
                    {{ t('businessPortal.dashboard.kpi.trend') }}
                    <span :style="{ color: kpi.trend >= 0 ? 'var(--ok)' : 'var(--danger)' }">
                      {{ kpi.trend >= 0 ? '+' : '' }}{{ kpi.trend
                      }}{{ t('businessPortal.dashboard.topProjects.percentSuffix') }}
                    </span>
                  </div>
                </div>
              </div>

              <!-- 趋势图 -->
              <div class="grid g3" style="margin-top: 14px">
                <div v-for="trend in dashboard.trends" :key="trend.key" class="card">
                  <h3>{{ trend.label }}</h3>
                  <div class="mini">
                    <i
                      v-for="(h, idx) in trend.bars"
                      :key="`tr-${idx}`"
                      :style="{ height: h + '%' }"
                    ></i>
                  </div>
                  <div class="meta">
                    {{ t('businessPortal.dashboard.trend.window7d') }} ·
                    {{ t('businessPortal.dashboard.trend.unit', { unit: trend.unit }) }}
                  </div>
                </div>
              </div>

              <!-- 实时监控 + TopN 项目 -->
              <div class="grid g2" style="margin-top: 14px">
                <div class="card">
                  <h3>{{ t('businessPortal.dashboard.realtime.title') }}</h3>
                  <table>
                    <thead>
                      <tr>
                        <th>{{ t('businessPortal.dashboard.realtime.columns.metric') }}</th>
                        <th>{{ t('businessPortal.dashboard.realtime.columns.current') }}</th>
                        <th>{{ t('businessPortal.dashboard.realtime.columns.threshold') }}</th>
                        <th>{{ t('businessPortal.dashboard.realtime.columns.status') }}</th>
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
                  <h3>{{ t('businessPortal.dashboard.topProjects.title') }}</h3>
                  <table>
                    <thead>
                      <tr>
                        <th>{{ t('businessPortal.dashboard.topProjects.columns.project') }}</th>
                        <th>{{ t('businessPortal.dashboard.topProjects.columns.cost') }}</th>
                        <th>{{ t('businessPortal.dashboard.topProjects.columns.usage') }}</th>
                        <th>{{ t('businessPortal.dashboard.topProjects.columns.jobCount') }}</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="p in dashboard.topProjects" :key="p.projectId">
                        <td>{{ p.projectName }}</td>
                        <td>
                          {{ p.cost.toFixed(0)
                          }}{{ t('businessPortal.dashboard.topProjects.costUnit') }}
                        </td>
                        <td>
                          {{ (p.usageRatio * 100).toFixed(0)
                          }}{{ t('businessPortal.dashboard.topProjects.percentSuffix') }}
                        </td>
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
              <div class="card">
                <h3>{{ t('businessPortal.workbench.loading') }}</h3>
              </div>
            </template>
            <template v-else-if="workbench">
              <div class="grid g2">
                <div class="card">
                  <h3>
                    {{ t('businessPortal.workbench.todos.title') }}
                    <span class="pill r">{{ workbench.todos.length }}</span>
                  </h3>
                  <table>
                    <thead>
                      <tr>
                        <th>{{ t('businessPortal.workbench.todos.columns.item') }}</th>
                        <th>{{ t('businessPortal.workbench.todos.columns.applicant') }}</th>
                        <th>{{ t('businessPortal.workbench.todos.columns.priority') }}</th>
                        <th></th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="td in workbench.todos" :key="td.id">
                        <td>{{ td.title }}</td>
                        <td>{{ td.applicant }}</td>
                        <td>
                          <span class="pill" :class="priorityClass(td.priority)">
                            {{ priorityText(td.priority) }}
                          </span>
                        </td>
                        <td>
                          <button
                            class="btn sm"
                            @click="
                              store.showToast(t('businessPortal.workbench.todos.approveDone'))
                            "
                          >
                            {{ t('businessPortal.workbench.todos.approve') }}
                          </button>
                          <button
                            class="btn ghost sm"
                            @click="store.showToast(t('businessPortal.workbench.todos.rejectDone'))"
                          >
                            {{ t('businessPortal.workbench.todos.reject') }}
                          </button>
                        </td>
                      </tr>
                      <tr v-if="workbench.todos.length === 0">
                        <td colspan="4" style="text-align: center; color: var(--muted)">
                          {{ t('businessPortal.workbench.todos.empty') }}
                        </td>
                      </tr>
                    </tbody>
                  </table>
                </div>
                <div class="card">
                  <h3>{{ t('businessPortal.workbench.tools.title') }}</h3>
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
                <h3>{{ t('businessPortal.workbench.recentTasks.title') }}</h3>
                <table>
                  <thead>
                    <tr>
                      <th>{{ t('businessPortal.workbench.recentTasks.columns.task') }}</th>
                      <th>{{ t('businessPortal.workbench.recentTasks.columns.type') }}</th>
                      <th>{{ t('businessPortal.workbench.recentTasks.columns.status') }}</th>
                      <th>{{ t('businessPortal.workbench.recentTasks.columns.updatedAt') }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="task in workbench.recentTasks" :key="task.id">
                      <td>{{ task.name }}</td>
                      <td>{{ kindText(task.kind) }}</td>
                      <td>
                        <span class="pill" :class="recentStatusClass(task.status)">
                          {{ task.status }}
                        </span>
                      </td>
                      <td>{{ task.updatedAt }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
            </template>
          </div>

          <!-- ③ 数据目录 -->
          <div v-if="tab === 'catalog'">
            <template v-if="catalogLoading">
              <div class="card">
                <h3>{{ t('businessPortal.catalog.loading') }}</h3>
              </div>
            </template>
            <template v-else-if="catalog">
              <div class="card">
                <h3>
                  {{ t('businessPortal.catalog.title') }}
                  <span class="pill b">
                    {{ t('businessPortal.catalog.nodeCount', { count: catalog.nodes.length }) }}
                  </span>
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
                    <span v-if="node.assetCount > 0" class="meta">
                      {{ t('businessPortal.catalog.assetCount', { count: node.assetCount }) }}
                    </span>
                  </div>
                </div>
              </div>
            </template>
          </div>

          <!-- ④ BI 报表 -->
          <div v-if="tab === 'reports'">
            <div class="toolbar">
              <button class="btn sm" @click="openReportModal">
                {{ t('businessPortal.reports.newReport') }}
              </button>
              <div class="spacer"></div>
              <span class="pill p">
                {{ t('businessPortal.reports.countPill', { count: reports.length }) }}
              </span>
            </div>
            <template v-if="reportsLoading">
              <div class="card">
                <h3>{{ t('businessPortal.reports.loading') }}</h3>
              </div>
            </template>
            <template v-else>
              <div class="grid g3">
                <div v-for="r in reports" :key="r.id" class="card">
                  <div class="row">
                    <b>{{ r.name }}</b>
                    <span class="pill" :class="reportStatusClass(r.status)">
                      {{ reportStatusText(r.status) }}
                    </span>
                  </div>
                  <div class="meta">
                    {{ reportTypeText(r.config.type) }} · {{ r.config.chartType }}
                  </div>
                  <div class="meta">
                    {{ t('businessPortal.reports.creator', { name: r.creatorId || '—' }) }}
                  </div>
                  <button class="btn ghost sm" style="margin-top: 8px" @click="viewReport(r)">
                    {{ t('businessPortal.reports.view') }}
                  </button>
                  <button
                    class="btn ghost sm"
                    style="margin-top: 8px; margin-left: 4px"
                    @click="handleDeleteReport(r.id)"
                  >
                    {{ t('businessPortal.reports.delete') }}
                  </button>
                </div>
                <div v-if="reports.length === 0" class="card">
                  <h3>{{ t('businessPortal.reports.empty.title') }}</h3>
                  <div class="meta" style="color: var(--muted)">
                    {{ t('businessPortal.reports.empty.hint') }}
                  </div>
                </div>
              </div>
            </template>
          </div>
        </template>
      </main>
    </div>

    <!-- 新建业务线 Modal -->
    <Modal
      :visible="modalVisible"
      :title="t('businessPortal.createBl.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('businessPortal.createBl.name') }}</label>
      <input v-model="form.name" :placeholder="t('businessPortal.createBl.namePlaceholder')" />
      <label>{{ t('businessPortal.createBl.tenantId') }}</label>
      <input
        v-model="form.tenantId"
        :placeholder="t('businessPortal.createBl.tenantIdPlaceholder')"
      />
      <label>{{ t('businessPortal.createBl.description') }}</label>
      <input
        v-model="form.description"
        :placeholder="t('businessPortal.createBl.descriptionPlaceholder')"
      />
      <label>{{ t('businessPortal.createBl.budgetTotal') }}</label>
      <input v-model.number="form.budgetTotal" type="number" />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">
          {{ t('businessPortal.createBl.cancel') }}
        </button>
        <button class="btn" :disabled="creating" @click="handleCreate">
          {{
            creating ? t('businessPortal.createBl.creating') : t('businessPortal.createBl.create')
          }}
        </button>
      </template>
    </Modal>

    <!-- 新建报表 Modal -->
    <Modal
      :visible="reportModalVisible"
      :title="t('businessPortal.createReport.title')"
      @close="reportModalVisible = false"
    >
      <label>{{ t('businessPortal.createReport.name') }}</label>
      <input
        v-model="reportForm.name"
        :placeholder="t('businessPortal.createReport.namePlaceholder')"
      />
      <label>{{ t('businessPortal.createReport.description') }}</label>
      <input
        v-model="reportForm.description"
        :placeholder="t('businessPortal.createReport.descriptionPlaceholder')"
      />
      <label>{{ t('businessPortal.createReport.type') }}</label>
      <select v-model="reportForm.type">
        <option value="chart">{{ t('businessPortal.createReport.types.chart') }}</option>
        <option value="table">{{ t('businessPortal.createReport.types.table') }}</option>
        <option value="dashboard">{{ t('businessPortal.createReport.types.dashboard') }}</option>
        <option value="pivot">{{ t('businessPortal.createReport.types.pivot') }}</option>
      </select>
      <label>{{ t('businessPortal.createReport.chartType') }}</label>
      <select v-model="reportForm.chartType">
        <option value="line">{{ t('businessPortal.createReport.chartTypes.line') }}</option>
        <option value="bar">{{ t('businessPortal.createReport.chartTypes.bar') }}</option>
        <option value="pie">{{ t('businessPortal.createReport.chartTypes.pie') }}</option>
        <option value="area">{{ t('businessPortal.createReport.chartTypes.area') }}</option>
      </select>
      <template #footer>
        <button class="btn ghost" @click="reportModalVisible = false">
          {{ t('businessPortal.createReport.cancel') }}
        </button>
        <button class="btn" :disabled="reportCreating" @click="handleCreateReport">
          {{
            reportCreating
              ? t('businessPortal.createReport.creating')
              : t('businessPortal.createReport.create')
          }}
        </button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
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

const { t, te } = useI18n()
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
    store.showToast(t('businessPortal.createBl.needNameAndTenant'))
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
    store.showToast(t('businessPortal.createBl.created', { name: bl.name }))
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
    store.showToast(t('businessPortal.createReport.needName'))
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
    store.showToast(t('businessPortal.createReport.created'))
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
    store.showToast(t('businessPortal.reportActions.deleted'))
    await reloadReports()
  } catch {
    // 错误已由拦截器提示
  }
}

function viewReport(r: Report): void {
  store.showToast(t('businessPortal.reportActions.viewToast', { name: r.name }))
}

/* ------------------------------ 辅助函数 ------------------------------ */
function statusClass(s: BusinessLineStatus): string {
  return s === 'active' ? 'g' : s === 'suspended' ? 'a' : 'r'
}

function statusText(s: BusinessLineStatus): string {
  return t(`businessPortal.status.bl.${s}`)
}

function usageRatio(b: { used: number; total: number }): number {
  if (b.total <= 0) return 0
  return Math.min(1, b.used / b.total)
}

function monitorClass(s: string): string {
  return s === 'ok' ? 'g' : s === 'warn' ? 'a' : 'r'
}

function monitorText(s: string): string {
  return t(`businessPortal.status.monitor.${s}`)
}

function priorityClass(p: string): string {
  return p === 'urgent' ? 'r' : p === 'high' ? 'a' : ''
}

function priorityText(p: string): string {
  return t(`businessPortal.status.priority.${p}`)
}

function kindText(k: string): string {
  const key = `businessPortal.status.kind.${k}`
  return te(key) ? t(key) : k
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
  return t(`businessPortal.status.report.${s}`)
}

function reportTypeText(rt: string): string {
  const key = `businessPortal.status.reportType.${rt}`
  return te(key) ? t(key) : rt
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
