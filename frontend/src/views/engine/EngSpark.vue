<template>
  <div class="eng-spark-page">
    <h1>批计算（Spark）</h1>
    <div class="sub">Spark 引擎监控 · 批作业管理 · 15 秒自动刷新</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
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
            Spark 作业列表加载失败，<a href="javascript:void(0)" @click="loadList">重试</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>运行中作业</h3>
          <div class="kpi">{{ kpi.running }}</div>
          <div class="meta">当前活跃 Spark 作业</div>
        </div>
        <div class="card">
          <h3>今日完成</h3>
          <div class="kpi s">{{ kpi.finished }}</div>
          <div class="meta">FINISHED 状态</div>
        </div>
        <div class="card">
          <h3>今日失败</h3>
          <div class="kpi d">{{ kpi.failed }}</div>
          <div class="meta">FAILED 状态</div>
        </div>
        <div class="card">
          <h3>平均执行时长</h3>
          <div class="kpi">{{ formatDuration(kpi.avgDurationMs) }}</div>
          <div class="meta">基于已完成作业</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：作业列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openSubmitDialog">+ 提交作业</el-button>
        <el-select
          v-model="statusFilter"
          placeholder="状态筛选"
          clearable
          style="width: 140px"
          @change="handleFilterChange"
        >
          <el-option label="运行中" value="RUNNING" />
          <el-option label="已完成" value="FINISHED" />
          <el-option label="失败" value="FAILED" />
          <el-option label="已取消" value="KILLED" />
          <el-option label="等待中" value="PENDING" />
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
        :empty-text="error ? '加载失败，请重试' : '暂无 Spark 作业'"
      >
        <el-table-column prop="name" label="作业名" min-width="180" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="owner" label="负责人" width="120" />
        <el-table-column prop="submittedAt" label="提交时间" width="180" />
        <el-table-column label="运行时长" width="120">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column prop="driverResource" label="Driver 资源" width="140" />
        <el-table-column label="Stage 进度" width="180">
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
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canRun(row.status)"
              link
              type="primary"
              :loading="runningId === row.id"
              @click="handleRun(row)"
            >
              运行
            </el-button>
            <el-button
              v-if="canCancel(row.status)"
              link
              type="warning"
              :loading="cancelingId === row.id"
              @click="handleCancel(row)"
            >
              取消
            </el-button>
            <el-button link type="primary" @click="openLogDialog(row)">日志</el-button>
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
          @size-change="loadList"
          @current-change="loadList"
        />
      </div>
    </el-card>

    <!-- 提交作业弹窗 -->
    <el-dialog
      v-model="submitDialogVisible"
      title="提交 Spark 批作业"
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
        <el-form-item label="作业名称" prop="name">
          <el-input v-model="submitForm.name" placeholder="如 订单宽表 ETL" />
        </el-form-item>
        <el-form-item label="主类全限定名" prop="mainClass">
          <el-input
            v-model="submitForm.mainClass"
            placeholder="如 com.example.OrderEtlJob"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item label="JAR 路径" prop="jarUri">
          <el-input
            v-model="submitForm.jarUri"
            placeholder="如 hdfs:///apps/order-etl.jar"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item label="启动参数" prop="args">
          <el-input
            v-model="submitForm.args"
            type="textarea"
            :rows="3"
            placeholder="如 --date 2026-08-16 --mode full"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item label="Driver 资源" prop="driverResource">
          <el-input v-model="submitForm.driverResource" placeholder="如 2c/4g" />
        </el-form-item>
        <el-form-item label="Executor 资源" prop="executorResource">
          <el-input v-model="submitForm.executorResource" placeholder="如 4c/8g × 10" />
        </el-form-item>
        <el-form-item label="Cron 表达式" prop="schedule">
          <el-input
            v-model="submitForm.schedule"
            placeholder="如 0 0 * * *（每日 0 点），留空表示手动触发"
          />
        </el-form-item>
        <el-form-item label="负责人" prop="owner">
          <el-input v-model="submitForm.owner" placeholder="负责人姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="submitDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 日志弹窗 -->
    <el-dialog
      v-model="logDialogVisible"
      :title="`作业日志 - ${currentLogJob?.name || ''}`"
      width="800px"
      :close-on-click-modal="true"
      @opened="scrollLogToBottom"
    >
      <div v-loading="logLoading" class="log-container">
        <pre class="log-content">{{ logContent || '暂无日志' }}</pre>
      </div>
      <template #footer>
        <el-button @click="logDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="refreshLog">刷新</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as engineApi from '@/api/engine'
import type { SparkJob } from '@/api/engine'

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

const submitRules: FormRules = {
  name: [{ required: true, message: '请输入作业名称', trigger: 'blur' }],
  mainClass: [{ required: true, message: '请输入主类全限定名', trigger: 'blur' }],
  jarUri: [{ required: true, message: '请输入 JAR 路径', trigger: 'blur' }]
}

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
      ElMessage.success('Spark 作业已提交')
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
    ElMessage.success(`作业已运行，DAG ID：${dagId}`)
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
      `确定取消作业「${row.name}」吗？运行中的作业将被终止。`,
      '取消作业确认',
      { type: 'warning', confirmButtonText: '确定取消', cancelButtonText: '保留' }
    )
    cancelingId.value = row.id
    await engineApi.cancelSparkJob(row.id)
    ElMessage.success('作业已取消')
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
      `确认删除作业「${row.name}」？该操作不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await engineApi.deleteSparkJob(row.id)
    ElMessage.success('作业已删除')
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
    logContent.value = logs || '暂无日志'
    scrollLogToBottom()
  } catch {
    logContent.value = '日志加载失败'
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

const STATUS_MAP: Record<string, { label: string; type: 'primary' | 'success' | 'danger' | 'info' | 'warning' }> = {
  RUNNING: { label: '运行中', type: 'primary' },
  FINISHED: { label: '已完成', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  KILLED: { label: '已取消', type: 'info' },
  PENDING: { label: '等待中', type: 'info' },
  SCHEDULED: { label: '已调度', type: 'warning' },
}

function statusLabel(status: string): string {
  return STATUS_MAP[status]?.label ?? status
}

function statusTagType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return STATUS_MAP[status]?.type ?? 'info'
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

watch(() => appStore.workspace, () => {
  currentPage.value = 1
  void loadList()
})
</script>

<style scoped>
.eng-spark-page {
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