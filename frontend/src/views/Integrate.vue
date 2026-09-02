<template>
  <div>
    <h1>数据集成</h1>
    <div class="sub">
      基于 SeaTunnel 可视化配置异构数据源同步至湖仓集一体存储，支持批流一体，无需搬运代码。
    </div>
    <div class="section-title">数据源连接器</div>
    <div v-if="connectorsLoading" class="conn-grid" style="color: var(--muted)">加载连接器…</div>
    <div v-else-if="connectorsError" class="conn-grid" style="color: var(--red)">
      加载失败，
      <a href="javascript:void(0)" @click="loadConnectors">重试</a>
    </div>
    <div v-else-if="connectors.length === 0" class="conn-grid" style="color: var(--muted)">
      暂无可用连接器
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
          {{ c.category === 'source' ? '源' : '目标' }}
        </span>
      </div>
    </div>
    <div class="toolbar" style="margin-top: 16px">
      <button class="btn sm" @click="openSyncModal">+ 新建同步任务</button>
      <div class="spacer"></div>
      <span class="pill b">批流一体</span>
    </div>
    <div class="card">
      <div v-if="tasksLoading" style="padding: 16px; color: var(--muted)">加载同步任务…</div>
      <div v-else-if="tasksError" style="padding: 16px; color: var(--red)">
        {{ tasksError.message }}，
        <a href="javascript:void(0)" @click="loadTasks">重试</a>
      </div>
      <table v-else>
        <tr>
          <th>任务</th>
          <th>源→目标</th>
          <th>模式</th>
          <th>状态</th>
          <th>最近运行</th>
          <th>操作</th>
        </tr>
        <tr v-for="t in tasks" :key="t.id">
          <td>{{ t.name }}</td>
          <td>{{ t.sourceToTarget }}</td>
          <td>{{ modeLabel(t.mode) }}</td>
          <td>
            <span class="pill" :class="statusPillClass(t.status)">
              {{ statusPillText(t.status) }}
            </span>
          </td>
          <td>{{ t.lastRunAt || '--' }}{{ t.lastRunDuration ? ' · ' + t.lastRunDuration : '' }}</td>
          <td>
            <button
              v-if="t.status !== 'running'"
              class="btn sm"
              :disabled="actingId === t.id"
              @click="handleRunTask(t)"
            >
              {{ actingId === t.id ? '运行中…' : '运行' }}
            </button>
            <button
              v-else
              class="btn sm ghost"
              :disabled="actingId === t.id"
              @click="handleStopTask(t)"
            >
              {{ actingId === t.id ? '停止中…' : '停止' }}
            </button>
          </td>
        </tr>
        <tr v-if="tasks.length === 0">
          <td colspan="6" style="text-align: center; color: var(--muted)">暂无同步任务</td>
        </tr>
      </table>
    </div>

    <!-- 新建同步任务弹窗 -->
    <Modal :visible="syncModal" title="新建同步任务" @close="syncModal = false">
      <label>任务名</label>
      <input v-model="syncForm.name" placeholder="如 订单全量" />
      <label>源类型</label>
      <select v-model="syncForm.sourceType">
        <option v-for="c in sourceConnectors" :key="c.name" :value="c.name">{{ c.name }}</option>
      </select>
      <label>目标类型</label>
      <select v-model="syncForm.targetType">
        <option v-for="c in sinkConnectors" :key="c.name" :value="c.name">{{ c.name }}</option>
      </select>
      <label>源表</label>
      <input v-model="syncForm.sourceTable" placeholder="如 orders" />
      <label>目标表</label>
      <input v-model="syncForm.targetTable" placeholder="如 iceberg.orders" />
      <label>模式</label>
      <select v-model="syncForm.mode">
        <option value="batch">批</option>
        <option value="stream_cdc">流（CDC）</option>
      </select>
      <label>调度频率</label>
      <input v-model="syncForm.schedule" placeholder="如 0 4 * * *（每日 04:00）" />
      <div v-if="syncFormError" class="note" style="color: var(--red); margin-top: 8px">
        {{ syncFormError }}
      </div>
      <template #footer>
        <button class="btn ghost" @click="syncModal = false">取消</button>
        <button class="btn" :disabled="syncSubmitting" @click="handleCreateSyncTask">
          {{ syncSubmitting ? '创建中…' : '创建' }}
        </button>
      </template>
    </Modal>

    <!-- 新增数据源弹窗 -->
    <Modal :visible="srcModal" title="新增数据源" @close="srcModal = false">
      <label>类型</label>
      <select>
        <option>MySQL</option>
        <option>Oracle</option>
        <option>PostgreSQL</option>
        <option>API</option>
      </select>
      <label>连接串</label>
      <input placeholder="jdbc:mysql://…" />
      <label>账号</label>
      <input />
      <label>密码</label>
      <input type="password" />
      <template #footer>
        <button class="btn ghost" @click="srcModal = false">取消</button>
        <button class="btn" @click="ok('数据源已添加')">测试并保存</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessageBox } from 'element-plus'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as integrateApi from '@/api/integrate'
import type { Connector, SyncTask, SyncMode, SyncStatus, ConnectorStatus } from '@/api/integrate'
import type { PagedResult } from '@/api/types'

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
      return '已连通'
    case 'pending_config':
      return '待配置'
    case 'pending_auth':
      return '待授权'
    default:
      return '未连通'
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

/** 同步模式 → 中文 */
function modeLabel(m: SyncMode): string {
  return m === 'stream_cdc' ? '流' : '批'
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
      return '成功'
    case 'running':
      return '运行中'
    case 'failed':
      return '失败'
    case 'pending':
      return '等待中'
    case 'stopped':
      return '已停止'
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
    store.showToast(`已触发任务：${task.name}`)
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
    await ElMessageBox.confirm(`确定停止任务「${task.name}」吗？`, '停止确认', {
      type: 'warning',
      confirmButtonText: '停止',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    // 用户取消
    return
  }
  actingId.value = task.id
  try {
    await integrateApi.stopSyncTask(task.id)
    store.showToast(`已停止任务：${task.name}`)
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
    syncFormError.value = '请输入任务名'
    return
  }
  if (!syncForm.sourceTable.trim()) {
    syncFormError.value = '请输入源表'
    return
  }
  if (!syncForm.targetTable.trim()) {
    syncFormError.value = '请输入目标表'
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
    store.showToast('同步任务已创建')
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
