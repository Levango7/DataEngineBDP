<template>
  <div class="gateway-page">
    <PageHeader :title="t('gateway.title')" :subtitle="t('gateway.subtitle')" />

    <!-- 统计卡片 -->
    <div class="grid g4">
      <div class="card">
        <h3>{{ t('gateway.kpi.todayCalls') }}</h3>
        <div class="kpi s">{{ stats?.todayCallCount?.toLocaleString() ?? '--' }}</div>
        <div class="meta">{{ t('gateway.kpi.callsMeta') }}</div>
      </div>
      <div class="card">
        <h3>{{ t('gateway.kpi.avgLatency') }}</h3>
        <div class="kpi s">{{ stats?.avgLatencyMs ?? '--' }}ms</div>
      </div>
      <div class="card">
        <h3>{{ t('gateway.kpi.successRate') }}</h3>
        <div class="kpi s">{{ stats?.successRate ?? '--' }}%</div>
      </div>
      <div class="card">
        <h3>{{ t('gateway.kpi.activeKeys') }}</h3>
        <div class="kpi s">{{ stats?.activeKeyCount ?? '--' }}</div>
      </div>
    </div>

    <!-- 延迟分布图表 -->
    <div class="card" style="margin-top: 14px">
      <h3>
        {{ t('gateway.trend') }}
        <el-button size="small" style="margin-left: 8px" @click="loadStats">
          {{ t('gateway.refresh') }}
        </el-button>
      </h3>
      <div ref="chartRef" class="chart-area"></div>
    </div>

    <!-- API Key 管理 -->
    <div class="card" style="margin-top: 14px">
      <h3>
        {{ t('gateway.keysTitle') }}
        <el-button size="small" type="primary" style="margin-left: 8px" @click="openCreateModal">
          {{ t('gateway.newKey') }}
        </el-button>
      </h3>
      <div v-if="keysLoading" style="color: var(--ds-text-tertiary)">{{ t('common.loading') }}</div>
      <div v-else-if="keysError" style="color: var(--ds-color-error-600)">
        {{ keysError.message }}，
        <a href="javascript:void(0)" @click="loadApiKeys">{{ t('common.retry') }}</a>
      </div>
      <el-table
        v-else-if="apiKeys"
        :data="apiKeys"
        stripe
        border
        style="width: 100%"
        :empty-text="t('gateway.keysEmpty')"
      >
        <el-table-column :label="t('gateway.cols.name')" prop="name" min-width="120" />
        <el-table-column :label="t('gateway.cols.apiKey')" min-width="200">
          <template #default="{ row }">
            <code class="api-key-cell">{{ row.apiKey || '--' }}</code>
            <el-button v-if="row.apiKey" size="small" link @click="copyText(row.apiKey)">
              {{ t('gateway.copy') }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column :label="t('gateway.cols.routeModel')" prop="routeModel" width="140" />
        <el-table-column :label="t('gateway.cols.rateLimit')" width="100">
          <template #default="{ row }">{{ row.rateLimit }}/s</template>
        </el-table-column>
        <el-table-column :label="t('gateway.cols.status')" width="110">
          <template #default="{ row }">
            <span class="pill" :class="keyStatusPillClass(row.status)">
              {{ keyStatusPillText(row.status) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('gateway.cols.createdAt')" width="180">
          <template #default="{ row }">{{ formatDate(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('gateway.cols.actions')" width="160" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link @click="openEditModal(row)">
              {{ t('gateway.edit') }}
            </el-button>
            <el-button size="small" link type="danger" @click="handleDelete(row)">
              {{ t('common.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 创建/编辑 Key 弹窗 -->
    <Modal
      :visible="modalVisible"
      :title="editingKey ? t('gateway.editModal.titleEdit') : t('gateway.editModal.titleCreate')"
      @close="closeModal"
    >
      <label>{{ t('gateway.editModal.name') }}</label>
      <el-input
        v-model="form.name"
        :placeholder="t('gateway.editModal.namePlaceholder')"
        :disabled="!!editingKey"
        style="width: 100%"
      />
      <label>{{ t('gateway.editModal.routeModel') }}</label>
      <el-select v-model="form.routeModel" style="width: 100%">
        <el-option :label="t('gateway.editModal.routeModelOptions.qiong7B')" value="qiong-7B" />
        <el-option
          :label="t('gateway.editModal.routeModelOptions.riskDomain13B')"
          value="风控-领域-1.3B"
        />
        <el-option
          :label="t('gateway.editModal.routeModelOptions.marketingDomain3B')"
          value="营销-领域-3B"
        />
      </el-select>
      <label>{{ t('gateway.editModal.rateLimit') }}</label>
      <el-input-number v-model="form.rateLimit" :min="1" style="width: 100%" />
      <label>{{ t('gateway.editModal.scope') }}</label>
      <el-input
        v-model="form.scope"
        :placeholder="t('gateway.editModal.scopePlaceholder')"
        style="width: 100%"
      />
      <template #footer>
        <el-button @click="closeModal">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :disabled="submitting" @click="handleSubmit">
          {{
            submitting
              ? t('gateway.editModal.processing')
              : editingKey
                ? t('gateway.editModal.save')
                : t('gateway.editModal.generate')
          }}
        </el-button>
      </template>
    </Modal>

    <!-- Secret 一次性展示弹窗 -->
    <Modal
      :visible="secretModalVisible"
      :title="t('gateway.secretModal.title')"
      @close="closeSecretModal"
    >
      <div class="secret-warning">
        <el-icon class="secret-warning__icon"><WarningFilled /></el-icon>
        {{ t('gateway.secretModal.warning') }}
      </div>
      <label>apiKey</label>
      <div class="secret-row">
        <code class="secret-cell">{{ createdKey?.apiKey }}</code>
        <el-button size="small" @click="copyText(createdKey?.apiKey || '')">
          {{ t('gateway.copy') }}
        </el-button>
      </div>
      <label>secret</label>
      <div class="secret-row">
        <code class="secret-cell">{{ createdKey?.secret }}</code>
        <el-button size="small" @click="copyText(createdKey?.secret || '')">
          {{ t('gateway.copy') }}
        </el-button>
      </div>
      <template #footer>
        <el-button type="primary" @click="closeSecretModal">
          {{ t('gateway.secretModal.saved') }}
        </el-button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue'
import { ElMessageBox } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader } from '@/components/ui'
import Modal from '@/components/Modal.vue'
import * as echarts from 'echarts'
import * as gatewayApi from '@/api/gateway'
import type { GatewayStats, ApiKey, KeyStatus } from '@/api/gateway'

const { t, tm, locale } = useI18n()
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
      return t('gateway.keyStatus.enabled')
    case 'pending':
      return t('gateway.keyStatus.pending')
    case 'disabled':
      return t('gateway.keyStatus.disabled')
    default:
      return s
  }
}

/** 格式化日期（跟随当前语言环境） */
function formatDate(iso: string): string {
  if (!iso) return '--'
  try {
    return new Date(iso).toLocaleString(locale.value)
  } catch {
    return iso
  }
}

/** 复制文本到剪贴板 */
async function copyText(text: string): Promise<void> {
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    store.showToast(t('gateway.toast.copied'))
  } catch {
    store.showToast(t('gateway.toast.copyFailed'))
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
    store.showToast(t('gateway.editModal.nameRequired'))
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
      store.showToast(t('gateway.editModal.updated'))
      modalVisible.value = false
    } else {
      // 创建模式：后端返回一次性 secret
      const created = await gatewayApi.createApiKey({
        name: form.name,
        routeModel: form.routeModel,
        rateLimit: form.rateLimit,
        scope: form.scope
      })
      store.showToast(t('gateway.editModal.generated'))
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
    await ElMessageBox.confirm(
      t('gateway.deleteConfirm.message', { name: key.name }),
      t('gateway.deleteConfirm.title'),
      {
        type: 'warning',
        confirmButtonText: t('gateway.deleteConfirm.confirm'),
        cancelButtonText: t('common.cancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
  } catch {
    // 用户取消
    return
  }
  try {
    await gatewayApi.deleteApiKey(key.id)
    store.showToast(t('gateway.deleteConfirm.deleted'))
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
  const days = tm('gateway.chart.days') as string[]
  const callTrend = days.map((_, i) => Math.round(base * (0.6 + i * 0.06)))
  const latencyTrend = days.map(() => stats.value?.avgLatencyMs || 0)
  const callsLabel = t('gateway.chart.calls')
  const latencyLabel = t('gateway.chart.latency')

  chart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: [callsLabel, latencyLabel], right: 10, top: 0 },
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
        name: callsLabel,
        axisLabel: { color: 'var(--ds-text-secondary)' },
        splitLine: { lineStyle: { color: 'var(--ds-border-default)' } }
      },
      {
        type: 'value',
        name: latencyLabel,
        axisLabel: { color: 'var(--ds-text-secondary)' },
        splitLine: { show: false }
      }
    ],
    series: [
      {
        name: callsLabel,
        type: 'bar',
        data: callTrend,
        itemStyle: { color: 'var(--ds-color-success-700)' }
      },
      {
        name: latencyLabel,
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
.chart-area {
  width: 100%;
  height: 280px;
}
.api-key-cell {
  font-family: var(--ds-font-family-mono);
  font-size: 12px;
  color: var(--ds-color-success-700);
  background: var(--ds-color-success-50);
  padding: 2px 6px;
  border-radius: 4px;
  margin-right: 4px;
}
.secret-warning {
  background: var(--ds-color-warning-50);
  border: 1px solid var(--ds-color-warning-400);
  border-radius: 6px;
  padding: 8px 12px;
  margin-bottom: 12px;
  color: var(--ds-color-warning-800);
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
  font-family: var(--ds-font-family-mono);
  font-size: 12px;
  color: var(--ds-color-error-600);
  background: var(--ds-color-error-50);
  padding: 6px 8px;
  border-radius: 4px;
  word-break: break-all;
}

/* ============ 响应式断点 ============ */
/* 中等屏幕：KPI 卡片改为 2 列 */
@media (max-width: 1100px) {
  .grid.g4 {
    grid-template-columns: repeat(2, 1fr);
  }
  .chart-area {
    height: 240px;
  }
}

/* 小屏幕：KPI 卡片单列，图表高度缩小 */
@media (max-width: 720px) {
  .grid.g4 {
    grid-template-columns: 1fr;
  }
  .chart-area {
    height: 200px;
  }
  .secret-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
