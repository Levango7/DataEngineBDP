<template>
  <div class="govern-meta-page">
    <h1>元数据管理</h1>
    <div class="sub">数据源 · 采集调度 · 采集历史 · 30 秒自动刷新</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
    <div class="grid g4">
      <template v-if="loading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="error">
        <div class="card" style="grid-column: span 4">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            数据源列表加载失败，
            <a href="javascript:void(0)" @click="reload">重试</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>数据源总数</h3>
          <div class="kpi">{{ kpi.total }}</div>
          <div class="meta">已登记采集数据源</div>
        </div>
        <div class="card">
          <h3>活跃数据源</h3>
          <div class="kpi s">{{ kpi.active }}</div>
          <div class="meta">状态为 ACTIVE</div>
        </div>
        <div class="card">
          <h3>今日采集次数</h3>
          <div class="kpi">{{ kpi.todayCount }}</div>
          <div class="meta">基于采集历史聚合</div>
        </div>
        <div class="card">
          <h3>最近采集成功率</h3>
          <div class="kpi">{{ kpi.successRate }}%</div>
          <div class="meta">最近 20 次采集</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：数据源列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openSourceDialog()">+ 添加数据源</el-button>
        <el-select
          v-model="typeFilter"
          placeholder="类型筛选"
          clearable
          style="width: 160px"
          @change="reload"
        >
          <el-option v-for="t in collectorTypes" :key="t" :label="t" :value="t" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="reload" />
      </div>

      <el-table
        v-loading="loading"
        :data="filteredSources"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? '加载失败，请重试' : '暂无数据源'"
      >
        <el-table-column prop="name" label="数据源名称" min-width="180" />
        <el-table-column prop="type" label="类型" width="120">
          <template #default="{ row }">
            <el-tag effect="light">{{ row.type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="cron" label="Cron 表达式" width="160">
          <template #default="{ row }">
            <span
              v-if="row.cron"
              style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
            >
              {{ row.cron }}
            </span>
            <span v-else style="color: var(--muted)">未配置</span>
          </template>
        </el-table-column>
        <el-table-column prop="lastCollectedAt" label="最近采集" width="180">
          <template #default="{ row }">{{ row.lastCollectedAt || '--' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :loading="triggeringId === row.id"
              @click="handleTrigger(row)"
            >
              采集
            </el-button>
            <el-button link type="success" :loading="testingId === row.id" @click="handleTest(row)">
              测试
            </el-button>
            <el-button link type="warning" @click="openScheduleDialog(row)">调度</el-button>
            <el-button link type="primary" @click="openHistoryDrawer(row)">历史</el-button>
            <el-button link type="primary" @click="openSourceDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 采集历史抽屉 -->
    <el-drawer
      v-model="historyDrawerVisible"
      :title="`采集历史 - ${currentSource?.name ?? ''}`"
      size="50%"
    >
      <div v-loading="historyLoading">
        <el-empty v-if="!historyLoading && historyList.length === 0" description="暂无采集历史" />
        <el-timeline v-else>
          <el-timeline-item
            v-for="h in historyList"
            :key="h.taskId"
            :timestamp="h.triggeredAt"
            :type="historyTimelineType(h.status)"
            placement="top"
          >
            <div class="history-item">
              <span class="history-status">
                <el-tag :type="historyTagType(h.status)" size="small" effect="light">
                  {{ historyStatusLabel(h.status) }}
                </el-tag>
              </span>
              <span class="history-trigger">触发：{{ h.triggerType }}</span>
              <span class="history-duration">耗时：{{ formatDuration(h.durationMs) }}</span>
              <span class="history-count">对象数：{{ h.collectedCount ?? '--' }}</span>
              <div v-if="h.errorMessage" class="history-error">{{ h.errorMessage }}</div>
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- 添加/编辑数据源弹窗 -->
    <el-dialog
      v-model="sourceDialogVisible"
      :title="sourceForm.id ? '编辑数据源' : '添加数据源'"
      width="640px"
      :close-on-click-modal="false"
      @closed="resetSourceForm"
    >
      <el-form
        ref="sourceFormRef"
        :model="sourceForm"
        :rules="sourceRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item label="数据源名称" prop="name">
          <el-input v-model="sourceForm.name" placeholder="如 prod-hive-cluster" />
        </el-form-item>
        <el-form-item label="类型" prop="type">
          <el-select v-model="sourceForm.type" placeholder="选择类型" style="width: 100%">
            <el-option v-for="t in collectorTypes" :key="t" :label="t" :value="t" />
            <el-option label="hive" value="hive" />
            <el-option label="mysql" value="mysql" />
            <el-option label="postgres" value="postgres" />
            <el-option label="kafka" value="kafka" />
            <el-option label="iotdb" value="iotdb" />
            <el-option label="doris" value="doris" />
            <el-option label="clickhouse" value="clickhouse" />
            <el-option label="hbase" value="hbase" />
            <el-option label="es" value="es" />
          </el-select>
        </el-form-item>
        <el-form-item label="连接 URL" prop="connectionUrl">
          <el-input
            v-model="sourceForm.connectionUrl"
            placeholder="如 jdbc:hive2://host:10000/db"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item label="用户名" prop="username">
          <el-input v-model="sourceForm.username" placeholder="用户名" />
        </el-form-item>
        <el-form-item label="密码" prop="password">
          <el-input
            v-model="sourceForm.password"
            type="password"
            show-password
            placeholder="留空表示不修改"
          />
        </el-form-item>
        <el-form-item label="Cron 表达式" prop="cron">
          <el-input
            v-model="sourceForm.cron"
            placeholder="如 0 0 * * *（每日 0 点），留空表示仅手动触发"
          />
        </el-form-item>
        <el-form-item label="备注" prop="comment">
          <el-input v-model="sourceForm.comment" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sourceDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitSource">
          {{ sourceForm.id ? '保存' : '添加' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 定时采集配置弹窗 -->
    <el-dialog
      v-model="scheduleDialogVisible"
      :title="`定时采集配置 - ${currentSource?.name ?? ''}`"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px" label-position="right">
        <el-form-item label="当前 Cron">
          <span
            v-if="currentSource?.cron"
            style="font-family: 'SFMono-Regular', Consolas, monospace"
          >
            {{ currentSource.cron }}
          </span>
          <span v-else style="color: var(--muted)">未配置</span>
        </el-form-item>
        <el-form-item label="新 Cron">
          <el-input
            v-model="scheduleCron"
            placeholder="如 0 0 * * *"
            style="font-family: 'SFMono-Regular', Consolas, monospace"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button
          v-if="currentSource?.cron"
          type="danger"
          :loading="unscheduling"
          @click="handleUnschedule"
        >
          取消定时
        </el-button>
        <el-button @click="scheduleDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="scheduling" @click="handleSchedule">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as governMetaApi from '@/api/govern-meta'
import type { MetadataSource, CollectionHistory } from '@/api/govern-meta'

/* ------------------------------ 数据源列表 ------------------------------ */

const {
  data: sources,
  loading,
  error,
  execute: reload
} = useApi<MetadataSource[]>(() => governMetaApi.listSources())

const collectorTypes = ref<string[]>([])
const typeFilter = ref<string>('')

/** 拉取已注册 Collector 类型 */
async function loadCollectors() {
  try {
    collectorTypes.value = await governMetaApi.listCollectors()
  } catch {
    // 拦截器已提示，使用默认类型
  }
}

/** 按类型筛选后的数据源 */
const filteredSources = computed(() => {
  const list = sources.value ?? []
  if (!typeFilter.value) return list
  return list.filter((s) => s.type === typeFilter.value)
})

/** KPI 聚合 */
const kpi = computed(() => {
  const list = sources.value ?? []
  const total = list.length
  const active = list.filter((s) => s.status === 'ACTIVE').length
  const today = new Date().toISOString().slice(0, 10)
  const todayCount = list.filter((s) => s.lastCollectedAt?.startsWith(today)).length
  // 最近采集成功率：基于 lastCollectedCount > 0 视为成功，简化口径
  const collectedRecently = list.filter((s) => s.lastCollectedAt)
  const success = collectedRecently.filter(
    (s) => (s.lastCollectedCount ?? 0) > 0 && s.status !== 'ERROR'
  ).length
  const successRate = collectedRecently.length
    ? Math.round((success / collectedRecently.length) * 100)
    : 0
  return { total, active, todayCount, successRate }
})

/* ------------------------------ 采集 / 测试 / 删除 ------------------------------ */

const triggeringId = ref<number | undefined>()
const testingId = ref<number | undefined>()

/** 手动触发采集 */
async function handleTrigger(row: MetadataSource) {
  if (!row.id) return
  triggeringId.value = row.id
  try {
    const result = await governMetaApi.triggerCollection(row.id)
    ElMessage.success(`采集完成，共 ${result.collectedCount} 个对象`)
    await reload()
  } catch {
    // 拦截器已提示
  } finally {
    triggeringId.value = undefined
  }
}

/** 测试连接 */
async function handleTest(row: MetadataSource) {
  if (!row.id) return
  testingId.value = row.id
  try {
    const { connected, message } = await governMetaApi.testConnection(row.id)
    ElMessage[connected ? 'success' : 'error'](message)
  } catch {
    // 拦截器已提示
  } finally {
    testingId.value = undefined
  }
}

/** 删除数据源 */
async function handleDelete(row: MetadataSource) {
  if (!row.id) return
  try {
    await ElMessageBox.confirm(`确认删除数据源「${row.name}」？该操作不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await governMetaApi.deleteSource(row.id)
    ElMessage.success('数据源已删除')
    await reload()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 采集历史抽屉 ------------------------------ */

const historyDrawerVisible = ref(false)
const historyLoading = ref(false)
const historyList = ref<CollectionHistory[]>([])
const currentSource = ref<MetadataSource | null>(null)

/** 打开采集历史抽屉 */
async function openHistoryDrawer(row: MetadataSource) {
  currentSource.value = row
  historyDrawerVisible.value = true
  historyLoading.value = true
  try {
    historyList.value = await governMetaApi.listCollectionHistory(row.id!)
  } catch {
    historyList.value = []
  } finally {
    historyLoading.value = false
  }
}

/* ------------------------------ 添加 / 编辑数据源 ------------------------------ */

const sourceDialogVisible = ref(false)
const submitting = ref(false)
const sourceFormRef = ref<FormInstance>()

interface SourceForm {
  id?: number
  name: string
  type: string
  connectionUrl: string
  username: string
  password: string
  cron: string
  comment: string
}

const sourceForm = reactive<SourceForm>({
  id: undefined,
  name: '',
  type: 'hive',
  connectionUrl: '',
  username: '',
  password: '',
  cron: '',
  comment: ''
})

const sourceRules: FormRules = {
  name: [{ required: true, message: '请输入数据源名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  connectionUrl: [{ required: true, message: '请输入连接 URL', trigger: 'blur' }]
}

/** 打开添加/编辑弹窗 */
function openSourceDialog(row?: MetadataSource) {
  resetSourceForm()
  if (row) {
    sourceForm.id = row.id
    sourceForm.name = row.name
    sourceForm.type = row.type
    sourceForm.connectionUrl = row.connectionUrl
    sourceForm.username = row.username ?? ''
    sourceForm.password = ''
    sourceForm.cron = row.cron ?? ''
    sourceForm.comment = row.comment ?? ''
  }
  sourceDialogVisible.value = true
}

/** 重置表单 */
function resetSourceForm() {
  sourceForm.id = undefined
  sourceForm.name = ''
  sourceForm.type = 'hive'
  sourceForm.connectionUrl = ''
  sourceForm.username = ''
  sourceForm.password = ''
  sourceForm.cron = ''
  sourceForm.comment = ''
  sourceFormRef.value?.clearValidate()
}

/** 提交添加/编辑 */
async function handleSubmitSource() {
  if (!sourceFormRef.value) return
  await sourceFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const payload: MetadataSource = {
        name: sourceForm.name,
        type: sourceForm.type,
        connectionUrl: sourceForm.connectionUrl,
        username: sourceForm.username || undefined,
        password: sourceForm.password || undefined,
        cron: sourceForm.cron || undefined,
        comment: sourceForm.comment || undefined
      }
      if (sourceForm.id) {
        await governMetaApi.updateSource(sourceForm.id, payload)
        ElMessage.success('数据源已更新')
      } else {
        await governMetaApi.addSource(payload)
        ElMessage.success('数据源已添加')
      }
      sourceDialogVisible.value = false
      await reload()
    } catch {
      // 拦截器已提示
    } finally {
      submitting.value = false
    }
  })
}

/* ------------------------------ 定时采集配置 ------------------------------ */

const scheduleDialogVisible = ref(false)
const scheduleCron = ref('')
const scheduling = ref(false)
const unscheduling = ref(false)

/** 打开调度配置弹窗 */
function openScheduleDialog(row: MetadataSource) {
  currentSource.value = row
  scheduleCron.value = row.cron ?? ''
  scheduleDialogVisible.value = true
}

/** 保存定时采集 */
async function handleSchedule() {
  if (!currentSource.value?.id) return
  if (!scheduleCron.value.trim()) {
    ElMessage.warning('请输入 Cron 表达式')
    return
  }
  scheduling.value = true
  try {
    const result = await governMetaApi.scheduleCollection(
      currentSource.value.id,
      scheduleCron.value.trim()
    )
    ElMessage.success(
      result.scheduled
        ? `定时采集已注册${result.nextFireAt ? '，下次触发：' + result.nextFireAt : ''}`
        : '注册失败'
    )
    scheduleDialogVisible.value = false
    await reload()
  } catch {
    // 拦截器已提示
  } finally {
    scheduling.value = false
  }
}

/** 取消定时采集 */
async function handleUnschedule() {
  if (!currentSource.value?.id) return
  unscheduling.value = true
  try {
    await ElMessageBox.confirm(
      `确认取消数据源「${currentSource.value.name}」的定时采集？`,
      '取消定时确认',
      { type: 'warning', confirmButtonText: '确定取消', cancelButtonText: '保留' }
    )
    await governMetaApi.unscheduleCollection(currentSource.value.id)
    ElMessage.success('定时采集已取消')
    scheduleDialogVisible.value = false
    await reload()
  } catch {
    // 用户取消或操作失败
  } finally {
    unscheduling.value = false
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

const STATUS_MAP: Record<
  string,
  { label: string; type: 'primary' | 'success' | 'danger' | 'info' | 'warning' }
> = {
  ACTIVE: { label: '活跃', type: 'success' },
  INACTIVE: { label: '停用', type: 'info' },
  ERROR: { label: '异常', type: 'danger' }
}

function statusLabel(status?: string): string {
  return STATUS_MAP[status ?? '']?.label ?? status ?? '--'
}

function statusTagType(status?: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return STATUS_MAP[status ?? '']?.type ?? 'info'
}

const HISTORY_STATUS_MAP: Record<
  string,
  { label: string; type: 'primary' | 'success' | 'danger' | 'info' | 'warning' }
> = {
  RUNNING: { label: '运行中', type: 'primary' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' }
}

function historyStatusLabel(status: string): string {
  return HISTORY_STATUS_MAP[status]?.label ?? status
}

function historyTagType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return HISTORY_STATUS_MAP[status]?.type ?? 'info'
}

/** 采集历史状态 → timeline 类型 */
function historyTimelineType(
  status: string
): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return historyTagType(status)
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
  void reload()
  void loadCollectors()
  // 30s 轮询刷新
  timer = setInterval(() => void reload(), 30000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.govern-meta-page {
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
.history-item {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
  font-size: 13px;
  color: var(--ds-text-primary);
}
.history-error {
  width: 100%;
  color: var(--ds-color-error-600);
  font-size: 12px;
  margin-top: 4px;
}
</style>
