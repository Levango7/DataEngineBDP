<template>
  <div>
    <PageHeader :title="t('integrate.title')" :subtitle="t('integrate.subtitle')" />
    <div class="section-title">{{ t('integrate.connectors') }}</div>
    <div v-if="connectorsLoading" class="conn-grid state-loading">
      {{ t('integrate.connectorsLoading') }}
    </div>
    <div v-else-if="connectorsError" class="conn-grid state-error">
      {{ t('common.loadFailed') }}，
      <a href="javascript:void(0)" @click="loadConnectors">{{ t('common.retry') }}</a>
    </div>
    <div v-else-if="connectors.length === 0" class="conn-grid state-loading">
      {{ t('integrate.connectorsEmpty') }}
    </div>
    <div v-else class="conn-grid">
      <div
        v-for="c in connectors"
        :key="`${c.name}-${c.category || 'source'}`"
        class="conn"
        :class="{ selected: isConnectorSelected(c) }"
        @click="onConnectorClick(c)"
      >
        <div class="logo">{{ c.logo }}</div>
        {{ c.name }}
        <span
          class="pill"
          :class="connectorPillClass(c.status)"
          style="display: block; margin-top: 6px"
        >
          {{ connectorPillText(c.status) }}
        </span>
        <span v-if="c.category" class="category-tag">
          {{
            c.category === 'source' ? t('integrate.categorySource') : t('integrate.categorySink')
          }}
        </span>
      </div>
    </div>
    <Toolbar
      style="margin-top: 16px"
      :show-create="true"
      :create-label="t('integrate.newTask')"
      :create-aria-label="t('integrate.newTask')"
      :show-refresh="false"
      @create="openSyncModal"
    >
      <template #actions>
        <span class="pill b">{{ t('integrate.batchStream') }}</span>
      </template>
    </Toolbar>
    <div class="card">
      <div v-if="tasksLoading" class="state-tip state-loading">
        {{ t('integrate.tasksLoading') }}
      </div>
      <div v-else-if="tasksError" class="state-tip state-error">
        {{ tasksError.message }}，
        <a href="javascript:void(0)" @click="loadTasks">{{ t('common.retry') }}</a>
      </div>
      <!-- 同步任务列表：使用 el-table 替换原生 table，操作列使用 el-button -->
      <el-table
        v-else
        :data="tasks"
        stripe
        border
        role="table"
        :aria-label="t('integrate.title')"
        :empty-text="t('integrate.tasksEmpty')"
      >
        <el-table-column prop="name" :label="t('integrate.cols.task')" min-width="160" />
        <el-table-column
          prop="sourceToTarget"
          :label="t('integrate.cols.sourceToTarget')"
          min-width="180"
        />
        <el-table-column :label="t('integrate.cols.mode')" width="100">
          <template #default="{ row }">
            {{ modeLabel(row.mode) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('integrate.cols.status')" width="120">
          <template #default="{ row }">
            <span class="pill" :class="statusPillClass(row.status)">
              {{ statusPillText(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('integrate.cols.lastRun')" width="200">
          <template #default="{ row }">
            {{ row.lastRunAt || '--'
            }}{{ row.lastRunDuration ? ' · ' + row.lastRunDuration : '' }}
          </template>
        </el-table-column>
        <el-table-column :label="t('integrate.cols.actions')" width="140" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status !== 'running'"
              size="small"
              type="primary"
              :loading="actingId === row.id"
              @click="handleRunTask(row)"
            >
              {{ actingId === row.id ? t('integrate.running') : t('integrate.run') }}
            </el-button>
            <el-button
              v-else
              size="small"
              :loading="actingId === row.id"
              @click="handleStopTask(row)"
            >
              {{ actingId === row.id ? t('integrate.stopping') : t('integrate.stop') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 新建同步任务弹窗 -->
    <Modal
      :visible="syncModal"
      :title="t('integrate.createModal.title')"
      @close="syncModal = false"
    >
      <label>{{ t('integrate.createModal.name') }}</label>
      <el-input v-model="syncForm.name" :placeholder="t('integrate.createModal.namePlaceholder')" />
      <label>{{ t('integrate.createModal.sourceType') }}</label>
      <el-select v-model="syncForm.sourceType" style="width: 100%">
        <el-option v-for="c in sourceConnectors" :key="c.name" :label="c.name" :value="c.name" />
      </el-select>
      <label>{{ t('integrate.createModal.targetType') }}</label>
      <el-select v-model="syncForm.targetType" style="width: 100%">
        <el-option v-for="c in sinkConnectors" :key="c.name" :label="c.name" :value="c.name" />
      </el-select>
      <label>{{ t('integrate.createModal.sourceTable') }}</label>
      <el-input
        v-model="syncForm.sourceTable"
        :placeholder="t('integrate.createModal.sourceTablePlaceholder')"
      />
      <label>{{ t('integrate.createModal.targetTable') }}</label>
      <el-input
        v-model="syncForm.targetTable"
        :placeholder="t('integrate.createModal.targetTablePlaceholder')"
      />
      <label>{{ t('integrate.createModal.mode') }}</label>
      <el-select v-model="syncForm.mode" style="width: 100%">
        <el-option :label="t('integrate.createModal.modeBatch')" value="batch" />
        <el-option :label="t('integrate.createModal.modeStreamCdc')" value="stream_cdc" />
      </el-select>
      <label>{{ t('integrate.createModal.schedule') }}</label>
      <el-input
        v-model="syncForm.schedule"
        :placeholder="t('integrate.createModal.schedulePlaceholder')"
      />
      <div v-if="syncFormError" class="note form-error">
        {{ syncFormError }}
      </div>
      <template #footer>
        <el-button @click="syncModal = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="syncSubmitting" @click="handleCreateSyncTask">
          {{ syncSubmitting ? t('integrate.createModal.creating') : t('common.create') }}
        </el-button>
      </template>
    </Modal>

    <!-- 新增数据源弹窗 -->
    <Modal :visible="srcModal" :title="t('integrate.sourceModal.title')" @close="srcModal = false">
      <label>{{ t('integrate.sourceModal.type') }}</label>
      <el-select v-model="srcForm.type" style="width: 100%">
        <el-option label="MySQL" value="MySQL" />
        <el-option label="Oracle" value="Oracle" />
        <el-option label="PostgreSQL" value="PostgreSQL" />
        <el-option label="API" value="API" />
      </el-select>
      <label>{{ t('integrate.sourceModal.connStr') }}</label>
      <el-input v-model="srcForm.connStr" placeholder="jdbc:mysql://…" />
      <label>{{ t('integrate.sourceModal.account') }}</label>
      <el-input v-model="srcForm.account" />
      <label>{{ t('integrate.sourceModal.password') }}</label>
      <el-input v-model="srcForm.password" type="password" show-password />
      <template #footer>
        <el-button @click="srcModal = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="ok(t('integrate.toast.sourceAdded'))">
          {{ t('integrate.sourceModal.testAndSave') }}
        </el-button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader, Toolbar } from '@/components/ui'
import Modal from '@/components/Modal.vue'
import * as integrateApi from '@/api/integrate'
import type { Connector, SyncTask, SyncMode, SyncStatus, ConnectorStatus } from '@/api/integrate'
import type { PagedResult } from '@/api/types'

const { t } = useI18n()
const store = useAppStore()
const syncModal = ref(false)
const srcModal = ref(false)

/* ------------------------------ 连接器列表 ------------------------------ */

// 连接器列表：通过 useApi 包装，失败时不阻塞页面
const {
  data: connectorsData,
  loading: connectorsLoading,
  error: connectorsError,
  execute: loadConnectors
} = useApi<Connector[]>(() => integrateApi.listConnectors(), {
  initialData: []
})
const connectors = computed<Connector[]>(() => connectorsData.value ?? [])

/** Source 连接器（用于新建任务时选择源） */
const sourceConnectors = computed<Connector[]>(() =>
  connectors.value.filter((c) => !c.category || c.category === 'source')
)
/** Sink 连接器（用于新建任务时选择目标） */
const sinkConnectors = computed<Connector[]>(() =>
  connectors.value.filter((c) => !c.category || c.category === 'sink')
)

/** 选中的连接器（用于高亮） */
const selectedConnectorName = ref<string>('')

/** 连接器点击处理 */
function onConnectorClick(c: Connector): void {
  if (c.status === 'pending_config' || c.status === 'pending_auth') {
    srcModal.value = true
    return
  }
  selectedConnectorName.value = c.name
  store.showToast(`${c.name} ${connectorPillText(c.status)}`)
}

/** 是否选中 */
function isConnectorSelected(c: Connector): boolean {
  return selectedConnectorName.value === c.name
}

/** 连接器状态 → pill 样式 */
function connectorPillClass(s: ConnectorStatus): string {
  switch (s) {
    case 'connected':
      return 'g'
    case 'pending_config':
    case 'pending_auth':
      return 'a'
    default:
      return 'b'
  }
}

/** 连接器状态 → pill 文案 */
function connectorPillText(s: ConnectorStatus): string {
  switch (s) {
    case 'connected':
      return t('integrate.connectorStatus.connected')
    case 'pending_config':
      return t('integrate.connectorStatus.pending_config')
    case 'pending_auth':
      return t('integrate.connectorStatus.pending_auth')
    default:
      return t('integrate.connectorStatus.disconnected')
  }
}

/* ------------------------------ 同步任务列表 ------------------------------ */

// 同步任务列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: tasksPaged,
  loading: tasksLoading,
  error: tasksError,
  execute: loadTasks
} = useApi<PagedResult<SyncTask>>(() => integrateApi.listSyncTasks({ page: 1, pageSize: 100 }))
const tasks = computed<SyncTask[]>(() => tasksPaged.value?.list ?? [])

/** 同步模式 → 词条 */
function modeLabel(m: SyncMode): string {
  return m === 'stream_cdc' ? t('integrate.modes.stream_cdc') : t('integrate.modes.batch')
}

/** 任务状态 → pill 样式 */
function statusPillClass(s: SyncStatus): string {
  switch (s) {
    case 'success':
      return 'g'
    case 'running':
      return 'a'
    case 'failed':
      return 'r'
    default:
      return 'b'
  }
}

/** 任务状态 → pill 文案 */
function statusPillText(s: SyncStatus): string {
  switch (s) {
    case 'success':
      return t('integrate.taskStatus.success')
    case 'running':
      return t('integrate.taskStatus.running')
    case 'failed':
      return t('integrate.taskStatus.failed')
    case 'pending':
      return t('integrate.taskStatus.pending')
    case 'stopped':
      return t('integrate.taskStatus.stopped')
    default:
      return s
  }
}

/* ------------------------------ 运行 / 停止任务 ------------------------------ */

const actingId = ref<string>('')

/** 运行任务 */
async function handleRunTask(task: SyncTask): Promise<void> {
  actingId.value = task.id
  try {
    await integrateApi.runSyncTask(task.id)
    store.showToast(t('integrate.toast.triggered', { name: task.name }))
    await loadTasks()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    actingId.value = ''
  }
}

/** 停止任务 */
async function handleStopTask(task: SyncTask): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t('integrate.confirmStop.message', { name: task.name }),
      t('integrate.confirmStop.title'),
      {
        type: 'warning',
        confirmButtonText: t('integrate.confirmStop.confirm'),
        cancelButtonText: t('integrate.confirmStop.cancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    // 用户取消
    return
  }
  actingId.value = task.id
  try {
    await integrateApi.stopSyncTask(task.id)
    store.showToast(t('integrate.toast.stopped', { name: task.name }))
    await loadTasks()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    actingId.value = ''
  }
}

/* ------------------------------ 新建同步任务 ------------------------------ */

interface SyncForm {
  name: string
  sourceType: string
  targetType: string
  sourceTable: string
  targetTable: string
  mode: SyncMode
  schedule: string
}

const syncForm = reactive<SyncForm>({
  name: '',
  sourceType: 'MySQL',
  targetType: 'Iceberg',
  sourceTable: '',
  targetTable: '',
  mode: 'batch',
  schedule: ''
})
const syncSubmitting = ref(false)
const syncFormError = ref<string>('')

/** 打开新建任务弹窗：重置表单 */
function openSyncModal(): void {
  syncForm.name = ''
  syncForm.sourceType = sourceConnectors.value[0]?.name ?? 'MySQL'
  syncForm.targetType = sinkConnectors.value[0]?.name ?? 'Iceberg'
  syncForm.sourceTable = ''
  syncForm.targetTable = ''
  syncForm.mode = 'batch'
  syncForm.schedule = ''
  syncFormError.value = ''
  syncModal.value = true
}

/** 创建同步任务 */
async function handleCreateSyncTask(): Promise<void> {
  // 表单校验
  if (!syncForm.name.trim()) {
    syncFormError.value = t('integrate.createModal.nameRequired')
    return
  }
  if (!syncForm.sourceTable.trim()) {
    syncFormError.value = t('integrate.createModal.sourceTableRequired')
    return
  }
  if (!syncForm.targetTable.trim()) {
    syncFormError.value = t('integrate.createModal.targetTableRequired')
    return
  }
  syncFormError.value = ''
  syncSubmitting.value = true
  try {
    await integrateApi.createSyncTask({
      name: syncForm.name,
      sourceType: syncForm.sourceType,
      targetType: syncForm.targetType,
      mode: syncForm.mode,
      schedule: syncForm.schedule || undefined
    })
    store.showToast(t('integrate.toast.created'))
    syncModal.value = false
    await loadTasks()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    syncSubmitting.value = false
  }
}

/* ------------------------------ 新增数据源表单 ------------------------------ */

// 数据源表单：使用 reactive 集中管理，替代原先未绑定的原生 input
const srcForm = reactive<{
  type: string
  connStr: string
  account: string
  password: string
}>({
  type: 'MySQL',
  connStr: '',
  account: '',
  password: ''
})

function ok(msg: string): void {
  syncModal.value = false
  srcModal.value = false
  store.showToast(msg)
}

/* ------------------------------ 状态轮询（5 秒） ------------------------------ */

let pollTimer: ReturnType<typeof setInterval> | null = null

/** 启动轮询：仅当存在运行中任务时才轮询 */
function startPolling(): void {
  if (pollTimer) return
  pollTimer = setInterval(() => {
    const hasRunning = tasks.value.some((t) => t.status === 'running' || t.status === 'pending')
    if (hasRunning) {
      void loadTasks()
    }
  }, 5000)
}

function stopPolling(): void {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/* ------------------------------ 初始化 ------------------------------ */

onMounted(() => {
  void loadConnectors()
  void loadTasks()
  startPolling()
})

onUnmounted(() => {
  stopPolling()
})
</script>

<style scoped>
.conn-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
  gap: 10px;
  margin-top: 8px;
}
.conn {
  border: 1px solid var(--ds-border-default);
  border-radius: var(--ds-radius-md);
  padding: var(--ds-spacing-3);
  text-align: center;
  cursor: pointer;
  position: relative;
  transition: border-color var(--ds-transition-fast);
}
.conn:hover {
  border-color: var(--ds-color-info-200);
}
.conn.selected {
  border-color: var(--ds-color-info-600);
  background: var(--ds-color-info-50);
}
.conn .logo {
  font-size: var(--ds-font-size-xl);
  font-weight: var(--ds-font-weight-semibold);
  margin-bottom: var(--ds-spacing-1);
}
.category-tag {
  position: absolute;
  top: var(--ds-spacing-1);
  right: var(--ds-spacing-1);
  font-size: 12px;
  color: var(--ds-text-secondary);
  background: var(--ds-bg-subtle);
  padding: 1px var(--ds-spacing-1);
  border-radius: var(--ds-radius-sm);
}
/* 状态提示：使用 design tokens 替代硬编码颜色 */
.state-tip {
  padding: var(--ds-spacing-4);
}
.state-loading {
  color: var(--ds-text-tertiary);
}
.state-error {
  color: var(--ds-color-error-500);
}
.note {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-secondary);
}
/* 表单错误提示：使用 design tokens 替代硬编码颜色 */
.form-error {
  color: var(--ds-color-error-500);
  margin-top: var(--ds-spacing-2);
}
</style>
