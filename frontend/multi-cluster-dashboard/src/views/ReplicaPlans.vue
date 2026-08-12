<template>
  <div class="replica-plans">
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
              <div class="card-title">副本权重分配方案</div>
              <el-button type="primary" @click="showCreateDialog = true">新建方案</el-button>
            </div>
            <el-table :data="plans" stripe style="width: 100%">
              <el-table-column prop="policyName" label="策略名" min-width="160" />
              <el-table-column prop="workload" label="工作负载" min-width="140" />
              <el-table-column prop="totalReplicas" label="总副本数" width="100" />
              <el-table-column label="权重" min-width="200">
                <template #default="{ row }">{{ formatWeights(row.weights) }}</template>
              </el-table-column>
              <el-table-column label="分配" min-width="200">
                <template #default="{ row }">{{ formatAllocation(row.allocation) }}</template>
              </el-table-column>
              <el-table-column prop="reason" label="原因" width="100" />
              <el-table-column label="操作" width="140" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" @click="handleEdit(row)">调整权重</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>

          <div class="card" v-if="currentPlan">
            <div class="card-title">副本分配可视化 - {{ currentPlan.policyName }}</div>
            <div ref="allocationChartRef" class="chart-container"></div>
          </div>
        </div>

        <!-- 创建对话框 -->
        <el-dialog v-model="showCreateDialog" title="新建副本权重方案" width="600px">
          <el-form :model="createForm" label-width="120px">
            <el-form-item label="策略名" required>
              <el-input v-model="createForm.policyName" placeholder="weighted-spread" />
            </el-form-item>
            <el-form-item label="工作负载" required>
              <el-input v-model="createForm.workload" placeholder="spark-master" />
            </el-form-item>
            <el-form-item label="总副本数" required>
              <el-input-number v-model="createForm.totalReplicas" :min="1" />
            </el-form-item>
            <el-form-item label="集群权重">
              <div v-for="(w, i) in createForm.weights" :key="i" style="display: flex; gap: 8px; margin-bottom: 8px;">
                <el-input v-model="w.cluster" placeholder="集群名" style="width: 220px;" />
                <el-input-number v-model="w.weight" :min="0" />
                <el-button type="danger" @click="createForm.weights.splice(i, 1)">删除</el-button>
              </div>
              <el-button size="small" @click="createForm.weights.push({ cluster: '', weight: 1 })">添加集群</el-button>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="showCreateDialog = false">取消</el-button>
            <el-button type="primary" @click="handleCreate">创建</el-button>
          </template>
        </el-dialog>

        <!-- 调整权重对话框 -->
        <el-dialog v-model="showEditDialog" title="动态调整副本权重" width="600px">
          <el-form :model="editForm" label-width="120px">
            <el-form-item label="总副本数">
              <el-input-number v-model="editForm.totalReplicas" :min="1" />
            </el-form-item>
            <el-form-item label="集群权重">
              <div v-for="(w, i) in editForm.weights" :key="i" style="display: flex; gap: 8px; margin-bottom: 8px;">
                <el-input v-model="w.cluster" disabled style="width: 220px;" />
                <el-input-number v-model="w.weight" :min="0" />
              </div>
            </el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="showEditDialog = false">取消</el-button>
            <el-button type="primary" @click="handleUpdate">应用</el-button>
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
import {
  listReplicaPlans,
  createReplicaPlan,
  updateReplicaPlan,
  type ReplicaWeightPlan,
} from '@/api/multiCluster'

const route = useRoute()
const activeMenu = ref(route.path)

const plans = ref<ReplicaWeightPlan[]>([])
const currentPlan = ref<ReplicaWeightPlan | null>(null)
const showCreateDialog = ref(false)
const showEditDialog = ref(false)
const allocationChartRef = ref<HTMLElement>()
let allocationChart: echarts.ECharts | null = null

const createForm = ref({
  policyName: '',
  workload: '',
  totalReplicas: 6,
  weights: [
    { cluster: 'xinchang-cluster', weight: 3 },
    { cluster: 'local-cluster', weight: 2 },
    { cluster: 'cce-cluster', weight: 1 },
  ] as Array<{ cluster: string; weight: number }>,
})

const editForm = ref({
  policyName: '',
  totalReplicas: 0,
  weights: [] as Array<{ cluster: string; weight: number }>,
})

function formatWeights(json: string): string {
  try {
    const w = JSON.parse(json)
    return Object.entries(w).map(([k, v]) => `${k}=${v}`).join(', ')
  } catch {
    return '-'
  }
}

function formatAllocation(json: string): string {
  try {
    const a = JSON.parse(json)
    return Object.entries(a).map(([k, v]) => `${k}=${v}`).join(', ')
  } catch {
    return '-'
  }
}

async function loadPlans() {
  try {
    const resp = await listReplicaPlans({ limit: 100 })
    plans.value = resp.data.items || []
    if (plans.value.length > 0 && !currentPlan.value) {
      currentPlan.value = plans.value[0]
      await nextTick()
      renderAllocation()
    }
  } catch (e) {
    console.error('加载方案失败:', e)
  }
}

function renderAllocation() {
  if (!allocationChartRef.value || !currentPlan.value) return
  if (!allocationChart) allocationChart = echarts.init(allocationChartRef.value)

  let allocation: Record<string, number> = {}
  let weights: Record<string, number> = {}
  try {
    allocation = JSON.parse(currentPlan.value.allocation)
    weights = JSON.parse(currentPlan.value.weights)
  } catch {
    return
  }

  const clusters = Object.keys(allocation)
  allocationChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    xAxis: { type: 'category', data: clusters, axisLabel: { rotate: 20 } },
    yAxis: { type: 'value', name: '副本数' },
    series: [
      {
        name: '分配副本',
        type: 'bar',
        data: clusters.map((k) => allocation[k]),
        itemStyle: { color: '#409eff' },
      },
      {
        name: '权重',
        type: 'line',
        data: clusters.map((k) => weights[k] || 0),
        itemStyle: { color: '#e6a23c' },
      },
    ],
  })
}

async function handleCreate() {
  try {
    const weightsMap: Record<string, number> = {}
    for (const w of createForm.value.weights) {
      if (w.cluster) weightsMap[w.cluster] = w.weight
    }
    await createReplicaPlan({
      policyName: createForm.value.policyName,
      workload: createForm.value.workload,
      totalReplicas: createForm.value.totalReplicas,
      weights: weightsMap,
    })
    ElMessage.success('创建成功')
    showCreateDialog.value = false
    loadPlans()
  } catch (e) {
    ElMessage.error('创建失败')
  }
}

function handleEdit(plan: ReplicaWeightPlan) {
  editForm.value.policyName = plan.policyName
  editForm.value.totalReplicas = plan.totalReplicas
  try {
    const weights = JSON.parse(plan.weights)
    editForm.value.weights = Object.entries(weights).map(([cluster, weight]) => ({
      cluster,
      weight: weight as number,
    }))
  } catch {
    editForm.value.weights = []
  }
  showEditDialog.value = true
}

async function handleUpdate() {
  try {
    const weightsMap: Record<string, number> = {}
    for (const w of editForm.value.weights) {
      weightsMap[w.cluster] = w.weight
    }
    await updateReplicaPlan(editForm.value.policyName, {
      totalReplicas: editForm.value.totalReplicas,
      weights: weightsMap,
      reason: 'manual',
    })
    ElMessage.success('调整成功')
    showEditDialog.value = false
    loadPlans()
  } catch (e) {
    ElMessage.error('调整失败')
  }
}

function handleResize() {
  allocationChart?.resize()
}

onMounted(() => {
  loadPlans()
  window.addEventListener('resize', handleResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  allocationChart?.dispose()
})
</script>