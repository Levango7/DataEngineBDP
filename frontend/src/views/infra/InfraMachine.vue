<template>
  <div class="infra-machine-page">
    <h1>{{ t('infraMachine.title') }}</h1>
    <div class="sub">{{ t('infraMachine.subtitle') }}</div>

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
          <h3>{{ t('infraMachine.kpi.total') }}</h3>
          <div class="kpi">{{ kpi.total }}</div>
          <div class="meta">{{ t('infraMachine.kpi.totalMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraMachine.kpi.running') }}</h3>
          <div class="kpi s">{{ kpi.running }}</div>
          <div class="meta">{{ t('infraMachine.kpi.runningMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraMachine.kpi.creating') }}</h3>
          <div class="kpi w">{{ kpi.creating }}</div>
          <div class="meta">{{ t('infraMachine.kpi.creatingMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('infraMachine.kpi.failed') }}</h3>
          <div class="kpi d">{{ kpi.failed }}</div>
          <div class="meta">{{ t('infraMachine.kpi.failedMeta') }}</div>
        </div>
      </template>
    </div>

    <!-- 操作栏 + 集群列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openCreateDialog">
          {{ t('infraMachine.toolbar.create') }}
        </el-button>
        <div class="spacer"></div>
        <el-button
          :icon="Refresh"
          circle
          :aria-label="t('infraMachine.toolbar.refreshAria')"
          @click="reload"
        />
      </div>

      <el-table
        v-loading="loading"
        :data="clusters ?? []"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? t('infraMachine.table.loadFailed') : t('infraMachine.table.empty')"
      >
        <el-table-column
          prop="clusterName"
          :label="t('infraMachine.table.columns.name')"
          min-width="180"
        />
        <el-table-column :label="t('infraMachine.table.columns.status')" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="k8sVersion"
          :label="t('infraMachine.table.columns.k8sVersion')"
          width="120"
        />
        <el-table-column
          :label="t('infraMachine.table.columns.nodeCount')"
          width="140"
          align="center"
        >
          <template #default="{ row }">
            {{
              t('infraMachine.table.columns.nodeCountFmt', {
                master: row.controlPlaneCount,
                worker: row.workerCount
              })
            }}
          </template>
        </el-table-column>
        <el-table-column
          prop="podCidr"
          :label="t('infraMachine.table.columns.podCidr')"
          min-width="160"
        />
        <el-table-column
          prop="serviceCidr"
          :label="t('infraMachine.table.columns.serviceCidr')"
          min-width="160"
        />
        <el-table-column
          prop="createdAt"
          :label="t('infraMachine.table.columns.createdAt')"
          width="180"
        />
        <el-table-column :label="t('infraMachine.table.columns.actions')" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openScaleDialog(row)">
              {{ t('infraMachine.table.actions.scale') }}
            </el-button>
            <el-button link type="danger" @click="handleDestroy(row)">
              {{ t('infraMachine.table.actions.destroy') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建集群向导 -->
    <el-dialog
      v-model="createDialogVisible"
      :title="t('infraMachine.create.title')"
      width="640px"
      :close-on-click-modal="false"
      @closed="resetCreateForm"
    >
      <el-steps :active="createStep" finish-status="success" simple style="margin-bottom: 20px">
        <el-step :title="t('infraMachine.create.steps.basic')" />
        <el-step :title="t('infraMachine.create.steps.nodes')" />
        <el-step :title="t('infraMachine.create.steps.network')" />
      </el-steps>

      <el-form
        ref="createFormRef"
        :model="createForm"
        :rules="createRules"
        label-width="120px"
        label-position="right"
      >
        <!-- 步骤 1：基础信息 -->
        <template v-if="createStep === 0">
          <el-form-item :label="t('infraMachine.create.fields.clusterName')" prop="clusterName">
            <el-input
              v-model="createForm.clusterName"
              :placeholder="t('infraMachine.create.fields.clusterNamePlaceholder')"
            />
          </el-form-item>
          <el-form-item :label="t('infraMachine.create.fields.k8sVersion')" prop="k8sVersion">
            <el-select v-model="createForm.k8sVersion" style="width: 100%">
              <el-option label="v1.28" value="v1.28" />
              <el-option label="v1.27" value="v1.27" />
              <el-option label="v1.26" value="v1.26" />
            </el-select>
          </el-form-item>
        </template>

        <!-- 步骤 2：节点规格 -->
        <template v-else-if="createStep === 1">
          <el-form-item :label="t('infraMachine.create.fields.masterCount')" prop="masterCount">
            <el-input-number v-model="createForm.masterCount" :min="1" :max="9" />
          </el-form-item>
          <el-form-item :label="t('infraMachine.create.fields.workerCount')" prop="workerCount">
            <el-input-number v-model="createForm.workerCount" :min="1" :max="200" />
          </el-form-item>
          <el-form-item :label="t('infraMachine.create.fields.cpu')" prop="cpu">
            <el-input-number v-model="createForm.cpu" :min="2" :max="128" />
          </el-form-item>
          <el-form-item :label="t('infraMachine.create.fields.memory')" prop="memory">
            <el-input-number v-model="createForm.memory" :min="4" :max="1024" />
          </el-form-item>
          <el-form-item :label="t('infraMachine.create.fields.disk')" prop="disk">
            <el-input-number v-model="createForm.disk" :min="50" :max="2000" />
          </el-form-item>
        </template>

        <!-- 步骤 3：网络配置 -->
        <template v-else-if="createStep === 2">
          <el-form-item :label="t('infraMachine.create.fields.podCidr')" prop="podCidr">
            <el-input
              v-model="createForm.podCidr"
              :placeholder="t('infraMachine.create.fields.podCidrPlaceholder')"
            />
          </el-form-item>
          <el-form-item :label="t('infraMachine.create.fields.serviceCidr')" prop="serviceCidr">
            <el-input
              v-model="createForm.serviceCidr"
              :placeholder="t('infraMachine.create.fields.serviceCidrPlaceholder')"
            />
          </el-form-item>
        </template>
      </el-form>

      <template #footer>
        <el-button v-if="createStep > 0" @click="createStep--">
          {{ t('infraMachine.create.actions.prev') }}
        </el-button>
        <el-button v-if="createStep < 2" type="primary" @click="nextCreateStep">
          {{ t('infraMachine.create.actions.next') }}
        </el-button>
        <el-button v-else type="primary" :loading="submitting" @click="handleCreate">
          {{ t('infraMachine.create.actions.submit') }}
        </el-button>
        <el-button @click="createDialogVisible = false">
          {{ t('infraMachine.create.actions.cancel') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 扩缩容弹窗 -->
    <el-dialog
      v-model="scaleDialogVisible"
      :title="t('infraMachine.scale.title')"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px" label-position="right">
        <el-form-item :label="t('infraMachine.scale.fields.clusterName')">
          <el-input :model-value="scalingTarget?.clusterName" disabled />
        </el-form-item>
        <el-form-item :label="t('infraMachine.scale.fields.currentNodes')">
          <el-input :model-value="scalingTarget?.workerCount" disabled />
        </el-form-item>
        <el-form-item :label="t('infraMachine.scale.fields.targetNodes')">
          <el-input-number v-model="scaleTargetCount" :min="1" :max="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scaleDialogVisible = false">
          {{ t('infraMachine.scale.actions.cancel') }}
        </el-button>
        <el-button type="primary" :loading="scaling" @click="handleScale">
          {{ t('infraMachine.scale.actions.confirm') }}
        </el-button>
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
import type { ClusterInfo, ClusterStatus, ClusterCreateRequest } from '@/api/infra'

const { t, te } = useI18n()

/* ------------------------------ 集群列表 ------------------------------ */

const {
  data: clusters,
  loading,
  error,
  execute: reload
} = useApi<ClusterInfo[]>(() => infraApi.getXinchangClusters())

/** KPI 聚合 */
const kpi = computed(() => {
  const list = clusters.value ?? []
  return {
    total: list.length,
    running: list.filter((c) => c.status === 'RUNNING').length,
    creating: list.filter((c) => c.status === 'CREATING' || c.status === 'UPDATING').length,
    failed: list.filter((c) => c.status === 'FAILED').length
  }
})

/* ------------------------------ 新建集群 ------------------------------ */

const createDialogVisible = ref(false)
const createStep = ref(0)
const submitting = ref(false)
const createFormRef = ref<FormInstance>()

interface CreateForm {
  clusterName: string
  k8sVersion: string
  masterCount: number
  workerCount: number
  cpu: number
  memory: number
  disk: number
  podCidr: string
  serviceCidr: string
}

const createForm = reactive<CreateForm>({
  clusterName: '',
  k8sVersion: 'v1.28',
  masterCount: 3,
  workerCount: 3,
  cpu: 8,
  memory: 16,
  disk: 100,
  podCidr: '10.244.0.0/16',
  serviceCidr: '10.96.0.0/12'
})

const createRules = computed<FormRules>(() => ({
  clusterName: [
    { required: true, message: t('infraMachine.rules.clusterNameRequired'), trigger: 'blur' }
  ],
  k8sVersion: [
    { required: true, message: t('infraMachine.rules.k8sVersionRequired'), trigger: 'change' }
  ],
  podCidr: [{ required: true, message: t('infraMachine.rules.podCidrRequired'), trigger: 'blur' }],
  serviceCidr: [
    { required: true, message: t('infraMachine.rules.serviceCidrRequired'), trigger: 'blur' }
  ]
}))

/** 打开新建弹窗 */
function openCreateDialog() {
  createStep.value = 0
  resetCreateForm()
  createDialogVisible.value = true
}

/** 重置新建表单 */
function resetCreateForm() {
  createForm.clusterName = ''
  createForm.k8sVersion = 'v1.28'
  createForm.masterCount = 3
  createForm.workerCount = 3
  createForm.cpu = 8
  createForm.memory = 16
  createForm.disk = 100
  createForm.podCidr = '10.244.0.0/16'
  createForm.serviceCidr = '10.96.0.0/12'
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
  if (!createFormRef.value) return
  await createFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const req: ClusterCreateRequest = {
        clusterName: createForm.clusterName,
        k8sVersion: createForm.k8sVersion,
        podCidr: createForm.podCidr,
        serviceCidr: createForm.serviceCidr,
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
      }
      await infraApi.createXinchangCluster(req)
      ElMessage.success(t('infraMachine.messages.created'))
      createDialogVisible.value = false
      await reload()
    } catch {
      // 错误提示已由拦截器统一处理
    } finally {
      submitting.value = false
    }
  })
}

/* ------------------------------ 扩缩容 ------------------------------ */

const scaleDialogVisible = ref(false)
const scaling = ref(false)
const scalingTarget = ref<ClusterInfo | null>(null)
const scaleTargetCount = ref(3)

/** 打开扩缩容弹窗 */
function openScaleDialog(row: ClusterInfo) {
  scalingTarget.value = row
  scaleTargetCount.value = row.workerCount
  scaleDialogVisible.value = true
}

/** 提交扩缩容 */
async function handleScale() {
  if (!scalingTarget.value) return
  scaling.value = true
  try {
    await infraApi.scaleXinchangCluster(scalingTarget.value.clusterId, {
      targetNodeCount: scaleTargetCount.value
    })
    ElMessage.success(t('infraMachine.messages.scaled'))
    scaleDialogVisible.value = false
    await reload()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    scaling.value = false
  }
}

/* ------------------------------ 销毁 ------------------------------ */

/** 销毁集群（带二次确认） */
async function handleDestroy(row: ClusterInfo) {
  try {
    await ElMessageBox.confirm(
      t('infraMachine.messages.destroyConfirm', { name: row.clusterName }),
      t('infraMachine.messages.destroyConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('infraMachine.messages.destroyConfirmOk'),
        cancelButtonText: t('infraMachine.messages.destroyConfirmCancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await infraApi.destroyXinchangCluster(row.clusterId)
    ElMessage.success(t('infraMachine.messages.destroyed'))
    await reload()
  } catch {
    // 用户取消或销毁失败，不重复提示
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 状态 → 词条（复用 infraK8s.status.*） */
function statusLabel(status: ClusterStatus): string {
  return t(`infraK8s.status.${status}`)
}

/** 状态 → tag 类型 */
const STATUS_TAG_TYPE_MAP: Record<ClusterStatus, 'success' | 'warning' | 'danger' | 'info'> = {
  CREATING: 'warning',
  RUNNING: 'success',
  FAILED: 'danger',
  DESTROYED: 'info',
  UPDATING: 'warning'
}

function statusTagType(status: ClusterStatus): 'success' | 'warning' | 'danger' | 'info' {
  return STATUS_TAG_TYPE_MAP[status] ?? 'info'
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
.infra-machine-page {
  padding: 0;
}
.sub {
  color: var(--muted);
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
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 16px;
  background: var(--panel);
}
.card h3 {
  font-size: 13px;
  font-weight: 600;
  color: var(--muted);
  margin: 0 0 8px;
}
.kpi {
  font-size: 28px;
  font-weight: 700;
  color: var(--ink);
  line-height: 1.2;
}
.kpi.s {
  color: var(--green);
}
.kpi.w {
  color: var(--amber);
}
.kpi.d {
  color: var(--red);
}
.meta {
  font-size: 12px;
  color: var(--muted);
  margin-top: 6px;
}
.page-card {
  border: 1px solid var(--line);
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
</style>
