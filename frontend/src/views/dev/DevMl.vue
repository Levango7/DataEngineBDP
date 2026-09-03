<template>
  <div class="dev-ml-page">
    <h1>{{ t('devMl.title') }}</h1>
    <div class="sub">{{ t('devMl.subtitle') }}</div>
    <div class="grid g4">
      <div class="card">
        <h3>{{ t('devMl.kpi.trainJobs') }}</h3>
        <div v-if="trainLoading" class="kpi-skeleton">{{ t('devMl.kpi.loading') }}</div>
        <div v-else-if="trainError" class="kpi-error">
          {{ t('devMl.kpi.loadFailed') }}
          <button class="retry-btn" @click="loadTrain">{{ t('devMl.kpi.retry') }}</button>
        </div>
        <template v-else>
          <div class="kpi">{{ trainKpi.total }}</div>
          <div class="meta">{{ t('devMl.kpi.trainJobsMeta') }}</div>
        </template>
      </div>
      <div class="card">
        <h3>{{ t('devMl.kpi.running') }}</h3>
        <div v-if="trainLoading" class="kpi-skeleton">{{ t('devMl.kpi.loading') }}</div>
        <div v-else-if="trainError" class="kpi-error">
          {{ t('devMl.kpi.loadFailed') }}
          <button class="retry-btn" @click="loadTrain">{{ t('devMl.kpi.retry') }}</button>
        </div>
        <template v-else>
          <div class="kpi running">{{ trainKpi.running }}</div>
          <div class="meta">{{ t('devMl.kpi.runningMeta') }}</div>
        </template>
      </div>
      <div class="card">
        <h3>{{ t('devMl.kpi.models') }}</h3>
        <div v-if="modelsLoading" class="kpi-skeleton">{{ t('devMl.kpi.loading') }}</div>
        <div v-else-if="modelsError" class="kpi-error">
          {{ t('devMl.kpi.loadFailed') }}
          <button class="retry-btn" @click="loadModels">{{ t('devMl.kpi.retry') }}</button>
        </div>
        <template v-else>
          <div class="kpi">{{ modelKpi.total }}</div>
          <div class="meta">{{ t('devMl.kpi.modelsMeta') }}</div>
        </template>
      </div>
      <div class="card">
        <h3>{{ t('devMl.kpi.inference') }}</h3>
        <div v-if="svcLoading" class="kpi-skeleton">{{ t('devMl.kpi.loading') }}</div>
        <div v-else-if="svcError" class="kpi-error">
          {{ t('devMl.kpi.loadFailed') }}
          <button class="retry-btn" @click="loadServices">{{ t('devMl.kpi.retry') }}</button>
        </div>
        <template v-else>
          <div class="kpi">{{ svcKpi.total }}</div>
          <div class="meta">{{ t('devMl.kpi.inferenceMeta', { count: svcKpi.running }) }}</div>
        </template>
      </div>
    </div>
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <el-tabs v-model="activeTab" type="card">
        <el-tab-pane :label="t('devMl.tabs.train')" name="train">
          <TrainPanel
            :jobs="trainJobs"
            :total="trainTotal"
            :page="trainPage"
            :size="trainSize"
            :status-filter="trainStatusFilter"
            :loading="trainLoading"
            :error="trainError"
            :status-label="trainStatusLabel"
            :status-type="trainStatusType"
            :kpi="trainKpi"
            @load="loadTrain"
            @filter="onTrainFilter"
            @open-train="openTrainForm"
            @open-register="openRegisterForm"
            @open-log="openLogDialog"
            @stop="handleStopTrain"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('devMl.tabs.model')" name="model">
          <ModelPanel
            :models="models"
            :loading="modelsLoading"
            :error="modelsError"
            :status-label="modelStatusLabel"
            :status-type="modelStatusType"
            @load="loadModels"
            @search="onModelSearch"
            @delete="handleDeleteModel"
            @deploy="openDeployForm"
            @versions="openVersions"
          />
        </el-tab-pane>
        <el-tab-pane :label="t('devMl.tabs.inference')" name="inference">
          <SvcPanel
            :services="services"
            :loading="svcLoading"
            :error="svcError"
            :status-filter="svcStatusFilter"
            :status-label="svcStatusLabel"
            :status-type="svcStatusType"
            :can-stop-svc="canStopSvc"
            :can-scale="canScale"
            @load="loadServices"
            @filter="onSvcFilter"
            @scale="openScaleForm"
            @stop="handleStopSvc"
          />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 弹窗：提交训练 -->
    <el-dialog
      v-model="trainDialogVisible"
      :title="t('devMl.trainForm.title')"
      width="640px"
      :close-on-click-modal="false"
      @closed="resetTrainForm"
    >
      <el-form
        ref="trainFormRef"
        :model="trainForm"
        :rules="trainRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item :label="t('devMl.trainForm.fields.name')" prop="name">
          <el-input v-model="trainForm.name" :placeholder="t('devMl.trainForm.fields.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('devMl.trainForm.fields.algorithm')" prop="algorithm">
          <el-select v-model="trainForm.algorithm" style="width: 100%">
            <el-option :label="t('devMl.trainForm.algorithms.xgboost')" value="xgboost" />
            <el-option :label="t('devMl.trainForm.algorithms.lightgbm')" value="lightgbm" />
            <el-option :label="t('devMl.trainForm.algorithms.tensorflow')" value="tensorflow" />
            <el-option :label="t('devMl.trainForm.algorithms.pytorch')" value="pytorch" />
            <el-option :label="t('devMl.trainForm.algorithms.sklearn')" value="sklearn" />
            <el-option :label="t('devMl.trainForm.algorithms.sparkml')" value="sparkml" />
            <el-option :label="t('devMl.trainForm.algorithms.huggingface')" value="huggingface" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('devMl.trainForm.fields.dataset')" prop="dataset">
          <el-input v-model="trainForm.dataset" :placeholder="t('devMl.trainForm.fields.datasetPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('devMl.trainForm.fields.epochs')" prop="epochs">
          <el-input-number v-model="trainForm.epochs" :min="1" :max="1000" />
        </el-form-item>
        <el-form-item :label="t('devMl.trainForm.fields.resourceSpec')" prop="resourceSpec">
          <el-input v-model="trainForm.resourceSpec" :placeholder="t('devMl.trainForm.fields.resourceSpecPlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('devMl.trainForm.fields.hyperparams')" prop="hyperparams">
          <el-input
            v-model="trainForm.hyperparams"
            type="textarea"
            :rows="4"
            :placeholder="t('devMl.trainForm.fields.hyperparamsPlaceholder')"
            style="font-family: monospace; font-size: 12px"
          />
        </el-form-item>
        <el-form-item :label="t('devMl.trainForm.fields.owner')" prop="owner">
          <el-input v-model="trainForm.owner" :placeholder="t('devMl.trainForm.fields.ownerPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="trainDialogVisible = false">{{ t('devMl.trainForm.actions.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitTrain">{{ t('devMl.trainForm.actions.submit') }}</el-button>
      </template>
    </el-dialog>

    <!-- 弹窗：注册模型 -->
    <el-dialog
      v-model="registerDialogVisible"
      :title="t('devMl.registerForm.title', { name: currentTrainJob?.name || '' })"
      width="540px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        label-width="100px"
        label-position="right"
      >
        <el-form-item :label="t('devMl.registerForm.fields.name')" prop="name">
          <el-input v-model="registerForm.name" />
        </el-form-item>
        <el-form-item :label="t('devMl.registerForm.fields.version')" prop="version">
          <el-input v-model="registerForm.version" />
        </el-form-item>
        <el-form-item :label="t('devMl.registerForm.fields.modelPath')" prop="modelPath">
          <el-input
            v-model="registerForm.modelPath"
            :placeholder="t('devMl.registerForm.fields.modelPathPlaceholder')"
            style="font-family: monospace; font-size: 12px"
          />
        </el-form-item>
        <el-form-item :label="t('devMl.registerForm.fields.description')" prop="description">
          <el-input v-model="registerForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialogVisible = false">{{ t('devMl.registerForm.actions.cancel') }}</el-button>
        <el-button type="primary" :loading="registering" @click="handleRegister">{{ t('devMl.registerForm.actions.submit') }}</el-button>
      </template>
    </el-dialog>

    <!-- 弹窗：部署推理 -->
    <el-dialog
      v-model="deployDialogVisible"
      :title="t('devMl.deployForm.title', { name: currentModel?.name || '' })"
      width="540px"
      :close-on-click-modal="false"
    >
      <el-form
        ref="deployFormRef"
        :model="deployForm"
        :rules="deployRules"
        label-width="100px"
        label-position="right"
      >
        <el-form-item :label="t('devMl.deployForm.fields.serviceName')" prop="serviceName">
          <el-input v-model="deployForm.serviceName" />
        </el-form-item>
        <el-form-item :label="t('devMl.deployForm.fields.version')" prop="version">
          <el-input
            v-model="deployForm.version"
            :placeholder="t('devMl.deployForm.fields.versionPlaceholder', { ver: currentModel?.latestVersion || 'v1' })"
          />
        </el-form-item>
        <el-form-item :label="t('devMl.deployForm.fields.replicas')" prop="replicas">
          <el-input-number v-model="deployForm.replicas" :min="1" :max="20" />
        </el-form-item>
        <el-form-item :label="t('devMl.deployForm.fields.resourceSpec')" prop="resourceSpec">
          <el-input v-model="deployForm.resourceSpec" :placeholder="t('devMl.deployForm.fields.resourceSpecPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deployDialogVisible = false">{{ t('devMl.deployForm.actions.cancel') }}</el-button>
        <el-button type="primary" :loading="deploying" @click="handleDeploy">{{ t('devMl.deployForm.actions.submit') }}</el-button>
      </template>
    </el-dialog>

    <!-- 弹窗：扩缩容 -->
    <el-dialog
      v-model="scaleDialogVisible"
      :title="t('devMl.scaleForm.title', { name: currentSvc?.serviceName || '' })"
      width="360px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px" label-position="right">
        <el-form-item :label="t('devMl.scaleForm.fields.currentReplicas')">{{ currentSvc?.replicas ?? 0 }}</el-form-item>
        <el-form-item :label="t('devMl.scaleForm.fields.targetReplicas')">
          <el-input-number v-model="scaleTarget" :min="0" :max="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scaleDialogVisible = false">{{ t('devMl.scaleForm.actions.cancel') }}</el-button>
        <el-button type="primary" :loading="scaling" @click="handleScale">{{ t('devMl.scaleForm.actions.apply') }}</el-button>
      </template>
    </el-dialog>

    <!-- 日志弹窗 -->
    <el-dialog
      v-model="logDialogVisible"
      :title="t('devMl.logDialog.title', { name: currentLogJob?.name || '' })"
      width="800px"
      @opened="scrollLogToBottom"
    >
      <div v-loading="logLoading" class="log-container">
        <pre class="log-content">{{ logContent || t('devMl.logDialog.empty') }}</pre>
      </div>
      <template #footer>
        <el-button @click="logDialogVisible = false">{{ t('devMl.logDialog.close') }}</el-button>
        <el-button type="primary" @click="refreshLog">{{ t('devMl.logDialog.refresh') }}</el-button>
      </template>
    </el-dialog>

    <!-- 版本抽屉 -->
    <el-drawer
      v-model="versionDrawerVisible"
      :title="t('devMl.versionDrawer.title', { name: currentModel?.name || '' })"
      size="50%"
    >
      <el-table
        v-loading="versionsLoading"
        :data="versions"
        stripe
        border
        size="small"
        :empty-text="t('devMl.versionDrawer.empty')"
      >
        <el-table-column prop="version" :label="t('devMl.versionDrawer.columns.version')" width="120" />
        <el-table-column :label="t('devMl.versionDrawer.columns.status')" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="light">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('devMl.versionDrawer.columns.metrics')" min-width="200">
          <template #default="{ row }">
            {{ row.metrics ? formatMetrics(row.metrics) : t('devMl.versionDrawer.noStatus') }}
          </template>
        </el-table-column>
        <el-table-column prop="registeredAt" :label="t('devMl.versionDrawer.columns.registeredAt')" width="180">
          <template #default="{ row }">{{ row.registeredAt || t('devMl.versionDrawer.noStatus') }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import * as devMlApi from '@/api/dev-ml'
import type { TrainJob, MlModel, InferenceService, ModelVersion } from '@/api/dev-ml'
import TrainPanel from './components/TrainPanel.vue'
import ModelPanel from './components/ModelPanel.vue'
import SvcPanel from './components/SvcPanel.vue'

const { t, te } = useI18n()
const appStore = useAppStore()
const activeTab = ref('train')

const TRAIN_MAP: Record<string, { l: string; t: string }> = {
  PENDING: { l: '等待中', t: 'info' },
  RUNNING: { l: '运行中', t: 'primary' },
  SUCCEEDED: { l: '成功', t: 'success' },
  FAILED: { l: '失败', t: 'danger' },
  KILLED: { l: '已取消', t: 'info' },
  SCHEDULED: { l: '已调度', t: 'warning' }
}
const MODEL_MAP: Record<string, { l: string; t: string }> = {
  DRAFT: { l: '草稿', t: 'info' },
  REGISTERED: { l: '已注册', t: 'primary' },
  DEPLOYED: { l: '已部署', t: 'success' },
  ARCHIVED: { l: '已归档', t: 'info' },
  FAILED: { l: '失败', t: 'danger' }
}
const SVC_MAP: Record<string, { l: string; t: string }> = {
  DEPLOYING: { l: '部署中', t: 'warning' },
  RUNNING: { l: '运行中', t: 'success' },
  STOPPED: { l: '已停止', t: 'info' },
  FAILED: { l: '失败', t: 'danger' },
  SCALING: { l: '扩缩容', t: 'primary' }
}
const trainStatusLabel = (s: string) => t(`devMl.status.train.${s}`, TRAIN_MAP[s]?.l ?? s)
const trainStatusType = (s: string) => TRAIN_MAP[s]?.t ?? 'info'
const modelStatusLabel = (s?: string) => {
  const key = `devMl.status.model.${s ?? ''}`
  return te(key) ? t(key) : s ?? t('devMl.versionDrawer.noStatus')
}
const modelStatusType = (s?: string) => MODEL_MAP[s ?? '']?.t ?? 'info'
const svcStatusLabel = (s: string) => t(`devMl.status.svc.${s}`, SVC_MAP[s]?.l ?? s)
const svcStatusType = (s: string) => SVC_MAP[s]?.t ?? 'info'
const formatMetrics = (m: Record<string, number>) =>
  Object.entries(m)
    .map(([k, v]) => `${k}=${typeof v === 'number' ? v.toFixed(4) : v}`)
    .join(' · ')

// ── 训练实验 ──────────────────────────────────────────
const trainJobs = ref<TrainJob[]>([]),
  trainTotal = ref(0)
const trainPage = ref(1),
  trainSize = ref(20),
  trainStatusFilter = ref('')
const trainLoading = ref(false),
  trainError = ref(false)
async function loadTrain() {
  trainLoading.value = true
  trainError.value = false
  try {
    const r = await devMlApi.listTrainJobs({
      workspaceId: appStore.workspace,
      status: trainStatusFilter.value || undefined,
      page: trainPage.value,
      size: trainSize.value
    })
    trainJobs.value = r.list
    trainTotal.value = r.total
  } catch {
    trainError.value = true
  } finally {
    trainLoading.value = false
  }
}
function onTrainFilter(p: number, s: number, f: string) {
  trainPage.value = p
  trainSize.value = s
  trainStatusFilter.value = f
  loadTrain()
}
const trainKpi = computed(() => ({
  total: trainJobs.value.length,
  running: trainJobs.value.filter((j) => j.status === 'RUNNING').length
}))

// ── 模型仓库 ──────────────────────────────────────────
const models = ref<MlModel[]>([]),
  modelsLoading = ref(false),
  modelsError = ref(false),
  modelKeyword = ref('')
async function loadModels() {
  modelsLoading.value = true
  modelsError.value = false
  try {
    models.value = await devMlApi.listModels({ keyword: modelKeyword.value || undefined })
  } catch {
    modelsError.value = true
    models.value = []
  } finally {
    modelsLoading.value = false
  }
}
function onModelSearch(k: string) {
  modelKeyword.value = k
  loadModels()
}
const modelKpi = computed(() => ({ total: models.value.length }))

// ── 推理服务 ──────────────────────────────────────────
const services = ref<InferenceService[]>([]),
  svcLoading = ref(false),
  svcError = ref(false),
  svcStatusFilter = ref('')
async function loadServices() {
  svcLoading.value = true
  svcError.value = false
  try {
    services.value = await devMlApi.listInferenceServices({
      status: svcStatusFilter.value || undefined
    })
  } catch {
    svcError.value = true
    services.value = []
  } finally {
    svcLoading.value = false
  }
}
function onSvcFilter(s: string) {
  svcStatusFilter.value = s
  loadServices()
}
const svcKpi = computed(() => ({
  total: services.value.length,
  running: services.value.filter((s) => s.status === 'RUNNING').length
}))

// ── 提交训练 ──────────────────────────────────────────
const trainDialogVisible = ref(false),
  submitting = ref(false),
  trainFormRef = ref<FormInstance>()
const trainForm = reactive({
  name: '',
  algorithm: 'xgboost',
  dataset: '',
  epochs: 10,
  resourceSpec: '4c/16g × 2',
  hyperparams: '',
  owner: ''
})
const trainRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('devMl.rules.trainName'), trigger: 'blur' }],
  algorithm: [{ required: true, message: t('devMl.rules.algorithm'), trigger: 'change' }],
  dataset: [{ required: true, message: t('devMl.rules.dataset'), trigger: 'blur' }]
}))
function resetTrainForm() {
  Object.assign(trainForm, {
    name: '',
    algorithm: 'xgboost',
    dataset: '',
    epochs: 10,
    resourceSpec: '4c/16g × 2',
    hyperparams: '',
    owner: ''
  })
  trainFormRef.value?.clearValidate()
}
function openTrainForm() {
  resetTrainForm()
  trainDialogVisible.value = true
}
async function handleSubmitTrain() {
  if (!trainFormRef.value) return
  await trainFormRef.value.validate(async (v) => {
    if (!v) return
    submitting.value = true
    try {
      await devMlApi.createTrainJob({ ...trainForm, workspaceId: appStore.workspace })
      ElMessage.success(t('devMl.messages.trainSubmitted'))
      trainDialogVisible.value = false
      await loadTrain()
    } catch {
    } finally {
      submitting.value = false
    }
  })
}

// ── 注册模型 ──────────────────────────────────────────
const registerDialogVisible = ref(false),
  registering = ref(false),
  registerFormRef = ref<FormInstance>()
const registerForm = reactive({ name: '', version: 'v1.0.0', modelPath: '', description: '' })
const registerRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('devMl.rules.modelName'), trigger: 'blur' }],
  version: [{ required: true, message: t('devMl.rules.modelVersion'), trigger: 'blur' }]
}))
const currentTrainJob = ref<TrainJob | null>(null)
function openRegisterForm(row: TrainJob) {
  currentTrainJob.value = row
  Object.assign(registerForm, {
    name: row.name + '-model',
    version: 'v1.0.0',
    modelPath: '',
    description: ''
  })
  registerFormRef.value?.clearValidate()
  registerDialogVisible.value = true
}
async function handleRegister() {
  if (!registerFormRef.value || !currentTrainJob.value) return
  await registerFormRef.value.validate(async (v) => {
    if (!v) return
    registering.value = true
    try {
      await devMlApi.registerModel({
        ...registerForm,
        algorithm: currentTrainJob.value!.algorithm,
        trainJobId: currentTrainJob.value!.id,
        metrics: currentTrainJob.value!.metrics
      })
      ElMessage.success(t('devMl.messages.modelRegistered'))
      registerDialogVisible.value = false
      await loadModels()
    } catch {
    } finally {
      registering.value = false
    }
  })
}

// ── 部署推理 ──────────────────────────────────────────
const deployDialogVisible = ref(false),
  deploying = ref(false),
  deployFormRef = ref<FormInstance>()
const deployForm = reactive({ serviceName: '', version: '', replicas: 1, resourceSpec: '2c/4g' })
const deployRules = computed<FormRules>(() => ({
  version: [{ required: true, message: t('devMl.rules.deployVersion'), trigger: 'blur' }]
}))
const currentModel = ref<MlModel | null>(null)
function openDeployForm(row: MlModel) {
  currentModel.value = row
  Object.assign(deployForm, {
    serviceName: row.name + '-svc',
    version: row.latestVersion,
    replicas: 1,
    resourceSpec: '2c/4g'
  })
  deployFormRef.value?.clearValidate()
  deployDialogVisible.value = true
}
async function handleDeploy() {
  if (!deployFormRef.value || !currentModel.value) return
  await deployFormRef.value.validate(async (v) => {
    if (!v) return
    deploying.value = true
    try {
      await devMlApi.deployInference({ ...deployForm, modelName: currentModel.value!.name })
      ElMessage.success(t('devMl.messages.modelDeployed'))
      deployDialogVisible.value = false
      await loadServices()
    } catch {
    } finally {
      deploying.value = false
    }
  })
}

// ── 扩缩容 ────────────────────────────────────────────
const scaleDialogVisible = ref(false),
  scaling = ref(false),
  currentSvc = ref<InferenceService | null>(null),
  scaleTarget = ref(1)
function openScaleForm(row: InferenceService) {
  currentSvc.value = row
  scaleTarget.value = row.replicas ?? 1
  scaleDialogVisible.value = true
}
async function handleScale() {
  if (!currentSvc.value) return
  scaling.value = true
  try {
    await devMlApi.scaleInference(currentSvc.value.id, { replicas: scaleTarget.value })
    ElMessage.success(t('devMl.messages.modelScaled', { count: scaleTarget.value }))
    scaleDialogVisible.value = false
    await loadServices()
  } catch {
  } finally {
    scaling.value = false
  }
}

// ── 日志 ──────────────────────────────────────────────
const logDialogVisible = ref(false),
  logLoading = ref(false),
  logContent = ref(''),
  currentLogJob = ref<TrainJob | null>(null)
async function openLogDialog(row: TrainJob) {
  currentLogJob.value = row
  logDialogVisible.value = true
  await refreshLog()
}
async function refreshLog() {
  if (!currentLogJob.value) return
  logLoading.value = true
  try {
    logContent.value = (await devMlApi.getTrainJobLogs(currentLogJob.value.id)) || t('devMl.logDialog.empty')
    scrollLogToBottom()
  } catch {
    logContent.value = t('devMl.messages.logLoadFailed')
  } finally {
    logLoading.value = false
  }
}
function scrollLogToBottom() {
  const c = document.querySelector('.log-content') as HTMLElement
  if (c) c.scrollTop = c.scrollHeight
}

// ── 停止训练 ──────────────────────────────────────────
const stoppingId = ref('')
function canStop(s: string) {
  return ['PENDING', 'RUNNING', 'SCHEDULED'].includes(s)
}
async function handleStopTrain(row: TrainJob) {
  stoppingId.value = row.id
  try {
    await ElMessageBox.confirm(t('devMl.messages.stopTrainConfirm', { name: row.name }), t('devMl.messages.stopTrainConfirmTitle'), { type: 'warning' })
    await devMlApi.stopTrainJob(row.id)
    ElMessage.success(t('devMl.messages.trainStopped'))
    await loadTrain()
  } catch {
  } finally {
    stoppingId.value = ''
  }
}

// ── 停止/扩缩容推理 ───────────────────────────────────
const stoppingSvcId = ref('')
function canStopSvc(s: string) {
  return ['DEPLOYING', 'RUNNING', 'SCALING', 'FAILED'].includes(s)
}
function canScale(s: string) {
  return ['RUNNING', 'SCALING'].includes(s)
}
async function handleStopSvc(row: InferenceService) {
  stoppingSvcId.value = row.id
  try {
    await ElMessageBox.confirm(t('devMl.messages.stopSvcConfirm', { name: row.serviceName }), t('devMl.messages.stopSvcConfirmTitle'), {
      type: 'warning',
      confirmButtonClass: 'el-button--danger'
    })
    await devMlApi.stopInference(row.id)
    ElMessage.success(t('devMl.messages.svcStopped'))
    await loadServices()
  } catch {
  } finally {
    stoppingSvcId.value = ''
  }
}

// ── 删除模型 ──────────────────────────────────────────
async function handleDeleteModel(row: MlModel) {
  try {
    await ElMessageBox.confirm(t('devMl.messages.deleteConfirm', { name: row.name }), t('devMl.messages.deleteConfirmTitle'), {
      type: 'warning',
      confirmButtonClass: 'el-button--danger'
    })
    await devMlApi.deleteModel(row.id)
    ElMessage.success(t('devMl.messages.modelDeleted'))
    await loadModels()
  } catch {}
}

// ── 模型版本 ──────────────────────────────────────────
const versionDrawerVisible = ref(false),
  versionsLoading = ref(false),
  versions = ref<ModelVersion[]>([])
function openVersions(row: MlModel) {
  currentModel.value = row
  versionDrawerVisible.value = true
  versionsLoading.value = true
  devMlApi
    .listModelVersions(row.name)
    .then((v) => {
      versions.value = v
    })
    .catch(() => {
      versions.value = []
    })
    .finally(() => {
      versionsLoading.value = false
    })
}

// ── 生命周期 ──────────────────────────────────────────
let timer: ReturnType<typeof setInterval> | null = null
onMounted(() => {
  void loadTrain()
  void loadModels()
  void loadServices()
  timer = setInterval(() => {
    void loadTrain()
    void loadServices()
  }, 15000)
})
onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})

watch(
  () => appStore.workspace,
  () => {
    trainPage.value = 1
    void loadTrain()
  }
)
</script>

<style scoped>
.dev-ml-page {
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
  font-size: 28px;
  font-weight: 700;
  color: var(--ds-text-primary);
  line-height: 1.2;
}
.kpi.running {
  color: var(--ds-color-success-600);
}
.meta {
  font-size: 12px;
  color: var(--ds-text-secondary);
  margin-top: 6px;
}
.kpi-skeleton {
  font-size: 18px;
  font-weight: 600;
  color: var(--ds-text-muted, var(--ds-text-secondary));
  line-height: 1.2;
  padding: 4px 0;
  animation: kpi-pulse 1.4s ease-in-out infinite;
}
.kpi-error {
  font-size: 14px;
  font-weight: 500;
  color: #f56c6c;
  line-height: 1.4;
  padding: 4px 0;
}
.retry-btn {
  margin-left: 6px;
  padding: 2px 10px;
  font-size: 12px;
  color: #409eff;
  background: transparent;
  border: 1px solid #409eff;
  border-radius: 4px;
  cursor: pointer;
  transition: background 0.2s;
}
.retry-btn:hover {
  background: #ecf5ff;
}
@keyframes kpi-pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}
.page-card {
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
}
.log-container {
  background: #1a2027;
  border-radius: 8px;
  padding: 12px;
  min-height: 320px;
  max-height: 480px;
  overflow: auto;
}
.log-content {
  color: #cbd5e1;
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
}
</style>
