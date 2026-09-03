<template>
  <div class="eng-flink-page">
    <h1>{{ t('engines.flink.title') }}</h1>
    <div class="sub">{{ t('engines.flink.subtitle') }}</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
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
            {{ t('engines.flink.loadFailed') }}
            <a href="javascript:void(0)" @click="loadList">{{ t('engines.kpi.loadFailedRetry') }}</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>{{ t('engines.flink.kpi.runningJob') }}</h3>
          <div class="kpi">{{ kpi.running }}</div>
          <div class="meta">{{ t('engines.flink.kpi.runningJobMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engines.flink.kpi.todayFailed') }}</h3>
          <div class="kpi d">{{ kpi.failed }}</div>
          <div class="meta">{{ t('engines.flink.kpi.todayFailedMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engines.flink.kpi.avgLatency') }}</h3>
          <div class="kpi">{{ formatLatency(kpi.avgLatencyMs) }}</div>
          <div class="meta">{{ t('engines.flink.kpi.avgLatencyMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engines.flink.kpi.cpSuccessRate') }}</h3>
          <div class="kpi s">{{ kpi.cpSuccessRate }}%</div>
          <div class="meta">{{ t('engines.flink.kpi.cpBreakdown', { ok: kpi.cpSuccess, fail: kpi.cpFail }) }}</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：作业列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openSubmitDialog">{{ t('engines.flink.submitJob') }}</el-button>
        <el-select
          v-model="statusFilter"
          :placeholder="t('engines.flink.statusFilter')"
          clearable
          style="width: 140px"
          @change="handleFilterChange"
        >
          <el-option :label="t('engines.flink.statuses.RUNNING')" value="RUNNING" />
          <el-option :label="t('engines.flink.statuses.FAILED')" value="FAILED" />
          <el-option :label="t('engines.flink.statuses.CANCELED')" value="CANCELED" />
          <el-option :label="t('engines.flink.statuses.FINISHED')" value="FINISHED" />
          <el-option :label="t('engines.flink.statuses.RESTARTING')" value="RESTARTING" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="loadList" />
      </div>

      <el-table
        v-loading="loading"
        :data="jobList"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? t('engines.table.loadFailed') : t('engines.flink.table.empty')"
      >
        <el-table-column prop="name" :label="t('engines.flink.table.columns.name')" min-width="180" />
        <el-table-column :label="t('engines.flink.table.columns.status')" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('engines.flink.table.columns.parallelism')" width="100" align="center">
          <template #default="{ row }">{{ row.parallelism }}</template>
        </el-table-column>
        <el-table-column :label="t('engines.flink.table.columns.duration')" width="120">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column :label="t('engines.flink.table.columns.checkpoint')" width="140" align="center">
          <template #default="{ row }">{{ row.checkpointCount }}</template>
        </el-table-column>
        <el-table-column :label="t('engines.flink.table.columns.backpressure')" width="100">
          <template #default="{ row }">
            <el-tag :type="backpressureTagType(row.backpressureLevel)" effect="light" size="small">
              {{ backpressureLabel(row.backpressureLevel) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('engines.flink.table.columns.actions')" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openMonitorDrawer(row)">{{ t('engines.flink.actions.monitor') }}</el-button>
            <el-button
              v-if="canStop(row.status)"
              link
              type="warning"
              :loading="stoppingId === row.id"
              @click="handleStop(row)"
            >
              {{ t('engines.flink.actions.stop') }}
            </el-button>
            <el-button
              v-if="canSavepoint(row.status)"
              link
              type="primary"
              :loading="savepointingId === row.id"
              @click="handleSavepoint(row)"
            >
              {{ t('engines.flink.actions.savepoint') }}
            </el-button>
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
          @size-change="loadList"
          @current-change="loadList"
        />
      </div>
    </el-card>

    <!-- 提交流作业弹窗 -->
    <el-dialog
      v-model="submitDialogVisible"
      :title="t('engines.flink.submit.title')"
      width="640px"
      :close-on-click-modal="false"
      @closed="resetSubmitForm"
    >
      <el-form
        ref="submitFormRef"
        :model="submitForm"
        :rules="submitRules"
        label-width="140px"
        label-position="right"
      >
        <el-form-item :label="t('engines.flink.submit.jobName')" prop="name">
          <el-input v-model="submitForm.name" :placeholder="t('engines.flink.submit.jobNamePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('engines.flink.submit.sql')" prop="sql">
          <el-input
            v-model="submitForm.sql"
            type="textarea"
            :rows="8"
            :placeholder="t('engines.flink.submit.sqlPlaceholder')"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item :label="t('engines.flink.submit.parallelism')" prop="parallelism">
          <el-input-number v-model="submitForm.parallelism" :min="1" :max="200" />
        </el-form-item>
        <el-form-item :label="t('engines.flink.submit.cpInterval')" prop="checkpointIntervalMs">
          <el-input-number v-model="submitForm.checkpointIntervalMs" :min="1000" :step="1000" />
          <span style="margin-left: 8px; color: var(--muted); font-size: 12px">{{ t('engines.flink.submit.msUnit') }}</span>
        </el-form-item>
        <el-form-item :label="t('engines.flink.submit.owner')" prop="owner">
          <el-input v-model="submitForm.owner" :placeholder="t('engines.flink.submit.ownerPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitDialogVisible = false">{{ t('engines.flink.submit.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">{{ t('engines.flink.submit.submit') }}</el-button>
      </template>
    </el-dialog>

    <!-- 监控详情抽屉 -->
    <el-drawer
      v-model="monitorDrawerVisible"
      :title="t('engines.flink.monitor.title', { name: currentMonitorJob?.name ?? '' })"
      size="60%"
      @closed="closeMonitorDrawer"
    >
      <template v-if="currentMonitorJob">
        <el-descriptions :column="2" border>
          <el-descriptions-item :label="t('engines.flink.monitor.jobId')">{{ currentMonitorJob.id }}</el-descriptions-item>
          <el-descriptions-item :label="t('engines.flink.monitor.status')">
            <el-tag :type="statusTagType(currentMonitorJob.status)" effect="light">
              {{ statusLabel(currentMonitorJob.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('engines.flink.monitor.parallelism')">
            {{ currentMonitorJob.parallelism }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('engines.flink.monitor.duration')">
            {{ formatDuration(currentMonitorJob.durationMs) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('engines.flink.monitor.sourceThroughput')">
            {{ currentMonitorJob.sourceThroughput ?? '--' }} {{ t('engines.flink.monitor.throughputUnit') }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('engines.flink.monitor.sinkThroughput')">
            {{ currentMonitorJob.sinkThroughput ?? '--' }} {{ t('engines.flink.monitor.throughputUnit') }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('engines.flink.monitor.avgLatency')">
            {{ formatLatency(currentMonitorJob.latencyMs) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('engines.flink.monitor.backpressureLevel')">
            <el-tag
              :type="backpressureTagType(currentMonitorJob.backpressureLevel)"
              effect="light"
              size="small"
            >
              {{ backpressureLabel(currentMonitorJob.backpressureLevel) }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <h3 style="margin: 20px 0 12px">{{ t('engines.flink.monitor.cpHistory') }}</h3>
        <el-table
          v-loading="cpLoading"
          :data="checkpoints"
          stripe
          border
          size="small"
          :empty-text="cpError ? t('engines.flink.monitor.cpLoadFailed') : t('engines.flink.monitor.cpEmpty')"
        >
          <el-table-column prop="id" :label="t('engines.flink.monitor.cpId')" width="120" />
          <el-table-column prop="triggerTime" :label="t('engines.flink.monitor.cpTrigger')" width="180" />
          <el-table-column prop="completedTime" :label="t('engines.flink.monitor.cpCompleted')" width="180" />
          <el-table-column :label="t('engines.flink.monitor.cpStatus')" width="120">
            <template #default="{ row }">
              <el-tag :type="cpStatusTagType(row.status)" effect="light" size="small">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('engines.flink.monitor.cpSize')" width="120" align="right">
            <template #default="{ row }">{{ formatBytes(row.size) }}</template>
          </el-table-column>
          <el-table-column :label="t('engines.flink.monitor.cpDuration')" width="100">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
        </el-table>

        <h3 style="margin: 20px 0 12px">{{ t('engines.flink.monitor.backpressureTitle') }}</h3>
        <el-table
          v-loading="bpLoading"
          :data="backpressure?.operators ?? []"
          stripe
          border
          size="small"
          :empty-text="bpError ? t('engines.flink.monitor.bpLoadFailed') : t('engines.flink.monitor.bpEmpty')"
        >
          <el-table-column prop="name" :label="t('engines.flink.monitor.operator')" min-width="180" />
          <el-table-column :label="t('engines.flink.monitor.bpLevel')" width="120">
            <template #default="{ row }">
              <el-tag :type="backpressureTagType(row.level)" effect="light" size="small">
                {{ backpressureLabel(row.level) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('engines.flink.monitor.bpRatio')" width="200">
            <template #default="{ row }">
              <el-progress
                :percentage="Math.round(row.ratio * 100)"
                :stroke-width="14"
                :text-inside="true"
              />
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as engineApi from '@/api/engine'
import type { FlinkJob, Checkpoint, BackpressureMetrics, BackpressureLevel } from '@/api/engine'

const { t, te } = useI18n()

/* ------------------------------ 列表查询 ------------------------------ */

const loading = ref(false)
const error = ref(false)
const jobList = ref<FlinkJob[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const statusFilter = ref<string>('')

const appStore = useAppStore()

/** 拉取作业列表 */
async function loadList() {
  loading.value = true
  error.value = false
  try {
    const result = await engineApi.getFlinkJobs({
      workspaceId: appStore.workspace || undefined,
      status: statusFilter.value || undefined,
      page: currentPage.value,
      size: pageSize.value
    })
    jobList.value = result.list
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
  loadList()
}

/** KPI 聚合 */
const kpi = computed(() => {
  const list = jobList.value
  const running = list.filter((j) => j.status === 'RUNNING')
  const failed = list.filter((j) => j.status === 'FAILED').length
  const latencies = running.map((j) => j.latencyMs ?? 0).filter((v) => v > 0)
  const avgLatencyMs = latencies.length
    ? latencies.reduce((s, v) => s + v, 0) / latencies.length
    : 0
  const cpSuccess = list.reduce((s, j) => s + (j.checkpointSuccessCount ?? 0), 0)
  const cpFail = list.reduce((s, j) => s + (j.checkpointFailCount ?? 0), 0)
  const cpTotal = cpSuccess + cpFail
  const cpSuccessRate = cpTotal ? Math.round((cpSuccess / cpTotal) * 100) : 0
  return {
    running: running.length,
    failed,
    avgLatencyMs,
    cpSuccess,
    cpFail,
    cpSuccessRate
  }
})

/* ------------------------------ 提交作业 ------------------------------ */

const submitDialogVisible = ref(false)
const submitting = ref(false)
const submitFormRef = ref<FormInstance>()

interface SubmitForm {
  name: string
  sql: string
  parallelism: number
  checkpointIntervalMs: number
  owner: string
}

const submitForm = reactive<SubmitForm>({
  name: '',
  sql: '',
  parallelism: 4,
  checkpointIntervalMs: 60000,
  owner: ''
})

const submitRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('engines.flink.rules.nameRequired'), trigger: 'blur' }],
  sql: [{ required: true, message: t('engines.flink.rules.sqlRequired'), trigger: 'blur' }],
  parallelism: [{ required: true, message: t('engines.flink.rules.parallelismRequired'), trigger: 'change' }]
}))

/** 打开提交弹窗 */
function openSubmitDialog() {
  resetSubmitForm()
  submitDialogVisible.value = true
}

/** 重置提交表单 */
function resetSubmitForm() {
  submitForm.name = ''
  submitForm.sql = ''
  submitForm.parallelism = 4
  submitForm.checkpointIntervalMs = 60000
  submitForm.owner = ''
  submitFormRef.value?.clearValidate()
}

/** 提交作业 */
async function handleSubmit() {
  if (!submitFormRef.value) return
  await submitFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await engineApi.submitFlinkJob({
        name: submitForm.name,
        workspaceId: appStore.workspace,
        sql: submitForm.sql,
        parallelism: submitForm.parallelism,
        checkpointIntervalMs: submitForm.checkpointIntervalMs,
        owner: submitForm.owner || undefined
      })
      ElMessage.success(t('engines.flink.submit.submitted'))
      submitDialogVisible.value = false
      await loadList()
    } catch {
      // 错误提示已由拦截器统一处理
    } finally {
      submitting.value = false
    }
  })
}

/* ------------------------------ 停止 / Savepoint ------------------------------ */

const stoppingId = ref<string>('')
const savepointingId = ref<string>('')

/** 停止作业 */
async function handleStop(row: FlinkJob) {
  try {
    await ElMessageBox.confirm(t('engines.flink.stopDialog.confirm', { name: row.name }), t('engines.flink.stopDialog.title'), {
      type: 'warning',
      confirmButtonText: t('engines.flink.stopDialog.confirmOk'),
      cancelButtonText: t('engines.flink.stopDialog.cancel')
    })
    stoppingId.value = row.id
    await engineApi.stopFlinkJob(row.id)
    ElMessage.success(t('engines.flink.messages.stopped'))
    await loadList()
  } catch {
    // 用户取消或操作失败
  } finally {
    stoppingId.value = ''
  }
}

/** 触发 Savepoint */
async function handleSavepoint(row: FlinkJob) {
  try {
    savepointingId.value = row.id
    const { savepointPath } = await engineApi.triggerSavepoint(row.id)
    ElMessage.success(t('engines.flink.messages.savepointTriggered', { path: savepointPath }))
    await loadList()
  } catch {
    // 拦截器已提示
  } finally {
    savepointingId.value = ''
  }
}

/** 是否可停止 */
function canStop(status: string): boolean {
  return ['RUNNING', 'RESTARTING', 'SCHEDULED'].includes(status)
}

/** 是否可触发 Savepoint */
function canSavepoint(status: string): boolean {
  return ['RUNNING'].includes(status)
}

/* ------------------------------ 监控详情 ------------------------------ */

const monitorDrawerVisible = ref(false)
const currentMonitorJob = ref<FlinkJob | null>(null)
const checkpoints = ref<Checkpoint[]>([])
const cpLoading = ref(false)
const cpError = ref(false)
const backpressure = ref<BackpressureMetrics | null>(null)
const bpLoading = ref(false)
const bpError = ref(false)

/** 打开监控抽屉 */
async function openMonitorDrawer(row: FlinkJob) {
  currentMonitorJob.value = row
  monitorDrawerVisible.value = true
  await Promise.all([loadCheckpoints(row.id), loadBackpressure(row.id)])
}

/** 关闭监控抽屉 */
function closeMonitorDrawer() {
  currentMonitorJob.value = null
  checkpoints.value = []
  backpressure.value = null
}

/** 加载 Checkpoint 列表 */
async function loadCheckpoints(jobId: string) {
  cpLoading.value = true
  cpError.value = false
  try {
    checkpoints.value = await engineApi.getCheckpoints(jobId)
  } catch {
    cpError.value = true
  } finally {
    cpLoading.value = false
  }
}

/** 加载反压指标 */
async function loadBackpressure(jobId: string) {
  bpLoading.value = true
  bpError.value = false
  try {
    backpressure.value = await engineApi.getBackpressure(jobId)
  } catch {
    bpError.value = true
  } finally {
    bpLoading.value = false
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

const STATUS_MAP: Record<
  string,
  { label: string; type: 'primary' | 'success' | 'danger' | 'info' | 'warning' }
> = {
  RUNNING: { label: '运行中', type: 'primary' },
  FAILED: { label: '失败', type: 'danger' },
  CANCELED: { label: '已取消', type: 'info' },
  FINISHED: { label: '已完成', type: 'success' },
  RESTARTING: { label: '重启中', type: 'warning' },
  CREATED: { label: '已创建', type: 'info' },
  SCHEDULED: { label: '已调度', type: 'warning' }
}

function statusLabel(status: string): string {
  return STATUS_MAP[status]?.label ?? status
}

function statusTagType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return STATUS_MAP[status]?.type ?? 'info'
}

const CP_STATUS_MAP: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
  COMPLETED: 'success',
  IN_PROGRESS: 'warning',
  FAILED: 'danger',
  DISCARDED: 'info'
}

function cpStatusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  return CP_STATUS_MAP[status] ?? 'info'
}

const BACKPRESSURE_MAP: Record<
  BackpressureLevel,
  { label: string; type: 'success' | 'warning' | 'danger' }
> = {
  ok: { label: '正常', type: 'success' },
  low: { label: '低', type: 'warning' },
  high: { label: '高', type: 'danger' }
}

function backpressureLabel(level: BackpressureLevel): string {
  return BACKPRESSURE_MAP[level]?.label ?? level
}

function backpressureTagType(level: BackpressureLevel): 'success' | 'warning' | 'danger' {
  return BACKPRESSURE_MAP[level]?.type ?? 'success'
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

/** 延迟格式化（毫秒） */
function formatLatency(ms?: number): string {
  if (!ms && ms !== 0) return '--'
  if (ms < 1000) return `${Math.round(ms)} ms`
  return `${(ms / 1000).toFixed(2)} s`
}

/** 字节格式化 */
function formatBytes(bytes?: number): string {
  if (!bytes && bytes !== 0) return '--'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/* ------------------------------ 生命周期 ------------------------------ */

let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  loadList()
  // 10s 轮询刷新
  timer = setInterval(() => loadList(), 10000)
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
    void loadList()
  }
)
</script>

<style scoped>
.eng-flink-page {
  padding: 0;
}
.sub {
  color: var(--muted);
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
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 16px;
  background: var(--panel);
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: var(--muted);
  margin: 0 0 8px;
}
.kpi {
  font-size: 28px;
  font-weight: 700;
  color: var(--ink);
  line-height: 1.2;
}
.kpi.s {
  color: var(--green);
}
.kpi.d {
  color: var(--red);
}
.meta {
  font-size: 12px;
  color: var(--muted);
  margin-top: 6px;
}
.page-card {
  border: 1px solid var(--line);
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
