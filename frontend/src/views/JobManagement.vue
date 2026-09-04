<template>
  <div class="job-page" role="main" :aria-label="t('jobmgmt.pageAria')">
    <h1>{{ t('jobmgmt.title') }}</h1>
    <div class="sub">{{ t('jobmgmt.subtitle') }}</div>

    <el-card shadow="never" class="page-card">
      <!-- 顶部操作栏 -->
      <div class="toolbar" role="toolbar" :aria-label="t('jobmgmt.toolbarAria')">
        <el-button type="primary" :aria-label="t('jobmgmt.submitAria')" @click="openSubmitDialog">
          {{ t('jobmgmt.submitJob') }}
        </el-button>
        <div class="spacer"></div>
        <el-button
          :icon="Refresh"
          circle
          :aria-label="t('jobmgmt.refreshAria')"
          @click="loadList"
        />
      </div>

      <!-- 状态筛选 tabs -->
      <el-tabs
        v-model="activeTab"
        role="tablist"
        :aria-label="t('jobmgmt.tabsAria')"
        @tab-change="handleTabChange"
      >
        <el-tab-pane :label="t('jobmgmt.tabs.all')" name="all" />
        <el-tab-pane :label="t('jobmgmt.tabs.running')" name="running" />
        <el-tab-pane :label="t('jobmgmt.tabs.success')" name="success" />
        <el-tab-pane :label="t('jobmgmt.tabs.failed')" name="failed" />
        <el-tab-pane :label="t('jobmgmt.tabs.pending')" name="pending" />
      </el-tabs>

      <!-- 作业列表 -->
      <el-table
        v-loading="loading"
        :data="jobList"
        stripe
        border
        role="table"
        :aria-label="t('jobmgmt.tableAria')"
        :empty-text="error ? t('jobmgmt.emptyError') : t('jobmgmt.empty')"
      >
        <el-table-column prop="id" label="ID" width="120" />
        <el-table-column prop="name" :label="t('jobmgmt.cols.name')" min-width="180" />
        <el-table-column :label="t('jobmgmt.cols.type')" width="100">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('jobmgmt.cols.status')" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="owner" :label="t('jobmgmt.cols.owner')" width="120" />
        <el-table-column prop="lastRunAt" :label="t('jobmgmt.cols.lastRun')" width="180" />
        <el-table-column :label="t('jobmgmt.cols.duration')" width="120">
          <template #default="{ row }">
            {{ formatDuration(row.lastRunDuration) }}
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('jobmgmt.cols.createdAt')" width="180" />
        <el-table-column :label="t('jobmgmt.cols.actions')" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :aria-label="t('jobmgmt.viewLogAria', { name: row.name })"
              @click="openLogDialog(row)"
            >
              {{ t('jobmgmt.viewLog') }}
            </el-button>
            <el-button
              v-if="canCancel(row.status)"
              link
              type="warning"
              :loading="cancelingId === row.id"
              :aria-label="t('jobmgmt.cancelAria', { name: row.name })"
              @click="handleCancel(row)"
            >
              {{ t('jobmgmt.cancel') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap" role="navigation" :aria-label="t('jobmgmt.paginationAria')">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          :aria-label="t('jobmgmt.paginationNavAria')"
          @size-change="loadList"
          @current-change="loadList"
        />
      </div>
    </el-card>

    <!-- 提交作业弹窗 -->
    <el-dialog
      v-model="submitDialogVisible"
      :title="t('jobmgmt.submitModal.title')"
      width="640px"
      :close-on-click-modal="false"
      role="dialog"
      aria-modal="true"
      :aria-label="t('jobmgmt.submitModal.aria')"
      @closed="resetSubmitForm"
    >
      <el-form
        ref="submitFormRef"
        :model="submitForm"
        :rules="submitRules"
        label-width="100px"
        label-position="right"
      >
        <el-form-item :label="t('jobmgmt.submitModal.name')" prop="name">
          <el-input
            v-model="submitForm.name"
            :placeholder="t('jobmgmt.submitModal.namePlaceholder')"
            :aria-label="t('jobmgmt.submitModal.name')"
          />
        </el-form-item>
        <el-form-item :label="t('jobmgmt.submitModal.type')" prop="type">
          <el-select
            v-model="submitForm.type"
            style="width: 100%"
            :aria-label="t('jobmgmt.submitModal.type')"
          >
            <el-option :label="t('jobmgmt.submitModal.typeBatch')" value="batch" />
            <el-option :label="t('jobmgmt.submitModal.typeStream')" value="stream" />
            <el-option :label="t('jobmgmt.submitModal.typeSql')" value="sql" />
            <el-option :label="t('jobmgmt.submitModal.typePython')" value="python" />
            <el-option :label="t('jobmgmt.submitModal.typeShell')" value="shell" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('jobmgmt.submitModal.engine')" prop="engine">
          <el-select
            v-model="submitForm.engine"
            style="width: 100%"
            :aria-label="t('jobmgmt.submitModal.engine')"
          >
            <el-option label="Spark" value="spark" />
            <el-option label="Flink" value="flink" />
            <el-option label="Trino" value="trino" />
            <el-option label="Doris" value="doris" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('jobmgmt.submitModal.owner')" prop="owner">
          <el-input
            v-model="submitForm.owner"
            :placeholder="t('jobmgmt.submitModal.ownerPlaceholder')"
            :aria-label="t('jobmgmt.submitModal.owner')"
          />
        </el-form-item>
        <el-form-item :label="t('jobmgmt.submitModal.cron')" prop="schedule">
          <el-input
            v-model="submitForm.schedule"
            :placeholder="t('jobmgmt.submitModal.cronPlaceholder')"
            :aria-label="t('jobmgmt.submitModal.cron')"
          />
        </el-form-item>
        <el-form-item :label="t('jobmgmt.submitModal.code')" prop="code">
          <el-input
            v-model="submitForm.code"
            type="textarea"
            :rows="10"
            :placeholder="t('jobmgmt.submitModal.codePlaceholder')"
            :aria-label="t('jobmgmt.submitModal.code')"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button
          :aria-label="t('jobmgmt.submitModal.cancelAria')"
          @click="submitDialogVisible = false"
        >
          {{ t('common.cancel') }}
        </el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :aria-label="t('jobmgmt.submitAria')"
          @click="handleSubmit"
        >
          {{ t('jobmgmt.submitModal.submit') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 查看日志弹窗 -->
    <el-dialog
      v-model="logDialogVisible"
      :title="t('jobmgmt.logModal.title', { name: currentLogJob?.name || '' })"
      width="800px"
      :close-on-click-modal="true"
      role="dialog"
      aria-modal="true"
      :aria-label="t('jobmgmt.logModal.aria')"
      @opened="scrollLogToBottom"
    >
      <div
        v-loading="logLoading"
        class="log-container"
        role="region"
        :aria-label="t('jobmgmt.logModal.regionAria')"
      >
        <pre class="log-content" :aria-label="t('jobmgmt.logModal.logAria')">{{
          logContent || t('jobmgmt.logModal.empty')
        }}</pre>
      </div>
      <template #footer>
        <el-button :aria-label="t('jobmgmt.logModal.closeAria')" @click="logDialogVisible = false">
          {{ t('jobmgmt.logModal.close') }}
        </el-button>
        <el-button
          type="primary"
          :aria-label="t('jobmgmt.logModal.refreshAria')"
          @click="refreshLog"
        >
          {{ t('jobmgmt.logModal.refresh') }}
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
import { useApi } from '@/composables/useApi'
import * as jobApi from '@/api/job'
import type { Job, JobStatus, JobType, PagedResult } from '@/api/types'

/* ------------------------------ 列表查询 ------------------------------ */

const { t } = useI18n()

// 作业列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: jobPaged,
  loading,
  error,
  execute: loadList
} = useApi<PagedResult<Job>>(
  () =>
    jobApi.listJobs({
      status: activeTab.value === 'all' ? undefined : (activeTab.value as JobStatus),
      page: currentPage.value,
      pageSize: pageSize.value
    }),
  {
    onError: () => ElMessage.error(t('jobmgmt.loadFailed'))
  }
)

const jobList = computed<Job[]>(() => jobPaged.value?.list ?? [])
const total = computed<number>(() => jobPaged.value?.total ?? 0)
const currentPage = ref(1)
const pageSize = ref(20)
const activeTab = ref<string>('all')

/** 拉取作业列表 */
const appStore = useAppStore()

// 工作空间切换时重载列表（修复 #4：切换后残留旧工作空间数据）
watch(
  () => appStore.workspace,
  () => {
    currentPage.value = 1
    void loadList()
  }
)

/** tab 切换 */
function handleTabChange() {
  currentPage.value = 1
  void loadList()
}

/* ------------------------------ 提交作业 ------------------------------ */

const submitDialogVisible = ref(false)
const submitting = ref(false)
const submitFormRef = ref<FormInstance>()

interface SubmitForm {
  name: string
  type: JobType
  engine: string
  owner: string
  schedule: string
  code: string
}

const submitForm = reactive<SubmitForm>({
  name: '',
  type: 'sql',
  engine: 'spark',
  owner: '',
  schedule: '',
  code: ''
})

const submitRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('jobmgmt.submitModal.nameRequired'), trigger: 'blur' }],
  type: [{ required: true, message: t('jobmgmt.submitModal.typeRequired'), trigger: 'change' }],
  engine: [{ required: true, message: t('jobmgmt.submitModal.engineRequired'), trigger: 'change' }],
  code: [{ required: true, message: t('jobmgmt.submitModal.codeRequired'), trigger: 'blur' }]
}))

/** 打开提交弹窗 */
function openSubmitDialog() {
  resetSubmitForm()
  submitDialogVisible.value = true
}

/** 重置提交表单 */
function resetSubmitForm() {
  submitForm.name = ''
  submitForm.type = 'sql'
  submitForm.engine = 'spark'
  submitForm.owner = ''
  submitForm.schedule = ''
  submitForm.code = ''
  submitFormRef.value?.clearValidate()
}

/** 提交作业 */
async function handleSubmit() {
  if (!submitFormRef.value) return
  await submitFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      // 组装作业配置：将引擎与代码合并为 JSON 字符串
      const config = JSON.stringify({
        engine: submitForm.engine,
        code: submitForm.code
      })
      await jobApi.submitJob({
        name: submitForm.name,
        workspaceId: appStore.workspace,
        type: submitForm.type,
        config,
        schedule: submitForm.schedule || undefined,
        owner: submitForm.owner || undefined
      })
      ElMessage.success(t('jobmgmt.submitModal.submitted'))
      submitDialogVisible.value = false
      await loadList()
    } catch {
      // 错误提示已由拦截器统一处理
    } finally {
      submitting.value = false
    }
  })
}

/* ------------------------------ 取消作业 ------------------------------ */

const cancelingId = ref<string>('')

/** 取消作业 */
async function handleCancel(row: Job) {
  try {
    await ElMessageBox.confirm(
      t('jobmgmt.cancelConfirm.message', { name: row.name }),
      t('jobmgmt.cancelConfirm.title'),
      {
        type: 'warning',
        confirmButtonText: t('jobmgmt.cancelConfirm.confirm'),
        cancelButtonText: t('jobmgmt.cancelConfirm.keep')
      }
    )
    cancelingId.value = row.id
    await jobApi.cancelJob(row.id)
    ElMessage.success(t('jobmgmt.cancelConfirm.canceled'))
    await loadList()
  } catch {
    // 用户取消或操作失败
  } finally {
    cancelingId.value = ''
  }
}

/** 是否可取消 */
function canCancel(status: JobStatus): boolean {
  return ['running', 'pending', 'scheduled'].includes(status)
}

/* ------------------------------ 查看日志 ------------------------------ */

const logDialogVisible = ref(false)
const currentLogJob = ref<Job | null>(null)

// 作业日志：通过 useApi 包装按需加载
const {
  data: logContent,
  loading: logLoading,
  execute: loadLog
} = useApi<string, [string]>((id: string) => jobApi.getJobLogs(id), { initialData: '' })

/** 打开日志弹窗 */
async function openLogDialog(row: Job) {
  currentLogJob.value = row
  logDialogVisible.value = true
  await refreshLog()
}

/** 刷新日志 */
async function refreshLog() {
  if (!currentLogJob.value) return
  await loadLog(currentLogJob.value.id)
  scrollLogToBottom()
}

/** 日志滚动到底部 */
function scrollLogToBottom() {
  const container = document.querySelector('.log-content') as HTMLElement
  if (container) {
    container.scrollTop = container.scrollHeight
  }
}

/* ------------------------------ 标签辅助 ------------------------------ */

const JOB_TYPES: JobType[] = ['batch', 'stream', 'sql', 'python', 'shell']

function typeLabel(type: JobType): string {
  return JOB_TYPES.includes(type) ? t(`jobmgmt.types.${type}`) : type
}

const STATUS_TAG_TYPES: Record<JobStatus, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
  running: 'primary',
  success: 'success',
  failed: 'danger',
  canceled: 'info',
  pending: 'info',
  scheduled: 'warning'
}

const JOB_STATUSES: JobStatus[] = [
  'running',
  'success',
  'failed',
  'canceled',
  'pending',
  'scheduled'
]

function statusLabel(status: JobStatus): string {
  return JOB_STATUSES.includes(status) ? t(`jobmgmt.status.${status}`) : status
}

function statusTagType(status: JobStatus): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return STATUS_TAG_TYPES[status] ?? 'info'
}

/** 耗时格式化 */
function formatDuration(seconds?: number): string {
  if (!seconds && seconds !== 0) return '--'
  if (seconds < 60) return `${seconds}s`
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ${seconds % 60}s`
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  return `${h}h ${m}m`
}

/* ------------------------------ 初始化 ------------------------------ */

/* ------------------------------ 自动刷新（5 秒轮询） ------------------------------ */

let pollTimer: ReturnType<typeof setInterval> | null = null

/** 启动轮询：仅当存在运行中/等待中作业时才刷新列表，避免无谓请求 */
function startPolling(): void {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    // 日志弹窗打开时不刷新列表（避免分页跳动）
    if (logDialogVisible.value) return
    const hasActive = jobList.value.some(
      (j) => j.status === 'running' || j.status === 'pending' || j.status === 'scheduled'
    )
    if (hasActive) {
      void loadList()
    }
  }, 5000)
}

function stopPolling(): void {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

onMounted(() => {
  void loadList()
  startPolling()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.job-page {
  padding: 0;
}
.sub {
  color: var(--ds-text-secondary);
  font-size: 13px;
  margin-bottom: 16px;
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
