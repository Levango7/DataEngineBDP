<template>
  <div class="eng-spark-page">
    <h1>{{ t('engines.spark.title') }}</h1>
    <div class="sub">{{ t('engines.spark.subtitle') }}</div>

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
            {{ t('engines.spark.loadFailed') }}
            <a href="javascript:void(0)" @click="loadList">
              {{ t('engines.kpi.loadFailedRetry') }}
            </a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>{{ t('engines.spark.kpi.runningJob') }}</h3>
          <div class="kpi">{{ kpi.running }}</div>
          <div class="meta">{{ t('engines.spark.kpi.runningJobMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engines.spark.kpi.todayFinished') }}</h3>
          <div class="kpi s">{{ kpi.finished }}</div>
          <div class="meta">{{ t('engines.spark.kpi.todayFinishedMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engines.spark.kpi.todayFailed') }}</h3>
          <div class="kpi d">{{ kpi.failed }}</div>
          <div class="meta">{{ t('engines.spark.kpi.todayFailedMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('engines.spark.kpi.avgDuration') }}</h3>
          <div class="kpi">{{ formatDuration(kpi.avgDurationMs) }}</div>
          <div class="meta">{{ t('engines.spark.kpi.avgDurationMeta') }}</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：作业列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openSubmitDialog">
          {{ t('engines.spark.submitJob') }}
        </el-button>
        <el-select
          v-model="statusFilter"
          :placeholder="t('engines.spark.statusFilter')"
          clearable
          style="width: 140px"
          @change="handleFilterChange"
        >
          <el-option :label="t('engines.spark.statuses.RUNNING')" value="RUNNING" />
          <el-option :label="t('engines.spark.statuses.FINISHED')" value="FINISHED" />
          <el-option :label="t('engines.spark.statuses.FAILED')" value="FAILED" />
          <el-option :label="t('engines.spark.statuses.KILLED')" value="KILLED" />
          <el-option :label="t('engines.spark.statuses.PENDING')" value="PENDING" />
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
        :empty-text="error ? t('engines.kafka.loadFailed') : t('engines.spark.table.empty')"
      >
        <el-table-column
          prop="name"
          :label="t('engines.spark.table.columns.name')"
          min-width="180"
        />
        <el-table-column :label="t('engines.spark.table.columns.status')" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="owner" :label="t('engines.spark.table.columns.owner')" width="120" />
        <el-table-column
          prop="submittedAt"
          :label="t('engines.spark.table.columns.submittedAt')"
          width="180"
        />
        <el-table-column :label="t('engines.spark.table.columns.duration')" width="120">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column
          prop="driverResource"
          :label="t('engines.spark.table.columns.driverResource')"
          width="140"
        />
        <el-table-column :label="t('engines.spark.table.columns.stageProgress')" width="180">
          <template #default="{ row }">
            <el-progress
              v-if="row.stageTotal"
              :percentage="stagePercent(row.stageCompleted, row.stageTotal)"
              :stroke-width="14"
              :text-inside="true"
            />
            <span v-else>--</span>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('engines.spark.table.columns.actions')"
          width="220"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              v-if="canRun(row.status)"
              link
              type="primary"
              :loading="runningId === row.id"
              @click="handleRun(row)"
            >
              {{ t('engines.spark.table.actions.run') }}
            </el-button>
            <el-button
              v-if="canCancel(row.status)"
              link
              type="warning"
              :loading="cancelingId === row.id"
              @click="handleCancel(row)"
            >
              {{ t('engines.spark.table.actions.cancel') }}
            </el-button>
            <el-button link type="primary" @click="openLogDialog(row)">
              {{ t('engines.spark.table.actions.log') }}
            </el-button>
            <el-button link type="danger" @click="handleDelete(row)">
              {{ t('engines.spark.table.actions.delete') }}
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

    <!-- 提交作业弹窗 -->
    <el-dialog
      v-model="submitDialogVisible"
      :title="t('engines.spark.submit.title')"
      width="640px"
      :close-on-click-modal="false"
      @closed="resetSubmitForm"
    >
      <el-form
        ref="submitFormRef"
        :model="submitForm"
        :rules="submitRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item :label="t('engines.spark.submit.fields.name')" prop="name">
          <el-input
            v-model="submitForm.name"
            :placeholder="t('engines.spark.submit.fields.namePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('engines.spark.submit.fields.mainClass')" prop="mainClass">
          <el-input
            v-model="submitForm.mainClass"
            :placeholder="t('engines.spark.submit.fields.mainClassPlaceholder')"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item :label="t('engines.spark.submit.fields.jarUri')" prop="jarUri">
          <el-input
            v-model="submitForm.jarUri"
            :placeholder="t('engines.spark.submit.fields.jarUriPlaceholder')"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item :label="t('engines.spark.submit.fields.args')" prop="args">
          <el-input
            v-model="submitForm.args"
            type="textarea"
            :rows="3"
            :placeholder="t('engines.spark.submit.fields.argsPlaceholder')"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item
          :label="t('engines.spark.submit.fields.driverResource')"
          prop="driverResource"
        >
          <el-input
            v-model="submitForm.driverResource"
            :placeholder="t('engines.spark.submit.fields.driverResourcePlaceholder')"
          />
        </el-form-item>
        <el-form-item
          :label="t('engines.spark.submit.fields.executorResource')"
          prop="executorResource"
        >
          <el-input
            v-model="submitForm.executorResource"
            :placeholder="t('engines.spark.submit.fields.executorResourcePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('engines.spark.submit.fields.schedule')" prop="schedule">
          <el-input
            v-model="submitForm.schedule"
            :placeholder="t('engines.spark.submit.fields.schedulePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('engines.spark.submit.fields.owner')" prop="owner">
          <el-input
            v-model="submitForm.owner"
            :placeholder="t('engines.spark.submit.fields.ownerPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitDialogVisible = false">
          {{ t('engines.spark.submit.actions.cancel') }}
        </el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ t('engines.spark.submit.actions.submit') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 日志弹窗 -->
    <el-dialog
      v-model="logDialogVisible"
      :title="t('engines.spark.log.title', { name: currentLogJob?.name || '' })"
      width="800px"
      :close-on-click-modal="true"
      @opened="scrollLogToBottom"
    >
      <div v-loading="logLoading" class="log-container">
        <pre class="log-content">{{ logContent || t('engines.spark.log.empty') }}</pre>
      </div>
      <template #footer>
        <el-button @click="logDialogVisible = false">{{ t('engines.spark.log.close') }}</el-button>
        <el-button type="primary" @click="refreshLog">
          {{ t('engines.spark.log.refresh') }}
        </el-button>
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
import * as engineApi from '@/api/engine'
import type { SparkJob } from '@/api/engine'

const { t, te } = useI18n()

/* ------------------------------ 列表查询 ------------------------------ */

const loading = ref(false)
const error = ref(false)
const jobList = ref<SparkJob[]>([])
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
    const result = await engineApi.getSparkJobs({
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
  const running = list.filter((j) => j.status === 'RUNNING').length
  const finished = list.filter((j) => j.status === 'FINISHED').length
  const failed = list.filter((j) => j.status === 'FAILED').length
  const completed = list.filter((j) => j.status === 'FINISHED' && j.durationMs)
  const totalDuration = completed.reduce((s, j) => s + (j.durationMs ?? 0), 0)
  const avgDurationMs = completed.length ? totalDuration / completed.length : 0
  return { running, finished, failed, avgDurationMs }
})

/* ------------------------------ 提交作业 ------------------------------ */

const submitDialogVisible = ref(false)
const submitting = ref(false)
const submitFormRef = ref<FormInstance>()

interface SubmitForm {
  name: string
  mainClass: string
  jarUri: string
  args: string
  driverResource: string
  executorResource: string
  schedule: string
  owner: string
}

const submitForm = reactive<SubmitForm>({
  name: '',
  mainClass: '',
  jarUri: '',
  args: '',
  driverResource: '2c/4g',
  executorResource: '4c/8g × 10',
  schedule: '',
  owner: ''
})

const submitRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('engines.spark.rules.nameRequired'), trigger: 'blur' }],
  mainClass: [
    { required: true, message: t('engines.spark.rules.mainClassRequired'), trigger: 'blur' }
  ],
  jarUri: [{ required: true, message: t('engines.spark.rules.jarUriRequired'), trigger: 'blur' }]
}))

/** 打开提交弹窗 */
function openSubmitDialog() {
  resetSubmitForm()
  submitDialogVisible.value = true
}

/** 重置提交表单 */
function resetSubmitForm() {
  submitForm.name = ''
  submitForm.mainClass = ''
  submitForm.jarUri = ''
  submitForm.args = ''
  submitForm.driverResource = '2c/4g'
  submitForm.executorResource = '4c/8g × 10'
  submitForm.schedule = ''
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
      await engineApi.submitSparkJob({
        name: submitForm.name,
        workspaceId: appStore.workspace,
        mainClass: submitForm.mainClass,
        jarUri: submitForm.jarUri || undefined,
        args: submitForm.args || undefined,
        driverResource: submitForm.driverResource || undefined,
        executorResource: submitForm.executorResource || undefined,
        schedule: submitForm.schedule || undefined,
        owner: submitForm.owner || undefined
      })
      ElMessage.success(t('engines.spark.messages.submitted'))
      submitDialogVisible.value = false
      await loadList()
    } catch {
      // 错误提示已由拦截器统一处理
    } finally {
      submitting.value = false
    }
  })
}

/* ------------------------------ 运行 / 取消 / 删除 ------------------------------ */

const runningId = ref<string>('')
const cancelingId = ref<string>('')

/** 运行作业 */
async function handleRun(row: SparkJob) {
  runningId.value = row.id
  try {
    const { dagId } = await engineApi.runSparkJob(row.id)
    ElMessage.success(t('engines.spark.messages.running', { dagId }))
    await loadList()
  } catch {
    // 拦截器已提示
  } finally {
    runningId.value = ''
  }
}

/** 取消作业 */
async function handleCancel(row: SparkJob) {
  try {
    await ElMessageBox.confirm(
      t('engines.spark.cancelDialog.confirm', { name: row.name }),
      t('engines.spark.cancelDialog.title'),
      {
        type: 'warning',
        confirmButtonText: t('engines.spark.cancelDialog.confirmOk'),
        cancelButtonText: t('engines.spark.cancelDialog.cancel')
      }
    )
    cancelingId.value = row.id
    await engineApi.cancelSparkJob(row.id)
    ElMessage.success(t('engines.spark.messages.cancelled'))
    await loadList()
  } catch {
    // 用户取消或操作失败
  } finally {
    cancelingId.value = ''
  }
}

/** 删除作业 */
async function handleDelete(row: SparkJob) {
  try {
    await ElMessageBox.confirm(
      t('engines.spark.deleteDialog.confirm', { name: row.name }),
      t('engines.spark.deleteDialog.title'),
      {
        type: 'warning',
        confirmButtonText: t('engines.spark.deleteDialog.confirmOk'),
        cancelButtonText: t('engines.spark.deleteDialog.cancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await engineApi.deleteSparkJob(row.id)
    ElMessage.success(t('engines.spark.messages.deleted'))
    await loadList()
  } catch {
    // 用户取消或删除失败
  }
}

/** 是否可运行 */
function canRun(status: string): boolean {
  return ['PENDING', 'SCHEDULED', 'FINISHED', 'FAILED', 'KILLED'].includes(status)
}

/** 是否可取消 */
function canCancel(status: string): boolean {
  return ['RUNNING', 'PENDING', 'SCHEDULED'].includes(status)
}

/* ------------------------------ 日志 ------------------------------ */

const logDialogVisible = ref(false)
const logLoading = ref(false)
const logContent = ref<string>('')
const currentLogJob = ref<SparkJob | null>(null)

/** 打开日志弹窗 */
async function openLogDialog(row: SparkJob) {
  currentLogJob.value = row
  logDialogVisible.value = true
  await refreshLog()
}

/** 刷新日志 */
async function refreshLog() {
  if (!currentLogJob.value) return
  logLoading.value = true
  try {
    const logs = await engineApi.getSparkJobLogs(currentLogJob.value.id)
    logContent.value = logs || t('engines.spark.log.empty')
    scrollLogToBottom()
  } catch {
    logContent.value = t('engines.spark.log.loadFailed')
  } finally {
    logLoading.value = false
  }
}

/** 日志滚动到底部 */
function scrollLogToBottom() {
  const container = document.querySelector('.log-content') as HTMLElement
  if (container) {
    container.scrollTop = container.scrollHeight
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

const STATUS_TAG_TYPE_MAP: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
  RUNNING: 'primary',
  FINISHED: 'success',
  FAILED: 'danger',
  KILLED: 'info',
  PENDING: 'info',
  SCHEDULED: 'warning'
}

function statusLabel(status: string): string {
  const key = `engines.spark.statuses.${status}`
  return te(key) ? t(key) : status
}

function statusTagType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return STATUS_TAG_TYPE_MAP[status] ?? 'info'
}

/** Stage 进度百分比 */
function stagePercent(completed?: number, total?: number): number {
  if (!total || total === 0) return 0
  return Math.round(((completed ?? 0) / total) * 100)
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
  loadList()
  // 15s 轮询刷新
  timer = setInterval(() => loadList(), 15000)
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
.eng-spark-page {
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
.log-container {
  background: #1a2027;
  border-radius: 8px;
  padding: 12px;
  min-height: 320px;
  max-height: 480px;
  overflow: auto;
}
.log-content {
  color: #cbd5e1;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
</style>
