<template>
  <div>
    <h1>{{ t('integrate.title') }}</h1>
    <div class="sub">{{ t('integrate.subtitle') }}</div>
    <div class="section-title">{{ t('integrate.connectors') }}</div>
    <div v-if="connectorsLoading" class="conn-grid" style="color: var(--muted)">
      {{ t('integrate.connectorsLoading') }}
    </div>
    <div v-else-if="connectorsError" class="conn-grid" style="color: var(--red)">
      {{ t('common.loadFailed') }}，
      <a href="javascript:void(0)" @click="loadConnectors">{{ t('common.retry') }}</a>
    </div>
    <div v-else-if="connectors.length === 0" class="conn-grid" style="color: var(--muted)">
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
    <div class="toolbar" style="margin-top: 16px">
      <button class="btn sm" @click="openSyncModal">{{ t('integrate.newTask') }}</button>
      <div class="spacer"></div>
      <span class="pill b">{{ t('integrate.batchStream') }}</span>
    </div>
    <div class="card">
      <div v-if="tasksLoading" style="padding: 16px; color: var(--muted)">
        {{ t('integrate.tasksLoading') }}
      </div>
      <div v-else-if="tasksError" style="padding: 16px; color: var(--red)">
        {{ tasksError.message }}，
        <a href="javascript:void(0)" @click="loadTasks">{{ t('common.retry') }}</a>
      </div>
      <table v-else>
        <tr>
          <th>{{ t('integrate.cols.task') }}</th>
          <th>{{ t('integrate.cols.sourceToTarget') }}</th>
          <th>{{ t('integrate.cols.mode') }}</th>
          <th>{{ t('integrate.cols.status') }}</th>
          <th>{{ t('integrate.cols.lastRun') }}</th>
          <th>{{ t('integrate.cols.actions') }}</th>
        </tr>
        <tr v-for="task in tasks" :key="task.id">
          <td>{{ task.name }}</td>
          <td>{{ task.sourceToTarget }}</td>
          <td>{{ modeLabel(task.mode) }}</td>
          <td>
            <span class="pill" :class="statusPillClass(task.status)">
              {{ statusPillText(task.status) }}
            </span>
          </td>
          <td>
            {{ task.lastRunAt || '--'
            }}{{ task.lastRunDuration ? ' · ' + task.lastRunDuration : '' }}
          </td>
          <td>
            <button
              v-if="task.status !== 'running'"
              class="btn sm"
              :disabled="actingId === task.id"
              @click="handleRunTask(task)"
            >
              {{ actingId === task.id ? t('integrate.running') : t('integrate.run') }}
            </button>
            <button
              v-else
              class="btn sm ghost"
              :disabled="actingId === task.id"
              @click="handleStopTask(task)"
            >
              {{ actingId === task.id ? t('integrate.stopping') : t('integrate.stop') }}
            </button>
          </td>
        </tr>
        <tr v-if="tasks.length === 0">
          <td colspan="6" style="text-align: center; color: var(--muted)">
            {{ t('integrate.tasksEmpty') }}
          </td>
        </tr>
      </table>
    </div>

    <!-- 新建同步任务弹窗 -->
    <Modal
      :visible="syncModal"
      :title="t('integrate.createModal.title')"
      @close="syncModal = false"
    >
      <label>{{ t('integrate.createModal.name') }}</label>
      <input v-model="syncForm.name" :placeholder="t('integrate.createModal.namePlaceholder')" />
      <label>{{ t('integrate.createModal.sourceType') }}</label>
      <select v-model="syncForm.sourceType">
        <option v-for="c in sourceConnectors" :key="c.name" :value="c.name">{{ c.name }}</option>
      </select>
      <label>{{ t('integrate.createModal.targetType') }}</label>
      <select v-model="syncForm.targetType">
        <option v-for="c in sinkConnectors" :key="c.name" :value="c.name">{{ c.name }}</option>
      </select>
      <label>{{ t('integrate.createModal.sourceTable') }}</label>
      <input
        v-model="syncForm.sourceTable"
        :placeholder="t('integrate.createModal.sourceTablePlaceholder')"
      />
      <label>{{ t('integrate.createModal.targetTable') }}</label>
      <input
        v-model="syncForm.targetTable"
        :placeholder="t('integrate.createModal.targetTablePlaceholder')"
      />
      <label>{{ t('integrate.createModal.mode') }}</label>
      <select v-model="syncForm.mode">
        <option value="batch">{{ t('integrate.createModal.modeBatch') }}</option>
        <option value="stream_cdc">{{ t('integrate.createModal.modeStreamCdc') }}</option>
      </select>
      <label>{{ t('integrate.createModal.schedule') }}</label>
      <input
        v-model="syncForm.schedule"
        :placeholder="t('integrate.createModal.schedulePlaceholder')"
      />
      <div v-if="syncFormError" class="note" style="color: var(--red); margin-top: 8px">
        {{ syncFormError }}
      </div>
      <template #footer>
        <button class="btn ghost" @click="syncModal = false">{{ t('common.cancel') }}</button>
        <button class="btn" :disabled="syncSubmitting" @click="handleCreateSyncTask">
          {{ syncSubmitting ? t('integrate.createModal.creating') : t('common.create') }}
        </button>
      </template>
    </Modal>

    <!-- 新增数据源弹窗 -->
    <Modal :visible="srcModal" :title="t('integrate.sourceModal.title')" @close="srcModal = false">
      <label>{{ t('integrate.sourceModal.type') }}</label>
      <select>
        <option>MySQL</option>
        <option>Oracle</option>
        <option>PostgreSQL</option>
        <option>API</option>
      </select>
      <label>{{ t('integrate.sourceModal.connStr') }}</label>
      <input placeholder="jdbc:mysql://…" />
      <label>{{ t('integrate.sourceModal.account') }}</label>
      <input />
      <label>{{ t('integrate.sourceModal.password') }}</label>
      <input type="password" />
      <template #footer>
        <button class="btn ghost" @click="srcModal = false">{{ t('common.cancel') }}</button>
        <button class="btn" @click="ok(t('integrate.toast.sourceAdded'))">
          {{ t('integrate.sourceModal.testAndSave') }}
        </button>
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
  border-radius: 8px;
  padding: 12px;
  text-align: center;
  cursor: pointer;
  position: relative;
  transition: border-color 0.15s;
}
.conn:hover {
  border-color: #c7d2fe;
}
.conn.selected {
  border-color: #4f46e5;
  background: #eef2ff;
}
.conn .logo {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}
.category-tag {
  position: absolute;
  top: 4px;
  right: 4px;
  font-size: 10px;
  color: var(--ds-text-secondary);
  background: #f4f5f7;
  padding: 1px 4px;
  border-radius: 3px;
}
.btn.sm {
  padding: 4px 10px;
  font-size: 12px;
}
.btn.sm:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.note {
  font-size: 12px;
  color: var(--ds-text-secondary);
}
</style>
