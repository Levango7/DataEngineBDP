<template>
  <div>
    <h1>数据集成</h1>
    <div class="sub">
      基于 SeaTunnel 可视化配置异构数据源同步至湖仓集一体存储，支持批流一体，无需搬运代码。
    </div>
    <div class="section-title">数据源连接器</div>
    <div v-if="connectorsLoading" class="conn-grid" style="color: var(--muted)">加载连接器…</div>
    <div v-else class="conn-grid">
      <div class="conn" v-for="c in connectors" :key="c.name" @click="onConnectorClick(c)">
        <div class="logo">{{ c.logo }}</div>
        {{ c.name }}
        <span class="pill" :class="connectorPillClass(c.status)" style="display: block; margin-top: 6px">{{ connectorPillText(c.status) }}</span>
      </div>
    </div>
    <div class="toolbar" style="margin-top: 16px">
      <button class="btn sm" @click="syncModal = true">+ 新建同步任务</button>
      <div class="spacer"></div>
      <span class="pill b">批流一体</span>
    </div>
    <div class="card">
      <div v-if="tasksLoading" style="padding: 16px; color: var(--muted)">加载同步任务…</div>
      <div v-else-if="tasksError" style="padding: 16px; color: var(--red)">
        {{ tasksError.message }}，<a href="javascript:void(0)" @click="loadTasks">重试</a>
      </div>
      <table v-else>
        <tr><th>任务</th><th>源→目标</th><th>模式</th><th>状态</th><th>最近运行</th></tr>
        <tr v-for="t in tasks" :key="t.id">
          <td>{{ t.name }}</td>
          <td>{{ t.sourceToTarget }}</td>
          <td>{{ modeLabel(t.mode) }}</td>
          <td><span class="pill" :class="statusPillClass(t.status)">{{ statusPillText(t.status) }}</span></td>
          <td>{{ t.lastRunAt || '--' }}{{ t.lastRunDuration ? ' · ' + t.lastRunDuration : '' }}</td>
        </tr>
        <tr v-if="tasks.length === 0">
          <td colspan="5" style="text-align: center; color: var(--muted)">暂无同步任务</td>
        </tr>
      </table>
    </div>

    <Modal :visible="syncModal" title="新建同步任务" @close="syncModal = false">
      <label>任务名</label><input placeholder="如 订单全量" />
      <label>源</label>
      <select><option>MySQL</option><option>Oracle</option><option>Kafka</option></select>
      <label>目标</label>
      <select><option>Iceberg(湖)</option><option>Doris(仓/集)</option></select>
      <label>模式</label>
      <select><option>批</option><option>流(CDC)</option></select>
      <label>调度频率</label><input value="每日 04:00" />
      <template #footer>
        <button class="btn ghost" @click="syncModal = false">取消</button>
        <button class="btn" @click="ok('同步任务已创建')">创建</button>
      </template>
    </Modal>

    <Modal :visible="srcModal" title="新增数据源" @close="srcModal = false">
      <label>类型</label>
      <select><option>MySQL</option><option>Oracle</option><option>PostgreSQL</option><option>API</option></select>
      <label>连接串</label><input placeholder="jdbc:mysql://…" />
      <label>账号</label><input />
      <label>密码</label><input type="password" />
      <template #footer>
        <button class="btn ghost" @click="srcModal = false">取消</button>
        <button class="btn" @click="ok('数据源已添加')">测试并保存</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as integrateApi from '@/api/integrate'
import type { Connector, SyncTask, SyncMode, SyncStatus, ConnectorStatus } from '@/api/integrate'
import type { PagedResult } from '@/api/types'

const store = useAppStore()
const syncModal = ref(false)
const srcModal = ref(false)

// 连接器列表：通过 useApi 包装，失败时不阻塞页面
const {
  data: connectorsData,
  loading: connectorsLoading,
  execute: loadConnectors
} = useApi<Connector[]>(() => integrateApi.listConnectors(), {
  initialData: []
})
const connectors = computed<Connector[]>(() => connectorsData.value ?? [])

// 同步任务列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: tasksPaged,
  loading: tasksLoading,
  error: tasksError,
  execute: loadTasks
} = useApi<PagedResult<SyncTask>>(() => integrateApi.listSyncTasks({ page: 1, pageSize: 100 }))
const tasks = computed<SyncTask[]>(() => tasksPaged.value?.list ?? [])

/** 连接器点击处理 */
function onConnectorClick(c: Connector) {
  if (c.status === 'pending_config' || c.status === 'pending_auth') {
    srcModal.value = true
  } else {
    store.showToast(`${c.name} ${connectorPillText(c.status)}`)
  }
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

function ok(msg: string) {
  syncModal.value = false
  srcModal.value = false
  store.showToast(msg)
}

onMounted(() => {
  void loadConnectors()
  void loadTasks()
})
</script>