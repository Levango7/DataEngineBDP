<template>
  <div class="dev-sched-page">
    <h1>{{ t('devSched.title') }}</h1>
    <div class="sub">{{ t('devSched.subtitle') }}</div>

    <!-- KPI 卡片区 -->
    <div class="grid g4">
      <template v-if="loading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>{{ t('engines.kpi.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('engines.kpi.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else-if="error">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('engines.kpi.loadFailed') }}</h3>
          <div class="meta" style="color: var(--muted)">
            {{ t('devSched.messages.listLoadFailed') }}
            <a href="javascript:void(0)" @click="reload">{{ t('engines.kpi.loadFailedRetry') }}</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>{{ t('devSched.kpi.total') }}</h3>
          <div class="kpi">{{ kpi.total }}</div>
          <div class="meta">{{ t('devSched.kpi.totalMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('devSched.kpi.running') }}</h3>
          <div class="kpi">{{ kpi.running }}</div>
          <div class="meta">{{ t('devSched.kpi.runningMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('devSched.kpi.todaySuccess') }}</h3>
          <div class="kpi s">{{ kpi.todaySuccess }}</div>
          <div class="meta">{{ t('devSched.kpi.todaySuccessMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('devSched.kpi.todayFailed') }}</h3>
          <div class="kpi d">{{ kpi.todayFailed }}</div>
          <div class="meta">{{ t('devSched.kpi.todayFailedMeta') }}</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：DAG 列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openCreateDialog">{{ t('devSched.toolbar.create') }}</el-button>
        <el-select
          v-model="statusFilter"
          :placeholder="t('devSched.toolbar.statusFilter')"
          clearable
          style="width: 140px"
          @change="handleFilterChange"
        >
          <el-option :label="t('devSched.status.DRAFT')" value="DRAFT" />
          <el-option :label="t('devSched.status.SCHEDULED')" value="SCHEDULED" />
          <el-option :label="t('devSched.status.RUNNING')" value="RUNNING" />
          <el-option :label="t('devSched.status.SUCCESS')" value="SUCCESS" />
          <el-option :label="t('devSched.status.FAILED')" value="FAILED" />
          <el-option :label="t('devSched.status.KILLED')" value="KILLED" />
          <el-option :label="t('devSched.status.PAUSED')" value="PAUSED" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle :aria-label="t('devSched.toolbar.refreshAria')" @click="reload" />
      </div>

      <el-table
        v-loading="loading"
        :data="dagList"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? t('devSched.table.loadFailed') : t('devSched.table.empty')"
      >
        <el-table-column prop="id" :label="t('devSched.table.columns.id')" width="160">
          <template #default="{ row }">
            <span style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px">
              {{ row.id }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="name" :label="t('devSched.table.columns.name')" min-width="180" />
        <el-table-column :label="t('devSched.table.columns.status')" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('devSched.table.columns.schedule')" width="160">
          <template #default="{ row }">
            <span
              v-if="row.schedule"
              style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
            >
              {{ row.schedule }}
            </span>
            <span v-else style="color: var(--muted)">{{ t('devSched.table.scheduleEmpty') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastRunAt" :label="t('devSched.table.columns.lastRun')" width="180">
          <template #default="{ row }">{{ row.lastRunAt || t('devSched.table.notAvailable') }}</template>
        </el-table-column>
        <el-table-column prop="owner" :label="t('devSched.table.columns.owner')" width="120">
          <template #default="{ row }">{{ row.owner || t('devSched.table.notAvailable') }}</template>
        </el-table-column>
        <el-table-column :label="t('devSched.table.columns.actions')" width="320" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditDrawer(row)">{{ t('devSched.table.actions.edit') }}</el-button>
            <el-button
              v-if="canRun(row.status)"
              link
              type="success"
              :loading="runningId === row.id"
              @click="handleRun(row)"
            >
              {{ t('devSched.table.actions.run') }}
            </el-button>
            <el-button link type="primary" @click="openHistoryDrawer(row)">{{ t('devSched.table.actions.history') }}</el-button>
            <el-button
              v-if="canRerun(row.lastRunStatus)"
              link
              type="warning"
              @click="handleRerun(row)"
            >
              {{ t('devSched.table.actions.rerun') }}
            </el-button>
            <el-button link type="warning" @click="openBackfillDialog(row)">{{ t('devSched.table.actions.backfill') }}</el-button>
            <el-button link type="danger" @click="handleDelete(row)">{{ t('devSched.table.actions.delete') }}</el-button>
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
      :title="editForm.id ? t('devSched.editDrawer.titleEdit', { name: editForm.name }) : t('devSched.editDrawer.titleCreate')"
      size="60%"
    >
      <el-form
        ref="editFormRef"
        :model="editForm"
        :rules="editRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item :label="t('devSched.editDrawer.fields.name')" prop="name">
          <el-input v-model="editForm.name" :placeholder="t('devSched.editDrawer.fields.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('devSched.editDrawer.fields.description')" prop="description">
          <el-input v-model="editForm.description" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item :label="t('devSched.editDrawer.fields.schedule')" prop="schedule">
          <el-input
            v-model="editForm.schedule"
            :placeholder="t('devSched.editDrawer.fields.schedulePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('devSched.editDrawer.fields.owner')" prop="owner">
          <el-input v-model="editForm.owner" :placeholder="t('devSched.editDrawer.fields.ownerPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('devSched.editDrawer.fields.dagJson')" prop="dagJson">
          <el-input
            v-model="editForm.dagJson"
            type="textarea"
            :rows="12"
            :placeholder="t('devSched.editDrawer.fields.dagJsonPlaceholder')"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDrawerVisible = false">{{ t('devSched.editDrawer.actions.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitDag">{{ t('devSched.editDrawer.actions.save') }}</el-button>
      </template>
    </el-drawer>

    <!-- 运行历史抽屉 -->
    <el-drawer
      v-model="historyDrawerVisible"
      :title="t('devSched.historyDrawer.title', { name: currentDag?.name ?? '' })"
      size="60%"
    >
      <el-table
        v-loading="runsLoading"
        :data="runs"
        stripe
        border
        size="small"
        :empty-text="runsError ? t('devSched.historyDrawer.loadFailed') : t('devSched.historyDrawer.empty')"
      >
        <el-table-column prop="id" :label="t('devSched.historyDrawer.columns.id')" width="100" />
        <el-table-column prop="runType" :label="t('devSched.historyDrawer.columns.runType')" width="120">
          <template #default="{ row }">{{ runTypeLabel(row.runType) }}</template>
        </el-table-column>
        <el-table-column :label="t('devSched.historyDrawer.columns.status')" width="110">
          <template #default="{ row }">
            <el-tag :type="runStatusTagType(row.status)" size="small" effect="light">
              {{ runStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="bizTime" :label="t('devSched.historyDrawer.columns.bizTime')" width="160">
          <template #default="{ row }">{{ row.bizTime || t('devSched.table.notAvailable') }}</template>
        </el-table-column>
        <el-table-column prop="triggeredBy" :label="t('devSched.historyDrawer.columns.triggeredBy')" width="120">
          <template #default="{ row }">{{ row.triggeredBy || t('devSched.table.notAvailable') }}</template>
        </el-table-column>
        <el-table-column prop="startTime" :label="t('devSched.historyDrawer.columns.startTime')" width="180">
          <template #default="{ row }">{{ row.startTime || t('devSched.table.notAvailable') }}</template>
        </el-table-column>
        <el-table-column :label="t('devSched.historyDrawer.columns.duration')" width="100">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column :label="t('devSched.historyDrawer.columns.actions')" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'FAILED'"
              link
              type="warning"
              :loading="rerunningId === row.id"
              @click="handleRerunRun(row)"
            >
              {{ t('devSched.historyDrawer.actions.rerun') }}
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
      :title="t('devSched.backfillDialog.title', { name: currentDag?.name ?? '' })"
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
        <el-form-item :label="t('devSched.backfillDialog.fields.startDate')" prop="startDate">
          <el-date-picker
            v-model="backfillForm.startDate"
            type="date"
            value-format="YYYY-MM-DD"
            :placeholder="t('devSched.backfillDialog.placeholders.startDate')"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="t('devSched.backfillDialog.fields.endDate')" prop="endDate">
          <el-date-picker
            v-model="backfillForm.endDate"
            type="date"
            value-format="YYYY-MM-DD"
            :placeholder="t('devSched.backfillDialog.placeholders.endDate')"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item :label="t('devSched.backfillDialog.fields.intervalDays')" prop="intervalDays">
          <el-input-number v-model="backfillForm.intervalDays" :min="1" :max="30" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="backfillDialogVisible = false">{{ t('devSched.backfillDialog.actions.cancel') }}</el-button>
        <el-button type="primary" :loading="backfilling" @click="handleBackfill">{{ t('devSched.backfillDialog.actions.submit') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as devSchedApi from '@/api/dev-sched'
import type { DagJob, DagCreateRequest } from '@/api/dev-sched'
import type { DagRunRecord } from '@/api/streamBatch'

const { t, te } = useI18n()

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

const editRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('devSched.rules.dagName'), trigger: 'blur' }]
}))

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
        ElMessage.success(t('devSched.messages.updated'))
      } else {
        await devSchedApi.createDag(payload)
        ElMessage.success(t('devSched.messages.created'))
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
    ElMessage.success(t('devSched.messages.runDone', { id: dagId, status }))
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
    await ElMessageBox.confirm(t('devSched.messages.rerunConfirm', { name: row.name }), t('devSched.messages.rerunConfirmTitle'), {
      type: 'warning'
    })
    // 通过 streamBatch 模块触发：先取最近一次失败 run，再 rerun
    const runsPage = await devSchedApi.streamBatchApi.listDagRuns(row.id, {
      status: 'FAILED',
      page: 0,
      size: 1
    })
    if (!runsPage.content.length) {
      ElMessage.warning(t('devSched.messages.rerunFailedNone'))
      return
    }
    const runId = runsPage.content[0].id
    await devSchedApi.streamBatchApi.rerunDagRun(row.id, runId)
    ElMessage.success(t('devSched.messages.rerunDone'))
    await reload()
  } catch {
    // 用户取消或操作失败
  }
}

/** 删除 DAG */
async function handleDelete(row: DagJob) {
  try {
    await ElMessageBox.confirm(t('devSched.messages.deleteConfirm', { name: row.name }), t('devSched.messages.deleteConfirmTitle'), {
      type: 'warning',
      confirmButtonText: t('devSched.messages.deleteConfirmTitle'),
      cancelButtonText: t('devSched.editDrawer.actions.cancel'),
      confirmButtonClass: 'el-button--danger'
    })
    await devSchedApi.deleteDag(row.id)
    ElMessage.success(t('devSched.messages.deleted'))
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
    ElMessage.success(t('devSched.messages.rerunDone'))
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

const backfillRules = computed<FormRules>(() => ({
  startDate: [{ required: true, message: t('devSched.rules.startDate'), trigger: 'change' }],
  endDate: [{ required: true, message: t('devSched.rules.endDate'), trigger: 'change' }]
}))

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
      ElMessage.success(t('devSched.messages.backfillCreated', { count: created }))
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

const STATUS_TAG_TYPE_MAP: Record<
  string,
  'primary' | 'success' | 'danger' | 'info' | 'warning'
> = {
  DRAFT: 'info',
  PENDING: 'info',
  SCHEDULED: 'warning',
  RUNNING: 'primary',
  SUCCESS: 'success',
  FAILED: 'danger',
  KILLED: 'info',
  PAUSED: 'warning'
}

function statusLabel(status: string): string {
  return t(`devSched.status.${status}`, status)
}

function statusTagType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return STATUS_TAG_TYPE_MAP[status] ?? 'info'
}

const RUN_STATUS_TAG_TYPE_MAP: Record<
  string,
  'primary' | 'success' | 'danger' | 'info' | 'warning'
> = {
  RUNNING: 'primary',
  SUCCESS: 'success',
  FAILED: 'danger',
  KILLED: 'info',
  PENDING: 'info'
}

function runStatusLabel(status: string): string {
  return t(`devSched.status.${status}`, status)
}

function runStatusTagType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return RUN_STATUS_TAG_TYPE_MAP[status] ?? 'info'
}

function runTypeLabel(runType: string): string {
  return t(`devSched.historyDrawer.runTypes.${runType}`, runType)
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

watch(
  () => appStore.workspace,
  () => {
    currentPage.value = 1
    void reload()
  }
)
</script>

<style scoped>
.dev-sched-page {
  padding: 0;
}
.sub {
  color: var(--ds-text-secondary);
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
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
  padding: 16px;
  background: #fff;
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: var(--ds-text-secondary);
  margin: 0 0 8px;
}
.kpi {
  font-size: 28px;
  font-weight: 700;
  color: var(--ds-text-primary);
  line-height: 1.2;
}
.kpi.s {
  color: var(--ds-color-success-600);
}
.kpi.d {
  color: var(--ds-color-error-600);
}
.meta {
  font-size: 12px;
  color: var(--ds-text-secondary);
  margin-top: 6px;
}
.page-card {
  border: 1px solid var(--ds-border-default);
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
