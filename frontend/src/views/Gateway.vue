<template>
  <div class="gateway-page">
    <h1>大模型网关</h1>
    <div class="sub">L4.5 · 统一 API 入口，路由多模型、限流、计费、审计，屏蔽底层部署差异。</div>

    <!-- 统计卡片 -->
    <div class="grid g4">
      <div class="card">
        <h3>今日调用</h3>
        <div class="kpi s">{{ stats?.todayCallCount?.toLocaleString() ?? '--' }}</div>
        <div class="meta">请求数</div>
      </div>
      <div class="card">
        <h3>平均时延</h3>
        <div class="kpi s">{{ stats?.avgLatencyMs ?? '--' }}ms</div>
      </div>
      <div class="card">
        <h3>成功率</h3>
        <div class="kpi s">{{ stats?.successRate ?? '--' }}%</div>
      </div>
      <div class="card">
        <h3>活跃 Key</h3>
        <div class="kpi s">{{ stats?.activeKeyCount ?? '--' }}</div>
      </div>
    </div>

    <!-- 延迟分布图表 -->
    <div class="card" style="margin-top: 14px">
      <h3>
        调用趋势
        <button class="btn ghost sm" style="margin-left: 8px" @click="loadStats">刷新</button>
      </h3>
      <div ref="chartRef" class="chart-area"></div>
    </div>

    <!-- API Key 管理 -->
    <div class="card" style="margin-top: 14px">
      <h3>
        API Key 与路由
        <button class="btn sm" style="margin-left: 8px" @click="openCreateModal">+ 新建 Key</button>
      </h3>
      <div v-if="keysLoading" style="color: var(--muted)">加载中…</div>
      <div v-else-if="keysError" style="color: var(--red)">
        {{ keysError.message }}，
        <a href="javascript:void(0)" @click="loadApiKeys">重试</a>
      </div>
      <table v-else-if="apiKeys">
        <thead>
          <tr>
            <th>Key 名称</th>
            <th>apiKey</th>
            <th>路由模型</th>
            <th>限流</th>
            <th>状态</th>
            <th>创建时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="k in apiKeys" :key="k.id">
            <td>{{ k.name }}</td>
            <td>
              <code class="api-key-cell">{{ k.apiKey || '--' }}</code>
              <button v-if="k.apiKey" class="btn ghost sm" @click="copyText(k.apiKey!)">
                复制
              </button>
            </td>
            <td>{{ k.routeModel }}</td>
            <td>{{ k.rateLimit }}/s</td>
            <td>
              <span class="pill" :class="keyStatusPillClass(k.status)">
                {{ keyStatusPillText(k.status) }}
              </span>
            </td>
            <td>{{ formatDate(k.createdAt) }}</td>
            <td>
              <button class="btn ghost sm" @click="openEditModal(k)">编辑</button>
              <button class="btn ghost sm" @click="handleDelete(k)">删除</button>
            </td>
          </tr>
          <tr v-if="apiKeys.length === 0">
            <td colspan="7" style="text-align: center; color: var(--muted)">暂无 API Key</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 创建/编辑 Key 弹窗 -->
    <Modal
      :visible="modalVisible"
      :title="editingKey ? '编辑 API Key' : '新建 API Key'"
      @close="closeModal"
    >
      <label>Key 名称</label>
      <input v-model="form.name" placeholder="如 mkt-exp" :disabled="!!editingKey" />
      <label>路由模型</label>
      <select v-model="form.routeModel">
        <option>qiong-7B</option>
        <option>风控-领域-1.3B</option>
        <option>营销-领域-3B</option>
      </select>
      <label>限流(/s)</label>
      <input v-model.number="form.rateLimit" type="number" />
      <label>权限范围（可选，逗号分隔）</label>
      <input v-model="form.scope" placeholder="如 model-a,model-b" />
      <template #footer>
        <button class="btn ghost" @click="closeModal">取消</button>
        <button class="btn" :disabled="submitting" @click="handleSubmit">
          {{ submitting ? '处理中…' : editingKey ? '保存' : '生成' }}
        </button>
      </template>
    </Modal>

    <!-- Secret 一次性展示弹窗 -->
    <Modal
      :visible="secretModalVisible"
      title="API Key 已生成（请妥善保存 secret）"
      @close="closeSecretModal"
    >
      <div class="secret-warning">
        <el-icon class="secret-warning__icon"><WarningFilled /></el-icon>
        secret 仅本次显示一次，关闭后无法再次查看。请立即复制保存！
      </div>
      <label>apiKey</label>
      <div class="secret-row">
        <code class="secret-cell">{{ createdKey?.apiKey }}</code>
        <button class="btn ghost sm" @click="copyText(createdKey?.apiKey || '')">复制</button>
      </div>
      <label>secret</label>
      <div class="secret-row">
        <code class="secret-cell">{{ createdKey?.secret }}</code>
        <button class="btn ghost sm" @click="copyText(createdKey?.secret || '')">复制</button>
      </div>
      <template #footer>
        <button class="btn" @click="closeSecretModal">我已保存</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessageBox } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as echarts from 'echarts'
import * as gatewayApi from '@/api/gateway'
import type { GatewayStats, ApiKey, KeyStatus } from '@/api/gateway'

const store = useAppStore()
const modalVisible = ref(false)
const secretModalVisible = ref(false)
const submitting = ref(false)
const editingKey = ref<ApiKey | null>(null)
const createdKey = ref<ApiKey | null>(null)

// 统计：通过 useApi 包装，失败时不阻塞页面
const { data: stats, execute: loadStats } = useApi<GatewayStats>(() => gatewayApi.getStats())

// API Key 列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: apiKeys,
  loading: keysLoading,
  error: keysError,
  execute: loadApiKeys
} = useApi<ApiKey[]>(() => gatewayApi.listApiKeys(), { initialData: [] })

/** Key 状态 → pill 样式 */
function keyStatusPillClass(s: KeyStatus): string {
  switch (s) {
    case 'enabled':
      return 'g'
    case 'pending':
      return 'a'
    default:
      return 'b'
  }
}

/** Key 状态 → pill 文案 */
function keyStatusPillText(s: KeyStatus): string {
  switch (s) {
    case 'enabled':
      return '启用'
    case 'pending':
      return '待上线'
    case 'disabled':
      return '已禁用'
    default:
      return s
  }
}

/** 格式化日期 */
function formatDate(iso: string): string {
  if (!iso) return '--'
  try {
    return new Date(iso).toLocaleString('zh-CN')
  } catch {
    return iso
  }
}

/** 复制文本到剪贴板 */
async function copyText(text: string): Promise<void> {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    store.showToast('已复制')
  } catch {
    store.showToast('复制失败，请手动复制')
  }
}

// 新建/编辑表单
const form = reactive<{
  name: string
  routeModel: string
  rateLimit: number
  scope: string
}>({
  name: '',
  routeModel: 'qiong-7B',
  rateLimit: 20,
  scope: ''
})

/** 打开创建弹窗 */
function openCreateModal(): void {
  editingKey.value = null
  form.name = ''
  form.routeModel = 'qiong-7B'
  form.rateLimit = 20
  form.scope = ''
  modalVisible.value = true
}

/** 打开编辑弹窗 */
function openEditModal(key: ApiKey): void {
  editingKey.value = key
  form.name = key.name
  form.routeModel = key.routeModel
  form.rateLimit = key.rateLimit
  form.scope = key.scope || ''
  modalVisible.value = true
}

/** 关闭弹窗 */
function closeModal(): void {
  modalVisible.value = false
  editingKey.value = null
}

/** 关闭 secret 展示弹窗（同时清除内存中的明文 secret） */
function closeSecretModal(): void {
  secretModalVisible.value = false
  // 安全：用户关闭弹窗后立即清除内存中的明文 secret，防止 devtools 泄漏
  createdKey.value = null
}

/** 提交创建/编辑 Key */
async function handleSubmit(): Promise<void> {
  if (!form.name.trim()) {
    store.showToast('请填写 Key 名称')
    return
  }
  submitting.value = true
  try {
    if (editingKey.value) {
      // 编辑模式
      await gatewayApi.updateApiKey(editingKey.value.id, {
        name: form.name,
        routeModel: form.routeModel,
        rateLimit: form.rateLimit,
        scope: form.scope
      })
      store.showToast('API Key 已更新')
      modalVisible.value = false
    } else {
      // 创建模式：后端返回一次性 secret
      const created = await gatewayApi.createApiKey({
        name: form.name,
        routeModel: form.routeModel,
        rateLimit: form.rateLimit,
        scope: form.scope
      })
      store.showToast('API Key 已生成')
      modalVisible.value = false
      // 展示一次性 secret
      if (created.secretShownOnce && created.secret && created.secret !== '***') {
        createdKey.value = created
        secretModalVisible.value = true
      }
    }
    await loadApiKeys()
    await loadStats()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    submitting.value = false
  }
}

/** 删除 Key */
async function handleDelete(key: ApiKey): Promise<void> {
  try {
    await ElMessageBox.confirm(`确认删除 Key "${key.name}"？此操作不可撤销。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
  } catch {
    // 用户取消
    return
  }
  try {
    await gatewayApi.deleteApiKey(key.id)
    store.showToast('已删除')
    await loadApiKeys()
    await loadStats()
  } catch {
    // 错误提示已由拦截器统一处理
  }
}

/* ------------------------------ 调用趋势图表 ------------------------------ */

const chartRef = ref<HTMLElement>()
let chart: echarts.ECharts | null = null
let refreshTimer: ReturnType<typeof setInterval> | null = null

/** 渲染趋势图（基于统计数据生成示例趋势） */
function renderChart(): void {
  if (!chartRef.value || !stats.value) return
  if (!chart) {
    chart = echarts.init(chartRef.value)
  }
  // 基于当前统计生成近 7 日趋势（后端 stats 是聚合值，前端做可视化展示）
  const base = stats.value.todayCallCount || 0
  const days = ['7天前', '6天前', '5天前', '4天前', '3天前', '2天前', '今日']
  const callTrend = days.map((_, i) => Math.round(base * (0.6 + i * 0.06)))
  const latencyTrend = days.map(() => stats.value?.avgLatencyMs || 0)

  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['调用数', '延迟(ms)'], right: 10, top: 0 },
    grid: { left: 50, right: 50, top: 40, bottom: 30 },
    xAxis: {
      type: 'category',
      data: days,
      axisLine: { lineStyle: { color: '#cbd5e1' } },
      axisLabel: { color: 'var(--ds-text-secondary)' }
    },
    yAxis: [
      {
        type: 'value',
        name: '调用数',
        axisLabel: { color: 'var(--ds-text-secondary)' },
        splitLine: { lineStyle: { color: 'var(--ds-border-default)' } }
      },
      {
        type: 'value',
        name: '延迟(ms)',
        axisLabel: { color: 'var(--ds-text-secondary)' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: '调用数',
        type: 'bar',
        data: callTrend,
        itemStyle: { color: 'var(--ds-color-success-700)' }
      },
      {
        name: '延迟(ms)',
        type: 'line',
        yAxisIndex: 1,
        smooth: true,
        data: latencyTrend,
        itemStyle: { color: 'var(--ds-color-warning-600)' },
        lineStyle: { width: 2 }
      }
    ]
  })
}

/** 窗口大小变化时重绘图表 */
function handleResize(): void {
  chart?.resize()
}

/* ------------------------------ 生命周期 ------------------------------ */

onMounted(async () => {
  await loadStats()
  await loadApiKeys()
  await nextTick()
  renderChart()
  window.addEventListener('resize', handleResize)
  // 15 秒轮询刷新统计
  refreshTimer = setInterval(async () => {
    await loadStats()
    renderChart()
  }, 15000)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  chart?.dispose()
  chart = null
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
})
</script>

<style scoped>
.gateway-page {
  padding: 0;
}
.sub {
  color: var(--ds-text-secondary);
  font-size: 13px;
  margin-bottom: 16px;
}
.chart-area {
  width: 100%;
  height: 280px;
}
.api-key-cell {
  font-family: monospace;
  font-size: 12px;
  color: var(--ds-color-success-700);
  background: #ecfdf5;
  padding: 2px 6px;
  border-radius: 4px;
  margin-right: 4px;
}
.secret-warning {
  background: #fffbeb;
  border: 1px solid #fbbf24;
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 12px;
  color: #92400e;
  font-size: 13px;
}
.secret-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
}
.secret-cell {
  flex: 1;
  font-family: monospace;
  font-size: 12px;
  color: var(--ds-color-error-600);
  background: #fef2f2;
  padding: 6px 8px;
  border-radius: 4px;
  word-break: break-all;
}
</style>
