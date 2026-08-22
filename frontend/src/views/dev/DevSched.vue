<template>
  <div class="dev-sched-page">
    <h1>调度编排（DolphinScheduler）</h1>
    <div class="sub">DAG 工作流 · 流批统一 · 补数据 · 15 秒自动刷新</div>

    <!-- KPI 卡片区 -->
    <div class="grid g4">
      <template v-if="loading">
        <div class="card" v-for="i in 4" :key="i">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="error">
        <div class="card" style="grid-column: span 4">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            DAG 列表加载失败，<a href="javascript:void(0)" @click="reload">重试</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>DAG 总数</h3>
          <div class="kpi">{{ kpi.total }}</div>
          <div class="meta">全部 DAG 作业</div>
        </div>
        <div class="card">
          <h3>运行中</h3>
          <div class="kpi">{{ kpi.running }}</div>
          <div class="meta">状态为 RUNNING</div>
        </div>
        <div class="card">
          <h3>今日成功</h3>
          <div class="kpi s">{{ kpi.todaySuccess }}</div>
          <div class="meta">最近 24h SUCCESS</div>
        </div>
        <div class="card">
          <h3>今日失败</h3>
          <div class="kpi d">{{ kpi.todayFailed }}</div>
          <div class="meta">最近 24h FAILED</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：DAG 列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openCreateDialog">+ 新建 DAG</el-button>
        <el-select
          v-model="statusFilter"
          placeholder="状态筛选"
          clearable
          style="width: 140px"
          @change="handleFilterChange"
        >
          <el-option label="草稿" value="DRAFT" />
          <el-option label="已调度" value="SCHEDULED" />
          <el-option label="运行中" value="RUNNING" />
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
          <el-option label="已取消" value="KILLED" />
          <el-option label="已暂停" value="PAUSED" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="reload" />
      </div>

      <el-table
        v-loading="loading"
        :data="dagList"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? '加载失败，请重试' : '暂无 DAG 作业'"
      >
        <el-table-column prop="id" label="DAG ID" width="160">
          <template #default="{ row }">
            <span style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px">
              {{ row.id }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="DAG 名称" min-width="180" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="schedule" label="调度" width="160">
          <template #default="{ row }">
            <span v-if="row.schedule" style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px">
              {{ row.schedule }}
            </span>
            <span v-else style="color: var(--muted)">未配置</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastRunAt" label="最近运行" width="180">
          <template #default="{ row }">{{ row.lastRunAt || '--' }}</template>
        </el-table-column>
        <el-table-column prop="owner" label="负责人" width="120">
          <template #default="{ row }">{{ row.owner || '--' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditDrawer(row)">编辑</el-button>
            <el-button
              v-if="canRun(row.status)"
              link
              type="success"
              :loading="runningId === row.id"
              @click="handleRun(row)"
            >
              运行
            </el-button>
            <el-button link type="primary" @click="openHistoryDrawer(row)">历史</el-button>
            <el-button
              v-if="canRerun(row.lastRunStatus)"
              link
              type="warning"
              @click="handleRerun(row)"
            >
              重跑
            </el-button>
            <el-button link type="warning" @click="openBackfillDialog(row)">补数据</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="reload"
          @current-change="reload"
        />
      </div>
    </el-card>

    <!-- DAG 编辑抽屉 -->
    <el-drawer
      v-model="editDrawerVisible"
      :title="editForm.id ? `编辑 DAG - ${editForm.name}` : '新建 DAG'"
      size="60%"
    >
      <el-form
        ref="editFormRef"
        :model="editForm"
        :rules="editRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item label="DAG 名称" prop="name">
          <el-input v-model="editForm.name" placeholder="如 订单宽表 ETL" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="editForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="Cron 调度" prop="schedule">
          <el-input
            v-model="editForm.schedule"
            placeholder="如 0 0 * * *（每日 0 点），留空表示仅手动触发"
          />
        </el-form-item>
        <el-form-item label="负责人" prop="owner">
          <el-input v-model="editForm.owner" placeholder="负责人姓名" />
        </el-form-item>
        <el-form-item label="DAG 定义" prop="dagJson">
          <el-input
            v-model="editForm.dagJson"
            type="textarea"
            :rows="12"
            placeholder='{"nodes":[],"edges":[]}'
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDrawerVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitDag">保存</el-button>
      </template>
    </el-drawer>

    <!-- 运行历史抽屉 -->
    <el-drawer
      v-model="historyDrawerVisible"
      :title="`运行历史 - ${currentDag?.name ?? ''}`"
      size="60%"
    >
      <el-table
        v-loading="runsLoading"
        :data="runs"
        stripe
        border
        size="small"
        :empty-text="runsError ? '运行历史加载失败' : '暂无运行记录'"
      >
        <el-table-column prop="id" label="Run ID" width="100" />
        <el-table-column prop="runType" label="触发方式" width="120">
          <template #default="{ row }">{{ runTypeLabel(row.runType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="runStatusTagType(row.status)" size="small" effect="light">
              {{ runStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="bizTime" label="业务时间" width="160">
          <template #default="{ row }">{{ row.bizTime || '--' }}</template>
        </el-table-column>
        <el-table-column prop="triggeredBy" label="触发人" width="120">
          <template #default="{ row }">{{ row.triggeredBy || '--' }}</template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="180">
          <template #default="{ row }">{{ row.startTime || '--' }}</template>
        </el-table-column>
        <el-table-column label="耗时" width="100">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'FAILED'"
              link
              type="warning"
              :loading="rerunningId === row.id"
              @click="handleRerunRun(row)"
            >
              重跑
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="runsPage"
          v-model:page-size="runsSize"
          :page-sizes="[10, 20, 50]"
          :total="runsTotal"
          layout="total, prev, pager, next"
          background
          @current-change="loadRuns"
        />
      </div>
    </el-drawer>

    <!-- 补数据弹窗 -->
    <el-dialog
      v-model="backfillDialogVisible"
      :title="`补数据 - ${currentDag?.name ?? ''}`"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="backfillFormRef"
        :model="backfillForm"
        :rules="backfillRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item label="起始日期" prop="startDate">
          <el-date-picker
            v-model="backfillForm.startDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择起始日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="结束日期" prop="endDate">
          <el-date-picker
            v-model="backfillForm.endDate"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="选择结束日期"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="间隔天数" prop="intervalDays">
          <el-input-number v-model="backfillForm.intervalDays" :min="1" :max="30" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="backfillDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="backfilling" @click="handleBackfill">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as devSchedApi from '@/api/dev-sched'
import type { DagJob, DagCreateRequest } from '@/api/dev-sched'
import type { DagRunRecord } from '@/api/streamBatch'

/* ------------------------------ DAG 列表 ------------------------------ */

const appStore = useAppStore()

const loading = ref(false)
const error = ref(false)
const dagList = ref<DagJob[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const statusFilter = ref<string>('')

/** 拉取 DAG 列表 */
async function reload() {
  loading.value = true
  error.value = false
  try {
    const result = await devSchedApi.listDags({
      workspaceId: appStore.workspace || undefined,
      status: statusFilter.value || undefined,
      page: currentPage.value,
      size: pageSize.value
    })
    dagList.value = result.list
    total.value = result.total
  } catch {
    error.value = true
  } finally {
    loading.value = false
  }
}

/** 状态筛选变化 */
function handleFilterChange() {
  currentPage.value = 1
  void reload()
}

/** KPI 聚合 */
const kpi = computed(() => {
  const list = dagList.value
  const total = list.length
  const running = list.filter((d) => d.status === 'RUNNING').length
  const today = new Date().toISOString().slice(0, 10)
  const todaySuccess = list.filter(
    (d) => d.lastRunStatus === 'SUCCESS' && d.lastRunAt?.startsWith(today)
  ).length
  const todayFailed = list.filter(
    (d) => d.lastRunStatus === 'FAILED' && d.lastRunAt?.startsWith(today)
  ).length
  return { total, running, todaySuccess, todayFailed }
})

/* ------------------------------ 新建 / 编辑 DAG ------------------------------ */

const editDrawerVisible = ref(false)
const submitting = ref(false)
const editFormRef = ref<FormInstance>()

interface EditForm extends DagCreateRequest {
  id?: string
}

const editForm = reactive<EditForm>({
  id: undefined,
  name: '',
  description: '',
  workspaceId: undefined,
  dagJson: '',
  schedule: '',
  owner: ''
})

const editRules: FormRules = {
  name: [{ required: true, message: '请输入 DAG 名称', trigger: 'blur' }]
}

/** 打开新建抽屉 */
function openCreateDialog() {
  resetEditForm()
  editDrawerVisible.value = true
}

/** 打开编辑抽屉 */
function openEditDrawer(row: DagJob) {
  resetEditForm()
  editForm.id = row.id
  editForm.name = row.name
  editForm.description = row.description ?? ''
  editForm.workspaceId = row.workspaceId
  editForm.dagJson = row.dagJson ?? ''
  editForm.schedule = row.schedule ?? ''
  editForm.owner = row.owner ?? ''
  editDrawerVisible.value = true
}

/** 重置编辑表单 */
function resetEditForm() {
  editForm.id = undefined
  editForm.name = ''
  editForm.description = ''
  editForm.workspaceId = undefined
  editForm.dagJson = ''
  editForm.schedule = ''
  editForm.owner = ''
  editFormRef.value?.clearValidate()
}

/** 提交保存 DAG */
async function handleSubmitDag() {
  if (!editFormRef.value) return
  await editFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const payload: DagCreateRequest = {
        name: editForm.name,
        description: editForm.description || undefined,
        workspaceId: editForm.workspaceId ?? appStore.workspace,
        dagJson: editForm.dagJson || undefined,
        schedule: editForm.schedule || undefined,
        owner: editForm.owner || undefined
      }
      if (editForm.id) {
        await devSchedApi.updateDag(editForm.id, payload)
        ElMessage.success('DAG 已更新')
      } else {
        await devSchedApi.createDag(payload)
        ElMessage.success('DAG 已创建')
      }
      editDrawerVisible.value = false
      await reload()
    } catch {
      // 拦截器已提示
    } finally {
      submitting.value = false
    }
  })
}

/* ------------------------------ 运行 / 重跑 / 删除 ------------------------------ */

const runningId = ref<string>('')
const rerunningId = ref<number | undefined>()

/** 是否可运行 */
function canRun(status: string): boolean {
  return ['DRAFT', 'SCHEDULED', 'SUCCESS', 'FAILED', 'KILLED', 'PAUSED'].includes(status)
}

/** 是否可重跑（最近一次失败） */
function canRerun(lastRunStatus?: string): boolean {
  return lastRunStatus === 'FAILED'
}

/** 运行 DAG */
async function handleRun(row: DagJob) {
  runningId.value = row.id
  try {
    const { dagId, status } = await devSchedApi.runDag(row.id)
    ElMessage.success(`DAG 已运行，ID：${dagId}，状态：${status}`)
    await reload()
  } catch {
    // 拦截器已提示
  } finally {
    runningId.value = ''
  }
}

/** 重跑最近一次失败 */
async function handleRerun(row: DagJob) {
  try {
    await ElMessageBox.confirm(
      `确认对 DAG「${row.name}」最近一次失败实例执行重跑？`,
      '重跑确认',
      { type: 'warning' }
    )
    // 通过 streamBatch 模块触发：先取最近一次失败 run，再 rerun
    const runsPage = await devSchedApi.streamBatchApi.listDagRuns(row.id, {
      status: 'FAILED',
      page: 0,
      size: 1
    })
    if (!runsPage.content.length) {
      ElMessage.warning('未找到可重跑的失败实例')
      return
    }
    const runId = runsPage.content[0].id
    await devSchedApi.streamBatchApi.rerunDagRun(row.id, runId)
    ElMessage.success('重跑已触发')
    await reload()
  } catch {
    // 用户取消或操作失败
  }
}

/** 删除 DAG */
async function handleDelete(row: DagJob) {
  try {
    await ElMessageBox.confirm(
      `确认删除 DAG「${row.name}」？该操作不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await devSchedApi.deleteDag(row.id)
    ElMessage.success('DAG 已删除')
    await reload()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 运行历史 ------------------------------ */

const historyDrawerVisible = ref(false)
const runsLoading = ref(false)
const runsError = ref(false)
const runs = ref<DagRunRecord[]>([])
const runsTotal = ref(0)
const runsPage = ref(0)
const runsSize = ref(20)
const currentDag = ref<DagJob | null>(null)

/** 打开运行历史抽屉 */
function openHistoryDrawer(row: DagJob) {
  currentDag.value = row
  runsPage.value = 0
  historyDrawerVisible.value = true
  void loadRuns()
}

/** 加载运行历史 */
async function loadRuns() {
  if (!currentDag.value) return
  runsLoading.value = true
  runsError.value = false
  try {
    const page = await devSchedApi.streamBatchApi.listDagRuns(currentDag.value.id, {
      page: runsPage.value,
      size: runsSize.value
    })
    runs.value = page.content
    runsTotal.value = page.totalElements
  } catch {
    runsError.value = true
    runs.value = []
  } finally {
    runsLoading.value = false
  }
}

/** 重跑某次失败实例 */
async function handleRerunRun(row: DagRunRecord) {
  if (!currentDag.value) return
  rerunningId.value = row.id
  try {
    await devSchedApi.streamBatchApi.rerunDagRun(currentDag.value.id, row.id)
    ElMessage.success('重跑已触发')
    await loadRuns()
  } catch {
    // 拦截器已提示
  } finally {
    rerunningId.value = undefined
  }
}

/* ------------------------------ 补数据 ------------------------------ */

const backfillDialogVisible = ref(false)
const backfilling = ref(false)
const backfillFormRef = ref<FormInstance>()

const backfillForm = reactive({
  startDate: '',
  endDate: '',
  intervalDays: 1
})

const backfillRules: FormRules = {
  startDate: [{ required: true, message: '请选择起始日期', trigger: 'change' }],
  endDate: [{ required: true, message: '请选择结束日期', trigger: 'change' }]
}

/** 打开补数据弹窗 */
function openBackfillDialog(row: DagJob) {
  currentDag.value = row
  backfillForm.startDate = ''
  backfillForm.endDate = ''
  backfillForm.intervalDays = 1
  backfillDialogVisible.value = true
}

/** 提交补数据 */
async function handleBackfill() {
  if (!backfillFormRef.value || !currentDag.value) return
  const dag = currentDag.value
  await backfillFormRef.value.validate(async (valid) => {
    if (!valid) return
    backfilling.value = true
    try {
      const { created } = await devSchedApi.streamBatchApi.backfillDag(dag.id, {
        startDate: backfillForm.startDate,
        endDate: backfillForm.endDate,
        intervalDays: backfillForm.intervalDays
      })
      ElMessage.success(`已生成 ${created} 个回填实例`)
      backfillDialogVisible.value = false
      await loadRuns()
    } catch {
      // 拦截器已提示
    } finally {
      backfilling.value = false
    }
  })
}

/* ------------------------------ 辅助函数 ------------------------------ */

const STATUS_MAP: Record<string, { label: string; type: 'primary' | 'success' | 'danger' | 'info' | 'warning' }> = {
  DRAFT: { label: '草稿', type: 'info' },
  PENDING: { label: '等待中', type: 'info' },
  SCHEDULED: { label: '已调度', type: 'warning' },
  RUNNING: { label: '运行中', type: 'primary' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  KILLED: { label: '已取消', type: 'info' },
  PAUSED: { label: '已暂停', type: 'warning' },
}

function statusLabel(status: string): string {
  return STATUS_MAP[status]?.label ?? status
}

function statusTagType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return STATUS_MAP[status]?.type ?? 'info'
}

const RUN_STATUS_MAP: Record<string, { label: string; type: 'primary' | 'success' | 'danger' | 'info' | 'warning' }> = {
  RUNNING: { label: '运行中', type: 'primary' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  KILLED: { label: '已取消', type: 'info' },
  PENDING: { label: '等待中', type: 'info' },
}

function runStatusLabel(status: string): string {
  return RUN_STATUS_MAP[status]?.label ?? status
}

function runStatusTagType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return RUN_STATUS_MAP[status]?.type ?? 'info'
}

const RUN_TYPE_LABELS: Record<string, string> = {
  MANUAL: '手动',
  SCHEDULED: '调度',
  RERUN: '重跑',
  BACKFILL: '补数据',
}

function runTypeLabel(runType: string): string {
  return RUN_TYPE_LABELS[runType] ?? runType
}

/** 耗时格式化（毫秒） */
function formatDuration(ms?: number | null): string {
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
  void reload()
  // 15s 轮询刷新
  timer = setInterval(() => void reload(), 15000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.dev-sched-page {
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
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>