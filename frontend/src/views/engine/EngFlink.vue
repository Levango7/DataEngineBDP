<template>
  <div class="eng-flink-page">
    <h1>流计算（Flink）</h1>
    <div class="sub">Flink 流作业 · Checkpoint · 反压监控 · 15 秒自动刷新</div>

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
            Flink 作业列表加载失败，<a href="javascript:void(0)" @click="loadList">重试</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>运行中作业</h3>
          <div class="kpi">{{ kpi.running }}</div>
          <div class="meta">RUNNING 状态</div>
        </div>
        <div class="card">
          <h3>今日失败</h3>
          <div class="kpi d">{{ kpi.failed }}</div>
          <div class="meta">FAILED 状态</div>
        </div>
        <div class="card">
          <h3>平均延迟</h3>
          <div class="kpi">{{ formatLatency(kpi.avgLatencyMs) }}</div>
          <div class="meta">基于运行中作业</div>
        </div>
        <div class="card">
          <h3>Checkpoint 成功率</h3>
          <div class="kpi s">{{ kpi.cpSuccessRate }}%</div>
          <div class="meta">成功 {{ kpi.cpSuccess }} / 失败 {{ kpi.cpFail }}</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：作业列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openSubmitDialog">+ 提交流作业</el-button>
        <el-select
          v-model="statusFilter"
          placeholder="状态筛选"
          clearable
          style="width: 140px"
          @change="handleFilterChange"
        >
          <el-option label="运行中" value="RUNNING" />
          <el-option label="失败" value="FAILED" />
          <el-option label="已取消" value="CANCELED" />
          <el-option label="已完成" value="FINISHED" />
          <el-option label="重启中" value="RESTARTING" />
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
        :empty-text="error ? '加载失败，请重试' : '暂无 Flink 作业'"
      >
        <el-table-column prop="name" label="作业名" min-width="180" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="并行度" width="100" align="center">
          <template #default="{ row }">{{ row.parallelism }}</template>
        </el-table-column>
        <el-table-column label="运行时长" width="120">
          <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
        </el-table-column>
        <el-table-column label="Checkpoint" width="140" align="center">
          <template #default="{ row }">{{ row.checkpointCount }}</template>
        </el-table-column>
        <el-table-column label="反压" width="100">
          <template #default="{ row }">
            <el-tag :type="backpressureTagType(row.backpressureLevel)" effect="light" size="small">
              {{ backpressureLabel(row.backpressureLevel) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openMonitorDrawer(row)">监控</el-button>
            <el-button
              v-if="canStop(row.status)"
              link
              type="warning"
              :loading="stoppingId === row.id"
              @click="handleStop(row)"
            >
              停止
            </el-button>
            <el-button
              v-if="canSavepoint(row.status)"
              link
              type="primary"
              :loading="savepointingId === row.id"
              @click="handleSavepoint(row)"
            >
              Savepoint
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
      title="提交 Flink 流作业"
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
        <el-form-item label="作业名称" prop="name">
          <el-input v-model="submitForm.name" placeholder="如 实时订单宽表" />
        </el-form-item>
        <el-form-item label="SQL 内容" prop="sql">
          <el-input
            v-model="submitForm.sql"
            type="textarea"
            :rows="8"
            placeholder="Flink SQL 语句"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item label="并行度" prop="parallelism">
          <el-input-number v-model="submitForm.parallelism" :min="1" :max="200" />
        </el-form-item>
        <el-form-item label="Checkpoint 间隔" prop="checkpointIntervalMs">
          <el-input-number
            v-model="submitForm.checkpointIntervalMs"
            :min="1000"
            :step="1000"
          />
          <span style="margin-left: 8px; color: #717a80; font-size: 12px">毫秒</span>
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

    <!-- 监控详情抽屉 -->
    <el-drawer
      v-model="monitorDrawerVisible"
      :title="`作业监控 - ${currentMonitorJob?.name ?? ''}`"
      size="60%"
      @closed="closeMonitorDrawer"
    >
      <template v-if="currentMonitorJob">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="作业 ID">{{ currentMonitorJob.id }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(currentMonitorJob.status)" effect="light">
              {{ statusLabel(currentMonitorJob.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="并行度">{{ currentMonitorJob.parallelism }}</el-descriptions-item>
          <el-descriptions-item label="运行时长">
            {{ formatDuration(currentMonitorJob.durationMs) }}
          </el-descriptions-item>
          <el-descriptions-item label="Source 吞吐">
            {{ currentMonitorJob.sourceThroughput ?? '--' }} 条/秒
          </el-descriptions-item>
          <el-descriptions-item label="Sink 吞吐">
            {{ currentMonitorJob.sinkThroughput ?? '--' }} 条/秒
          </el-descriptions-item>
          <el-descriptions-item label="平均延迟">
            {{ formatLatency(currentMonitorJob.latencyMs) }}
          </el-descriptions-item>
          <el-descriptions-item label="反压等级">
            <el-tag
              :type="backpressureTagType(currentMonitorJob.backpressureLevel)"
              effect="light"
              size="small"
            >
              {{ backpressureLabel(currentMonitorJob.backpressureLevel) }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <h3 style="margin: 20px 0 12px">Checkpoint 历史</h3>
        <el-table
          v-loading="cpLoading"
          :data="checkpoints"
          stripe
          border
          size="small"
          :empty-text="cpError ? '加载失败' : '暂无 Checkpoint'"
        >
          <el-table-column prop="id" label="ID" width="120" />
          <el-table-column prop="triggerTime" label="触发时间" width="180" />
          <el-table-column prop="completedTime" label="完成时间" width="180" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag :type="cpStatusTagType(row.status)" effect="light" size="small">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="大小" width="120" align="right">
            <template #default="{ row }">{{ formatBytes(row.size) }}</template>
          </el-table-column>
          <el-table-column label="耗时" width="100">
            <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
          </el-table-column>
        </el-table>

        <h3 style="margin: 20px 0 12px">反压详情</h3>
        <el-table
          v-loading="bpLoading"
          :data="backpressure?.operators ?? []"
          stripe
          border
          size="small"
          :empty-text="bpError ? '加载失败' : '暂无反压数据'"
        >
          <el-table-column prop="name" label="算子" min-width="180" />
          <el-table-column label="反压等级" width="120">
            <template #default="{ row }">
              <el-tag :type="backpressureTagType(row.level)" effect="light" size="small">
                {{ backpressureLabel(row.level) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="反压比率" width="200">
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
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as engineApi from '@/api/engine'
import type {
  FlinkJob,
  Checkpoint,
  BackpressureMetrics,
  BackpressureLevel
} from '@/api/engine'

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

const submitRules: FormRules = {
  name: [{ required: true, message: '请输入作业名称', trigger: 'blur' }],
  sql: [{ required: true, message: '请输入 Flink SQL', trigger: 'blur' }],
  parallelism: [{ required: true, message: '请设置并行度', trigger: 'change' }]
}

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
      ElMessage.success('Flink 流作业已提交')
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
    await ElMessageBox.confirm(
      `确定停止作业「${row.name}」吗？流作业将被取消。`,
      '停止作业确认',
      { type: 'warning', confirmButtonText: '确定停止', cancelButtonText: '保留' }
    )
    stoppingId.value = row.id
    await engineApi.stopFlinkJob(row.id)
    ElMessage.success('作业已停止')
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
    ElMessage.success(`Savepoint 已触发：${savepointPath}`)
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

/** 状态 → 中文 */
function statusLabel(status: string): string {
  const map: Record<string, string> = {
    RUNNING: '运行中',
    FAILED: '失败',
    CANCELED: '已取消',
    FINISHED: '已完成',
    RESTARTING: '重启中',
    CREATED: '已创建',
    SCHEDULED: '已调度'
  }
  return map[status] ?? status
}

/** 状态 → tag 类型 */
function statusTagType(
  status: string
): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  const map: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
    RUNNING: 'primary',
    FAILED: 'danger',
    CANCELED: 'info',
    FINISHED: 'success',
    RESTARTING: 'warning',
    CREATED: 'info',
    SCHEDULED: 'warning'
  }
  return map[status] ?? 'info'
}

/** Checkpoint 状态 → tag 类型 */
function cpStatusTagType(status: string): 'success' | 'warning' | 'danger' | 'info' {
  const map: Record<string, 'success' | 'warning' | 'danger' | 'info'> = {
    COMPLETED: 'success',
    IN_PROGRESS: 'warning',
    FAILED: 'danger',
    DISCARDED: 'info'
  }
  return map[status] ?? 'info'
}

/** 反压等级 → 中文 */
function backpressureLabel(level: BackpressureLevel): string {
  const map: Record<BackpressureLevel, string> = {
    ok: '正常',
    low: '低',
    high: '高'
  }
  return map[level] ?? level
}

/** 反压等级 → tag 类型 */
function backpressureTagType(level: BackpressureLevel): 'success' | 'warning' | 'danger' {
  const map: Record<BackpressureLevel, 'success' | 'warning' | 'danger'> = {
    ok: 'success',
    low: 'warning',
    high: 'danger'
  }
  return map[level] ?? 'success'
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
  // 15s 轮询刷新
  timer = setInterval(() => loadList(), 15000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.eng-flink-page {
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