<template>
  <div class="govern-meta-page">
    <h1>{{ t('governMeta.page.title') }}</h1>
    <div class="sub">{{ t('governMeta.page.subtitle') }}</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
    <div class="grid g4">
      <template v-if="loading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>{{ t('governMeta.page.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('governMeta.page.loadingHint') }}</div>
        </div>
      </template>
      <template v-else-if="error">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('governMeta.page.loadFailed') }}</h3>
          <div class="meta" style="color: var(--ds-text-tertiary)">
            {{ t('governMeta.page.loadFailedHint') }}
            <a href="javascript:void(0)" @click="reload">{{ t('governMeta.page.retry') }}</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>{{ t('governMeta.page.kpi.total') }}</h3>
          <div class="kpi">{{ kpi.total }}</div>
          <div class="meta">{{ t('governMeta.page.kpi.totalHint') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('governMeta.page.kpi.active') }}</h3>
          <div class="kpi s">{{ kpi.active }}</div>
          <div class="meta">{{ t('governMeta.page.kpi.activeHint') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('governMeta.page.kpi.today') }}</h3>
          <div class="kpi">{{ kpi.todayCount }}</div>
          <div class="meta">{{ t('governMeta.page.kpi.todayHint') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('governMeta.page.kpi.successRate') }}</h3>
          <div class="kpi">{{ kpi.successRate }}%</div>
          <div class="meta">{{ t('governMeta.page.kpi.successRateHint') }}</div>
        </div>
      </template>
    </div>

    <!-- 主内容区：数据源列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openSourceDialog()">
          {{ t('governMeta.toolbar.add') }}
        </el-button>
        <el-select
          v-model="typeFilter"
          :placeholder="t('governMeta.toolbar.typeFilter')"
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
        :empty-text="error ? t('governMeta.table.loadFailed') : t('governMeta.table.empty')"
      >
        <el-table-column :label="t('governMeta.table.colName')" prop="name" min-width="180" />
        <el-table-column :label="t('governMeta.table.colType')" prop="type" width="120">
          <template #default="{ row }">
            <el-tag effect="light">{{ row.type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('governMeta.table.colStatus')" width="110">
          <template #default="{ row }">
            <StatusTag :status="row.status" :label="statusLabel(row.status)" :status-map="statusTagMap" />
          </template>
        </el-table-column>
        <el-table-column :label="t('governMeta.table.colCron')" prop="cron" width="160">
          <template #default="{ row }">
            <span
              v-if="row.cron"
              style="font-family: var(--ds-font-family-mono); font-size: var(--ds-font-size-xs)"
            >
              {{ row.cron }}
            </span>
            <span v-else style="color: var(--ds-text-tertiary)">{{ t('governMeta.table.cronUnset') }}</span>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('governMeta.table.colLastCollect')"
          prop="lastCollectedAt"
          width="180"
        >
          <template #default="{ row }">
            {{ row.lastCollectedAt || t('governMeta.table.lastEmpty') }}
          </template>
        </el-table-column>
        <el-table-column :label="t('governMeta.table.colActions')" width="320" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :loading="triggeringId === row.id"
              @click="handleTrigger(row)"
            >
              {{ t('governMeta.table.actionCollect') }}
            </el-button>
            <el-button link type="success" :loading="testingId === row.id" @click="handleTest(row)">
              {{ t('governMeta.table.actionTest') }}
            </el-button>
            <el-button link type="warning" @click="openScheduleDialog(row)">
              {{ t('governMeta.table.actionSchedule') }}
            </el-button>
            <el-button link type="primary" @click="openHistoryDrawer(row)">
              {{ t('governMeta.table.actionHistory') }}
            </el-button>
            <el-button link type="primary" @click="openSourceDialog(row)">
              {{ t('governMeta.table.actionEdit') }}
            </el-button>
            <el-button link type="danger" @click="handleDelete(row)">
              {{ t('governMeta.table.actionDelete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 采集历史抽屉 -->
    <el-drawer
      v-model="historyDrawerVisible"
      :title="t('governMeta.history.title', { name: currentSource?.name ?? '' })"
      size="50%"
    >
      <div v-loading="historyLoading">
        <el-empty
          v-if="!historyLoading && historyList.length === 0"
          :description="t('governMeta.history.empty')"
        />
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
              <span class="history-trigger">
                {{ t('governMeta.history.trigger', { type: h.triggerType }) }}
              </span>
              <span class="history-duration">
                {{ t('governMeta.history.duration', { d: formatDuration(h.durationMs) }) }}
              </span>
              <span class="history-count">
                {{
                  t('governMeta.history.count', {
                    n: h.collectedCount ?? t('governMeta.table.lastEmpty')
                  })
                }}
              </span>
              <div v-if="h.errorMessage" class="history-error">{{ h.errorMessage }}</div>
            </div>
          </el-timeline-item>
        </el-timeline>
      </div>
    </el-drawer>

    <!-- 添加/编辑数据源弹窗 -->
    <el-dialog
      v-model="sourceDialogVisible"
      :title="
        sourceForm.id
          ? t('governMeta.sourceDialog.editTitle')
          : t('governMeta.sourceDialog.addTitle')
      "
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
        <el-form-item :label="t('governMeta.sourceDialog.fieldName')" prop="name">
          <el-input
            v-model="sourceForm.name"
            :placeholder="t('governMeta.sourceDialog.nameHint')"
          />
        </el-form-item>
        <el-form-item :label="t('governMeta.sourceDialog.fieldType')" prop="type">
          <el-select
            v-model="sourceForm.type"
            :placeholder="t('governMeta.sourceDialog.typeHint')"
            style="width: 100%"
          >
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
        <el-form-item :label="t('governMeta.sourceDialog.fieldUrl')" prop="connectionUrl">
          <el-input
            v-model="sourceForm.connectionUrl"
            :placeholder="t('governMeta.sourceDialog.urlHint')"
            style="font-family: var(--ds-font-family-mono); font-size: var(--ds-font-size-xs)"
          />
        </el-form-item>
        <el-form-item :label="t('governMeta.sourceDialog.fieldUsername')" prop="username">
          <el-input
            v-model="sourceForm.username"
            :placeholder="t('governMeta.sourceDialog.fieldUsername')"
          />
        </el-form-item>
        <el-form-item :label="t('governMeta.sourceDialog.fieldPassword')" prop="password">
          <el-input
            v-model="sourceForm.password"
            type="password"
            show-password
            :placeholder="t('governMeta.sourceDialog.passwordHint')"
          />
        </el-form-item>
        <el-form-item :label="t('governMeta.sourceDialog.fieldCron')" prop="cron">
          <el-input
            v-model="sourceForm.cron"
            :placeholder="t('governMeta.sourceDialog.cronHint')"
          />
        </el-form-item>
        <el-form-item :label="t('governMeta.sourceDialog.fieldComment')" prop="comment">
          <el-input v-model="sourceForm.comment" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sourceDialogVisible = false">
          {{ t('governMeta.sourceDialog.cancel') }}
        </el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitSource">
          {{ sourceForm.id ? t('governMeta.sourceDialog.save') : t('governMeta.sourceDialog.add') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 定时采集配置弹窗 -->
    <el-dialog
      v-model="scheduleDialogVisible"
      :title="t('governMeta.scheduleDialog.title', { name: currentSource?.name ?? '' })"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px" label-position="right">
        <el-form-item :label="t('governMeta.scheduleDialog.currentCron')">
          <span v-if="currentSource?.cron" style="font-family: var(--ds-font-family-mono)">
            {{ currentSource.cron }}
          </span>
          <span v-else style="color: var(--ds-text-tertiary)">{{ t('governMeta.table.cronUnset') }}</span>
        </el-form-item>
        <el-form-item :label="t('governMeta.scheduleDialog.newCron')">
          <el-input
            v-model="scheduleCron"
            :placeholder="t('governMeta.scheduleDialog.newCronHint')"
            style="font-family: var(--ds-font-family-mono)"
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
          {{ t('governMeta.scheduleDialog.unschedule') }}
        </el-button>
        <el-button @click="scheduleDialogVisible = false">
          {{ t('governMeta.scheduleDialog.close') }}
        </el-button>
        <el-button type="primary" :loading="scheduling" @click="handleSchedule">
          {{ t('governMeta.scheduleDialog.save') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import { StatusTag } from '@/components/ui'
import * as governMetaApi from '@/api/govern-meta'
import type { MetadataSource, CollectionHistory } from '@/api/govern-meta'

const { t } = useI18n()

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
    ElMessage.success(t('governMeta.messages.collectDone', { n: result.collectedCount }))
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
    await ElMessageBox.confirm(
      t('governMeta.messages.deleteConfirm', { name: row.name }),
      t('governMeta.messages.deleteTitle'),
      {
        type: 'warning',
        confirmButtonText: t('governMeta.messages.deleteOk'),
        cancelButtonText: t('common.cancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await governMetaApi.deleteSource(row.id)
    ElMessage.success(t('governMeta.messages.deleted'))
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
  name: [{ required: true, message: t('governMeta.rules.nameRequired'), trigger: 'blur' }],
  type: [{ required: true, message: t('governMeta.rules.typeRequired'), trigger: 'change' }],
  connectionUrl: [{ required: true, message: t('governMeta.rules.urlRequired'), trigger: 'blur' }]
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
        ElMessage.success(t('governMeta.messages.updated'))
      } else {
        await governMetaApi.addSource(payload)
        ElMessage.success(t('governMeta.messages.added'))
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
    ElMessage.warning(t('governMeta.rules.cronRequired'))
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
        ? t('governMeta.messages.scheduled', {
            next: result.nextFireAt
              ? t('governMeta.messages.nextFire', { when: result.nextFireAt })
              : ''
          })
        : t('governMeta.messages.scheduleFailed')
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
      t('governMeta.messages.unscheduleConfirm', { name: currentSource.value.name }),
      t('governMeta.messages.unscheduleTitle'),
      {
        type: 'warning',
        confirmButtonText: t('governMeta.messages.unscheduleOk'),
        cancelButtonText: t('governMeta.messages.unscheduleCancel')
      }
    )
    await governMetaApi.unscheduleCollection(currentSource.value.id)
    ElMessage.success(t('governMeta.messages.unscheduled'))
    scheduleDialogVisible.value = false
    await reload()
  } catch {
    // 用户取消或操作失败
  } finally {
    unscheduling.value = false
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

const STATUS_MAP = computed<
  Record<string, { label: string; type: 'primary' | 'success' | 'danger' | 'info' | 'warning' }>
>(() => ({
  ACTIVE: { label: t('governMeta.labels.statusActive'), type: 'success' },
  INACTIVE: { label: t('governMeta.labels.statusInactive'), type: 'info' },
  ERROR: { label: t('governMeta.labels.statusError'), type: 'danger' }
}))

const HISTORY_STATUS_MAP = computed<
  Record<string, { label: string; type: 'primary' | 'success' | 'danger' | 'info' | 'warning' }>
>(() => ({
  RUNNING: { label: t('governMeta.labels.historyRunning'), type: 'primary' },
  SUCCESS: { label: t('governMeta.labels.historySuccess'), type: 'success' },
  FAILED: { label: t('governMeta.labels.historyFailed'), type: 'danger' }
}))

function statusLabel(status?: string): string {
  return STATUS_MAP.value[status ?? '']?.label ?? status ?? '--'
}


/** StatusTag 组件所需的纯 type 映射（从 STATUS_MAP 派生） */
const statusTagMap = computed<Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'>>(() => {
  const m: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {}
  for (const [k, v] of Object.entries(STATUS_MAP.value)) m[k] = v.type
  return m
})

function historyStatusLabel(status: string): string {
  return HISTORY_STATUS_MAP.value[status]?.label ?? status
}

function historyTagType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return HISTORY_STATUS_MAP.value[status]?.type ?? 'info'
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
  font-size: var(--ds-font-size-base);
  margin-bottom: 16px;
}
.grid {
  display: grid;
  gap: 14px;
}
.grid.g4 {
  grid-template-columns: repeat(4, 1fr);
}
@media (max-width: 1024px) {
  .grid.g4 {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 640px) {
  .grid.g4 {
    grid-template-columns: 1fr;
  }
}
.card {
  border: 1px solid var(--ds-border-default);
  border-radius: var(--ds-radius-md-plus);
  padding: 16px;
  background: var(--ds-bg-surface);
}
.card h3 {
  font-size: var(--ds-font-size-base);
  font-weight: var(--ds-font-weight-semibold);
  color: var(--ds-text-secondary);
  margin: 0 0 8px;
}
.kpi {
  font-size: var(--ds-font-size-4xl);
  font-weight: var(--ds-font-weight-extrabold);
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
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-secondary);
  margin-top: 6px;
}
.page-card {
  border: 1px solid var(--ds-border-default);
  border-radius: var(--ds-radius-md-plus);
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
  font-size: var(--ds-font-size-base);
  color: var(--ds-text-primary);
}
.history-error {
  width: 100%;
  color: var(--ds-color-error-600);
  font-size: var(--ds-font-size-xs);
  margin-top: 4px;
}
</style>
