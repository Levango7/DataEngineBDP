<template>
  <div class="infra-sched-page">
    <h1>弹性调度</h1>
    <div class="sub">HPA · Cluster Autoscaler · 扩缩容事件历史</div>

    <!-- 集群选择器 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <span class="label">目标集群：</span>
        <el-select
          v-model="selectedClusterKey"
          placeholder="请选择集群"
          style="width: 320px"
          @change="handleClusterChange"
        >
          <el-option
            v-for="c in clusterOptions"
            :key="`${c.environment}/${c.clusterId}`"
            :label="`${c.clusterName}（${envLabel(c.environment)}）`"
            :value="`${c.environment}/${c.clusterId}`"
          />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="reloadAll" />
      </div>
    </el-card>

    <!-- KPI 卡片区：三态 -->
    <div class="grid g4" style="margin-top: 16px">
      <template v-if="!selectedCluster">
        <div class="card" style="grid-column: span 4">
          <h3>请先选择目标集群</h3>
          <div class="meta">在上方下拉框中选择需要管理的集群</div>
        </div>
      </template>
      <template v-else-if="summaryLoading">
        <div class="card" v-for="i in 4" :key="i">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="summaryError">
        <div class="card" style="grid-column: span 4">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            {{ summaryError.message }}，<a href="javascript:void(0)" @click="loadSummary">重试</a>
          </div>
        </div>
      </template>
      <template v-else-if="summary">
        <div class="card">
          <h3>策略总数</h3>
          <div class="kpi">{{ summary.total }}</div>
          <div class="meta">HPA 策略</div>
        </div>
        <div class="card">
          <h3>今日扩容</h3>
          <div class="kpi s">{{ summary.scaleUpToday }}</div>
          <div class="meta">次</div>
        </div>
        <div class="card">
          <h3>今日缩容</h3>
          <div class="kpi w">{{ summary.scaleDownToday }}</div>
          <div class="meta">次</div>
        </div>
        <div class="card">
          <h3>平均响应</h3>
          <div class="kpi">{{ formatDuration(summary.avgDurationMs) }}</div>
          <div class="meta">单次扩缩容耗时</div>
        </div>
      </template>
    </div>

    <!-- HPA 策略列表 -->
    <el-card v-if="selectedCluster" shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>HPA 策略列表</span>
          <el-button type="primary" size="small" @click="openCreateHpaDialog">+ 新建 HPA</el-button>
        </div>
      </template>
      <el-table
        v-loading="hpaLoading"
        :data="hpaList"
        stripe
        border
        style="width: 100%"
        :empty-text="hpaError ? '加载失败，请重试' : '暂无 HPA 策略'"
      >
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column prop="namespace" label="命名空间" width="140" />
        <el-table-column prop="targetDeployment" label="目标 Deployment" min-width="180" />
        <el-table-column label="副本数" width="160" align="center">
          <template #default="{ row }">
            <span class="muted">{{ row.minReplicas }} ~ {{ row.maxReplicas }}</span>
            <span style="margin-left: 8px">当前 {{ row.currentReplicas }}</span>
          </template>
        </el-table-column>
        <el-table-column label="CPU 阈值" width="120" align="center">
          <template #default="{ row }">{{ row.cpuThreshold }}%</template>
        </el-table-column>
        <el-table-column label="内存阈值" width="120" align="center">
          <template #default="{ row }">
            <span v-if="row.memoryThreshold">{{ row.memoryThreshold }}%</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'info'" effect="light">
              {{ hpaStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditHpaDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDeleteHpa(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 扩缩容事件流 -->
    <el-card v-if="selectedCluster" shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>扩缩容事件流</span>
        </div>
      </template>
      <template v-if="eventsLoading">
        <div class="meta">加载中…</div>
      </template>
      <template v-else-if="eventsError">
        <div class="meta" style="color: var(--muted)">事件流加载失败</div>
      </template>
      <template v-else-if="events && events.length > 0">
        <el-timeline>
          <el-timeline-item
            v-for="(ev, idx) in events"
            :key="idx"
            :timestamp="ev.timestamp"
            :type="ev.type === 'scale_up' ? 'success' : 'warning'"
            placement="top"
          >
            <div class="event-item">
              <el-tag :type="ev.type === 'scale_up' ? 'success' : 'warning'" effect="light" size="small">
                {{ eventTypeLabel(ev.type) }}
              </el-tag>
              <span class="event-trigger">触发：{{ ev.trigger }}</span>
              <span class="event-replicas">
                副本 {{ ev.fromReplicas }} → <strong>{{ ev.toReplicas }}</strong>
              </span>
              <span class="event-duration">耗时 {{ formatDuration(ev.durationMs) }}</span>
            </div>
          </el-timeline-item>
        </el-timeline>
      </template>
      <template v-else>
        <div class="meta">暂无扩缩容事件</div>
      </template>
    </el-card>

    <!-- 创建/编辑 HPA 弹窗 -->
    <el-dialog
      v-model="hpaDialogVisible"
      :title="isEditHpa ? '编辑 HPA 策略' : '新建 HPA 策略'"
      width="600px"
      :close-on-click-modal="false"
      @closed="resetHpaForm"
    >
      <el-form
        ref="hpaFormRef"
        :model="hpaForm"
        :rules="hpaRules"
        label-width="140px"
        label-position="right"
      >
        <el-form-item label="名称" prop="name">
          <el-input v-model="hpaForm.name" placeholder="如 web-hpa" :disabled="isEditHpa" />
        </el-form-item>
        <el-form-item label="命名空间" prop="namespace">
          <el-input v-model="hpaForm.namespace" placeholder="如 default" :disabled="isEditHpa" />
        </el-form-item>
        <el-form-item label="目标 Deployment" prop="targetDeployment">
          <el-input v-model="hpaForm.targetDeployment" placeholder="如 web-deploy" />
        </el-form-item>
        <el-form-item label="最小副本数" prop="minReplicas">
          <el-input-number v-model="hpaForm.minReplicas" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="最大副本数" prop="maxReplicas">
          <el-input-number v-model="hpaForm.maxReplicas" :min="1" :max="1000" />
        </el-form-item>
        <el-form-item label="CPU 阈值 (%)" prop="cpuThreshold">
          <el-slider v-model="hpaForm.cpuThreshold" :min="10" :max="95" show-input />
        </el-form-item>
        <el-form-item label="内存阈值 (%)">
          <el-slider v-model="hpaForm.memoryThreshold" :min="0" :max="95" show-input />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="hpaDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="savingHpa" @click="handleSaveHpa">
          {{ isEditHpa ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as infraApi from '@/api/infra'
import type {
  CrossEnvClusterInfo,
  ClusterEnv,
  HpaPolicy,
  ScaleEvent,
  ScaleEventType,
  HpaStatus,
  ScalingPolicySummary
} from '@/api/infra'

/* ------------------------------ 集群选择 ------------------------------ */

const { data: clusterList, execute: loadClusters } = useApi<CrossEnvClusterInfo[]>(
  () => infraApi.getClusters()
)

const clusterOptions = computed(() => clusterList.value ?? [])
const selectedClusterKey = ref<string>('')
const selectedCluster = ref<CrossEnvClusterInfo | null>(null)

function handleClusterChange(key: string) {
  if (!key) {
    selectedCluster.value = null
    return
  }
  const [env, clusterId] = key.split('/')
  const found = clusterOptions.value.find(
    (c) => c.environment === env && c.clusterId === clusterId
  )
  selectedCluster.value = found ?? null
}

watch(clusterOptions, (list) => {
  if (list.length > 0 && !selectedCluster.value) {
    const first = list[0]
    selectedClusterKey.value = `${first.environment}/${first.clusterId}`
    selectedCluster.value = first
  }
})

/* ------------------------------ 调度策略统计 ------------------------------ */

const summaryLoading = ref(false)
const summaryError = ref<Error | null>(null)
const summary = ref<ScalingPolicySummary | null>(null)

async function loadSummary() {
  if (!selectedCluster.value) return
  summaryLoading.value = true
  summaryError.value = null
  try {
    summary.value = await infraApi.getScalingPolicies(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId
    )
  } catch (e) {
    summaryError.value = e instanceof Error ? e : new Error(String(e))
  } finally {
    summaryLoading.value = false
  }
}

/* ------------------------------ HPA 列表 ------------------------------ */

const hpaLoading = ref(false)
const hpaError = ref(false)
const hpaList = ref<HpaPolicy[]>([])

async function loadHpa() {
  if (!selectedCluster.value) return
  hpaLoading.value = true
  hpaError.value = false
  try {
    hpaList.value = await infraApi.getHpas(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId
    )
  } catch {
    hpaError.value = true
  } finally {
    hpaLoading.value = false
  }
}

/* ------------------------------ 扩缩容事件 ------------------------------ */

const eventsLoading = ref(false)
const eventsError = ref(false)
const events = ref<ScaleEvent[]>([])

async function loadEvents() {
  if (!selectedCluster.value) return
  eventsLoading.value = true
  eventsError.value = false
  try {
    events.value = await infraApi.getScaleEvents(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId
    )
  } catch {
    eventsError.value = true
  } finally {
    eventsLoading.value = false
  }
}

/* ------------------------------ 创建/编辑 HPA ------------------------------ */

const hpaDialogVisible = ref(false)
const isEditHpa = ref(false)
const savingHpa = ref(false)
const hpaFormRef = ref<FormInstance>()

interface HpaForm {
  name: string
  namespace: string
  targetDeployment: string
  minReplicas: number
  maxReplicas: number
  cpuThreshold: number
  memoryThreshold: number
}

const hpaForm = reactive<HpaForm>({
  name: '',
  namespace: 'default',
  targetDeployment: '',
  minReplicas: 1,
  maxReplicas: 10,
  cpuThreshold: 80,
  memoryThreshold: 0
})

const hpaRules: FormRules = {
  name: [{ required: true, message: '请输入策略名', trigger: 'blur' }],
  namespace: [{ required: true, message: '请输入命名空间', trigger: 'blur' }],
  targetDeployment: [{ required: true, message: '请输入目标 Deployment', trigger: 'blur' }],
  minReplicas: [{ required: true, message: '请输入最小副本数', trigger: 'blur' }],
  maxReplicas: [{ required: true, message: '请输入最大副本数', trigger: 'blur' }],
  cpuThreshold: [{ required: true, message: '请输入 CPU 阈值', trigger: 'blur' }]
}

function openCreateHpaDialog() {
  isEditHpa.value = false
  resetHpaForm()
  hpaDialogVisible.value = true
}

function openEditHpaDialog(row: HpaPolicy) {
  isEditHpa.value = true
  hpaForm.name = row.name
  hpaForm.namespace = row.namespace
  hpaForm.targetDeployment = row.targetDeployment
  hpaForm.minReplicas = row.minReplicas
  hpaForm.maxReplicas = row.maxReplicas
  hpaForm.cpuThreshold = row.cpuThreshold
  hpaForm.memoryThreshold = row.memoryThreshold ?? 0
  hpaDialogVisible.value = true
}

function resetHpaForm() {
  hpaForm.name = ''
  hpaForm.namespace = 'default'
  hpaForm.targetDeployment = ''
  hpaForm.minReplicas = 1
  hpaForm.maxReplicas = 10
  hpaForm.cpuThreshold = 80
  hpaForm.memoryThreshold = 0
  hpaFormRef.value?.clearValidate()
}

/** 构造 HpaPolicy */
function buildHpaPolicy(currentReplicas = 1): HpaPolicy {
  return {
    name: hpaForm.name,
    namespace: hpaForm.namespace,
    targetDeployment: hpaForm.targetDeployment,
    minReplicas: hpaForm.minReplicas,
    maxReplicas: hpaForm.maxReplicas,
    currentReplicas,
    cpuThreshold: hpaForm.cpuThreshold,
    memoryThreshold: hpaForm.memoryThreshold > 0 ? hpaForm.memoryThreshold : undefined,
    status: 'active'
  }
}

async function handleSaveHpa() {
  if (!selectedCluster.value || !hpaFormRef.value) return
  await hpaFormRef.value.validate(async (valid) => {
    if (!valid) return
    savingHpa.value = true
    try {
      const env = selectedCluster.value!.environment
      const id = selectedCluster.value!.clusterId
      if (isEditHpa.value) {
        await infraApi.updateHpa(env, id, hpaForm.name, buildHpaPolicy())
        ElMessage.success('HPA 策略已更新')
      } else {
        await infraApi.createHpa(env, id, buildHpaPolicy())
        ElMessage.success('HPA 策略已创建')
      }
      hpaDialogVisible.value = false
      await Promise.all([loadHpa(), loadSummary()])
    } catch {
      // 错误提示已由拦截器统一处理
    } finally {
      savingHpa.value = false
    }
  })
}

async function handleDeleteHpa(row: HpaPolicy) {
  if (!selectedCluster.value) return
  try {
    await ElMessageBox.confirm(
      `确认删除 HPA 策略「${row.name}」？`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await infraApi.deleteHpa(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId,
      row.name
    )
    ElMessage.success('策略已删除')
    await Promise.all([loadHpa(), loadSummary()])
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

function envLabel(env: ClusterEnv): string {
  const map: Record<ClusterEnv, string> = {
    private: '私有云',
    cloud: '公有云',
    xinchuang: '信创'
  }
  return map[env] ?? env
}

function hpaStatusLabel(status: HpaStatus): string {
  const map: Record<HpaStatus, string> = {
    active: '运行中',
    paused: '已暂停'
  }
  return map[status] ?? status
}

function eventTypeLabel(t: ScaleEventType): string {
  const map: Record<ScaleEventType, string> = {
    scale_up: '扩容',
    scale_down: '缩容'
  }
  return map[t] ?? t
}

/** 毫秒 → 可读时长 */
function formatDuration(ms: number): string {
  if (!ms || ms <= 0) return '0 ms'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60000) return `${(ms / 1000).toFixed(2)} s`
  return `${(ms / 60000).toFixed(2)} min`
}

/* ------------------------------ 生命周期 ------------------------------ */

async function reloadAll() {
  await loadClusters()
  await Promise.all([loadSummary(), loadHpa(), loadEvents()])
}

watch(selectedCluster, () => {
  if (selectedCluster.value) {
    void loadSummary()
    void loadHpa()
    void loadEvents()
  } else {
    summary.value = null
    hpaList.value = []
    events.value = []
  }
})

onMounted(() => {
  void loadClusters()
})
</script>

<style scoped>
.infra-sched-page {
  padding: 0;
}
.sub {
  color: #717a80;
  font-size: 13px;
  margin-bottom: 16px;
}
.grid {
  display: grid;
  gap: 14px;
}
.grid.g4 {
  grid-template-columns: repeat(4, 1fr);
}
@media (max-width: 1100px) {
  .grid.g4 {
    grid-template-columns: repeat(2, 1fr);
  }
}
@media (max-width: 720px) {
  .grid.g4 {
    grid-template-columns: 1fr;
  }
}
.card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
  padding: 16px;
  background: #fff;
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: #717a80;
  margin: 0 0 8px;
}
.kpi {
  font-size: 24px;
  font-weight: 700;
  color: #232a2e;
  line-height: 1.2;
}
.kpi.s {
  color: #2f9e6f;
}
.kpi.w {
  color: #c08a2e;
}
.kpi.d {
  color: #c0504d;
}
.meta {
  font-size: 12px;
  color: #717a80;
  margin-top: 6px;
}
.muted {
  color: #717a80;
}
.page-card {
  border: 1px solid #e4e8ea;
  border-radius: 10px;
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
.toolbar .label {
  font-size: 13px;
  color: #717a80;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
}
.event-item {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.event-trigger {
  font-size: 13px;
  color: #232a2e;
}
.event-replicas {
  font-size: 13px;
  color: #717a80;
}
.event-replicas strong {
  color: #232a2e;
}
.event-duration {
  font-size: 12px;
  color: #717a80;
}
</style>