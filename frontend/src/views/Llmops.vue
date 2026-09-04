<template>
  <div class="llmops-page">
    <h1>{{ t('llmops.title') }}</h1>
    <div class="sub">{{ t('llmops.subtitle') }}</div>

    <!-- KPI 卡片区 -->
    <div class="grid g4">
      <template v-if="modelLoading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>{{ t('common.loading') }}</h3>
          <div class="kpi">--</div>
          <div class="meta">{{ t('llmops.loadingMeta') }}</div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>{{ t('llmops.kpi.models') }}</h3>
          <div class="kpi">{{ modelKpi.total }}</div>
          <div class="meta">{{ t('llmops.kpi.modelsMeta') }}</div>
        </div>
        <div class="card">
          <h3>{{ t('llmops.kpi.finetune') }}</h3>
          <div class="kpi">{{ finetuneKpi.total }}</div>
          <div class="meta">{{ t('llmops.kpi.runningMeta', { count: finetuneKpi.running }) }}</div>
        </div>
        <div class="card">
          <h3>{{ t('llmops.kpi.eval') }}</h3>
          <div class="kpi s">{{ evalKpi.total }}</div>
          <div class="meta">{{ t('llmops.kpi.evalMeta', { count: evalKpi.human }) }}</div>
        </div>
        <div class="card">
          <h3>{{ t('llmops.kpi.svc') }}</h3>
          <div class="kpi">{{ svcKpi.total }}</div>
          <div class="meta">{{ t('llmops.kpi.runningMeta', { count: svcKpi.running }) }}</div>
        </div>
      </template>
    </div>

    <!-- Tabs 主区 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <el-tabs v-model="activeTab" type="card">
        <!-- Tab1 模型管理 -->
        <el-tab-pane :label="t('llmops.tabs.models')" name="models">
          <div class="toolbar">
            <el-button type="primary" @click="openRegisterDialog">
              {{ t('llmops.registerModel') }}
            </el-button>
            <el-input
              v-model="modelKeyword"
              :placeholder="t('llmops.searchPlaceholder')"
              clearable
              style="width: 220px"
              @keyup.enter="loadModels"
              @clear="loadModels"
            />
            <el-button @click="loadModels">{{ t('llmops.search') }}</el-button>
            <div class="spacer"></div>
            <el-button :icon="Refresh" circle @click="loadModels" />
          </div>

          <el-table
            v-loading="modelLoading"
            :data="filteredModels"
            stripe
            border
            style="width: 100%"
            :empty-text="modelError ? t('llmops.emptyError') : t('llmops.modelsEmpty')"
          >
            <el-table-column prop="name" :label="t('llmops.modelCols.name')" min-width="160" />
            <el-table-column prop="algorithm" :label="t('llmops.modelCols.algorithm')" width="120">
              <template #default="{ row }">
                <el-tag effect="light" size="small">{{ row.algorithm }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="version" :label="t('llmops.modelCols.version')" width="100" />
            <el-table-column :label="t('llmops.modelCols.status')" width="110">
              <template #default="{ row }">
                <el-tag :type="modelStatusType(row.status)" effect="light">
                  {{ modelStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="trainJobId" :label="t('llmops.modelCols.trainJob')" width="140">
              <template #default="{ row }">{{ row.trainJobId || '--' }}</template>
            </el-table-column>
            <el-table-column
              prop="description"
              :label="t('llmops.modelCols.description')"
              min-width="160"
            >
              <template #default="{ row }">{{ row.description || '--' }}</template>
            </el-table-column>
            <el-table-column
              prop="registeredAt"
              :label="t('llmops.modelCols.registeredAt')"
              width="180"
            >
              <template #default="{ row }">{{ formatTime(row.registeredAt) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab2 微调 -->
        <el-tab-pane :label="t('llmops.tabs.finetune')" name="finetune">
          <div class="toolbar">
            <el-button type="primary" @click="openFinetuneDialog">
              {{ t('llmops.submitFinetune') }}
            </el-button>
            <div class="spacer"></div>
            <el-button :icon="Refresh" circle @click="loadFinetuneTasks" />
          </div>

          <el-table
            v-loading="finetuneLoading"
            :data="finetuneTasks"
            stripe
            border
            style="width: 100%"
            :empty-text="finetuneError ? t('llmops.emptyError') : t('llmops.ftEmpty')"
          >
            <el-table-column prop="taskId" :label="t('llmops.ftCols.taskId')" width="200" />
            <el-table-column
              prop="modelName"
              :label="t('llmops.ftCols.modelName')"
              min-width="140"
            />
            <el-table-column prop="baseModel" :label="t('llmops.ftCols.baseModel')" width="140" />
            <el-table-column prop="epochs" label="epochs" width="90" />
            <el-table-column :label="t('llmops.ftCols.status')" width="110">
              <template #default="{ row }">
                <el-tag :type="finetuneStatusType(row.status)" effect="light">
                  {{ finetuneStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('llmops.ftCols.progress')" min-width="220">
              <template #default="{ row }">
                <el-progress
                  :percentage="row.progress ?? 0"
                  :status="progressStatus(row.status)"
                  :stroke-width="14"
                  :text-inside="true"
                />
              </template>
            </el-table-column>
            <el-table-column prop="submittedAt" :label="t('llmops.ftCols.submittedAt')" width="180">
              <template #default="{ row }">{{ formatTime(row.submittedAt) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab3 评估 -->
        <el-tab-pane :label="t('llmops.tabs.eval')" name="eval">
          <div class="toolbar">
            <el-button type="primary" @click="openEvalDialog">
              {{ t('llmops.createMetric') }}
            </el-button>
            <el-button type="warning" plain @click="openHumanEvalDialog">
              {{ t('llmops.humanEval') }}
            </el-button>
            <div class="spacer"></div>
            <el-button :icon="Refresh" circle @click="loadEvalMetrics" />
          </div>

          <el-table
            v-loading="evalLoading"
            :data="evalMetrics"
            stripe
            border
            style="width: 100%"
            :empty-text="evalError ? t('llmops.emptyError') : t('llmops.evalEmpty')"
          >
            <el-table-column
              prop="modelName"
              :label="t('llmops.evalCols.modelName')"
              min-width="160"
            />
            <el-table-column prop="modelVersion" :label="t('llmops.evalCols.version')" width="100">
              <template #default="{ row }">{{ row.modelVersion || '--' }}</template>
            </el-table-column>
            <el-table-column :label="t('llmops.evalCols.evalType')" width="110">
              <template #default="{ row }">
                <el-tag
                  :type="row.evalType === 'human' ? 'warning' : 'primary'"
                  effect="light"
                  size="small"
                >
                  {{
                    row.evalType === 'human'
                      ? t('llmops.evalTypes.human')
                      : t('llmops.evalTypes.auto')
                  }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('llmops.evalCols.accuracy')" width="120">
              <template #default="{ row }">
                {{ row.accuracy != null ? (row.accuracy * 100).toFixed(2) + '%' : '--' }}
              </template>
            </el-table-column>
            <el-table-column :label="t('llmops.evalCols.hallucination')" width="120">
              <template #default="{ row }">
                {{
                  row.hallucinationRate != null
                    ? (row.hallucinationRate * 100).toFixed(2) + '%'
                    : '--'
                }}
              </template>
            </el-table-column>
            <el-table-column :label="t('llmops.evalCols.baseLift')" width="120">
              <template #default="{ row }">
                <span v-if="row.baseLiftPt != null" style="color: var(--ds-color-success-600)">
                  +{{ row.baseLiftPt.toFixed(2) }}pt
                </span>
                <span v-else style="color: var(--muted)">--</span>
              </template>
            </el-table-column>
            <el-table-column prop="dataset" :label="t('llmops.evalCols.dataset')" min-width="140">
              <template #default="{ row }">{{ row.dataset || '--' }}</template>
            </el-table-column>
            <el-table-column prop="createdAt" :label="t('llmops.evalCols.createdAt')" width="180">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab4 推理服务 -->
        <el-tab-pane :label="t('llmops.tabs.inference')" name="inference">
          <div class="toolbar">
            <el-select
              v-model="svcStatusFilter"
              :placeholder="t('llmops.svcStatusFilter')"
              clearable
              style="width: 140px"
              @change="loadServices"
            >
              <el-option :label="t('llmops.svcStatus.DEPLOYING')" value="DEPLOYING" />
              <el-option :label="t('llmops.svcStatus.RUNNING')" value="RUNNING" />
              <el-option :label="t('llmops.svcStatus.STOPPED')" value="STOPPED" />
              <el-option :label="t('llmops.svcStatus.FAILED')" value="FAILED" />
              <el-option :label="t('llmops.svcStatus.SCALING')" value="SCALING" />
            </el-select>
            <div class="spacer"></div>
            <el-button :icon="Refresh" circle @click="loadServices" />
          </div>

          <el-table
            v-loading="svcLoading"
            :data="services"
            stripe
            border
            style="width: 100%"
            :empty-text="svcError ? t('llmops.emptyError') : t('llmops.svcEmpty')"
          >
            <el-table-column
              prop="serviceName"
              :label="t('llmops.svcCols.service')"
              min-width="160"
            />
            <el-table-column prop="modelName" :label="t('llmops.svcCols.model')" width="140" />
            <el-table-column prop="modelVersion" :label="t('llmops.svcCols.version')" width="100" />
            <el-table-column :label="t('llmops.svcCols.status')" width="110">
              <template #default="{ row }">
                <el-tag :type="svcStatusType(row.status)" effect="light">
                  {{ svcStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('llmops.svcCols.replicas')" width="100">
              <template #default="{ row }">
                {{ row.replicas ?? 0 }} / {{ row.desiredReplicas ?? 0 }}
              </template>
            </el-table-column>
            <el-table-column label="QPS" width="100">
              <template #default="{ row }">{{ row.qps?.toFixed(2) ?? '--' }}</template>
            </el-table-column>
            <el-table-column :label="t('llmops.svcCols.latency')" width="110">
              <template #default="{ row }">{{ row.latencyMs?.toFixed(1) ?? '--' }}</template>
            </el-table-column>
            <el-table-column prop="endpoint" :label="t('llmops.svcCols.endpoint')" min-width="200">
              <template #default="{ row }">{{ row.endpoint || '--' }}</template>
            </el-table-column>
            <el-table-column prop="deployedAt" :label="t('llmops.svcCols.deployedAt')" width="180">
              <template #default="{ row }">{{ formatTime(row.deployedAt) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 注册模型弹窗 -->
    <el-dialog
      v-model="registerDialogVisible"
      :title="t('llmops.registerModal.title')"
      width="520px"
    >
      <el-form
        ref="registerFormRef"
        :model="registerForm"
        :rules="registerRules"
        label-width="100px"
      >
        <el-form-item :label="t('llmops.registerModal.name')" prop="name">
          <el-input
            v-model="registerForm.name"
            :placeholder="t('llmops.registerModal.namePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('llmops.registerModal.algorithm')" prop="algorithm">
          <el-select v-model="registerForm.algorithm" style="width: 100%">
            <el-option label="HuggingFace" value="huggingface" />
            <el-option label="PyTorch" value="pytorch" />
            <el-option label="TensorFlow" value="tensorflow" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('llmops.registerModal.version')" prop="version">
          <el-input
            v-model="registerForm.version"
            :placeholder="t('llmops.registerModal.versionPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('llmops.registerModal.trainJobId')">
          <el-input
            v-model="registerForm.trainJobId"
            :placeholder="t('llmops.registerModal.optional')"
          />
        </el-form-item>
        <el-form-item :label="t('llmops.registerModal.modelPath')">
          <el-input
            v-model="registerForm.modelPath"
            :placeholder="t('llmops.registerModal.optional')"
          />
        </el-form-item>
        <el-form-item :label="t('llmops.registerModal.description')">
          <el-input v-model="registerForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="registering" @click="handleRegister">
          {{ t('llmops.registerModal.register') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 提交微调弹窗 -->
    <el-dialog v-model="finetuneDialogVisible" :title="t('llmops.ftModal.title')" width="520px">
      <el-form
        ref="finetuneFormRef"
        :model="finetuneForm"
        :rules="finetuneRules"
        label-width="100px"
      >
        <el-form-item :label="t('llmops.ftModal.modelName')" prop="modelName">
          <el-input
            v-model="finetuneForm.modelName"
            :placeholder="t('llmops.ftModal.modelNamePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('llmops.ftModal.baseModel')" prop="baseModel">
          <el-select v-model="finetuneForm.baseModel" style="width: 100%" filterable allow-create>
            <el-option label="qiong-7B" value="qiong-7B" />
            <el-option label="qiong-13B" value="qiong-13B" />
            <el-option label="qwen-7B" value="qwen-7B" />
            <el-option label="llama3-8B" value="llama3-8B" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('llmops.ftModal.trainingData')" prop="trainingData">
          <el-input
            v-model="finetuneForm.trainingData"
            :placeholder="t('llmops.ftModal.trainingDataPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('llmops.ftModal.gpu')">
          <el-select v-model="finetuneForm.gpuConfig" style="width: 100%">
            <el-option label="1×GPU" value="1×GPU" />
            <el-option label="2×GPU" value="2×GPU" />
            <el-option label="4×GPU" value="4×GPU" />
            <el-option label="8×GPU" value="8×GPU" />
          </el-select>
        </el-form-item>
        <el-form-item label="epochs" prop="epochs">
          <el-input-number v-model="finetuneForm.epochs" :min="1" :max="100" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="finetuneDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleFinetune">
          {{ t('llmops.ftModal.submit') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 创建评估指标弹窗 -->
    <el-dialog v-model="evalDialogVisible" :title="t('llmops.evalModal.title')" width="520px">
      <el-form ref="evalFormRef" :model="evalForm" :rules="evalRules" label-width="100px">
        <el-form-item :label="t('llmops.evalModal.modelName')" prop="modelName">
          <el-input
            v-model="evalForm.modelName"
            :placeholder="t('llmops.evalModal.modelNamePlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('llmops.evalModal.version')">
          <el-input v-model="evalForm.modelVersion" :placeholder="t('llmops.evalModal.optional')" />
        </el-form-item>
        <el-form-item :label="t('llmops.evalModal.accuracy')">
          <el-input-number
            v-model="evalForm.accuracy"
            :min="0"
            :max="1"
            :step="0.01"
            :precision="4"
          />
        </el-form-item>
        <el-form-item :label="t('llmops.evalModal.hallucination')">
          <el-input-number
            v-model="evalForm.hallucinationRate"
            :min="0"
            :max="1"
            :step="0.01"
            :precision="4"
          />
        </el-form-item>
        <el-form-item :label="t('llmops.evalModal.baseLift')">
          <el-input-number v-model="evalForm.baseLiftPt" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item :label="t('llmops.evalModal.dataset')">
          <el-input
            v-model="evalForm.dataset"
            :placeholder="t('llmops.evalModal.datasetPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="evalDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="evalCreating" @click="handleCreateEval">
          {{ t('llmops.evalModal.create') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 人工评估弹窗 -->
    <el-dialog v-model="humanEvalDialogVisible" :title="t('llmops.humanModal.title')" width="440px">
      <el-form label-width="100px">
        <el-form-item :label="t('llmops.humanModal.modelName')">
          <el-select
            v-model="humanEvalModel"
            style="width: 100%"
            filterable
            allow-create
            :placeholder="t('llmops.humanModal.placeholder')"
          >
            <el-option v-for="m in models" :key="m.id" :label="m.name" :value="m.name" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="humanEvalDialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="warning" :loading="humanEvalSubmitting" @click="handleHumanEval">
          {{ t('llmops.humanModal.launch') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useApi } from '@/composables/useApi'
import * as llmopsApi from '@/api/llmops'
import type { ModelRegistry, EvalMetric, FinetuneResult, InferenceService } from '@/api/llmops'

/* ------------------------------ 通用 ------------------------------ */

const { t, locale } = useI18n()

/** 当前激活的 Tab，切换时保持各 Tab 数据状态 */
const activeTab = ref('models')

/* ------------------------------ 模型管理 ------------------------------ */

const modelKeyword = ref('')

// 模型列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: models,
  loading: modelLoading,
  error: modelError,
  execute: loadModels
} = useApi<ModelRegistry[]>(() => llmopsApi.listModels(), { initialData: [] })

/** 前端关键字过滤（后端已支持 modelName 参数，此处做本地二次过滤） */
const filteredModels = computed(() => {
  const kw = modelKeyword.value.trim().toLowerCase()
  if (!kw) return models.value ?? []
  return (models.value ?? []).filter((m) => m.name.toLowerCase().includes(kw))
})

/** 模型 KPI */
const modelKpi = computed(() => {
  const total = (models.value ?? []).length
  return { total }
})

/* ------------------------------ 微调任务 ------------------------------ */

// 微调任务列表
const {
  data: finetuneTasks,
  loading: finetuneLoading,
  error: finetuneError,
  execute: loadFinetuneTasks
} = useApi<FinetuneResult[]>(() => llmopsApi.listFinetuneTasks(), { initialData: [] })

/** 微调 KPI */
const finetuneKpi = computed(() => {
  const list = finetuneTasks.value ?? []
  const total = list.length
  const running = list.filter((t) => t.status === 'RUNNING' || t.status === 'SUBMITTED').length
  return { total, running }
})

/* ------------------------------ 评估指标 ------------------------------ */

// 评估指标列表
const {
  data: evalMetrics,
  loading: evalLoading,
  error: evalError,
  execute: loadEvalMetrics
} = useApi<EvalMetric[]>(() => llmopsApi.getEvalMetrics(), { initialData: [] })

/** 评估 KPI */
const evalKpi = computed(() => {
  const list = evalMetrics.value ?? []
  const total = list.length
  const human = list.filter((m) => m.evalType === 'human').length
  return { total, human }
})

/* ------------------------------ 推理服务 ------------------------------ */

const svcStatusFilter = ref('')

// 推理服务列表
const {
  data: services,
  loading: svcLoading,
  error: svcError,
  execute: loadServices
} = useApi<InferenceService[]>(
  () => llmopsApi.listInferenceServices(svcStatusFilter.value || undefined),
  { initialData: [] }
)

/** 推理服务 KPI */
const svcKpi = computed(() => {
  const list = services.value ?? []
  const total = list.length
  const running = list.filter((s) => s.status === 'RUNNING').length
  return { total, running }
})

/* ------------------------------ 注册模型弹窗 ------------------------------ */

const registerDialogVisible = ref(false)
const registering = ref(false)
const registerFormRef = ref<FormInstance>()

const registerForm = reactive({
  name: '',
  algorithm: 'huggingface',
  version: 'v1.0.0',
  trainJobId: '',
  modelPath: '',
  description: ''
})

const registerRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('llmops.registerModal.nameRequired'), trigger: 'blur' }],
  algorithm: [
    { required: true, message: t('llmops.registerModal.algorithmRequired'), trigger: 'change' }
  ],
  version: [{ required: true, message: t('llmops.registerModal.versionRequired'), trigger: 'blur' }]
}))

/** 打开注册模型弹窗 */
function openRegisterDialog() {
  registerForm.name = ''
  registerForm.algorithm = 'huggingface'
  registerForm.version = 'v1.0.0'
  registerForm.trainJobId = ''
  registerForm.modelPath = ''
  registerForm.description = ''
  registerDialogVisible.value = true
}

/** 提交注册模型 */
async function handleRegister() {
  if (!registerFormRef.value) return
  await registerFormRef.value.validate(async (valid) => {
    if (!valid) return
    registering.value = true
    try {
      await llmopsApi.registerModel({
        name: registerForm.name,
        algorithm: registerForm.algorithm,
        version: registerForm.version,
        trainJobId: registerForm.trainJobId || undefined,
        modelPath: registerForm.modelPath || undefined,
        description: registerForm.description || undefined
      })
      ElMessage.success(t('llmops.registerModal.registered'))
      registerDialogVisible.value = false
      await loadModels()
    } catch {
      // 拦截器已提示
    } finally {
      registering.value = false
    }
  })
}

/* ------------------------------ 提交微调弹窗 ------------------------------ */

const finetuneDialogVisible = ref(false)
const submitting = ref(false)
const finetuneFormRef = ref<FormInstance>()

const finetuneForm = reactive({
  modelName: '',
  baseModel: 'qiong-7B',
  trainingData: '',
  gpuConfig: '2×GPU',
  epochs: 3
})

const finetuneRules = computed<FormRules>(() => ({
  modelName: [{ required: true, message: t('llmops.ftModal.modelNameRequired'), trigger: 'blur' }],
  baseModel: [
    { required: true, message: t('llmops.ftModal.baseModelRequired'), trigger: 'change' }
  ],
  trainingData: [
    { required: true, message: t('llmops.ftModal.trainingDataRequired'), trigger: 'blur' }
  ]
}))

/** 打开提交微调弹窗 */
function openFinetuneDialog() {
  finetuneForm.modelName = ''
  finetuneForm.baseModel = 'qiong-7B'
  finetuneForm.trainingData = ''
  finetuneForm.gpuConfig = '2×GPU'
  finetuneForm.epochs = 3
  finetuneDialogVisible.value = true
}

/** 提交微调 */
async function handleFinetune() {
  if (!finetuneFormRef.value) return
  await finetuneFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await llmopsApi.submitFinetune({
        modelName: finetuneForm.modelName,
        baseModel: finetuneForm.baseModel,
        trainingData: finetuneForm.trainingData,
        gpuConfig: finetuneForm.gpuConfig,
        epochs: finetuneForm.epochs
      })
      ElMessage.success(t('llmops.ftModal.submitted'))
      finetuneDialogVisible.value = false
      await loadFinetuneTasks()
    } catch {
      // 拦截器已提示
    } finally {
      submitting.value = false
    }
  })
}

/* ------------------------------ 创建评估指标弹窗 ------------------------------ */

const evalDialogVisible = ref(false)
const evalCreating = ref(false)
const evalFormRef = ref<FormInstance>()

const evalForm = reactive({
  modelName: '',
  modelVersion: '',
  accuracy: 0.9,
  hallucinationRate: 0.05,
  baseLiftPt: 0,
  dataset: ''
})

const evalRules = computed<FormRules>(() => ({
  modelName: [{ required: true, message: t('llmops.evalModal.modelNameRequired'), trigger: 'blur' }]
}))

/** 打开创建指标弹窗 */
function openEvalDialog() {
  evalForm.modelName = ''
  evalForm.modelVersion = ''
  evalForm.accuracy = 0.9
  evalForm.hallucinationRate = 0.05
  evalForm.baseLiftPt = 0
  evalForm.dataset = ''
  evalDialogVisible.value = true
}

/** 提交创建指标 */
async function handleCreateEval() {
  if (!evalFormRef.value) return
  await evalFormRef.value.validate(async (valid) => {
    if (!valid) return
    evalCreating.value = true
    try {
      await llmopsApi.createEvalMetric({
        modelName: evalForm.modelName,
        modelVersion: evalForm.modelVersion || undefined,
        evalType: 'auto',
        accuracy: evalForm.accuracy,
        hallucinationRate: evalForm.hallucinationRate,
        baseLiftPt: evalForm.baseLiftPt,
        dataset: evalForm.dataset || undefined
      })
      ElMessage.success(t('llmops.evalModal.created'))
      evalDialogVisible.value = false
      await loadEvalMetrics()
    } catch {
      // 拦截器已提示
    } finally {
      evalCreating.value = false
    }
  })
}

/* ------------------------------ 人工评估 ------------------------------ */

const humanEvalDialogVisible = ref(false)
const humanEvalSubmitting = ref(false)
const humanEvalModel = ref('')

/** 打开人工评估弹窗 */
function openHumanEvalDialog() {
  humanEvalModel.value = (models.value ?? [])[0]?.name ?? ''
  humanEvalDialogVisible.value = true
}

/** 发起人工评估 */
async function handleHumanEval() {
  if (!humanEvalModel.value) {
    ElMessage.warning(t('llmops.humanModal.required'))
    return
  }
  humanEvalSubmitting.value = true
  try {
    await llmopsApi.triggerHumanEval(humanEvalModel.value)
    ElMessage.success(t('llmops.humanModal.launched'))
    humanEvalDialogVisible.value = false
    await loadEvalMetrics()
  } catch {
    // 拦截器已提示
  } finally {
    humanEvalSubmitting.value = false
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

const MODEL_STATUS_TYPES: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
  DRAFT: 'info',
  REGISTERED: 'primary',
  DEPLOYED: 'success',
  ARCHIVED: 'info',
  FAILED: 'danger'
}

const MODEL_STATUSES = ['DRAFT', 'REGISTERED', 'DEPLOYED', 'ARCHIVED', 'FAILED']

function modelStatusLabel(status: string): string {
  return MODEL_STATUSES.includes(status) ? t(`llmops.modelStatus.${status}`) : status
}

function modelStatusType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return MODEL_STATUS_TYPES[status] ?? 'info'
}

const FINETUNE_STATUS_TYPES: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> =
  {
    SUBMITTED: 'info',
    RUNNING: 'primary',
    SUCCEEDED: 'success',
    FAILED: 'danger'
  }

const FINETUNE_STATUSES = ['SUBMITTED', 'RUNNING', 'SUCCEEDED', 'FAILED']

function finetuneStatusLabel(status: string): string {
  return FINETUNE_STATUSES.includes(status) ? t(`llmops.ftStatus.${status}`) : status
}

function finetuneStatusType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return FINETUNE_STATUS_TYPES[status] ?? 'info'
}

/** 微调状态 → 进度条状态 */
function progressStatus(status: string): '' | 'success' | 'exception' | 'warning' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'exception'
  return ''
}

const SVC_STATUS_TYPES: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
  DEPLOYING: 'warning',
  RUNNING: 'success',
  STOPPED: 'info',
  FAILED: 'danger',
  SCALING: 'primary'
}

const SVC_STATUSES = ['DEPLOYING', 'RUNNING', 'STOPPED', 'FAILED', 'SCALING']

function svcStatusLabel(status: string): string {
  return SVC_STATUSES.includes(status) ? t(`llmops.svcStatus.${status}`) : status
}

function svcStatusType(status: string): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  return SVC_STATUS_TYPES[status] ?? 'info'
}

/** 时间格式化（ISO → 本地可读，跟随当前语言环境） */
function formatTime(iso?: string): string {
  if (!iso) return '--'
  return new Date(iso).toLocaleString(locale.value, { hour12: false })
}

/* ------------------------------ 生命周期 ------------------------------ */

/** 轮询定时器：每 5 秒刷新微调任务进度 */
let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  // 首次加载各 Tab 数据（Tab 切换时保持状态，不重复加载）
  void loadModels()
  void loadFinetuneTasks()
  void loadEvalMetrics()
  void loadServices()
  // 轮询微调任务进度，便于前端实时展示训练进度条
  timer = setInterval(() => {
    void loadFinetuneTasks()
  }, 5000)
})

onUnmounted(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.llmops-page {
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
.kpi.s {
  color: var(--ds-color-success-600);
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
</style>
