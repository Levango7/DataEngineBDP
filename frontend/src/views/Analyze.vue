<template>
  <div>
    <h1>{{ t('analyze.title') }}</h1>
    <div class="sub">{{ t('analyze.subtitle') }}</div>
    <div class="toolbar">
      <button class="btn sm" @click="openCreate">{{ t('analyze.newBoard') }}</button>
      <div class="spacer"></div>
      <span class="pill b">{{ t('analyze.sqlGateway') }}</span>
    </div>

    <!-- 看板列表：loading / 错误重试 / 空态 / 真实渲染 四态，无假数据 -->
    <div v-if="boardsLoading" class="meta" style="padding: 24px 4px">
      {{ t('analyze.boardsLoading') }}
    </div>
    <div v-else-if="boardsError" class="meta" style="color: var(--red); padding: 24px 4px">
      {{ boardsError.message }}，
      <a href="javascript:void(0)" @click="loadBoards">{{ t('common.retry') }}</a>
    </div>
    <div v-else-if="boards.length === 0" class="card" style="padding: 32px; text-align: center">
      <div style="font-size: 28px; margin-bottom: 8px">📊</div>
      <div class="meta">{{ t('analyze.empty') }}</div>
    </div>
    <template v-else>
      <div v-for="board in boards" :key="board.id" class="card" style="margin-bottom: 16px">
        <div style="display: flex; align-items: center; gap: 8px">
          <h3 style="margin: 0">{{ board.name }}</h3>
          <span v-if="board.description" class="meta">{{ board.description }}</span>
          <div class="spacer"></div>
          <button class="btn ghost sm" @click="removeBoard(board)">{{ t('common.delete') }}</button>
        </div>
        <div v-if="board.panels.length === 0" class="meta" style="padding: 16px 0">
          {{ t('analyze.noPanels') }}
        </div>
        <div v-else class="grid g3" style="margin-top: 12px">
          <div v-for="panel in board.panels" :key="panel.id" class="card" style="box-shadow: none">
            <h3>{{ panel.title }}</h3>
            <template v-if="panelData(panel).rows.length">
              <div v-if="panel.type === 'metric'">
                <div class="kpi s">{{ panelData(panel).rows[0].value }}</div>
                <div class="meta" style="margin-top: 4px">
                  {{ panelData(panel).rows[0].label ?? '' }}
                </div>
              </div>
              <div
                v-else
                ref="panelEl"
                :data-panel-id="panel.id"
                style="height: 160px"
                class="chart-cell"
              ></div>
            </template>
            <div v-else class="meta" style="padding: 24px 0">{{ t('analyze.noPanelData') }}</div>
          </div>
        </div>
      </div>
    </template>

    <!-- 实时指标：真实 API，保留原有三态 -->
    <div class="card" style="margin-top: 8px">
      <h3>{{ t('analyze.realtime') }}</h3>
      <div v-if="metricsLoading" class="kpi s">--</div>
      <div v-else-if="metricsError" class="meta" style="color: var(--red)">
        {{ metricsError.message }}，
        <a href="javascript:void(0)" @click="loadMetrics">{{ t('common.retry') }}</a>
      </div>
      <template v-else-if="metrics">
        <div v-for="m in metrics" :key="m.key" style="margin-bottom: 8px">
          <div class="kpi s">
            {{ m.value.toLocaleString() }}
            <span class="meta">{{ m.unit }}</span>
          </div>
          <div class="meta" style="margin-top: 4px">
            {{ t('analyze.latency', { label: m.label, sec: m.latencySec }) }}
          </div>
        </div>
        <div v-if="metrics.length === 0" class="meta">{{ t('analyze.noMetrics') }}</div>
      </template>
    </div>

    <Modal :visible="modalVisible" :title="t('analyze.createModal.title')" @close="modalVisible = false">
      <label>{{ t('analyze.createModal.name') }}</label>
      <input v-model="form.name" :placeholder="t('analyze.createModal.namePlaceholder')" />
      <label>{{ t('analyze.createModal.description') }}</label>
      <input
        v-model="form.description"
        :placeholder="t('analyze.createModal.descriptionPlaceholder')"
      />
      <label>{{ t('analyze.createModal.panels') }}</label>
      <div class="chips">
        <span
          v-for="pt in panelTypes"
          :key="pt"
          class="chip"
          :class="{ on: form.panels.includes(pt) }"
          @click="togglePanel(pt)"
        >
          {{ t(`analyze.panelTypes.${pt}`) }}
        </span>
      </div>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">{{ t('common.cancel') }}</button>
        <button class="btn" :disabled="creating || !form.name.trim()" @click="create">
          {{ creating ? t('analyze.createModal.creating') : t('common.create') }}
        </button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as echarts from 'echarts'
import * as analyzeApi from '@/api/analyze'
import type { Dashboard, Panel, PanelType, RealtimeMetric } from '@/api/analyze'

const { t } = useI18n()
const store = useAppStore()
const modalVisible = ref(false)
const creating = ref(false)

// 面板类型以后端枚举为准，展示名走词条（analyze.panelTypes.*）
const panelTypes: PanelType[] = ['line', 'pie', 'metric', 'bar']
const form = reactive({
  name: '',
  description: '',
  panels: [] as PanelType[]
})

const {
  data: metrics,
  loading: metricsLoading,
  error: metricsError,
  execute: loadMetrics
} = useApi<RealtimeMetric[]>(() => analyzeApi.getRealtimeMetrics(), { initialData: [] })

const boards = ref<Dashboard[]>([])
const boardsLoading = ref(true)
const boardsError = ref<{ message: string } | null>(null)

async function loadBoards(): Promise<void> {
  boardsLoading.value = true
  boardsError.value = null
  try {
    const res = await analyzeApi.listDashboards({ page: 1, pageSize: 20 })
    boards.value = res.list
  } catch (e) {
    boardsError.value = { message: e instanceof Error ? e.message : t('analyze.boardsLoadFailed') }
  } finally {
    boardsLoading.value = false
    await nextTick()
    renderCharts()
  }
}

function togglePanel(pt: PanelType): void {
  const i = form.panels.indexOf(pt)
  if (i >= 0) form.panels.splice(i, 1)
  else form.panels.push(pt)
}

function openCreate(): void {
  form.name = ''
  form.description = ''
  form.panels = ['line']
  modalVisible.value = true
}

async function create(): Promise<void> {
  creating.value = true
  try {
    await analyzeApi.createDashboard({
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      panels: form.panels.map((pt) => ({
        title: t(`analyze.panelTitles.${pt}`),
        type: pt,
        config: {}
      }))
    })
    modalVisible.value = false
    store.showToast(t('analyze.createModal.created'))
    await loadBoards()
  } catch (e) {
    store.showToast(e instanceof Error ? e.message : t('analyze.createModal.createFailed'))
  } finally {
    creating.value = false
  }
}

async function removeBoard(board: Dashboard): Promise<void> {
  try {
    await analyzeApi.deleteDashboard(board.id)
    store.showToast(t('analyze.createModal.deleted', { name: board.name }))
    await loadBoards()
  } catch (e) {
    store.showToast(e instanceof Error ? e.message : t('analyze.createModal.deleteFailed'))
  }
}

interface PanelRow {
  label?: string
  name?: string
  value: number
}

/** 面板数据行（前端契约 panels[].data.rows；无数据返回空） */
function panelData(p: Panel): { rows: PanelRow[] } {
  const d = (p.data ?? {}) as Record<string, unknown>
  const rows = Array.isArray(d.rows) ? (d.rows as PanelRow[]) : []
  return { rows }
}

const chartPool = new Map<string, echarts.ECharts>()

/** 按面板类型把 rows 渲染为 ECharts option */
function buildOption(p: Panel): echarts.EChartsCoreOption | null {
  const rows = panelData(p).rows
  if (!rows.length) return null
  if (p.type === 'pie') {
    return {
      tooltip: { trigger: 'item' },
      series: [
        {
          type: 'pie',
          radius: '65%',
          data: rows.map((r) => ({
            name: r.label ?? r.name ?? '',
            value: r.value
          }))
        }
      ]
    }
  }
  const labels = rows.map((r) => r.label ?? r.name ?? '')
  const values = rows.map((r) => r.value)
  if (p.type === 'bar') {
    return {
      xAxis: { type: 'category', data: labels },
      yAxis: { type: 'value' },
      series: [{ type: 'bar', data: values }]
    }
  }
  return {
    xAxis: { type: 'category', data: labels },
    yAxis: { type: 'value' },
    series: [{ type: 'line', data: values, smooth: true }]
  }
}

function renderCharts(): void {
  for (const board of boards.value) {
    for (const p of board.panels) {
      const opt = buildOption(p)
      if (!opt) continue
      const cell = document.querySelector<HTMLElement>(`.chart-cell[data-panel-id="${p.id}"]`)
      if (!cell) continue
      let inst = chartPool.get(p.id)
      if (!inst) {
        inst = echarts.init(cell)
        chartPool.set(p.id, inst)
      }
      inst.setOption(opt, true)
    }
  }
}

onMounted(() => {
  void loadBoards()
  void loadMetrics()
})

onUnmounted(() => {
  chartPool.forEach((inst) => inst.dispose())
  chartPool.clear()
})
</script>
