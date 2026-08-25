<template>
  <div class="loop-task-detail-page">
    <el-page-header @back="$router.back()" :content="`任务详情: ${taskId}`" />

    <!-- 任务概览 -->
    <el-card class="overview-card">
      <template #header>任务概览</template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="任务 ID">{{ task?.taskId }}</el-descriptions-item>
        <el-descriptions-item label="任务名称">{{ task?.taskName }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="getStatusType(task?.status)">{{ getStatusLabel(task?.status) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="基座模型">{{ task?.baseModel }}</el-descriptions-item>
        <el-descriptions-item label="微调方式">{{ task?.method }}</el-descriptions-item>
        <el-descriptions-item label="框架">{{ task?.framework }}</el-descriptions-item>
        <el-descriptions-item label="Adapter 版本">{{ task?.adapterVersion }}</el-descriptions-item>
        <el-descriptions-item label="报告版本">{{ task?.reportVersion }}</el-descriptions-item>
        <el-descriptions-item label="当前步骤">{{ task?.currentStep }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 训练指标图表 -->
    <el-card class="metrics-card">
      <template #header>训练指标（实时）</template>
      <el-row :gutter="20">
        <el-col :span="12">
          <h4>Loss 曲线</h4>
          <EChart :option="lossChartOption" height="300px" />
        </el-col>
        <el-col :span="12">
          <h4>学习率曲线</h4>
          <EChart :option="lrChartOption" height="300px" />
        </el-col>
      </el-row>
      <el-row :gutter="20" style="margin-top: 20px;">
        <el-col :span="12">
          <h4>GPU 利用率</h4>
          <EChart :option="gpuUtilChartOption" height="300px" />
        </el-col>
        <el-col :span="12">
          <h4>GPU 显存占用 (GB)</h4>
          <EChart :option="gpuMemChartOption" height="300px" />
        </el-col>
      </el-row>
    </el-card>

    <!-- 步骤结果 -->
    <el-card class="steps-card">
      <template #header>步骤结果</template>
      <el-collapse v-model="activeSteps">
        <el-collapse-item title="微调步骤" name="finetune">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="状态">{{ task?.finetuneResult.status }}</el-descriptions-item>
            <el-descriptions-item label="微调任务 ID">{{ task?.finetuneResult.taskId }}</el-descriptions-item>
            <el-descriptions-item label="Adapter 路径">{{ task?.finetuneResult.adapterPath }}</el-descriptions-item>
            <el-descriptions-item label="错误">{{ task?.finetuneResult.error || '无' }}</el-descriptions-item>
          </el-descriptions>
        </el-collapse-item>
        <el-collapse-item title="评测步骤" name="evaluate">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="状态">{{ task?.evalResult.status }}</el-descriptions-item>
            <el-descriptions-item label="评测任务 ID">{{ task?.evalResult.jobId }}</el-descriptions-item>
            <el-descriptions-item label="准确率">{{ task?.evalResult.accuracy?.toFixed(4) }}</el-descriptions-item>
            <el-descriptions-item label="召回率">{{ task?.evalResult.recall?.toFixed(4) }}</el-descriptions-item>
            <el-descriptions-item label="F1">{{ task?.evalResult.f1?.toFixed(4) }}</el-descriptions-item>
            <el-descriptions-item label="P95 延迟">{{ task?.evalResult.latencyP95?.toFixed(2) }} ms</el-descriptions-item>
            <el-descriptions-item label="Token 成本">{{ task?.evalResult.cost }}</el-descriptions-item>
            <el-descriptions-item label="幻觉率">{{ task?.evalResult.hallucination?.toFixed(4) }}</el-descriptions-item>
          </el-descriptions>
        </el-collapse-item>
        <el-collapse-item title="部署步骤" name="deploy">
          <el-descriptions :column="2" border>
            <el-descriptions-item label="状态">{{ task?.deployResult.status }}</el-descriptions-item>
            <el-descriptions-item label="部署 ID">{{ task?.deployResult.deploymentId }}</el-descriptions-item>
            <el-descriptions-item label="端点">{{ task?.deployResult.endpoint }}</el-descriptions-item>
            <el-descriptions-item label="健康">{{ task?.deployResult.healthy ? '✓' : '✗' }}</el-descriptions-item>
          </el-descriptions>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <!-- WebSocket 连接状态 -->
    <el-card class="ws-card">
      <template #header>实时监控</template>
      <el-tag :type="wsConnected ? 'success' : 'danger'">
        {{ wsConnected ? 'WebSocket 已连接' : 'WebSocket 未连接' }}
      </el-tag>
      <el-button style="margin-left: 12px;" @click="toggleWS">
        {{ wsConnected ? '断开' : '连接' }}
      </el-button>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import EChart from '@/components/EChart.vue'
import { getLoopTask, createTaskWebSocket } from '@/api/loop'
import type { LoopTaskResponse, WSMessage, TrainingMetrics } from '@/types'

const route = useRoute()
const taskId = ref(route.params.taskId as string)

const task = ref<LoopTaskResponse | null>(null)
const activeSteps = ref(['finetune', 'evaluate', 'deploy'])

// 训练指标数据
const metricsHistory = ref<TrainingMetrics[]>([])

// WebSocket
let ws: WebSocket | null = null
const wsConnected = ref(false)
let metricSeq = 0

// ============================================================
// ECharts 配置
// ============================================================
const lossChartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: metricsHistory.value.map(m => m.step), name: 'Step' },
  yAxis: { type: 'value', name: 'Loss' },
  series: [{ name: 'Loss', type: 'line', data: metricsHistory.value.map(m => m.loss), smooth: true, areaStyle: {} }]
}))

const lrChartOption = computed(() => ({
  tooltip: { trigger: 'axis' },
  xAxis: { type: 'category', data: metricsHistory.value.map(m => m.step), name: 'Step' },
  yAxis: { type: 'value', name: 'Learning Rate' },
  series: [{ name: 'LR', type: 'line', data: metricsHistory.value.map(m => m.learningRate), smooth: true }]
}))

const gpuUtilChartOption = computed(() => {
  const gpuCount = metricsHistory.value[0]?.gpuUtil.length || 1
  const series = []
  for (let i = 0; i < gpuCount; i++) {
    series.push({
      name: `GPU ${i}`,
      type: 'line',
      data: metricsHistory.value.map(m => m.gpuUtil[i] || 0),
      smooth: true
    })
  }
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: series.map(s => s.name) },
    xAxis: { type: 'category', data: metricsHistory.value.map(m => m.step), name: 'Step' },
    yAxis: { type: 'value', name: '利用率 (%)', max: 100 },
    series
  }
})

const gpuMemChartOption = computed(() => {
  const gpuCount = metricsHistory.value[0]?.gpuMemory.length || 1
  const series = []
  for (let i = 0; i < gpuCount; i++) {
    series.push({
      name: `GPU ${i}`,
      type: 'line',
      data: metricsHistory.value.map(m => m.gpuMemory[i] || 0),
      smooth: true
    })
  }
  return {
    tooltip: { trigger: 'axis' },
    legend: { data: series.map(s => s.name) },
    xAxis: { type: 'category', data: metricsHistory.value.map(m => m.step), name: 'Step' },
    yAxis: { type: 'value', name: '显存 (GB)' },
    series
  }
})

// ============================================================
// 加载任务详情
// ============================================================
async function loadTask() {
  try {
    task.value = await getLoopTask(taskId.value)
  } catch (e) {
    ElMessage.error('加载任务详情失败')
  }
}

// ============================================================
// WebSocket 实时监控
// ============================================================
function connectWS() {
  ws = createTaskWebSocket(
    taskId.value,
    (msg: WSMessage) => {
      if (msg.type === 'metrics' && typeof msg.data?.loss === 'number') {
        const rawStep = msg.data.step
        const step =
          typeof rawStep === 'number' && Number.isFinite(rawStep) ? rawStep : ++metricSeq
        metricsHistory.value.push({
          step,
          loss: msg.data.loss || 0,
          learningRate: msg.data.learningRate || 0,
          gpuUtil: Array.isArray(msg.data.gpuUtil) ? msg.data.gpuUtil : [],
          gpuMemory: Array.isArray(msg.data.gpuMemory) ? msg.data.gpuMemory : [],
          epoch: typeof msg.data.epoch === 'number' ? msg.data.epoch : 0
        })
      } else if (msg.type === 'status') {
        loadTask()
      } else if (msg.type === 'completed') {
        loadTask()
        ElMessage.success('闭环任务已完成')
      } else if (msg.type === 'error') {
        ElMessage.error(`步骤 ${msg.data.step} 错误: ${msg.data.error}`)
      }
    },
    () => { wsConnected.value = false },
    () => { wsConnected.value = false }
  )
  wsConnected.value = true
}

function disconnectWS() {
  ws?.close()
  ws = null
  wsConnected.value = false
}

function toggleWS() {
  if (wsConnected.value) {
    disconnectWS()
  } else {
    connectWS()
  }
}

watch(
  () => route.params.taskId,
  (newId) => {
    if (!newId || newId === taskId.value) return
    disconnectWS()
    task.value = null
    metricsHistory.value = []
    metricSeq = 0
    taskId.value = newId as string
    loadTask()
    connectWS()
  }
)

// 状态相关
function getStatusType(status?: string): string {
  const map: Record<string, string> = {
    pending: 'info', finetuning: 'warning', evaluating: 'warning',
    deploying: 'warning', completed: 'success', failed: 'danger', cancelled: 'info'
  }
  return map[status || ''] || 'info'
}

function getStatusLabel(status?: string): string {
  const map: Record<string, string> = {
    pending: '待执行', finetuning: '微调中', evaluating: '评测中',
    deploying: '部署中', completed: '已完成', failed: '失败', cancelled: '已取消'
  }
  return map[status || ''] || status || ''
}

onMounted(() => {
  loadTask()
  connectWS()
})

onUnmounted(() => {
  disconnectWS()
})
</script>

<style scoped>
.loop-task-detail-page {
  padding: 20px;
}
.overview-card, .metrics-card, .steps-card, .ws-card {
  margin-top: 16px;
}
h4 {
  margin: 0 0 8px 0;
  color: #606266;
}
</style>