<template>
  <div>
    <h1>BI 分析</h1>
    <div class="sub">
      基于 ECharts 看板组件，查询经统一 SQL 网关跨湖仓集联邦，客户无感知底层引擎。
    </div>
    <div class="toolbar">
      <button class="btn sm" @click="openCreate">+ 新建看板</button>
      <div class="spacer"></div>
      <span class="pill b">统一 SQL 网关</span>
    </div>

    <!-- 看板列表：loading / 错误重试 / 空态 / 真实渲染 四态，无假数据 -->
    <div v-if="boardsLoading" class="meta" style="padding: 24px 4px">看板加载中…</div>
    <div v-else-if="boardsError" class="meta" style="color: var(--red); padding: 24px 4px">
      {{ boardsError.message }}，
      <a href="javascript:void(0)" @click="loadBoards">重试</a>
    </div>
    <div v-else-if="boards.length === 0" class="card" style="padding: 32px; text-align: center">
      <div style="font-size: 28px; margin-bottom: 8px">📊</div>
      <div class="meta">还没有看板。点击右上角「+ 新建看板」创建第一个。</div>
    </div>
    <template v-else>
      <div v-for="board in boards" :key="board.id" class="card" style="margin-bottom: 16px">
        <div style="display: flex; align-items: center; gap: 8px">
          <h3 style="margin: 0">{{ board.name }}</h3>
          <span class="meta" v-if="board.description">{{ board.description }}</span>
          <div class="spacer"></div>
          <button class="btn ghost sm" @click="removeBoard(board)">删除</button>
        </div>
        <div v-if="board.panels.length === 0" class="meta" style="padding: 16px 0">
          该看板暂无组件（编辑能力建设中）
        </div>
        <div v-else class="grid g3" style="margin-top: 12px">
          <div v-for="panel in board.panels" :key="panel.id" class="card" style="box-shadow: none">
            <h3>{{ panel.title }}</h3>
            <template v-if="panelData(panel).rows.length">
              <div v-if="panel.type === 'metric'">
                <div class="kpi s">{{ panelData(panel).rows[0].value }}</div>
                <div class="meta" style="margin-top: 4px">{{ panelData(panel).rows[0].label ?? '' }}</div>
              </div>
              <div
                v-else
                ref="panelEl"
                :data-panel-id="panel.id"
                style="height: 160px"
                class="chart-cell"
              ></div>
            </template>
            <div v-else class="meta" style="padding: 24px 0">暂无数据（面板数据由创建者提供）</div>
          </div>
        </div>
      </div>
    </template>

    <!-- 实时指标：真实 API，保留原有三态 -->
    <div class="card" style="margin-top: 8px">
      <h3>实时指标</h3>
      <div v-if="metricsLoading" class="kpi s">--</div>
      <div v-else-if="metricsError" class="meta" style="color: var(--red)">
        {{ metricsError.message }}，
        <a href="javascript:void(0)" @click="loadMetrics">重试</a>
      </div>
      <template v-else-if="metrics">
        <div v-for="m in metrics" :key="m.key" style="margin-bottom: 8px">
          <div class="kpi s">
            {{ m.value.toLocaleString() }}
            <span class="meta">{{ m.unit }}</span>
          </div>
          <div class="meta" style="margin-top: 4px">
            {{ m.label }} · 延迟 &lt; {{ m.latencySec }}s
          </div>
        </div>
        <div v-if="metrics.length === 0" class="meta">暂无实时指标</div>
      </template>
    </div>

    <Modal :visible="modalVisible" title="新建看板" @close="modalVisible = false">
      <label>看板名</label>
      <input v-model="form.name" placeholder="如 经营驾驶舱" />
      <label>描述</label>
      <input v-model="form.description" placeholder="可选" />
      <label>组件</label>
      <div class="chips">
        <span
          v-for="t in panelTypes"
          :key="t"
          class="chip"
          :class="{ on: form.panels.includes(t) }"
          @click="togglePanel(t)"
          >{{ t }}</span
        >
      </div>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" :disabled="creating || !form.name.trim()" @click="create">
          {{ creating ? '创建中…' : '创建' }}
        </button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, onUnmounted, nextTick } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as echarts from 'echarts'
import * as analyzeApi from '@/api/analyze'
import type { Dashboard, Panel, PanelType, RealtimeMetric } from '@/api/analyze'

const store = useAppStore()
const modalVisible = ref(false)
const creating = ref(false)

const panelTypes = ['折线', '饼图', '指标卡', '柱状'] as const
const form = reactive({
  name: '',
  description: '',
  panels: [] as string[]
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
    boardsError.value = { message: e instanceof Error ? e.message : '看板加载失败' }
  } finally {
    boardsLoading.value = false
    await nextTick()
    renderCharts()
  }
}

function togglePanel(t: string): void {
  const i = form.panels.indexOf(t)
  if (i >= 0) form.panels.splice(i, 1)
  else form.panels.push(t)
}

function openCreate(): void {
  form.name = ''
  form.description = ''
  form.panels = ['折线']
  modalVisible.value = true
}

async function create(): Promise<void> {
  creating.value = true
  try {
    const panelTitleMap: Record<string, string> = {
      折线: '趋势',
      饼图: '占比',
      指标卡: '核心指标',
      柱状: '对比'
    }
    const typeMap: Record<string, PanelType> = {
      折线: 'line',
      饼图: 'pie',
      指标卡: 'metric',
      柱状: 'bar'
    }
    await analyzeApi.createDashboard({
      name: form.name.trim(),
      description: form.description.trim() || undefined,
      panels: form.panels.map((t) => ({
        title: panelTitleMap[t] ?? t,
        type: typeMap[t] ?? 'metric',
        config: {}
      }))
    })
    modalVisible.value = false
    store.showToast('看板已创建')
    await loadBoards()
  } catch (e) {
    store.showToast(e instanceof Error ? e.message : '创建失败')
  } finally {
    creating.value = false
  }
}

async function removeBoard(board: Dashboard): Promise<void> {
  try {
    await analyzeApi.deleteDashboard(board.id)
    store.showToast(`已删除: ${board.name}`)
    await loadBoards()
  } catch (e) {
    store.showToast(e instanceof Error ? e.message : '删除失败')
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
