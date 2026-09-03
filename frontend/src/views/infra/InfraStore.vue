<template>
  <div class="infra-store-page">
    <h1>{{ t('infraStore.title') }}</h1>
    <div class="sub">{{ t('infraStore.subtitle') }}</div>

    <!-- 集群选择器 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <span class="label">{{ t('infraStore.selectCluster.label') }}</span>
        <el-select
          v-model="selectedClusterKey"
          :placeholder="t('infraStore.selectCluster.placeholder')"
          style="width: 320px"
          @change="handleClusterChange"
        >
          <el-option
            v-for="c in clusterOptions"
            :key="`${c.environment}/${c.clusterId}`"
            :label="t('infraSched.selectCluster.optionFmt', { name: c.clusterName, env: envLabel(c.environment) })"
            :value="`${c.environment}/${c.clusterId}`"
          />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle :aria-label="t('infraStore.selectCluster.refreshAria')" @click="reloadAll" />
      </div>
    </el-card>

    <!-- KPI 卡片区：三态 -->
    <div class="grid g4" style="margin-top: 16px">
      <template v-if="!selectedCluster">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('infraStore.selectClusterHint') }}</h3>
          <div class="meta">{{ t('infraStore.selectClusterHintMeta') }}</div>
        </div>
      </template>
      <template v-else-if="usageLoading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>{{ t('engines.kpi.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('engines.kpi.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else-if="usageError">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('engines.kpi.loadFailed') }}</h3>
          <div class="meta" style="color: var(--muted)">
            {{ usageError.message }}，
            <a href="javascript:void(0)" @click="loadUsage">{{ t('engines.kpi.loadFailedRetry') }}</a>
          </div>
        </div>
      </template>
      <template v-else-if="usage">
        <div class="card">
          <h3>{{ t('infraStore.kpi.scCount') }}</h3>
          <div class="kpi">{{ storageClasses.length }}</div>
          <div class="meta">{{ t('infraStore.kpi.scCountMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraStore.kpi.pvcCount') }}</h3>
          <div class="kpi s">{{ pvcs.length }}</div>
          <div class="meta">{{ t('infraStore.kpi.pvcCountMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraStore.kpi.totalCapacity') }}</h3>
          <div class="kpi">{{ formatBytes(usage.totalCapacityBytes) }}</div>
          <div class="meta">{{ t('infraStore.kpi.totalCapacityMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraStore.kpi.usedCapacity') }}</h3>
          <div class="kpi w">{{ formatBytes(usage.usedCapacityBytes) }}</div>
          <div class="meta">{{ t('infraStore.kpi.usedCapacityMeta', { rate: usagePercent }) }}</div>
        </div>
      </template>
    </div>

    <!-- 存储用量图 + StorageClass tabs -->
    <div v-if="selectedCluster" class="grid g2" style="margin-top: 16px">
      <el-card shadow="never" class="page-card">
        <template #header>
          <div class="card-header">
            <span>{{ t('infraStore.capacityDist.title') }}</span>
          </div>
        </template>
        <template v-if="usageLoading">
          <div class="meta">{{ t('engines.kpi.loading') }}</div>
        </template>
        <template v-else-if="usageError">
          <div class="meta" style="color: var(--muted)">{{ t('infraStore.capacityDist.loadFailed') }}</div>
        </template>
        <template v-else-if="usage && usage.byStorageClass.length > 0">
          <div v-for="sc in usage.byStorageClass" :key="sc.name" class="sc-bar">
            <div class="sc-bar-head">
              <span>{{ sc.name }}</span>
              <span class="muted">{{ t('infraStore.capacityDist.used', { used: formatBytes(sc.used), cap: formatBytes(sc.capacity) }) }}</span>
            </div>
            <el-progress
              :percentage="scPercent(sc)"
              :color="usageColor(scPercent(sc))"
              :stroke-width="10"
            />
          </div>
        </template>
        <template v-else>
          <div class="meta">{{ t('infraStore.capacityDist.empty') }}</div>
        </template>
      </el-card>

      <el-card shadow="never" class="page-card">
        <template #header>
          <div class="card-header">
            <span>{{ t('infraStore.scList.title') }}</span>
          </div>
        </template>
        <el-table
          v-loading="classesLoading"
          :data="storageClasses"
          stripe
          border
          size="small"
          :empty-text="classesError ? t('infraStore.scList.loadFailed') : t('infraStore.scList.empty')"
        >
          <el-table-column prop="name" :label="t('infraStore.scList.columns.name')" min-width="160" />
          <el-table-column prop="provisioner" :label="t('infraStore.scList.columns.provisioner')" min-width="180" />
          <el-table-column :label="t('infraStore.scList.columns.reclaimPolicy')" width="120">
            <template #default="{ row }">
              <el-tag
                :type="row.reclaimPolicy === 'Retain' ? 'warning' : 'danger'"
                effect="light"
                size="small"
              >
                {{ row.reclaimPolicy }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('infraStore.scList.columns.default')" width="80" align="center">
            <template #default="{ row }">
              <el-tag v-if="row.default" type="success" effect="light" size="small">{{ t('infraStore.scList.defaultYes') }}</el-tag>
              <span v-else class="muted">-</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>

    <!-- PVC 列表 -->
    <el-card v-if="selectedCluster" shadow="never" class="page-card" style="margin-top: 16px">
      <template #header>
        <div class="card-header">
          <span>{{ t('infraStore.pvc.title') }}</span>
          <el-button type="primary" size="small" @click="openCreatePvcDialog">{{ t('infraStore.pvc.create') }}</el-button>
        </div>
      </template>
      <el-table
        v-loading="pvcsLoading"
        :data="pvcs"
        stripe
        border
        style="width: 100%"
        :empty-text="pvcsError ? t('infraStore.pvc.loadFailed') : t('infraStore.pvc.empty')"
      >
        <el-table-column prop="name" :label="t('infraStore.pvc.columns.name')" min-width="180" />
        <el-table-column prop="namespace" :label="t('infraStore.pvc.columns.namespace')" width="140" />
        <el-table-column prop="storageClassName" :label="t('infraStore.pvc.columns.storageClass')" min-width="160" />
        <el-table-column prop="capacity" :label="t('infraStore.pvc.columns.capacity')" width="120" align="center" />
        <el-table-column :label="t('infraStore.pvc.columns.status')" width="110">
          <template #default="{ row }">
            <el-tag :type="pvcStatusType(row.status)" effect="light">
              {{ t(`infraStore.pvc.status.${row.status}`, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="volumeName" :label="t('infraStore.pvc.columns.volumeName')" min-width="180">
          <template #default="{ row }">
            <span v-if="row.volumeName">{{ row.volumeName }}</span>
            <span v-else class="muted">{{ t('infraStore.pvc.boundEmpty') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('infraStore.pvc.columns.createdAt')" width="180" />
        <el-table-column :label="t('infraStore.pvc.columns.actions')" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleCreateSnapshot(row)">{{ t('infraStore.pvc.actions.snapshot') }}</el-button>
            <el-button link type="danger" @click="handleDeletePvc(row)">{{ t('infraStore.pvc.actions.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 创建 PVC 弹窗 -->
    <el-dialog
      v-model="createPvcVisible"
      :title="t('infraStore.createForm.title')"
      width="520px"
      :close-on-click-modal="false"
      @closed="resetPvcForm"
    >
      <el-form
        ref="pvcFormRef"
        :model="pvcForm"
        :rules="pvcRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item :label="t('infraStore.createForm.fields.name')" prop="name">
          <el-input v-model="pvcForm.name" :placeholder="t('infraStore.createForm.fields.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('infraStore.createForm.fields.namespace')" prop="namespace">
          <el-input v-model="pvcForm.namespace" :placeholder="t('infraStore.createForm.fields.namespacePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('infraStore.createForm.fields.storageClass')" prop="storageClassName">
          <el-select
            v-model="pvcForm.storageClassName"
            :placeholder="t('infraStore.createForm.fields.storageClassPlaceholder')"
            style="width: 100%"
          >
            <el-option
              v-for="sc in storageClasses"
              :key="sc.name"
              :label="sc.name"
              :value="sc.name"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('infraStore.createForm.fields.capacity')" prop="capacity">
          <el-input v-model="pvcForm.capacity" :placeholder="t('infraStore.createForm.fields.capacityPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createPvcVisible = false">{{ t('infraStore.createForm.actions.cancel') }}</el-button>
        <el-button type="primary" :loading="savingPvc" @click="handleCreatePvc">{{ t('infraStore.createForm.actions.submit') }}</el-button>
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
  StorageClass,
  PersistentVolumeClaim,
  StorageUsage,
  StorageClassUsage,
  PvcStatus
} from '@/api/infra'

const { t, te } = useI18n()

/* ------------------------------ 集群选择 ------------------------------ */

const { data: clusterList, execute: loadClusters } = useApi<CrossEnvClusterInfo[]>(() =>
  infraApi.getClusters()
)

const clusterOptions = computed(() => clusterList.value ?? [])
const selectedClusterKey = ref<string>('')
const selectedCluster = ref<CrossEnvClusterInfo | null>(null)

/** 解析选中集群 */
function handleClusterChange(key: string) {
  if (!key) {
    selectedCluster.value = null
    return
  }
  const [env, clusterId] = key.split('/')
  const found = clusterOptions.value.find((c) => c.environment === env && c.clusterId === clusterId)
  selectedCluster.value = found ?? null
}

/** 自动选中第一个集群 */
watch(clusterOptions, (list) => {
  if (list.length > 0 && !selectedCluster.value) {
    const first = list[0]
    selectedClusterKey.value = `${first.environment}/${first.clusterId}`
    selectedCluster.value = first
  }
})

/* ------------------------------ 存储用量 ------------------------------ */

const usageLoading = ref(false)
const usageError = ref<Error | null>(null)
const usage = ref<StorageUsage | null>(null)

async function loadUsage() {
  if (!selectedCluster.value) return
  usageLoading.value = true
  usageError.value = null
  try {
    usage.value = await infraApi.getStorageUsage(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId
    )
  } catch (e) {
    usageError.value = e instanceof Error ? e : new Error(String(e))
  } finally {
    usageLoading.value = false
  }
}

/** 总使用率 */
const usagePercent = computed(() => {
  if (!usage.value || !usage.value.totalCapacityBytes) return 0
  return Math.round((usage.value.usedCapacityBytes / usage.value.totalCapacityBytes) * 100)
})

/* ------------------------------ StorageClass 列表 ------------------------------ */

const classesLoading = ref(false)
const classesError = ref(false)
const storageClasses = ref<StorageClass[]>([])

async function loadStorageClasses() {
  if (!selectedCluster.value) return
  classesLoading.value = true
  classesError.value = false
  try {
    storageClasses.value = await infraApi.getStorageClasses(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId
    )
  } catch {
    classesError.value = true
  } finally {
    classesLoading.value = false
  }
}

/* ------------------------------ PVC 列表 ------------------------------ */

const pvcsLoading = ref(false)
const pvcsError = ref(false)
const pvcs = ref<PersistentVolumeClaim[]>([])

async function loadPvcs() {
  if (!selectedCluster.value) return
  pvcsLoading.value = true
  pvcsError.value = false
  try {
    pvcs.value = await infraApi.getPersistentVolumes(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId
    )
  } catch {
    pvcsError.value = true
  } finally {
    pvcsLoading.value = false
  }
}

/* ------------------------------ 创建 PVC ------------------------------ */

const createPvcVisible = ref(false)
const savingPvc = ref(false)
const pvcFormRef = ref<FormInstance>()

interface PvcForm {
  name: string
  namespace: string
  storageClassName: string
  capacity: string
}

const pvcForm = reactive<PvcForm>({
  name: '',
  namespace: 'default',
  storageClassName: '',
  capacity: '100Gi'
})

const pvcRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('infraStore.rules.nameRequired'), trigger: 'blur' }],
  namespace: [{ required: true, message: t('infraStore.rules.namespaceRequired'), trigger: 'blur' }],
  storageClassName: [{ required: true, message: t('infraStore.rules.storageClassRequired'), trigger: 'change' }],
  capacity: [{ required: true, message: t('infraStore.rules.capacityRequired'), trigger: 'blur' }]
}))

function openCreatePvcDialog() {
  resetPvcForm()
  // 默认选中默认 StorageClass
  const def = storageClasses.value.find((s) => s.default)
  if (def) pvcForm.storageClassName = def.name
  createPvcVisible.value = true
}

function resetPvcForm() {
  pvcForm.name = ''
  pvcForm.namespace = 'default'
  pvcForm.storageClassName = ''
  pvcForm.capacity = '100Gi'
  pvcFormRef.value?.clearValidate()
}

async function handleCreatePvc() {
  if (!selectedCluster.value || !pvcFormRef.value) return
  await pvcFormRef.value.validate(async (valid) => {
    if (!valid) return
    savingPvc.value = true
    try {
      await infraApi.createPvc(
        selectedCluster.value!.environment,
        selectedCluster.value!.clusterId,
        {
          name: pvcForm.name,
          namespace: pvcForm.namespace,
          storageClassName: pvcForm.storageClassName,
          capacity: pvcForm.capacity
        }
      )
      ElMessage.success(t('infraStore.messages.created'))
      createPvcVisible.value = false
      await Promise.all([loadPvcs(), loadUsage()])
    } catch {
      // 错误提示已由拦截器统一处理
    } finally {
      savingPvc.value = false
    }
  })
}

/** 删除 PVC */
async function handleDeletePvc(row: PersistentVolumeClaim) {
  if (!selectedCluster.value) return
  try {
    await ElMessageBox.confirm(t('infraStore.messages.deleteConfirm', { name: row.name }), t('infraStore.messages.deleteConfirmTitle'), {
      type: 'warning',
      confirmButtonText: t('infraStore.messages.deleteConfirmOk'),
      cancelButtonText: t('infraStore.messages.deleteConfirmCancel'),
      confirmButtonClass: 'el-button--danger'
    })
    await infraApi.deletePvc(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId,
      row.name
    )
    ElMessage.success(t('infraStore.messages.deleted'))
    await Promise.all([loadPvcs(), loadUsage()])
  } catch {
    // 用户取消或删除失败
  }
}

/** 创建快照 */
async function handleCreateSnapshot(row: PersistentVolumeClaim) {
  if (!selectedCluster.value) return
  try {
    const result = await infraApi.createSnapshot(
      selectedCluster.value.environment,
      selectedCluster.value.clusterId,
      row.name
    )
    ElMessage.success(t('infraStore.messages.snapshotCreated', { name: result.snapshotName }))
  } catch {
    // 错误提示已由拦截器统一处理
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 环境 → 词条（复用 infraK8s.env） */
function envLabel(env: ClusterEnv): string {
  return t(`infraK8s.env.${env}`)
}

/** PVC 状态 → tag 类型 */
const PVC_STATUS_TAG_MAP: Record<PvcStatus, 'success' | 'warning' | 'danger'> = {
  Bound: 'success',
  Pending: 'warning',
  Lost: 'danger'
}

function pvcStatusType(status: PvcStatus): 'success' | 'warning' | 'danger' {
  return PVC_STATUS_TAG_MAP[status] ?? 'warning'
}

/** 字节 → 可读容量 */
function formatBytes(bytes: number): string {
  if (!bytes || bytes <= 0) return '0 B'
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB', 'PiB']
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${(bytes / Math.pow(1024, i)).toFixed(2)} ${units[i]}`
}

/** StorageClass 使用率 */
function scPercent(sc: StorageClassUsage): number {
  if (!sc.capacity) return 0
  return Math.round((sc.used / sc.capacity) * 100)
}

/** 使用率 → 颜色 */
function usageColor(percentage: number): string {
  if (percentage >= 90) return 'var(--ds-color-error-600)'
  if (percentage >= 70) return 'var(--ds-color-warning-600)'
  return 'var(--ds-color-success-600)'
}

/* ------------------------------ 生命周期 ------------------------------ */

async function reloadAll() {
  await loadClusters()
  await Promise.all([loadUsage(), loadStorageClasses(), loadPvcs()])
}

watch(selectedCluster, () => {
  if (selectedCluster.value) {
    void loadUsage()
    void loadStorageClasses()
    void loadPvcs()
  } else {
    usage.value = null
    storageClasses.value = []
    pvcs.value = []
  }
})

onMounted(() => {
  void loadClusters()
})
</script>

<style scoped>
.infra-store-page {
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
.grid.g2 {
  grid-template-columns: repeat(2, 1fr);
}
@media (max-width: 1100px) {
  .grid.g4 {
    grid-template-columns: repeat(2, 1fr);
  }
  .grid.g2 {
    grid-template-columns: 1fr;
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
.sc-bar {
  margin-bottom: 16px;
}
.sc-bar-head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 6px;
}
.sc-bar-head .muted {
  font-size: 12px;
}
</style>
