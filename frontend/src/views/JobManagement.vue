<template>
  <div class="job-page" role="main" aria-label="作业管理页面">
    <h1>作业管理</h1>
    <div class="sub">
      提交与运维批/流/SQL/Python 作业，支持 Spark / Flink / Trino / Doris
      多引擎，可查看运行日志与状态。
    </div>

    <el-card shadow="never" class="page-card">
      <!-- 顶部操作栏 -->
      <div class="toolbar" role="toolbar" aria-label="作业列表操作栏">
        <el-button type="primary" aria-label="提交作业" @click="openSubmitDialog">
          + 提交作业
        </el-button>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle aria-label="刷新作业列表" @click="loadList" />
      </div>

      <!-- 状态筛选 tabs -->
      <el-tabs
        v-model="activeTab"
        role="tablist"
        aria-label="按作业状态筛选"
        @tab-change="handleTabChange"
      >
        <el-tab-pane label="全部" name="all" />
        <el-tab-pane label="运行中" name="running" />
        <el-tab-pane label="已完成" name="success" />
        <el-tab-pane label="失败" name="failed" />
        <el-tab-pane label="等待中" name="pending" />
      </el-tabs>

      <!-- 作业列表 -->
      <el-table
        v-loading="loading"
        :data="jobList"
        stripe
        border
        role="table"
        aria-label="作业列表表格"
        :empty-text="error ? '加载失败，请重试' : '暂无作业'"
      >
        <el-table-column prop="id" label="ID" width="120" />
        <el-table-column prop="name" label="作业名称" min-width="180" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">
            <el-tag effect="plain" size="small">{{ typeLabel(row.type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="owner" label="负责人" width="120" />
        <el-table-column prop="lastRunAt" label="最近运行" width="180" />
        <el-table-column label="耗时" width="120">
          <template #default="{ row }">
            {{ formatDuration(row.lastRunDuration) }}
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :aria-label="`查看作业 ${row.name} 日志`"
              @click="openLogDialog(row)"
            >
              查看日志
            </el-button>
            <el-button
              v-if="canCancel(row.status)"
              link
              type="warning"
              :loading="cancelingId === row.id"
              :aria-label="`取消作业 ${row.name}`"
              @click="handleCancel(row)"
            >
              取消
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap" role="navigation" aria-label="作业列表分页">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          aria-label="分页导航"
          @size-change="loadList"
          @current-change="loadList"
        />
      </div>
    </el-card>

    <!-- 提交作业弹窗 -->
    <el-dialog
      v-model="submitDialogVisible"
      title="提交作业"
      width="640px"
      :close-on-click-modal="false"
      role="dialog"
      aria-modal="true"
      aria-label="提交作业弹窗"
      @closed="resetSubmitForm"
    >
      <el-form
        ref="submitFormRef"
        :model="submitForm"
        :rules="submitRules"
        label-width="100px"
        label-position="right"
      >
        <el-form-item label="作业名称" prop="name">
          <el-input v-model="submitForm.name" placeholder="如 订单宽表 ETL" aria-label="作业名称" />
        </el-form-item>
        <el-form-item label="作业类型" prop="type">
          <el-select v-model="submitForm.type" style="width: 100%" aria-label="作业类型">
            <el-option label="批作业（Batch）" value="batch" />
            <el-option label="流作业（Stream）" value="stream" />
            <el-option label="SQL 作业" value="sql" />
            <el-option label="Python 作业" value="python" />
            <el-option label="Shell 作业" value="shell" />
          </el-select>
        </el-form-item>
        <el-form-item label="执行引擎" prop="engine">
          <el-select v-model="submitForm.engine" style="width: 100%" aria-label="执行引擎">
            <el-option label="Spark" value="spark" />
            <el-option label="Flink" value="flink" />
            <el-option label="Trino" value="trino" />
            <el-option label="Doris" value="doris" />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人" prop="owner">
          <el-input v-model="submitForm.owner" placeholder="负责人姓名" aria-label="负责人姓名" />
        </el-form-item>
        <el-form-item label="Cron 表达式" prop="schedule">
          <el-input
            v-model="submitForm.schedule"
            placeholder="如 0 0 * * *（每日 0 点），留空表示手动触发"
            aria-label="Cron 调度表达式"
          />
        </el-form-item>
        <el-form-item label="作业代码" prop="code">
          <el-input
            v-model="submitForm.code"
            type="textarea"
            :rows="10"
            placeholder="SQL 语句或代码内容"
            aria-label="作业代码内容"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button aria-label="取消提交" @click="submitDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" aria-label="提交作业" @click="handleSubmit">
          提交
        </el-button>
      </template>
    </el-dialog>

    <!-- 查看日志弹窗 -->
    <el-dialog
      v-model="logDialogVisible"
      :title="`作业日志 - ${currentLogJob?.name || ''}`"
      width="800px"
      :close-on-click-modal="true"
      role="dialog"
      aria-modal="true"
      aria-label="作业日志查看弹窗"
      @opened="scrollLogToBottom"
    >
      <div v-loading="logLoading" class="log-container" role="region" aria-label="日志内容区域">
        <pre class="log-content" aria-label="作业运行日志">{{ logContent || '暂无日志' }}</pre>
      </div>
      <template #footer>
        <el-button aria-label="关闭日志弹窗" @click="logDialogVisible = false">关闭</el-button>
        <el-button type="primary" aria-label="刷新日志" @click="refreshLog">刷新</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as jobApi from '@/api/job'
import type { Job, JobStatus, JobType, PagedResult } from '@/api/types'

/* ------------------------------ 列表查询 ------------------------------ */

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
    onError: () => ElMessage.error('作业列表加载失败')
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

const submitRules: FormRules = {
  name: [{ required: true, message: '请输入作业名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择作业类型', trigger: 'change' }],
  engine: [{ required: true, message: '请选择执行引擎', trigger: 'change' }],
  code: [{ required: true, message: '请输入作业代码', trigger: 'blur' }]
}

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
      ElMessage.success('作业已提交')
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
      `确定取消作业「${row.name}」吗？运行中的作业将被终止。`,
      '取消作业确认',
      {
        type: 'warning',
        confirmButtonText: '确定取消',
        cancelButtonText: '保留'
      }
    )
    cancelingId.value = row.id
    await jobApi.cancelJob(row.id)
    ElMessage.success('作业已取消')
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

const TYPE_LABELS: Record<JobType, string> = {
  batch: '批作业',
  stream: '流作业',
  sql: 'SQL',
  python: 'Python',
  shell: 'Shell'
}

function typeLabel(type: JobType): string {
  return TYPE_LABELS[type] ?? type
}

const STATUS_MAP: Record<
  JobStatus,
  { label: string; type: 'primary' | 'success' | 'danger' | 'info' | 'warning' }
> = {
  running: { label: '运行中', type: 'primary' },
  success: { label: '已完成', type: 'success' },
  failed: { label: '失败', type: 'danger' },
  canceled: { label: '已取消', type: 'info' },
  pending: { label: '等待中', type: 'info' },
  scheduled: { label: '已调度', type: 'warning' }
}

function statusLabel(status: JobStatus): string {
  return STATUS_MAP[status]?.label ?? status
}

function statusTagType(status: JobStatus): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return STATUS_MAP[status]?.type ?? 'info'
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
