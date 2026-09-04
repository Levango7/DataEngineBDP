<template>
  <div class="infra-sched-page">
    <h1>{{ t('infraSched.title') }}</h1>
    <div class="sub">{{ t('infraSched.subtitle') }}</div>

    <!-- 集群选择器 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <span class="label">{{ t('infraSched.selectCluster.label') }}</span>
        <el-select
          v-model="selectedClusterKey"
          :placeholder="t('infraSched.selectCluster.placeholder')"
          style="width: 320px"
          @change="handleClusterChange"
        >
          <el-option
            v-for="c in clusterOptions"
            :key="`${c.environment}/${c.clusterId}`"
            :label="
              t('infraSched.selectCluster.optionFmt', {
                name: c.clusterName,
                env: envLabel(c.environment)
              })
            "
            :value="`${c.environment}/${c.clusterId}`"
          />
        </el-select>
        <div class="spacer"></div>
        <el-button
          :icon="Refresh"
          circle
          :aria-label="t('infraSched.selectCluster.refreshAria')"
          @click="reloadAll"
        />
      </div>
    </el-card>

    <!-- KPI 卡片区：三态 -->
    <div class="grid g4" style="margin-top: 16px">
      <template v-if="!selectedCluster">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('infraSched.selectClusterHint') }}</h3>
          <div class="meta">{{ t('infraSched.selectClusterHintMeta') }}</div>
        </div>
      </template>
      <template v-else-if="summaryLoading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>{{ t('engines.kpi.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('engines.kpi.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else-if="summaryError">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('engines.kpi.loadFailed') }}</h3>
          <div class="meta" style="color: var(--muted)">
            {{ summaryError.message }}，
            <a href="javascript:void(0)" @click="loadSummary">
              {{ t('engines.kpi.loadFailedRetry') }}
            </a>
          </div>
        </div>
      </template>
      <template v-else-if="summary">
        <div class="card">
          <h3>{{ t('infraSched.kpi.total') }}</h3>
          <div class="kpi">{{ summary.total }}</div>
          <div class="meta">{{ t('infraSched.kpi.totalMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraSched.kpi.scaleUpToday') }}</h3>
          <div class="kpi s">{{ summary.scaleUpToday }}</div>
          <div class="meta">{{ t('infraSched.kpi.times') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraSched.kpi.scaleDownToday') }}</h3>
          <div class="kpi w">{{ summary.scaleDownToday }}</div>
          <div class="meta">{{ t('infraSched.kpi.times') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraSched.kpi.avgDuration') }}</h3>
          <div class="kpi">{{ formatDuration(summary.avgDurationMs) }}</div>
          <div class="meta">{{ t('infraSched.kpi.avgDurationMeta') }}</div>
        </div>
      </template>
    </div>

    <!-- HPA 策略列表 -->
    <el-card v-if="selectedCluster" shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>{{ t('infraSched.hpaList.title') }}</span>
          <el-button type="primary" size="small" @click="openCreateHpaDialog">
            {{ t('infraSched.hpaList.new') }}
          </el-button>
        </div>
      </template>
      <el-table
        v-loading="hpaLoading"
        :data="hpaList"
        stripe
        border
        style="width: 100%"
        :empty-text="hpaError ? t('infraSched.hpaList.loadFailed') : t('infraSched.hpaList.empty')"
      >
        <el-table-column
          prop="name"
          :label="t('infraSched.hpaList.columns.name')"
          min-width="160"
        />
        <el-table-column
          prop="namespace"
          :label="t('infraSched.hpaList.columns.namespace')"
          width="140"
        />
        <el-table-column
          prop="targetDeployment"
          :label="t('infraSched.hpaList.columns.targetDeployment')"
          min-width="180"
        />
        <el-table-column
          :label="t('infraSched.hpaList.columns.replicas')"
          width="160"
          align="center"
        >
          <template #default="{ row }">
            <span class="muted">
              {{
                t('infraSched.hpaList.columns.replicasFmt', {
                  min: row.minReplicas,
                  max: row.maxReplicas
                })
              }}
            </span>
            <span style="margin-left: 8px">
              {{
                t('infraSched.hpaList.columns.currentReplicas', { current: row.currentReplicas })
              }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('infraSched.hpaList.columns.cpuThreshold')"
          width="120"
          align="center"
        >
          <template #default="{ row }">{{ row.cpuThreshold }}%</template>
        </el-table-column>
        <el-table-column
          :label="t('infraSched.hpaList.columns.memoryThreshold')"
          width="120"
          align="center"
        >
          <template #default="{ row }">
            <span v-if="row.memoryThreshold">{{ row.memoryThreshold }}%</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('infraSched.hpaList.columns.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="row.status === 'active' ? 'success' : 'info'" effect="light">
              {{ hpaStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('infraSched.hpaList.columns.actions')" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEditHpaDialog(row)">
              {{ t('infraSched.hpaList.actions.edit') }}
            </el-button>
            <el-button link type="danger" @click="handleDeleteHpa(row)">
              {{ t('infraSched.hpaList.actions.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 扩缩容事件流 -->
    <el-card v-if="selectedCluster" shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>{{ t('infraSched.events.title') }}</span>
        </div>
      </template>
      <template v-if="eventsLoading">
        <div class="meta">{{ t('infraSched.events.loading') }}</div>
      </template>
      <template v-else-if="eventsError">
        <div class="meta" style="color: var(--muted)">{{ t('infraSched.events.loadFailed') }}</div>
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
              <el-tag
                :type="ev.type === 'scale_up' ? 'success' : 'warning'"
                effect="light"
                size="small"
              >
                {{ eventTypeLabel(ev.type) }}
              </el-tag>
              <span class="event-trigger">
                {{ t('infraSched.events.triggerFmt', { trigger: ev.trigger }) }}
              </span>
              <span class="event-replicas">
                {{
                  t('infraSched.events.replicasFmt', { from: ev.fromReplicas, to: ev.toReplicas })
                }}
              </span>
              <span class="event-duration">
                {{
                  t('infraSched.events.durationFmt', { duration: formatDuration(ev.durationMs) })
                }}
              </span>
            </div>
          </el-timeline-item>
        </el-timeline>
      </template>
      <template v-else>
        <div class="meta">{{ t('infraSched.events.empty') }}</div>
      </template>
    </el-card>

    <!-- 创建/编辑 HPA 弹窗 -->
    <el-dialog
      v-model="hpaDialogVisible"
      :title="isEditHpa ? t('infraSched.hpaForm.editTitle') : t('infraSched.hpaForm.createTitle')"
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
        <el-form-item :label="t('infraSched.hpaForm.fields.name')" prop="name">
          <el-input
            v-model="hpaForm.name"
            :placeholder="t('infraSched.hpaForm.fields.namePlaceholder')"
            :disabled="isEditHpa"
          />
        </el-form-item>
        <el-form-item :label="t('infraSched.hpaForm.fields.namespace')" prop="namespace">
          <el-input
            v-model="hpaForm.namespace"
            :placeholder="t('infraSched.hpaForm.fields.namespacePlaceholder')"
            :disabled="isEditHpa"
          />
        </el-form-item>
        <el-form-item
          :label="t('infraSched.hpaForm.fields.targetDeployment')"
          prop="targetDeployment"
        >
          <el-input
            v-model="hpaForm.targetDeployment"
            :placeholder="t('infraSched.hpaForm.fields.targetDeploymentPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('infraSched.hpaForm.fields.minReplicas')" prop="minReplicas">
          <el-input-number v-model="hpaForm.minReplicas" :min="1" :max="100" />
        </el-form-item>
        <el-form-item :label="t('infraSched.hpaForm.fields.maxReplicas')" prop="maxReplicas">
          <el-input-number v-model="hpaForm.maxReplicas" :min="1" :max="1000" />
        </el-form-item>
        <el-form-item :label="t('infraSched.hpaForm.fields.cpuThreshold')" prop="cpuThreshold">
          <el-slider v-model="hpaForm.cpuThreshold" :min="10" :max="95" show-input />
        </el-form-item>
        <el-form-item :label="t('infraSched.hpaForm.fields.memoryThreshold')">
          <el-slider v-model="hpaForm.memoryThreshold" :min="0" :max="95" show-input />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="hpaDialogVisible = false">
          {{ t('infraSched.hpaForm.actions.cancel') }}
        </el-button>
        <el-button type="primary" :loading="savingHpa" @click="handleSaveHpa">
          {{
            isEditHpa
              ? t('infraSched.hpaForm.actions.save')
              : t('infraSched.hpaForm.actions.create')
          }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
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

const { t, te } = useI18n()

/* ------------------------------ 集群选择 ------------------------------ */

const { data: clusterList, execute: loadClusters } = useApi<CrossEnvClusterInfo[]>(() =>
  infraApi.getClusters()
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
  const found = clusterOptions.value.find((c) => c.environment === env && c.clusterId === clusterId)
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

const hpaRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('infraSched.rules.nameRequired'), trigger: 'blur' }],
  namespace: [
    { required: true, message: t('infraSched.rules.namespaceRequired'), trigger: 'blur' }
  ],
  targetDeployment: [
    { required: true, message: t('infraSched.rules.targetDeploymentRequired'), trigger: 'blur' }
  ],
  minReplicas: [
    { required: true, message: t('infraSched.rules.minReplicasRequired'), trigger: 'blur' }
  ],
  maxReplicas: [
    { required: true, message: t('infraSched.rules.maxReplicasRequired'), trigger: 'blur' }
  ],
  cpuThreshold: [
    { required: true, message: t('infraSched.rules.cpuThresholdRequired'), trigger: 'blur' }
  ]
}))

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
        ElMessage.success(t('infraSched.messages.hpaUpdated'))
      } else {
        await infraApi.createHpa(env, id, buildHpaPolicy())
        ElMessage.success(t('infraSched.messages.hpaCreated'))
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
      t('infraSched.messages.hpaDeleteConfirm', { name: row.name }),
      t('infraSched.messages.hpaDeleteConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('infraSched.messages.hpaDeleteConfirmOk'),
        cancelButtonText: t('infraSched.messages.hpaDeleteConfirmCancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await infraApi.deleteHpa(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId,
      row.name
    )
    ElMessage.success(t('infraSched.messages.hpaDeleted'))
    await Promise.all([loadHpa(), loadSummary()])
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

function envLabel(env: ClusterEnv): string {
  return t(`infraK8s.env.${env}`)
}

function hpaStatusLabel(status: HpaStatus): string {
  return t(`infraSched.hpaStatus.${status}`)
}

function eventTypeLabel(et: ScaleEventType): string {
  return t(`infraSched.eventType.${et}`)
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
  color: var(--ds-text-secondary);
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
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
  padding: 16px;
  background: #fff;
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: var(--ds-text-secondary);
  margin: 0 0 8px;
}
.kpi {
  font-size: 24px;
  font-weight: 700;
  color: var(--ds-text-primary);
  line-height: 1.2;
}
.kpi.s {
  color: var(--ds-color-success-600);
}
.kpi.w {
  color: var(--ds-color-warning-600);
}
.kpi.d {
  color: var(--ds-color-error-600);
}
.meta {
  font-size: 12px;
  color: var(--ds-text-secondary);
  margin-top: 6px;
}
.muted {
  color: var(--ds-text-secondary);
}
.page-card {
  border: 1px solid var(--ds-border-default);
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
  color: var(--ds-text-secondary);
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
  color: var(--ds-text-primary);
}
.event-replicas {
  font-size: 13px;
  color: var(--ds-text-secondary);
}
.event-replicas strong {
  color: var(--ds-text-primary);
}
.event-duration {
  font-size: 12px;
  color: var(--ds-text-secondary);
}
</style>
