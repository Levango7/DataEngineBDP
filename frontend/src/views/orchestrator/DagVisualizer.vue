<!--
  DagVisualizer.vue — 编排 DAG 可视化主页面（T007 viz）

  功能：
  - DAG 节点状态着色：PENDING/RUNNING/SUCCESS/FAILED/SKIPPED/WAITING_HUMAN
  - 拓扑分层布局 + SVG 边连线（带箭头）
  - 节点点击查看详情（侧栏）
  - 集成 ThoughtChain / ToolCallRecord / ExecutionReplay 子组件
  - 工具栏：选择 DAG / 运行 / 停止 / 刷新 / 自动轮询

  布局：
  - 顶部：标题 + DAG 选择 + 操作按钮
  - 中部：左 DAG 画布 + 右详情/思考链/工具调用/回放 Tab
  - 底部：图例 + 状态摘要
-->
<template>
  <div class="dag-viz">
    <h1>编排 DAG 可视化</h1>
    <div class="sub">
      可视化 Agent 编排执行图，支持节点状态着色、思考链展示、工具调用记录、人工介入与断点续跑回放。
    </div>

    <!-- 顶部工具栏 -->
    <div class="toolbar">
      <el-select
        v-model="selectedDagId"
        placeholder="选择 DAG"
        filterable
        style="width: 280px"
        @change="onSelectDag"
      >
        <el-option
          v-for="d in dagList"
          :key="d.id"
          :label="d.name ? `${d.name} (${d.id.slice(0, 8)})` : d.id"
          :value="d.id"
        />
      </el-select>

      <el-button type="primary" :icon="VideoPlay" :disabled="!selectedDagId || running" :loading="running" @click="onRun">
        运行
      </el-button>
      <el-button type="warning" :icon="VideoPause" :disabled="!selectedDagId || !running" @click="onStop">
        停止
      </el-button>
      <el-button :icon="Refresh" @click="reloadAll">刷新</el-button>

      <label class="auto-poll">
        <input type="checkbox" v-model="autoPoll" /> 自动刷新（2s）
      </label>

      <span class="spacer" />

      <span v-if="graph" class="graph-status" :class="statusClass(graph.status)">
        图状态：{{ graph.status }}
      </span>
    </div>

    <!-- 主体：左画布 + 右详情 -->
    <div class="viz-body" v-if="graph">
      <!-- 左侧 DAG 画布 -->
      <div class="canvas-wrap card">
        <div class="canvas-head">
          <h3>{{ graph.name || graph.id }}</h3>
          <span class="meta">{{ graph.nodes.length }} 节点 · {{ graph.edges.length }} 边</span>
        </div>
        <div class="canvas" ref="canvasRef">
          <svg :width="svgWidth" :height="svgHeight" class="dag-svg">
            <!-- 边 -->
            <g class="edges">
              <path
                v-for="e in layout.edges"
                :key="`e-${e.source}-${e.target}`"
                :d="e.path"
                class="edge"
                :class="edgeClass(e)"
                :marker-end="`url(#arrow-${edgeClass(e)})`"
              />
            </g>
            <!-- 节点 -->
            <g class="nodes">
              <g
                v-for="n in layout.nodes"
                :key="n.id"
                :transform="`translate(${n.x}, ${n.y})`"
                class="node-g"
                :class="nodeClass(n)"
                @click="onNodeClick(n)"
              >
                <rect
                  :x="-nodeW / 2"
                  :y="-nodeH / 2"
                  :width="nodeW"
                  :height="nodeH"
                  :rx="8"
                  class="node-rect"
                />
                <text :y="-4" text-anchor="middle" class="node-name">{{ n.name }}</text>
                <text :y="12" text-anchor="middle" class="node-type">{{ n.taskType }}</text>
                <circle :cx="nodeW / 2 - 8" :cy="-nodeH / 2 + 8" r="4" class="node-dot" />
              </g>
            </g>
            <!-- 箭头定义 -->
            <defs>
              <marker id="arrow-default" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
                <path d="M0,0 L10,5 L0,10 Z" class="arrow-default" />
              </marker>
              <marker id="arrow-active" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
                <path d="M0,0 L10,5 L0,10 Z" class="arrow-active" />
              </marker>
              <marker id="arrow-failed" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
                <path d="M0,0 L10,5 L0,10 Z" class="arrow-failed" />
              </marker>
            </defs>
          </svg>
        </div>

        <!-- 图例 -->
        <div class="legend">
          <span v-for="s in statusLegend" :key="s.key" class="legend-item">
            <span class="legend-dot" :class="`s-${s.key}`" />
            {{ s.label }}
          </span>
        </div>
      </div>

      <!-- 右侧详情 Tab -->
      <div class="detail-wrap card">
        <div class="tabbar">
          <div
            v-for="t in tabs"
            :key="t.key"
            class="t"
            :class="{ on: activeTab === t.key }"
            @click="activeTab = t.key"
          >
            {{ t.label }}
            <span v-if="t.badge" class="tab-badge">{{ t.badge }}</span>
          </div>
        </div>

        <div class="tab-body">
          <!-- 节点详情 -->
          <div v-if="activeTab === 'node'" class="node-detail">
            <template v-if="selectedNode">
              <div class="kv"><span>节点 ID</span><b>{{ selectedNode.id }}</b></div>
              <div class="kv"><span>名称</span><b>{{ selectedNode.name }}</b></div>
              <div class="kv"><span>任务类型</span><b>{{ selectedNode.taskType }}</b></div>
              <div class="kv"><span>状态</span><b :class="`status-${nodeClass(selectedNode)}`">{{ selectedNode.status }}</b></div>
              <div class="kv"><span>命令</span><b class="mono">{{ selectedNode.command || '--' }}</b></div>
              <div class="kv"><span>超时(秒)</span><b>{{ selectedNode.timeoutSeconds || '不限' }}</b></div>
              <div class="kv"><span>最大重试</span><b>{{ selectedNode.maxRetries }}</b></div>
              <div class="kv"><span>开始时间</span><b>{{ selectedNode.startedAt || '--' }}</b></div>
              <div class="kv"><span>结束时间</span><b>{{ selectedNode.finishedAt || '--' }}</b></div>
              <div v-if="selectedNode.errorMessage" class="kv err">
                <span>错误</span><b>{{ selectedNode.errorMessage }}</b>
              </div>
              <div v-if="selectedNode.params" class="params-block">
                <div class="section-title">参数</div>
                <pre class="json">{{ JSON.stringify(selectedNode.params, null, 2) }}</pre>
              </div>
              <div v-if="nodeResult" class="params-block">
                <div class="section-title">输出</div>
                <pre class="json">{{ JSON.stringify(nodeResult.output, null, 2) }}</pre>
              </div>
            </template>
            <div v-else class="empty">点击左侧节点查看详情</div>
          </div>

          <!-- 思考链 -->
          <ThoughtChain
            v-else-if="activeTab === 'thought'"
            :dag-id="selectedDagId"
            :node-id="selectedNode?.id"
          />

          <!-- 工具调用记录 -->
          <ToolCallRecord
            v-else-if="activeTab === 'tool'"
            :dag-id="selectedDagId"
            :node-id="selectedNode?.id"
          />

          <!-- 回放控制 -->
          <ExecutionReplay
            v-else-if="activeTab === 'replay'"
            :dag-id="selectedDagId"
          />
        </div>
      </div>
    </div>

    <!-- 空态 -->
    <div v-else class="empty-state card">
      <div class="empty-icon">∅</div>
      <div class="empty-text">未选择 DAG，请从顶部下拉选择或先提交一个 DAG</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { VideoPlay, VideoPause, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useApi } from '@/composables/useApi'
import {
  listDags,
  getDagJson,
  runDag,
  stopDag,
  getResults,
  type DagGraphDto,
  type DagNodeDto,
  type NodeStatus,
  type TaskResultDto
} from '@/api/orchestrator-viz'
import ThoughtChain from './ThoughtChain.vue'
import ToolCallRecord from './ToolCallRecord.vue'
import ExecutionReplay from './ExecutionReplay.vue'

/* ------------------------------ 状态 ------------------------------ */

const selectedDagId = ref<string>('')
const running = ref(false)
const autoPoll = ref(false)
const activeTab = ref<'node' | 'thought' | 'tool' | 'replay'>('node')
const selectedNode = ref<DagNodeDto | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null

// DAG 列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: dagList,
  execute: loadDagList
} = useApi<DagGraphDto[]>(() => listDags(), { initialData: [] })

// DAG 图谱 + 结果：通过 useApi 包装并行加载
const {
  data: graphAndResults,
  execute: loadGraphRaw
} = useApi<[DagGraphDto, Record<string, TaskResultDto>], [string]>(
  (id: string) =>
    Promise.all([
      getDagJson(id),
      getResults(id).catch(() => ({} as Record<string, TaskResultDto>))
    ])
)
const graph = computed<DagGraphDto | null>(() => graphAndResults.value?.[0] ?? null)
const results = ref<Record<string, TaskResultDto>>({})

/* ------------------------------ 布局计算 ------------------------------ */

const nodeW = 140
const nodeH = 48
const layerGap = 90
const nodeGap = 30
const padding = 40

/** 节点布局位置 */
interface LayoutNode extends DagNodeDto {
  x: number
  y: number
  layer: number
}

/** 边布局 */
interface LayoutEdge {
  source: string
  target: string
  path: string
}

/** 布局结果 */
interface Layout {
  nodes: LayoutNode[]
  edges: LayoutEdge[]
}

/** 计算拓扑分层（最长路径分层） */
function computeLayers(g: DagGraphDto): Map<string, number> {
  const layers = new Map<string, number>()
  // 入度表
  const inDeg = new Map<string, number>()
  for (const n of g.nodes) inDeg.set(n.id, 0)
  for (const e of g.edges) inDeg.set(e.target, (inDeg.get(e.target) ?? 0) + 1)

  // 后继表
  const succ = new Map<string, string[]>()
  for (const n of g.nodes) succ.set(n.id, [])
  for (const e of g.edges) succ.get(e.source)?.push(e.target)

  // BFS 分层：root=0，其余 = max(前驱层) + 1
  const queue: string[] = []
  for (const n of g.nodes) {
    if ((inDeg.get(n.id) ?? 0) === 0) {
      layers.set(n.id, 0)
      queue.push(n.id)
    }
  }
  // 拷贝入度用于消减
  const rem = new Map(inDeg)
  while (queue.length > 0) {
    const id = queue.shift()!
    const layer = layers.get(id) ?? 0
    for (const t of succ.get(id) ?? []) {
      layers.set(t, Math.max(layers.get(t) ?? 0, layer + 1))
      rem.set(t, (rem.get(t) ?? 1) - 1)
      if ((rem.get(t) ?? 0) === 0) queue.push(t)
    }
  }
  // 孤立节点兜底为 0
  for (const n of g.nodes) if (!layers.has(n.id)) layers.set(n.id, 0)
  return layers
}

/** 计算布局 */
const layout = computed<Layout>(() => {
  const g = graph.value
  if (!g) return { nodes: [], edges: [] }

  const layers = computeLayers(g)
  // 按层分组
  const byLayer = new Map<number, string[]>()
  for (const n of g.nodes) {
    const l = layers.get(n.id) ?? 0
    if (!byLayer.has(l)) byLayer.set(l, [])
    byLayer.get(l)!.push(n.id)
  }
  const maxLayer = Math.max(0, ...byLayer.keys())

  // 节点位置
  const pos = new Map<string, { x: number; y: number }>()
  for (let l = 0; l <= maxLayer; l++) {
    const ids = byLayer.get(l) ?? []
    const totalW = ids.length * nodeW + (ids.length - 1) * nodeGap
    const startX = padding + (svgWidth.value - 2 * padding - totalW) / 2
    ids.forEach((id, i) => {
      pos.set(id, {
        x: startX + i * (nodeW + nodeGap) + nodeW / 2,
        y: padding + l * layerGap + nodeH / 2
      })
    })
  }

  const nodes: LayoutNode[] = g.nodes.map(n => {
    const p = pos.get(n.id) ?? { x: padding, y: padding }
    return { ...n, x: p.x, y: p.y, layer: layers.get(n.id) ?? 0 }
  })

  // 边路径（贝塞尔曲线）
  const edges: LayoutEdge[] = g.edges.map(e => {
    const s = pos.get(e.source)
    const t = pos.get(e.target)
    if (!s || !t) return { source: e.source, target: e.target, path: '' }
    const sx = s.x
    const sy = s.y + nodeH / 2
    const tx = t.x
    const ty = t.y - nodeH / 2
    const my = (sy + ty) / 2
    const path = `M ${sx} ${sy} C ${sx} ${my}, ${tx} ${my}, ${tx} ${ty}`
    return { source: e.source, target: e.target, path }
  })

  return { nodes, edges }
})

/** SVG 画布尺寸 */
const svgWidth = ref(900)
const svgHeight = computed(() => {
  if (!graph.value) return 400
  const layers = computeLayers(graph.value)
  const maxLayer = Math.max(0, ...layers.values())
  return padding * 2 + (maxLayer + 1) * layerGap
})

/* ------------------------------ 状态着色 ------------------------------ */

const statusLegend = [
  { key: 'PENDING', label: '待执行' },
  { key: 'RUNNING', label: '运行中' },
  { key: 'SUCCESS', label: '成功' },
  { key: 'FAILED', label: '失败' },
  { key: 'SKIPPED', label: '跳过' },
  { key: 'WAITING_HUMAN', label: '待人工' }
]

function nodeClass(n: DagNodeDto): string {
  return (n.status ?? 'PENDING').toLowerCase()
}

function edgeClass(e: LayoutEdge): string {
  if (!graph.value) return 'default'
  const src = graph.value.nodes.find(n => n.id === e.source)
  const tgt = graph.value.nodes.find(n => n.id === e.target)
  if (src?.status === 'FAILED') return 'failed'
  if (src?.status === 'SUCCESS' && tgt?.status === 'RUNNING') return 'active'
  return 'default'
}

function statusClass(s?: string): string {
  return `gs-${(s ?? 'DRAFT').toLowerCase()}`
}

/* ------------------------------ Tab 徽标 ------------------------------ */

const tabs = computed(() => [
  { key: 'node' as const, label: '节点详情', badge: 0 },
  { key: 'thought' as const, label: '思考链', badge: 0 },
  { key: 'tool' as const, label: '工具调用', badge: 0 },
  { key: 'replay' as const, label: '回放', badge: 0 }
])

/* ------------------------------ 选中节点结果 ------------------------------ */

const nodeResult = computed<TaskResultDto | null>(() => {
  if (!selectedNode.value) return null
  return results.value[selectedNode.value.id] ?? null
})

/* ------------------------------ 事件处理 ------------------------------ */

function onNodeClick(n: LayoutNode) {
  selectedNode.value = n
  activeTab.value = 'node'
}

async function loadGraph() {
  if (!selectedDagId.value) {
    return
  }
  await loadGraphRaw(selectedDagId.value)
  if (graphAndResults.value) {
    results.value = graphAndResults.value[1]
    running.value = graphAndResults.value[0].status === 'RUNNING'
  } else {
    results.value = {}
  }
}


async function onSelectDag() {
  selectedNode.value = null
  await loadGraph()
}

async function onRun() {
  if (!selectedDagId.value) return
  try {
    running.value = true
    results.value = await runDag(selectedDagId.value)
    await loadGraph()
    ElMessage.success('DAG 执行完成')
  } catch {
    // 错误已由拦截器提示
  } finally {
    running.value = false
  }
}

async function onStop() {
  if (!selectedDagId.value) return
  try {
    await stopDag(selectedDagId.value)
    ElMessage.warning('已请求停止')
    await loadGraph()
  } catch {
    // ignore
  }
}

async function reloadAll() {
  await loadDagList()
  await loadGraph()
}

/* ------------------------------ 自动轮询 ------------------------------ */

watch(autoPoll, (v) => {
  if (v) {
    pollTimer = setInterval(loadGraph, 2000)
  } else if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
})

onMounted(() => {
  void loadDagList().then(() => {
    if ((dagList.value ?? []).length > 0) {
      selectedDagId.value = (dagList.value ?? [])[0].id
      void loadGraph()
    }
  })
})

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<style scoped>
.dag-viz {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.toolbar .spacer {
  flex: 1;
}
.auto-poll {
  font-size: 12px;
  color: var(--muted);
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 0;
}
.auto-poll input {
  width: auto;
}
.graph-status {
  font-size: 12px;
  font-weight: 600;
  padding: 4px 10px;
  border-radius: 20px;
}
.gs-draft { background: var(--c-surface-alt); color: var(--muted); }
.gs-running { background: var(--c-amber-50); color: var(--amber); }
.gs-success { background: var(--c-green-50); color: var(--green); }
.gs-failed { background: var(--c-red-50); color: var(--red); }
.gs-stopped { background: var(--c-surface-alt); color: var(--muted); }
.gs-paused { background: var(--c-indigo-50); color: var(--c-violet); }

.viz-body {
  display: grid;
  grid-template-columns: 1fr 420px;
  gap: 14px;
  align-items: stretch;
}

.canvas-wrap {
  display: flex;
  flex-direction: column;
  min-height: 480px;
}
.canvas-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}
.canvas-head h3 {
  margin: 0;
  font-size: 14px;
}
.canvas {
  flex: 1;
  overflow: auto;
  background: var(--c-surface-hover);
  border-radius: 8px;
  padding: 8px;
}
.dag-svg {
  display: block;
  margin: 0 auto;
}

/* 节点样式 */
.node-g {
  cursor: pointer;
}
.node-rect {
  fill: #fff;
  stroke: var(--line);
  stroke-width: 1.5;
  transition: all 0.2s;
}
.node-g:hover .node-rect {
  stroke-width: 2;
  filter: drop-shadow(0 2px 6px rgba(0, 0, 0, 0.12));
}
.node-name {
  font-size: 12px;
  font-weight: 600;
  fill: var(--ink);
  pointer-events: none;
}
.node-type {
  font-size: 10px;
  fill: var(--muted);
  pointer-events: none;
}
.node-dot {
  fill: var(--muted);
}

/* 状态着色 */
.node-g.pending .node-rect { stroke: var(--c-slate-300); }
.node-g.pending .node-dot { fill: var(--c-slate-400); }

.node-g.running .node-rect { stroke: var(--amber); fill: var(--c-amber-50); }
.node-g.running .node-dot { fill: var(--amber); animation: pulse 1.2s infinite; }

.node-g.success .node-rect { stroke: var(--green); fill: var(--c-green-50); }
.node-g.success .node-dot { fill: var(--green); }

.node-g.failed .node-rect { stroke: var(--red); fill: var(--c-red-50); }
.node-g.failed .node-dot { fill: var(--red); }

.node-g.skipped .node-rect { stroke: var(--c-slate-300); fill: var(--c-surface-alt); }
.node-g.skipped .node-name { fill: var(--muted); }
.node-g.skipped .node-dot { fill: var(--c-slate-300); }

.node-g.waiting_human .node-rect { stroke: var(--c-violet); fill: var(--c-indigo-50); }
.node-g.waiting_human .node-dot { fill: var(--c-violet); animation: pulse 1.5s infinite; }

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.4; }
}

/* 边样式 */
.edge {
  fill: none;
  stroke: var(--c-slate-300);
  stroke-width: 1.5;
}
.edge.active {
  stroke: var(--amber);
  stroke-width: 2;
}
.edge.failed {
  stroke: var(--red);
  stroke-dasharray: 4 3;
}
.arrow-default { fill: var(--c-slate-300); }
.arrow-active { fill: var(--amber); }
.arrow-failed { fill: var(--red); }

/* 图例 */
.legend {
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
  font-size: 12px;
  color: var(--muted);
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px solid var(--line);
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 5px;
}
.legend-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  border: 1.5px solid var(--c-slate-300);
  background: #fff;
}
.legend-dot.s-pending { border-color: var(--c-slate-300); }
.legend-dot.s-running { border-color: var(--amber); background: var(--c-amber-50); }
.legend-dot.s-success { border-color: var(--green); background: var(--c-green-50); }
.legend-dot.s-failed { border-color: var(--red); background: var(--c-red-50); }
.legend-dot.s-skipped { border-color: var(--c-slate-300); background: var(--c-surface-alt); }
.legend-dot.s-waiting_human { border-color: var(--c-violet); background: var(--c-indigo-50); }

/* 详情面板 */
.detail-wrap {
  display: flex;
  flex-direction: column;
  min-height: 480px;
  padding: 0;
}
.tabbar {
  margin: 0;
  border-bottom: 1px solid var(--line);
}
.tab-badge {
  display: inline-block;
  margin-left: 4px;
  font-size: 10px;
  background: var(--primary-soft);
  color: var(--primary);
  border-radius: 10px;
  padding: 0 6px;
}
.tab-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px 16px;
}

.node-detail .kv {
  font-size: 13px;
}
.node-detail .kv b {
  font-weight: 600;
  color: var(--ink);
}
.node-detail .kv.err b {
  color: var(--red);
}
.mono {
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 11.5px;
  word-break: break-all;
}
.params-block {
  margin-top: 12px;
}
.json {
  background: var(--c-surface-hover);
  border-radius: 6px;
  padding: 10px;
  font-family: "SFMono-Regular", Consolas, monospace;
  font-size: 11.5px;
  color: var(--c-slate-700);
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 240px;
  overflow: auto;
}
.empty {
  color: var(--muted);
  text-align: center;
  padding: 40px 0;
  font-size: 13px;
}

.empty-state {
  text-align: center;
  padding: 80px 0;
}
.empty-icon {
  font-size: 48px;
  color: var(--c-slate-300);
  margin-bottom: 10px;
}
.empty-text {
  color: var(--muted);
  font-size: 13px;
}

.status-pending { color: var(--muted); }
.status-running { color: var(--amber); }
.status-success { color: var(--green); }
.status-failed { color: var(--red); }
.status-skipped { color: var(--muted); }
.status-waiting_human { color: var(--c-violet); }
</style>