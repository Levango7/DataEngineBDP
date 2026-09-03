<template>
  <div class="infra-k8s-page">
    <h1>{{ t('infraK8s.title') }}</h1>
    <div class="sub">{{ t('infraK8s.subtitle') }}</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
    <div class="grid g4">
      <template v-if="loading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>{{ t('engines.kpi.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('engines.kpi.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else-if="error">
        <div class="card" style="grid-column: span 4">
          <h3>{{ t('engines.kpi.loadFailed') }}</h3>
          <div class="meta" style="color: var(--muted)">
            {{ error.message }}，
            <a href="javascript:void(0)" @click="reload">{{ t('engines.kpi.loadFailedRetry') }}</a>
          </div>
        </div>
      </template>
      <template v-else-if="clusters">
        <div class="card">
          <h3>{{ t('infraK8s.kpi.total') }}</h3>
          <div class="kpi">{{ kpi.total }}</div>
          <div class="meta">{{ t('infraK8s.kpi.totalMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraK8s.kpi.running') }}</h3>
          <div class="kpi s">{{ kpi.running }}</div>
          <div class="meta">{{ t('infraK8s.kpi.runningMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraK8s.kpi.failed') }}</h3>
          <div class="kpi d">{{ kpi.failed }}</div>
          <div class="meta">{{ t('infraK8s.kpi.failedMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraK8s.kpi.envCount') }}</h3>
          <div class="kpi">{{ kpi.envCount }}</div>
          <div class="meta">
            {{ t('infraK8s.kpi.envBreakdown', { p: kpi.privateCount, c: kpi.cloudCount, x: kpi.xinchuangCount }) }}
          </div>
        </div>
      </template>
    </div>

    <!-- 操作栏 + 环境筛选 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openCreateDialog">{{ t('infraK8s.toolbar.create') }}</el-button>
        <el-tabs v-model="activeEnv" type="card" class="env-tabs" @tab-change="handleEnvChange">
          <el-tab-pane :label="t('infraK8s.toolbar.envTabs.all')" name="all" />
          <el-tab-pane :label="t('infraK8s.toolbar.envTabs.private')" name="private" />
          <el-tab-pane :label="t('infraK8s.toolbar.envTabs.cloud')" name="cloud" />
          <el-tab-pane :label="t('infraK8s.toolbar.envTabs.xinchuang')" name="xinchuang" />
        </el-tabs>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle :aria-label="t('infraK8s.toolbar.refreshAria')" @click="reload" />
      </div>

      <el-table
        v-loading="loading"
        :data="filteredClusters"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? t('infraK8s.table.loadFailed') : t('infraK8s.table.empty')"
      >
        <el-table-column prop="clusterName" :label="t('infraK8s.table.columns.name')" min-width="180" />
        <el-table-column :label="t('infraK8s.table.columns.env')" width="110">
          <template #default="{ row }">
            <el-tag :type="envTagType(row.environment)" effect="light">
              {{ envLabel(row.environment) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('infraK8s.table.columns.provider')" width="120">
          <template #default="{ row }">{{ providerLabel(row.provider) }}</template>
        </el-table-column>
        <el-table-column :label="t('infraK8s.table.columns.status')" width="110">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="k8sVersion" :label="t('infraK8s.table.columns.k8sVersion')" width="110" />
        <el-table-column :label="t('infraK8s.table.columns.nodeCount')" width="120" align="center">
          <template #default="{ row }">{{ row.controlPlaneCount + row.workerCount }}</template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('infraK8s.table.columns.createdAt')" width="180" />
        <el-table-column :label="t('infraK8s.table.columns.actions')" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetailDrawer(row)">{{ t('infraK8s.table.actions.detail') }}</el-button>
            <el-button link type="danger" @click="handleDestroy(row)">{{ t('infraK8s.table.actions.destroy') }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 集群详情抽屉 -->
    <el-drawer
      v-model="detailDrawerVisible"
      :title="detailCluster ? t('infraK8s.detail.title', { name: detailCluster.clusterName }) : t('infraK8s.detail.titleFallback')"
      size="60%"
      @closed="closeDetailDrawer"
    >
      <template v-if="detailCluster">
        <el-descriptions :column="2" border>
          <el-descriptions-item :label="t('infraK8s.detail.fields.clusterId')">{{ detailCluster.clusterId }}</el-descriptions-item>
          <el-descriptions-item :label="t('infraK8s.detail.fields.clusterName')">
            {{ detailCluster.clusterName }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraK8s.detail.fields.env')">
            {{ envLabel(detailCluster.environment) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraK8s.detail.fields.provider')">
            {{ providerLabel(detailCluster.provider) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraK8s.detail.fields.status')">
            <el-tag :type="statusTagType(detailCluster.status)" effect="light">
              {{ statusLabel(detailCluster.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraK8s.detail.fields.k8sVersion')">
            {{ detailCluster.k8sVersion }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraK8s.detail.fields.controlPlane')">
            {{ detailCluster.controlPlaneCount }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraK8s.detail.fields.worker')">
            {{ detailCluster.workerCount }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraK8s.detail.fields.podCidr')">{{ detailCluster.podCidr }}</el-descriptions-item>
          <el-descriptions-item :label="t('infraK8s.detail.fields.serviceCidr')">
            {{ detailCluster.serviceCidr }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraK8s.detail.fields.createdAt')">
            {{ detailCluster.createdAt }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('infraK8s.detail.fields.updatedAt')">
            {{ detailCluster.updatedAt }}
          </el-descriptions-item>
        </el-descriptions>

        <h3 style="margin: 20px 0 12px">{{ t('infraK8s.detail.nodesTitle') }}</h3>
        <el-table
          v-loading="nodesLoading"
          :data="nodes"
          stripe
          border
          size="small"
          :empty-text="nodesError ? t('infraK8s.detail.nodesLoadFailed') : t('infraK8s.detail.nodesEmpty')"
        >
          <el-table-column prop="name" :label="t('infraK8s.detail.nodeColumns.name')" min-width="180" />
          <el-table-column :label="t('infraK8s.detail.nodeColumns.role')" width="120">
            <template #default="{ row }">
              <el-tag
                v-for="role in row.roles"
                :key="role"
                :type="role === 'master' ? 'warning' : 'primary'"
                effect="light"
                size="small"
                style="margin-right: 4px"
              >
                {{ role }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('infraK8s.detail.nodeColumns.status')" width="100">
            <template #default="{ row }">
              <el-tag :type="nodeStatusType(row.status)" effect="light" size="small">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('infraK8s.detail.nodeColumns.cpu')" width="140">
            <template #default="{ row }">{{ t('infraK8s.detail.cpuFmt', { used: row.cpuUsed, cap: row.cpuCapacity }) }}</template>
          </el-table-column>
          <el-table-column :label="t('infraK8s.detail.nodeColumns.mem')" width="140">
            <template #default="{ row }">{{ t('infraK8s.detail.memFmt', { used: row.memUsed, cap: row.memCapacity }) }}</template>
          </el-table-column>
          <el-table-column prop="osImage" :label="t('infraK8s.detail.nodeColumns.os')" min-width="160" />
        </el-table>

        <h3 style="margin: 20px 0 12px">{{ t('infraK8s.detail.componentsTitle') }}</h3>
        <el-row :gutter="12">
          <el-col v-for="comp in components" :key="comp.name" :xs="12" :sm="8" :md="6" :lg="4">
            <div class="comp-card" :class="comp.status">
              <div class="comp-name">{{ comp.name }}</div>
              <div class="comp-status">
                <span class="dot"></span>
                {{ compStatusLabel(comp.status) }}
              </div>
              <div class="comp-meta">{{ comp.meta }}</div>
            </div>
          </el-col>
        </el-row>
      </template>
    </el-drawer>

    <!-- 新建集群向导 -->
    <el-dialog
      v-model="createDialogVisible"
      :title="t('infraK8s.create.title')"
      width="640px"
      :close-on-click-modal="false"
      @closed="resetCreateForm"
    >
      <el-steps :active="createStep" finish-status="success" simple style="margin-bottom: 20px">
        <el-step :title="t('infraK8s.create.steps.env')" />
        <el-step :title="t('infraK8s.create.steps.basic')" />
        <el-step :title="t('infraK8s.create.steps.nodes')" />
        <el-step :title="t('infraK8s.create.steps.confirm')" />
      </el-steps>

      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="120px"
        label-position="right"
      >
        <template v-if="createStep === 0">
          <el-form-item :label="t('infraK8s.create.fields.environment')" prop="environment">
            <el-select v-model="createForm.environment" style="width: 100%">
              <el-option :label="t('infraK8s.create.envOptions.private')" value="private" />
              <el-option :label="t('infraK8s.create.envOptions.cloud')" value="cloud" />
              <el-option :label="t('infraK8s.create.envOptions.xinchuang')" value="xinchuang" />
            </el-select>
          </el-form-item>
          <el-form-item :label="t('infraK8s.create.fields.provider')" prop="provider">
            <el-select v-model="createForm.provider" style="width: 100%">
              <el-option :label="t('infraK8s.create.providerOptions.vsphere')" value="vsphere" />
              <el-option :label="t('infraK8s.create.providerOptions.openstack')" value="openstack" />
              <el-option :label="t('infraK8s.create.providerOptions.huawei')" value="huawei" />
              <el-option :label="t('infraK8s.create.providerOptions.ali')" value="ali" />
              <el-option :label="t('infraK8s.create.providerOptions.tencent')" value="tencent" />
              <el-option :label="t('infraK8s.create.providerOptions.xinchang')" value="xinchang" />
            </el-select>
          </el-form-item>
        </template>

        <template v-else-if="createStep === 1">
          <el-form-item :label="t('infraK8s.create.fields.clusterName')" prop="clusterName">
            <el-input v-model="createForm.clusterName" :placeholder="t('infraK8s.create.fields.clusterNamePlaceholder')" />
          </el-form-item>
          <el-form-item :label="t('infraK8s.create.fields.k8sVersion')" prop="k8sVersion">
            <el-select v-model="createForm.k8sVersion" style="width: 100%">
              <el-option label="v1.28" value="v1.28" />
              <el-option label="v1.27" value="v1.27" />
              <el-option label="v1.26" value="v1.26" />
            </el-select>
          </el-form-item>
        </template>

        <template v-else-if="createStep === 2">
          <el-form-item :label="t('infraK8s.create.fields.masterCount')" prop="masterCount">
            <el-input-number v-model="createForm.masterCount" :min="1" :max="9" />
          </el-form-item>
          <el-form-item :label="t('infraK8s.create.fields.workerCount')" prop="workerCount">
            <el-input-number v-model="createForm.workerCount" :min="1" :max="200" />
          </el-form-item>
          <el-form-item :label="t('infraK8s.create.fields.cpu')" prop="cpu">
            <el-input-number v-model="createForm.cpu" :min="2" :max="128" />
          </el-form-item>
          <el-form-item :label="t('infraK8s.create.fields.memory')" prop="memory">
            <el-input-number v-model="createForm.memory" :min="4" :max="1024" />
          </el-form-item>
          <el-form-item :label="t('infraK8s.create.fields.disk')" prop="disk">
            <el-input-number v-model="createForm.disk" :min="50" :max="2000" />
          </el-form-item>
        </template>

        <template v-else-if="createStep === 3">
          <el-descriptions :column="1" border>
            <el-descriptions-item :label="t('infraK8s.create.summary.env')">
              {{ envLabel(createForm.environment) }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('infraK8s.create.summary.provider')">
              {{ providerLabel(createForm.provider) }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('infraK8s.create.summary.clusterName')">
              {{ createForm.clusterName }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('infraK8s.create.summary.k8sVersion')">
              {{ createForm.k8sVersion }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('infraK8s.create.summary.nodeCount')">
              {{ t('infraK8s.create.summary.nodeCountFmt', { master: createForm.masterCount, worker: createForm.workerCount }) }}
            </el-descriptions-item>
            <el-descriptions-item :label="t('infraK8s.create.summary.spec')">
              {{ t('infraK8s.create.summary.specFmt', { cpu: createForm.cpu, memory: createForm.memory, disk: createForm.disk }) }}
            </el-descriptions-item>
          </el-descriptions>
        </template>
      </el-form>

      <template #footer>
        <el-button v-if="createStep > 0" @click="createStep--">{{ t('infraK8s.create.actions.prev') }}</el-button>
        <el-button v-if="createStep < 3" type="primary" @click="nextCreateStep">{{ t('infraK8s.create.actions.next') }}</el-button>
        <el-button v-else type="primary" :loading="submitting" @click="handleCreate">
          {{ t('infraK8s.create.actions.submit') }}
        </el-button>
        <el-button @click="createDialogVisible = false">{{ t('infraK8s.create.actions.cancel') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as infraApi from '@/api/infra'
import type {
  CrossEnvClusterInfo,
  ClusterStatus,
  ClusterEnv,
  ProviderKind,
  ClusterNode,
  ClusterComponent,
  ComponentHealth
} from '@/api/infra'

const { t, te } = useI18n()

/* ------------------------------ 集群列表 ------------------------------ */

const {
  data: clusters,
  loading,
  error,
  execute: reload
} = useApi<CrossEnvClusterInfo[]>(() => infraApi.getClusters())

/** 环境筛选 */
const activeEnv = ref<'all' | ClusterEnv>('all')

/** 筛选后集群列表 */
const filteredClusters = computed(() => {
  const list = clusters.value ?? []
  if (activeEnv.value === 'all') return list
  return list.filter((c) => c.environment === activeEnv.value)
})

/** KPI 聚合 */
const kpi = computed(() => {
  const list = clusters.value ?? []
  const envSet = new Set(list.map((c) => c.environment))
  return {
    total: list.length,
    running: list.filter((c) => c.status === 'RUNNING').length,
    failed: list.filter((c) => c.status === 'FAILED').length,
    envCount: envSet.size,
    privateCount: list.filter((c) => c.environment === 'private').length,
    cloudCount: list.filter((c) => c.environment === 'cloud').length,
    xinchuangCount: list.filter((c) => c.environment === 'xinchuang').length
  }
})

/** 环境切换 */
function handleEnvChange() {
  // 切换后无需重新请求，前端筛选
}

/* ------------------------------ 集群详情 ------------------------------ */

const detailDrawerVisible = ref(false)
const detailCluster = ref<CrossEnvClusterInfo | null>(null)
const nodes = ref<ClusterNode[]>([])
const nodesLoading = ref(false)
const nodesError = ref(false)
const components = ref<ClusterComponent[]>([])

/** 打开详情抽屉 */
async function openDetailDrawer(row: CrossEnvClusterInfo) {
  detailCluster.value = row
  detailDrawerVisible.value = true
  await Promise.all([loadNodes(row), loadComponents(row)])
}

/** 关闭详情抽屉 */
function closeDetailDrawer() {
  detailCluster.value = null
  nodes.value = []
  components.value = []
}

/** 加载节点列表 */
async function loadNodes(row: CrossEnvClusterInfo) {
  nodesLoading.value = true
  nodesError.value = false
  try {
    nodes.value = await infraApi.getClusterNodes(row.environment, row.clusterId)
  } catch {
    nodesError.value = true
  } finally {
    nodesLoading.value = false
  }
}

/** 加载组件状态 */
async function loadComponents(row: CrossEnvClusterInfo) {
  try {
    components.value = await infraApi.getClusterComponents(row.environment, row.clusterId)
  } catch {
    components.value = []
  }
}

/* ------------------------------ 新建集群 ------------------------------ */

const createDialogVisible = ref(false)
const createStep = ref(0)
const submitting = ref(false)
const createFormRef = ref<FormInstance>()

interface CreateForm {
  environment: ClusterEnv
  provider: ProviderKind
  clusterName: string
  k8sVersion: string
  masterCount: number
  workerCount: number
  cpu: number
  memory: number
  disk: number
}

const createForm = reactive<CreateForm>({
  environment: 'private',
  provider: 'vsphere',
  clusterName: '',
  k8sVersion: 'v1.28',
  masterCount: 3,
  workerCount: 3,
  cpu: 8,
  memory: 16,
  disk: 100
})

const createRules = computed<FormRules>(() => ({
  environment: [{ required: true, message: t('infraK8s.rules.environmentRequired'), trigger: 'change' }],
  provider: [{ required: true, message: t('infraK8s.rules.providerRequired'), trigger: 'change' }],
  clusterName: [{ required: true, message: t('infraK8s.rules.clusterNameRequired'), trigger: 'blur' }],
  k8sVersion: [{ required: true, message: t('infraK8s.rules.k8sVersionRequired'), trigger: 'change' }]
}))

/** 打开新建弹窗 */
function openCreateDialog() {
  createStep.value = 0
  resetCreateForm()
  createDialogVisible.value = true
}

/** 重置新建表单 */
function resetCreateForm() {
  createForm.environment = 'private'
  createForm.provider = 'vsphere'
  createForm.clusterName = ''
  createForm.k8sVersion = 'v1.28'
  createForm.masterCount = 3
  createForm.workerCount = 3
  createForm.cpu = 8
  createForm.memory = 16
  createForm.disk = 100
  createFormRef.value?.clearValidate()
}

/** 下一步 */
async function nextCreateStep() {
  if (!createFormRef.value) return
  await createFormRef.value.validate((valid) => {
    if (valid) createStep.value++
  })
}

/** 提交创建 */
async function handleCreate() {
  submitting.value = true
  try {
    await infraApi.createCluster({
      environment: createForm.environment,
      provider: createForm.provider,
      clusterName: createForm.clusterName,
      k8sVersion: createForm.k8sVersion,
      podCidr: '10.244.0.0/16',
      serviceCidr: '10.96.0.0/12',
      workers: [
        {
          role: 'master',
          count: createForm.masterCount,
          cpu: createForm.cpu,
          memory: createForm.memory,
          disk: createForm.disk
        },
        {
          role: 'worker',
          count: createForm.workerCount,
          cpu: createForm.cpu,
          memory: createForm.memory,
          disk: createForm.disk
        }
      ]
    })
    ElMessage.success(t('infraK8s.messages.created'))
    createDialogVisible.value = false
    await reload()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    submitting.value = false
  }
}

/* ------------------------------ 销毁 ------------------------------ */

/** 销毁集群（带二次确认） */
async function handleDestroy(row: CrossEnvClusterInfo) {
  try {
    await ElMessageBox.confirm(t('infraK8s.messages.destroyConfirm', { name: row.clusterName }), t('infraK8s.messages.destroyConfirmTitle'), {
      type: 'warning',
      confirmButtonText: t('infraK8s.messages.destroyConfirmOk'),
      cancelButtonText: t('infraK8s.messages.destroyConfirmCancel'),
      confirmButtonClass: 'el-button--danger'
    })
    await infraApi.destroyCluster(row.environment, row.clusterId)
    ElMessage.success(t('infraK8s.messages.destroyed'))
    await reload()
  } catch {
    // 用户取消或销毁失败，不重复提示
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

const STATUS_TAG_TYPE_MAP: Record<ClusterStatus, 'success' | 'warning' | 'danger' | 'info'> = {
  CREATING: 'warning',
  RUNNING: 'success',
  FAILED: 'danger',
  DESTROYED: 'info',
  UPDATING: 'warning'
}

function statusLabel(status: ClusterStatus): string {
  return t(`infraK8s.status.${status}`)
}

function statusTagType(status: ClusterStatus): 'success' | 'warning' | 'danger' | 'info' {
  return STATUS_TAG_TYPE_MAP[status] ?? 'info'
}

const ENV_TAG_TYPE_MAP: Record<ClusterEnv, 'primary' | 'success' | 'warning'> = {
  private: 'primary',
  cloud: 'success',
  xinchuang: 'warning'
}

function envLabel(env: ClusterEnv): string {
  return t(`infraK8s.env.${env}`)
}

function envTagType(env: ClusterEnv): 'primary' | 'success' | 'warning' {
  return ENV_TAG_TYPE_MAP[env] ?? 'primary'
}

function providerLabel(p: ProviderKind): string {
  return t(`infraK8s.provider.${p}`)
}

/** 节点状态 → tag 类型 */
function nodeStatusType(status: string): 'success' | 'danger' | 'info' {
  if (status === 'Ready') return 'success'
  if (status === 'NotReady') return 'danger'
  return 'info'
}

/** 组件健康状态词条 */
function compStatusLabel(status: ComponentHealth): string {
  return t(`engines.components.status.${status}`)
}

/* ------------------------------ 生命周期 ------------------------------ */

let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void reload()
  // 30s 轮询刷新
  timer = setInterval(() => void reload(), 30000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.infra-k8s-page {
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
}
@media (max-width: 720px) {
  .grid.g4,
  .grid.g2 {
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
  font-size: 28px;
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
.page-card {
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
}
.toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.toolbar .spacer {
  flex: 1;
}
.env-tabs {
  margin-left: 8px;
}
.comp-card {
  border: 1px solid var(--ds-border-default);
  border-radius: 8px;
  padding: 12px;
  margin-bottom: 12px;
  background: #fff;
  text-align: center;
}
.comp-card.healthy {
  border-color: #bbf7d0;
  background: #ecfdf5;
}
.comp-card.warning {
  border-color: #fbbf24;
  background: #fffbeb;
}
.comp-card.error {
  border-color: var(--ds-color-error-600);
  background: #fef2f2;
}
.comp-name {
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 6px;
}
.comp-status {
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-bottom: 4px;
}
.comp-status .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
}
.comp-card.healthy .comp-status {
  color: var(--ds-color-success-600);
}
.comp-card.warning .comp-status {
  color: var(--ds-color-warning-600);
}
.comp-card.error .comp-status {
  color: var(--ds-color-error-600);
}
.comp-meta {
  font-size: 11px;
  color: var(--ds-text-secondary);
}
</style>
