<template>
  <div class="failover-history">
    <el-container>
      <el-header>
        <el-menu mode="horizontal" :default-active="activeMenu" router>
          <el-menu-item index="/">集群健康看板</el-menu-item>
          <el-menu-item index="/override-policies">OverridePolicy 管理</el-menu-item>
          <el-menu-item index="/failover-history">迁移历史</el-menu-item>
          <el-menu-item index="/replica-plans">副本权重分配</el-menu-item>
          <el-menu-item index="/failover-policies">故障迁移策略</el-menu-item>
        </el-menu>
      </el-header>
      <el-main>
        <div class="page-container">
          <div class="card">
            <div class="toolbar">
              <div class="card-title">故障迁移历史</div>
              <el-button type="primary" @click="showTriggerDialog = true">手动触发迁移</el-button>
            </div>
            <el-table :data="events" stripe style="width: 100%">
              <el-table-column prop="eventId" label="事件 ID" min-width="180" />
              <el-table-column prop="sourceCluster" label="源集群" width="140" />
              <el-table-column prop="targetCluster" label="目标集群" width="140" />
              <el-table-column prop="triggerReason" label="触发原因" width="120" />
              <el-table-column prop="policyName" label="策略名" min-width="140" />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <span :class="`status-tag status-${row.status}`">{{ row.status }}</span>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="100">
                <template #default="{ row }">{{ row.durationMs }}ms</template>
              </el-table-column>
              <el-table-column label="开始时间" width="180">
                <template #default="{ row }">{{ formatTime(row.startedAt) }}</template>
              </el-table-column>
            </el-table>
          </div>

          <div class="card">
            <div class="card-title">迁移时间线</div>
            <div ref="timelineRef" class="chart-container" style="height: 300px;"></div>
          </div>
        </div>

        <!-- 触发迁移对话框 -->
        <el-dialog v-model="showTriggerDialog" title="手动触发故障迁移" width="500px">
          <el-form :model="triggerForm" label-width="120px">
            <el-form-item label="源集群" required>
              <el-input v-model="triggerForm.sourceCluster" placeholder="xinchang-cluster" />
            </el-form-item>
            <el-form-item label="目标集群" required>
              <el-input v-model="triggerForm.targetCluster" placeholder="local-cluster" />
            </el-form-item>
            <el-form-item label="策略名">
              <el-input v-model="triggerForm.policyName" placeholder="default-failover" />
            </el-form-item>
            <el-form-item label="原因">
              <el-input v-model="triggerForm.reason" placeholder="manual" />
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="showTriggerDialog = false">取消</el-button>
            <el-button type="primary" @click="handleTrigger">触发</el-button>
          </template>
        </el-dialog>
      </el-main>
    </el-container>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import * as echarts from 'echarts'
import { ElMessage } from 'element-plus'
import { listFailoverEvents, triggerFailover, type FailoverEvent } from '@/api/multiCluster'

const route = useRoute()
const activeMenu = ref(route.path)

const events = ref<FailoverEvent[]>([])
const showTriggerDialog = ref(false)
const timelineRef = ref<HTMLElement>()
let timelineChart: echarts.ECharts | null = null

const triggerForm = ref({
  sourceCluster: 'xinchang-cluster',
  targetCluster: 'local-cluster',
  policyName: 'default-failover',
  reason: 'manual',
})

function formatTime(t: string): string {
  if (!t) return '-'
  return new Date(t).toLocaleString('zh-CN')
}

async function loadEvents() {
  try {
    const resp = await listFailoverEvents({ limit: 50 })
    events.value = resp.data.items || []
    await nextTick()
    renderTimeline()
  } catch (e) {
    console.error('加载迁移历史失败:', e)
  }
}

function renderTimeline() {
  if (!timelineRef.value) return
  if (!timelineChart) timelineChart = echarts.init(timelineRef.value)

  const data = events.value.map((e) => ({
    name: `${e.sourceCluster} → ${e.targetCluster}`,
    value: [new Date(e.startedAt).getTime(), e.durationMs],
    status: e.status,
  }))

  timelineChart.setOption({
    tooltip: {
      formatter: (p: any) => {
        const d = p.data
        return `${d.name}<br/>耗时: ${d.value[1]}ms<br/>状态: ${d.status}`
      },
    },
    xAxis: { type: 'time', name: '时间' },
    yAxis: { type: 'value', name: '耗时(ms)' },
    series: [
      {
        type: 'scatter',
        data,
        symbolSize: 12,
        itemStyle: {
          color: (p: any) => {
            const s = p.data.status
            if (s === 'succeeded') return '#67c23a'
            if (s === 'failed') return '#f56c6c'
            if (s === 'running') return '#409eff'
            return '#909399'
          },
        },
      },
    ],
  })
}

async function handleTrigger() {
  try {
    await triggerFailover({
      sourceCluster: triggerForm.value.sourceCluster,
      targetCluster: triggerForm.value.targetCluster,
      policyName: triggerForm.value.policyName,
      reason: triggerForm.value.reason,
    })
    ElMessage.success('迁移已触发')
    showTriggerDialog.value = false
    loadEvents()
  } catch (e) {
    ElMessage.error('触发失败')
  }
}

function handleResize() {
  timelineChart?.resize()
}

onMounted(() => {
  loadEvents()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  timelineChart?.dispose()
})
</script>