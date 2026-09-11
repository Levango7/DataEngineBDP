<template>
  <div>
    <PageHeader :title="t('ops.title')" :subtitle="t('ops.subtitle')" />
    <div class="grid g4">
      <div class="card">
        <h3>{{ t('ops.kpi.health') }}</h3>
        <div class="kpi s">
          <span class="pill" :class="healthPillClass(overview?.clusterHealth)">
            {{ healthPillText(overview?.clusterHealth) }}
          </span>
        </div>
      </div>
      <div class="card">
        <h3>{{ t('ops.kpi.runningJobs') }}</h3>
        <div class="kpi">{{ overview?.runningJobCount ?? '--' }}</div>
      </div>
      <div class="card">
        <h3>{{ t('ops.kpi.todayFailed') }}</h3>
        <div class="kpi s">{{ overview?.todayFailedCount ?? '--' }}</div>
      </div>
      <div class="card">
        <h3>{{ t('ops.kpi.avgLatency') }}</h3>
        <div class="kpi s">{{ overview?.avgLatencySec ?? '--' }}s</div>
      </div>
    </div>

    <!-- 统一运维台：组件健康总览（红黄绿） -->
    <div class="card" style="margin-top: 14px">
      <h3>
        {{ t('ops.componentsTitle') }}
        <span v-if="healthSummary" class="health-summary-meta">
          （{{ healthSummary.up }}/{{ healthSummary.total }} UP
          <span v-if="healthSummary.warn">· {{ healthSummary.warn }} WARN</span>
          <span v-if="healthSummary.down" class="health-down">· {{ healthSummary.down }} DOWN</span>
          ）
        </span>
      </h3>
      <div v-if="healthLoading" class="muted-text">{{ t('common.loading') }}</div>
      <div v-else-if="healthError" class="error-text">
        {{ healthError.message }}，
        <a href="javascript:void(0)" @click="loadHealth">{{ t('common.retry') }}</a>
      </div>
      <div v-else class="health-grid">
        <div
          v-for="c in healthComponents"
          :key="c.name"
          class="health-item"
          :title="`${c.url} · ${c.latencyMs}ms${c.detail ? ' · ' + c.detail : ''}`"
        >
          <span class="health-dot" :class="'st-' + c.status.toLowerCase()"></span>
          <span class="health-name">{{ c.name }}</span>
          <span class="health-status">{{ c.status }}</span>
        </div>
      </div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>{{ t('ops.jobsTitle') }}</h3>
      <div v-if="jobsLoading" class="muted-text">{{ t('common.loading') }}</div>
      <div v-else-if="jobsError" class="error-text">
        {{ jobsError.message }}，
        <a href="javascript:void(0)" @click="loadJobs">{{ t('common.retry') }}</a>
      </div>
      <el-table v-else-if="jobs" :data="jobs" stripe>
        <el-table-column :label="t('ops.jobCols.job')" prop="name" />
        <el-table-column :label="t('ops.jobCols.type')">
          <template #default="{ row }">{{ jobTypeLabel(row.type) }}</template>
        </el-table-column>
        <el-table-column :label="t('ops.jobCols.duration')" prop="duration" />
        <el-table-column :label="t('ops.jobCols.status')">
          <template #default="{ row }">
            <span class="pill" :class="jobStatusPillClass(row.status)">
              {{ jobStatusPillText(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('ops.log')">
          <template #default="{ row }">
            <el-button size="small" @click="openLog(row)">{{ t('ops.log') }}</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">{{ t('ops.jobsEmpty') }}</div>
        </template>
      </el-table>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3 class="alerts-title">
        {{ t('ops.alertsTitle') }}
        <span class="pill r">{{ filteredAlerts.length }}</span>
        <el-select v-model="alertLevelFilter" size="small" class="alert-filter" @change="() => {}">
          <el-option :label="t('ops.alertLevels.all')" value="all" />
          <el-option :label="t('ops.alertLevels.critical')" value="critical" />
          <el-option :label="t('ops.alertLevels.warn')" value="warn" />
          <el-option :label="t('ops.alertLevels.info')" value="info" />
        </el-select>
        <el-button size="small" @click="loadAlerts">
          {{ t('ops.refresh') }}
        </el-button>
      </h3>
      <div v-if="alertsLoading" class="muted-text">{{ t('common.loading') }}</div>
      <el-table v-else-if="filteredAlerts" :data="filteredAlerts" stripe>
        <el-table-column :label="t('ops.alertCols.alert')" prop="content" />
        <el-table-column :label="t('ops.alertCols.level')">
          <template #default="{ row }">
            <span class="pill" :class="alertLevelPillClass(row.level)">
              {{ alertLevelPillText(row.level) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('ops.alertCols.triggeredAt')">
          <template #default="{ row }">{{ formatAlertTime(row.triggeredAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('ops.alertCols.status')">
          <template #default="{ row }">
            <span class="pill" :class="row.handled ? 'g' : 'a'">
              {{ row.handled ? t('ops.handled') : t('ops.active') }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('ops.detail')">
          <template #default="{ row }">
            <el-button size="small" @click="openAlertDetail(row)">{{ t('ops.detail') }}</el-button>
            <el-button size="small" type="primary" @click="handleAlert(row)">
              {{ t('ops.handle') }}
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="empty-cell">{{ t('ops.alertsEmpty') }}</div>
        </template>
      </el-table>
    </div>

    <!-- 作业日志抽屉 -->
    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>{{ t('ops.logDrawer.title', { name: currentJob?.name }) }}</template>
      <div class="runlog" style="height: auto">
        <div v-if="logLoading" class="muted-text">{{ t('ops.logDrawer.loading') }}</div>
        <pre v-else class="log-pre">{{ logContent || t('ops.logDrawer.empty') }}</pre>
      </div>
      <div class="note">{{ t('ops.logDrawer.note') }}</div>
    </Drawer>

    <!-- 告警详情弹窗 -->
    <Modal
      :visible="alertDetailVisible"
      :title="t('ops.alertModal.title')"
      @close="alertDetailVisible = false"
    >
      <div v-if="currentAlert">
        <label>{{ t('ops.alertModal.content') }}</label>
        <div class="alert-detail-row">{{ currentAlert.content }}</div>
        <label>{{ t('ops.alertModal.level') }}</label>
        <div class="alert-detail-row">
          <span class="pill" :class="alertLevelPillClass(currentAlert.level)">
            {{ alertLevelPillText(currentAlert.level) }}
          </span>
        </div>
        <label>{{ t('ops.alertModal.triggeredAt') }}</label>
        <div class="alert-detail-row">{{ formatAlertTime(currentAlert.triggeredAt) }}</div>
        <label>{{ t('ops.alertModal.status') }}</label>
        <div class="alert-detail-row">
          <span class="pill" :class="currentAlert.handled ? 'g' : 'a'">
            {{ currentAlert.handled ? t('ops.handled') : t('ops.active') }}
          </span>
        </div>
        <label>{{ t('ops.alertModal.id') }}</label>
        <div class="alert-detail-row">
          <code>{{ currentAlert.id }}</code>
        </div>
      </div>
      <template #footer>
        <el-button @click="alertDetailVisible = false">
          {{ t('ops.alertModal.close') }}
        </el-button>
        <el-button
          v-if="currentAlert && !currentAlert.handled"
          type="primary"
          @click="handleAlert(currentAlert)"
        >
          {{ t('ops.alertModal.handle') }}
        </el-button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader } from '@/components/ui'
import Drawer from '@/components/Drawer.vue'
import Modal from '@/components/Modal.vue'
import * as opsApi from '@/api/ops'
import type {
  OpsOverview,
  OpsJob,
  Alert,
  OpsJobType,
  OpsJobStatus,
  AlertLevel,
  ComponentHealth,
  HealthOverview
} from '@/api/ops'

const { t, locale } = useI18n()
const store = useAppStore()
const drawerVisible = ref(false)
const alertDetailVisible = ref(false)
const currentAlert = ref<Alert | null>(null)
const alertLevelFilter = ref<'all' | AlertLevel>('all')

// 概览：通过 useApi 包装，失败时不阻塞页面
const { data: overview, execute: loadOverview } = useApi<OpsOverview>(() => opsApi.getOverview())

// 组件健康总览：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: healthData,
  loading: healthLoading,
  error: healthError,
  execute: loadHealth
} = useApi<HealthOverview>(() => opsApi.getHealthOverview())
const healthComponents = computed<ComponentHealth[]>(() => healthData.value?.components ?? [])
const healthSummary = computed<HealthOverview['summary'] | null>(
  () => healthData.value?.summary ?? null
)

// 作业列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: jobs,
  loading: jobsLoading,
  error: jobsError,
  execute: loadJobs
} = useApi<OpsJob[]>(() => opsApi.listJobs(), { initialData: [] })

// 告警列表：通过 useApi 包装，失败时不阻塞页面
const {
  data: alerts,
  loading: alertsLoading,
  execute: loadAlerts
} = useApi<Alert[]>(() => opsApi.listAlerts(), { initialData: [] })

/** 按级别筛选告警 */
const filteredAlerts = computed<Alert[]>(() => {
  if (!alerts.value) return []
  if (alertLevelFilter.value === 'all') return alerts.value
  return alerts.value.filter((a) => a.level === alertLevelFilter.value)
})

// 日志：通过 useApi 包装，按需加载
const {
  data: logContent,
  loading: logLoading,
  execute: loadLog
} = useApi<string, [string]>((id: string) => opsApi.getJobLogs(id), { initialData: '' })

// 当前查看日志的作业
const currentJob = ref<OpsJob | null>(null)

/** 打开日志抽屉 */
async function openLog(job: OpsJob): Promise<void> {
  currentJob.value = job
  drawerVisible.value = true
  await loadLog(job.id)
}

/** 打开告警详情弹窗 */
function openAlertDetail(alert: Alert): void {
  currentAlert.value = alert
  alertDetailVisible.value = true
}

/** 处理告警 */
async function handleAlert(alert: Alert): Promise<void> {
  try {
    await opsApi.handleAlert(alert.id, '处理')
    store.showToast(t('ops.toast.handled'))
    alertDetailVisible.value = false
    await loadAlerts()
    await loadOverview()
  } catch {
    // 错误提示已由拦截器统一处理
  }
}

/** 格式化告警时间（跟随当前语言环境） */
function formatAlertTime(iso: string): string {
  if (!iso) return '--'
  try {
    return new Date(iso).toLocaleString(locale.value)
  } catch {
    return iso
  }
}

/** 健康状态 → pill 样式 */
function healthPillClass(s?: string): string {
  switch (s) {
    case 'healthy':
      return 'g'
    case 'warning':
      return 'a'
    case 'critical':
      return 'r'
    default:
      return 'b'
  }
}

/** 健康状态 → pill 文案 */
function healthPillText(s?: string): string {
  switch (s) {
    case 'healthy':
      return t('ops.health.healthy')
    case 'warning':
      return t('ops.health.warning')
    case 'critical':
      return t('ops.health.critical')
    default:
      return '--'
  }
}

/** 作业类型 → 词条 */
function jobTypeLabel(jt: OpsJobType): string {
  switch (jt) {
    case 'stream_flink':
      return t('ops.jobTypes.stream_flink')
    case 'batch_spark':
      return t('ops.jobTypes.batch_spark')
    case 'batch_dag':
      return t('ops.jobTypes.batch_dag')
    default:
      return jt
  }
}

/** 作业状态 → pill 样式 */
function jobStatusPillClass(s: OpsJobStatus): string {
  switch (s) {
    case 'running':
      return 'a'
    case 'success':
      return 'g'
    case 'failed':
      return 'r'
    default:
      return 'b'
  }
}

/** 作业状态 → pill 文案 */
function jobStatusPillText(s: OpsJobStatus): string {
  switch (s) {
    case 'running':
      return t('ops.jobStatus.running')
    case 'success':
      return t('ops.jobStatus.success')
    case 'failed':
      return t('ops.jobStatus.failed')
    case 'pending':
      return t('ops.jobStatus.pending')
    default:
      return s
  }
}

/** 告警级别 → pill 样式 */
function alertLevelPillClass(l: AlertLevel): string {
  switch (l) {
    case 'warn':
      return 'a'
    case 'critical':
      return 'r'
    default:
      return 'b'
  }
}

/** 告警级别 → pill 文案 */
function alertLevelPillText(l: AlertLevel): string {
  switch (l) {
    case 'warn':
      return t('ops.alertLevels.warn')
    case 'critical':
      return t('ops.alertLevels.critical')
    default:
      return t('ops.alertLevels.info')
  }
}

/* ------------------------------ 15 秒轮询自动刷新 ------------------------------ */
let refreshTimer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void loadOverview()
  void loadJobs()
  void loadAlerts()
  void loadHealth()
  // 15 秒轮询刷新概览和告警
  refreshTimer = setInterval(() => {
    void loadOverview()
    void loadAlerts()
  }, 15000)
})

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
})
</script>

<style scoped>
/* 告警详情行：背景使用 design tokens 次级表面色 */
.alert-detail-row {
  margin-bottom: 12px;
  padding: 6px 8px;
  background: var(--ds-bg-subtle);
  border-radius: var(--ds-radius-sm);
  font-size: var(--ds-font-size-sm);
}

/* 健康总览元信息 */
.health-summary-meta {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  font-weight: var(--ds-font-weight-normal);
}
.health-down {
  color: var(--ds-color-error-600);
}

/* 通用文字色（使用 design tokens） */
.muted-text {
  color: var(--ds-text-tertiary);
}
.error-text {
  color: var(--ds-color-error-600);
}
.error-text a {
  color: var(--ds-color-primary-600);
  cursor: pointer;
}

/* 告警标题行内控件布局 */
.alerts-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.alert-filter {
  width: 140px;
}

/* 日志 pre 样式 */
.log-pre {
  white-space: pre-wrap;
  font-family: var(--ds-font-family-mono);
}

/* 空状态单元格 */
.empty-cell {
  text-align: center;
  color: var(--ds-text-tertiary);
  padding: 16px;
}

/* 响应式断点：中等屏幕紧凑化 */
@media (max-width: 1024px) {
  :deep(.el-table) {
    font-size: var(--ds-font-size-sm);
  }
  .health-grid {
    grid-template-columns: repeat(2, 1fr) !important;
  }
}

/* 响应式断点：小屏幕单列布局 */
@media (max-width: 640px) {
  :deep(.el-table) {
    font-size: var(--ds-font-size-xs);
  }
  :deep(.el-table .cell) {
    padding-left: 8px;
    padding-right: 8px;
  }
  .health-grid {
    grid-template-columns: 1fr !important;
  }
  .alerts-title {
    flex-wrap: wrap;
  }
  .alert-filter {
    width: 100%;
  }
}
</style>
