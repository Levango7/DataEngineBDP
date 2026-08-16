<template>
  <div class="dev-ml-page">
    <h1>机器学习</h1>
    <div class="sub">训练实验 · 模型注册 · 推理服务 · 15 秒自动刷新</div>

    <!-- KPI 卡片区 -->
    <div class="grid g4">
      <template v-if="trainLoading">
        <div class="card" v-for="i in 4" :key="i">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="trainError">
        <div class="card" style="grid-column: span 4">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            训练作业列表加载失败，<a href="javascript:void(0)" @click="loadTrain">重试</a>
          </div>
        </div>
      </template>
      <template v-else>
        <div class="card">
          <h3>训练作业数</h3>
          <div class="kpi">{{ trainKpi.total }}</div>
          <div class="meta">全部训练作业</div>
        </div>
        <div class="card">
          <h3>运行中</h3>
          <div class="kpi">{{ trainKpi.running }}</div>
          <div class="meta">状态为 RUNNING</div>
        </div>
        <div class="card">
          <h3>模型数</h3>
          <div class="kpi s">{{ modelKpi.total }}</div>
          <div class="meta">模型仓库</div>
        </div>
        <div class="card">
          <h3>推理服务数</h3>
          <div class="kpi">{{ svcKpi.total }}</div>
          <div class="meta">运行中 {{ svcKpi.running }} 个</div>
        </div>
      </template>
    </div>

    <!-- Tabs 主区 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <el-tabs v-model="activeTab" type="card">
        <!-- Tab1 训练实验 -->
        <el-tab-pane label="训练实验" name="train">
          <div class="toolbar">
            <el-button type="primary" @click="openTrainDialog">+ 提交训练</el-button>
            <el-select
              v-model="trainStatusFilter"
              placeholder="状态筛选"
              clearable
              style="width: 140px"
              @change="loadTrain"
            >
              <el-option label="等待中" value="PENDING" />
              <el-option label="运行中" value="RUNNING" />
              <el-option label="成功" value="SUCCEEDED" />
              <el-option label="失败" value="FAILED" />
              <el-option label="已取消" value="KILLED" />
            </el-select>
            <div class="spacer"></div>
            <el-button :icon="Refresh" circle @click="loadTrain" />
          </div>

          <el-table
            v-loading="trainLoading"
            :data="trainJobs"
            stripe
            border
            style="width: 100%"
            :empty-text="trainError ? '加载失败，请重试' : '暂无训练作业'"
          >
            <el-table-column prop="name" label="实验名" min-width="180" />
            <el-table-column prop="algorithm" label="算法" width="140">
              <template #default="{ row }">
                <el-tag effect="light" size="small">{{ row.algorithm }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="dataset" label="数据集" min-width="160" />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="trainStatusType(row.status)" effect="light">
                  {{ trainStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="指标" min-width="200">
              <template #default="{ row }">
                <span v-if="row.metrics">
                  {{ formatMetrics(row.metrics) }}
                </span>
                <span v-else style="color: var(--muted)">--</span>
              </template>
            </el-table-column>
            <el-table-column prop="owner" label="负责人" width="120">
              <template #default="{ row }">{{ row.owner || '--' }}</template>
            </el-table-column>
            <el-table-column prop="submittedAt" label="提交时间" width="180">
              <template #default="{ row }">{{ row.submittedAt || '--' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="240" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openLogDialog(row)">日志</el-button>
                <el-button
                  v-if="canStop(row.status)"
                  link
                  type="warning"
                  :loading="stoppingId === row.id"
                  @click="handleStopTrain(row)"
                >
                  停止
                </el-button>
                <el-button
                  v-if="canRegister(row.status)"
                  link
                  type="success"
                  @click="openRegisterDialog(row)"
                >
                  注册模型
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <!-- 分页 -->
          <div class="pagination-wrap">
            <el-pagination
              v-model:current-page="trainPage"
              v-model:page-size="trainSize"
              :page-sizes="[10, 20, 50]"
              :total="trainTotal"
              layout="total, sizes, prev, pager, next, jumper"
              background
              @size-change="loadTrain"
              @current-change="loadTrain"
            />
          </div>
        </el-tab-pane>

        <!-- Tab2 模型仓库 -->
        <el-tab-pane label="模型仓库" name="model">
          <div class="toolbar">
            <el-input
              v-model="modelKeyword"
              placeholder="按名称搜索"
              clearable
              style="width: 240px"
              @change="loadModels"
            />
            <div class="spacer"></div>
            <el-button :icon="Refresh" circle @click="loadModels" />
          </div>

          <el-table
            v-loading="modelsLoading"
            :data="models"
            stripe
            border
            style="width: 100%"
            :empty-text="modelsError ? '加载失败，请重试' : '暂无模型'"
          >
            <el-table-column prop="name" label="模型名" min-width="180" />
            <el-table-column prop="algorithm" label="算法" width="140">
              <template #default="{ row }">
                <el-tag effect="light" size="small">{{ row.algorithm }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="latestVersion" label="最新版本" width="120" />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="modelStatusType(row.status)" effect="light" size="small">
                  {{ modelStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="指标" min-width="200">
              <template #default="{ row }">
                <span v-if="row.metrics">{{ formatMetrics(row.metrics) }}</span>
                <span v-else style="color: var(--muted)">--</span>
              </template>
            </el-table-column>
            <el-table-column prop="registeredAt" label="注册时间" width="180">
              <template #default="{ row }">{{ row.registeredAt || '--' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openVersionDrawer(row)">版本</el-button>
                <el-button
                  link
                  type="success"
                  @click="openDeployDialog(row)"
                >
                  部署推理
                </el-button>
                <el-button link type="danger" @click="handleDeleteModel(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- Tab3 推理服务 -->
        <el-tab-pane label="推理服务" name="inference">
          <div class="toolbar">
            <el-select
              v-model="svcStatusFilter"
              placeholder="状态筛选"
              clearable
              style="width: 140px"
              @change="loadServices"
            >
              <el-option label="部署中" value="DEPLOYING" />
              <el-option label="运行中" value="RUNNING" />
              <el-option label="已停止" value="STOPPED" />
              <el-option label="失败" value="FAILED" />
              <el-option label="扩缩容" value="SCALING" />
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
            :empty-text="svcError ? '加载失败，请重试' : '暂无推理服务'"
          >
            <el-table-column prop="serviceName" label="服务名" min-width="180" />
            <el-table-column prop="modelName" label="模型" min-width="160" />
            <el-table-column prop="modelVersion" label="版本" width="100" />
            <el-table-column label="状态" width="120">
              <template #default="{ row }">
                <el-tag :type="svcStatusType(row.status)" effect="light">
                  {{ svcStatusLabel(row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="副本" width="120" align="center">
              <template #default="{ row }">
                {{ row.replicas ?? 0 }}<span v-if="row.desiredReplicas !== row.replicas"> / {{ row.desiredReplicas ?? 0 }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="qps" label="QPS" width="100">
              <template #default="{ row }">{{ row.qps ?? '--' }}</template>
            </el-table-column>
            <el-table-column label="延迟" width="120">
              <template #default="{ row }">
                {{ row.latencyMs !== undefined ? row.latencyMs + 'ms' : '--' }}
              </template>
            </el-table-column>
            <el-table-column label="操作" width="220" fixed="right">
              <template #default="{ row }">
                <el-button
                  v-if="canScale(row.status)"
                  link
                  type="primary"
                  @click="openScaleDialog(row)"
                >
                  扩缩容
                </el-button>
                <el-button
                  v-if="canStopSvc(row.status)"
                  link
                  type="danger"
                  :loading="stoppingSvcId === row.id"
                  @click="handleStopSvc(row)"
                >
                  停止
                </el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 提交训练弹窗 -->
    <el-dialog
      v-model="trainDialogVisible"
      title="提交训练作业"
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
        <el-form-item label="作业名称" prop="name">
          <el-input v-model="trainForm.name" placeholder="如 风控模型训练" />
        </el-form-item>
        <el-form-item label="算法" prop="algorithm">
          <el-select v-model="trainForm.algorithm" style="width: 100%">
            <el-option label="XGBoost" value="xgboost" />
            <el-option label="LightGBM" value="lightgbm" />
            <el-option label="TensorFlow" value="tensorflow" />
            <el-option label="PyTorch" value="pytorch" />
            <el-option label="scikit-learn" value="sklearn" />
            <el-option label="Spark MLlib" value="sparkml" />
            <el-option label="HuggingFace" value="huggingface" />
          </el-select>
        </el-form-item>
        <el-form-item label="数据集" prop="dataset">
          <el-input v-model="trainForm.dataset" placeholder="如 hdfs:///data/train.csv" />
        </el-form-item>
        <el-form-item label="训练轮次" prop="epochs">
          <el-input-number v-model="trainForm.epochs" :min="1" :max="1000" />
        </el-form-item>
        <el-form-item label="资源规格" prop="resourceSpec">
          <el-input v-model="trainForm.resourceSpec" placeholder="如 4c/16g × 2" />
        </el-form-item>
        <el-form-item label="超参 JSON" prop="hyperparams">
          <el-input
            v-model="trainForm.hyperparams"
            type="textarea"
            :rows="4"
            placeholder='{"learning_rate": 0.1, "max_depth": 6}'
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item label="负责人" prop="owner">
          <el-input v-model="trainForm.owner" placeholder="负责人姓名" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="trainDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmitTrain">提交</el-button>
      </template>
    </el-dialog>

    <!-- 日志弹窗 -->
    <el-dialog
      v-model="logDialogVisible"
      :title="`训练日志 - ${currentLogJob?.name || ''}`"
      width="800px"
      @opened="scrollLogToBottom"
    >
      <div v-loading="logLoading" class="log-container">
        <pre class="log-content">{{ logContent || '暂无日志' }}</pre>
      </div>
      <template #footer>
        <el-button @click="logDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="refreshLog">刷新</el-button>
      </template>
    </el-dialog>

    <!-- 注册模型弹窗 -->
    <el-dialog
      v-model="registerDialogVisible"
      :title="`注册模型 - ${currentTrainJob?.name || ''}`"
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
        <el-form-item label="模型名" prop="name">
          <el-input v-model="registerForm.name" placeholder="如 risk-model" />
        </el-form-item>
        <el-form-item label="版本" prop="version">
          <el-input v-model="registerForm.version" placeholder="如 v1.0.0" />
        </el-form-item>
        <el-form-item label="模型路径" prop="modelPath">
          <el-input
            v-model="registerForm.modelPath"
            placeholder="如 hdfs:///models/risk/v1"
            style="font-family: 'SFMono-Regular', Consolas, monospace; font-size: 12.5px"
          />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="registerForm.description" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="registering" @click="handleRegister">注册</el-button>
      </template>
    </el-dialog>

    <!-- 部署推理弹窗 -->
    <el-dialog
      v-model="deployDialogVisible"
      :title="`部署推理服务 - ${currentModel?.name || ''}`"
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
        <el-form-item label="服务名" prop="serviceName">
          <el-input v-model="deployForm.serviceName" placeholder="如 risk-svc" />
        </el-form-item>
        <el-form-item label="模型版本" prop="version">
          <el-input v-model="deployForm.version" :placeholder="currentModel?.latestVersion || 'v1'" />
        </el-form-item>
        <el-form-item label="副本数" prop="replicas">
          <el-input-number v-model="deployForm.replicas" :min="1" :max="20" />
        </el-form-item>
        <el-form-item label="资源规格" prop="resourceSpec">
          <el-input v-model="deployForm.resourceSpec" placeholder="如 2c/4g" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deployDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="deploying" @click="handleDeploy">部署</el-button>
      </template>
    </el-dialog>

    <!-- 扩缩容弹窗 -->
    <el-dialog
      v-model="scaleDialogVisible"
      :title="`扩缩容 - ${currentSvc?.serviceName || ''}`"
      width="360px"
      :close-on-click-modal="false"
    >
      <el-form label-width="100px" label-position="right">
        <el-form-item label="当前副本">
          {{ currentSvc?.replicas ?? 0 }}
        </el-form-item>
        <el-form-item label="目标副本">
          <el-input-number v-model="scaleTarget" :min="0" :max="50" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scaleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="scaling" @click="handleScale">应用</el-button>
      </template>
    </el-dialog>

    <!-- 模型版本抽屉 -->
    <el-drawer
      v-model="versionDrawerVisible"
      :title="`模型版本 - ${currentModel?.name || ''}`"
      size="50%"
    >
      <el-table
        v-loading="versionsLoading"
        :data="versions"
        stripe
        border
        size="small"
        :empty-text="'暂无版本'"
      >
        <el-table-column prop="version" label="版本" width="120" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag size="small" effect="light">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="指标" min-width="200">
          <template #default="{ row }">
            <span v-if="row.metrics">{{ formatMetrics(row.metrics) }}</span>
            <span v-else style="color: var(--muted)">--</span>
          </template>
        </el-table-column>
        <el-table-column prop="registeredAt" label="注册时间" width="180">
          <template #default="{ row }">{{ row.registeredAt || '--' }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import * as devMlApi from '@/api/dev-ml'
import type { TrainJob, MlModel, InferenceService, ModelVersion } from '@/api/dev-ml'

/* ------------------------------ 通用 ------------------------------ */

const appStore = useAppStore()
const activeTab = ref('train')

/* ------------------------------ 训练实验 ------------------------------ */

const trainLoading = ref(false)
const trainError = ref(false)
const trainJobs = ref<TrainJob[]>([])
const trainTotal = ref(0)
const trainPage = ref(1)
const trainSize = ref(20)
const trainStatusFilter = ref<string>('')

/** 加载训练作业 */
async function loadTrain() {
  trainLoading.value = true
  trainError.value = false
  try {
    const result = await devMlApi.listTrainJobs({
      workspaceId: appStore.workspace || undefined,
      status: trainStatusFilter.value || undefined,
      page: trainPage.value,
      size: trainSize.value
    })
    trainJobs.value = result.list
    trainTotal.value = result.total
  } catch {
    trainError.value = true
  } finally {
    trainLoading.value = false
  }
}

/** 训练 KPI */
const trainKpi = computed(() => {
  const list = trainJobs.value
  const total = list.length
  const running = list.filter((j) => j.status === 'RUNNING').length
  return { total, running }
})

/* ------------------------------ 模型仓库 ------------------------------ */

const modelsLoading = ref(false)
const modelsError = ref(false)
const models = ref<MlModel[]>([])
const modelKeyword = ref<string>('')

/** 加载模型列表 */
async function loadModels() {
  modelsLoading.value = true
  modelsError.value = false
  try {
    models.value = await devMlApi.listModels({
      keyword: modelKeyword.value || undefined
    })
  } catch {
    modelsError.value = true
    models.value = []
  } finally {
    modelsLoading.value = false
  }
}

/** 模型 KPI */
const modelKpi = computed(() => {
  const total = models.value.length
  return { total }
})

/* ------------------------------ 推理服务 ------------------------------ */

const svcLoading = ref(false)
const svcError = ref(false)
const services = ref<InferenceService[]>([])
const svcStatusFilter = ref<string>('')

/** 加载推理服务列表 */
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

/** 推理服务 KPI */
const svcKpi = computed(() => {
  const total = services.value.length
  const running = services.value.filter((s) => s.status === 'RUNNING').length
  return { total, running }
})

/* ------------------------------ 提交训练 ------------------------------ */

const trainDialogVisible = ref(false)
const submitting = ref(false)
const trainFormRef = ref<FormInstance>()

interface TrainForm {
  name: string
  algorithm: string
  dataset: string
  epochs: number
  resourceSpec: string
  hyperparams: string
  owner: string
}

const trainForm = reactive<TrainForm>({
  name: '',
  algorithm: 'xgboost',
  dataset: '',
  epochs: 10,
  resourceSpec: '4c/16g × 2',
  hyperparams: '',
  owner: ''
})

const trainRules: FormRules = {
  name: [{ required: true, message: '请输入作业名称', trigger: 'blur' }],
  algorithm: [{ required: true, message: '请选择算法', trigger: 'change' }],
  dataset: [{ required: true, message: '请输入数据集', trigger: 'blur' }]
}

/** 打开提交训练弹窗 */
function openTrainDialog() {
  resetTrainForm()
  trainDialogVisible.value = true
}

/** 重置训练表单 */
function resetTrainForm() {
  trainForm.name = ''
  trainForm.algorithm = 'xgboost'
  trainForm.dataset = ''
  trainForm.epochs = 10
  trainForm.resourceSpec = '4c/16g × 2'
  trainForm.hyperparams = ''
  trainForm.owner = ''
  trainFormRef.value?.clearValidate()
}

/** 提交训练 */
async function handleSubmitTrain() {
  if (!trainFormRef.value) return
  await trainFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await devMlApi.createTrainJob({
        name: trainForm.name,
        algorithm: trainForm.algorithm,
        dataset: trainForm.dataset,
        epochs: trainForm.epochs,
        resourceSpec: trainForm.resourceSpec || undefined,
        hyperparams: trainForm.hyperparams || undefined,
        owner: trainForm.owner || undefined,
        workspaceId: appStore.workspace || undefined
      })
      ElMessage.success('训练作业已提交')
      trainDialogVisible.value = false
      await loadTrain()
    } catch {
      // 拦截器已提示
    } finally {
      submitting.value = false
    }
  })
}

/* ------------------------------ 训练日志 ------------------------------ */

const logDialogVisible = ref(false)
const logLoading = ref(false)
const logContent = ref<string>('')
const currentLogJob = ref<TrainJob | null>(null)

/** 打开日志弹窗 */
async function openLogDialog(row: TrainJob) {
  currentLogJob.value = row
  logDialogVisible.value = true
  await refreshLog()
}

/** 刷新日志 */
async function refreshLog() {
  if (!currentLogJob.value) return
  logLoading.value = true
  try {
    const logs = await devMlApi.getTrainJobLogs(currentLogJob.value.id)
    logContent.value = logs || '暂无日志'
    scrollLogToBottom()
  } catch {
    logContent.value = '日志加载失败'
  } finally {
    logLoading.value = false
  }
}

/** 日志滚动到底部 */
function scrollLogToBottom() {
  const container = document.querySelector('.log-content') as HTMLElement
  if (container) {
    container.scrollTop = container.scrollHeight
  }
}

/* ------------------------------ 停止训练 ------------------------------ */

const stoppingId = ref<string>('')

/** 是否可停止 */
function canStop(status: string): boolean {
  return ['PENDING', 'RUNNING', 'SCHEDULED'].includes(status)
}

/** 停止训练 */
async function handleStopTrain(row: TrainJob) {
  stoppingId.value = row.id
  try {
    await ElMessageBox.confirm(
      `确认停止训练作业「${row.name}」？`,
      '停止确认',
      { type: 'warning' }
    )
    await devMlApi.stopTrainJob(row.id)
    ElMessage.success('训练作业已停止')
    await loadTrain()
  } catch {
    // 用户取消或操作失败
  } finally {
    stoppingId.value = ''
  }
}

/* ------------------------------ 注册模型 ------------------------------ */

const registerDialogVisible = ref(false)
const registering = ref(false)
const registerFormRef = ref<FormInstance>()
const currentTrainJob = ref<TrainJob | null>(null)

const registerForm = reactive({
  name: '',
  version: 'v1.0.0',
  modelPath: '',
  description: ''
})

const registerRules: FormRules = {
  name: [{ required: true, message: '请输入模型名', trigger: 'blur' }],
  version: [{ required: true, message: '请输入版本号', trigger: 'blur' }]
}

/** 是否可注册模型 */
function canRegister(status: string): boolean {
  return status === 'SUCCEEDED'
}

/** 打开注册模型弹窗 */
function openRegisterDialog(row: TrainJob) {
  currentTrainJob.value = row
  registerForm.name = row.name + '-model'
  registerForm.version = 'v1.0.0'
  registerForm.modelPath = ''
  registerForm.description = ''
  registerDialogVisible.value = true
}

/** 提交注册模型 */
async function handleRegister() {
  if (!registerFormRef.value || !currentTrainJob.value) return
  const trainJob = currentTrainJob.value
  await registerFormRef.value.validate(async (valid) => {
    if (!valid) return
    registering.value = true
    try {
      await devMlApi.registerModel({
        name: registerForm.name,
        algorithm: trainJob.algorithm,
        trainJobId: trainJob.id,
        modelPath: registerForm.modelPath || undefined,
        version: registerForm.version,
        metrics: trainJob.metrics,
        description: registerForm.description || undefined
      })
      ElMessage.success('模型已注册')
      registerDialogVisible.value = false
      await loadModels()
    } catch {
      // 拦截器已提示
    } finally {
      registering.value = false
    }
  })
}

/* ------------------------------ 部署推理 ------------------------------ */

const deployDialogVisible = ref(false)
const deploying = ref(false)
const deployFormRef = ref<FormInstance>()
const currentModel = ref<MlModel | null>(null)

const deployForm = reactive({
  serviceName: '',
  version: '',
  replicas: 1,
  resourceSpec: '2c/4g'
})

const deployRules: FormRules = {
  version: [{ required: true, message: '请输入模型版本', trigger: 'blur' }]
}

/** 打开部署推理弹窗 */
function openDeployDialog(row: MlModel) {
  currentModel.value = row
  deployForm.serviceName = row.name + '-svc'
  deployForm.version = row.latestVersion
  deployForm.replicas = 1
  deployForm.resourceSpec = '2c/4g'
  deployDialogVisible.value = true
}

/** 提交部署推理 */
async function handleDeploy() {
  if (!deployFormRef.value || !currentModel.value) return
  const model = currentModel.value
  await deployFormRef.value.validate(async (valid) => {
    if (!valid) return
    deploying.value = true
    try {
      await devMlApi.deployInference({
        serviceName: deployForm.serviceName || undefined,
        modelName: model.name,
        version: deployForm.version,
        replicas: deployForm.replicas,
        resourceSpec: deployForm.resourceSpec || undefined
      })
      ElMessage.success('推理服务已部署')
      deployDialogVisible.value = false
      await loadServices()
    } catch {
      // 拦截器已提示
    } finally {
      deploying.value = false
    }
  })
}

/* ------------------------------ 停止 / 扩缩容推理 ------------------------------ */

const stoppingSvcId = ref<string>('')

/** 是否可停止推理 */
function canStopSvc(status: string): boolean {
  return ['DEPLOYING', 'RUNNING', 'SCALING', 'FAILED'].includes(status)
}

/** 是否可扩缩容 */
function canScale(status: string): boolean {
  return ['RUNNING', 'SCALING'].includes(status)
}

/** 停止推理服务 */
async function handleStopSvc(row: InferenceService) {
  stoppingSvcId.value = row.id
  try {
    await ElMessageBox.confirm(
      `确认停止推理服务「${row.serviceName}」？`,
      '停止确认',
      { type: 'warning', confirmButtonClass: 'el-button--danger' }
    )
    await devMlApi.stopInference(row.id)
    ElMessage.success('推理服务已停止')
    await loadServices()
  } catch {
    // 用户取消或操作失败
  } finally {
    stoppingSvcId.value = ''
  }
}

const scaleDialogVisible = ref(false)
const scaling = ref(false)
const currentSvc = ref<InferenceService | null>(null)
const scaleTarget = ref(1)

/** 打开扩缩容弹窗 */
function openScaleDialog(row: InferenceService) {
  currentSvc.value = row
  scaleTarget.value = row.replicas ?? 1
  scaleDialogVisible.value = true
}

/** 应用扩缩容 */
async function handleScale() {
  if (!currentSvc.value) return
  scaling.value = true
  try {
    await devMlApi.scaleInference(currentSvc.value.id, { replicas: scaleTarget.value })
    ElMessage.success(`已扩缩容到 ${scaleTarget.value} 副本`)
    scaleDialogVisible.value = false
    await loadServices()
  } catch {
    // 拦截器已提示
  } finally {
    scaling.value = false
  }
}

/* ------------------------------ 删除模型 ------------------------------ */

/** 删除模型 */
async function handleDeleteModel(row: MlModel) {
  try {
    await ElMessageBox.confirm(
      `确认删除模型「${row.name}」？该操作不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await devMlApi.deleteModel(row.id)
    ElMessage.success('模型已删除')
    await loadModels()
  } catch {
    // 用户取消或删除失败
  }
}

/* ------------------------------ 模型版本 ------------------------------ */

const versionDrawerVisible = ref(false)
const versionsLoading = ref(false)
const versions = ref<ModelVersion[]>([])

/** 打开版本抽屉 */
async function openVersionDrawer(row: MlModel) {
  currentModel.value = row
  versionDrawerVisible.value = true
  versionsLoading.value = true
  try {
    versions.value = await devMlApi.listModelVersions(row.name)
  } catch {
    versions.value = []
  } finally {
    versionsLoading.value = false
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 训练状态 → 中文 */
function trainStatusLabel(status: string): string {
  const map: Record<string, string> = {
    PENDING: '等待中',
    RUNNING: '运行中',
    SUCCEEDED: '成功',
    FAILED: '失败',
    KILLED: '已取消',
    SCHEDULED: '已调度'
  }
  return map[status] ?? status
}

/** 训练状态 → tag 类型 */
function trainStatusType(
  status: string
): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  const map: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
    PENDING: 'info',
    RUNNING: 'primary',
    SUCCEEDED: 'success',
    FAILED: 'danger',
    KILLED: 'info',
    SCHEDULED: 'warning'
  }
  return map[status] ?? 'info'
}

/** 模型状态 → 中文 */
function modelStatusLabel(status?: string): string {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    REGISTERED: '已注册',
    DEPLOYED: '已部署',
    ARCHIVED: '已归档',
    FAILED: '失败'
  }
  return map[status ?? ''] ?? (status ?? '--')
}

/** 模型状态 → tag 类型 */
function modelStatusType(
  status?: string
): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  const map: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
    DRAFT: 'info',
    REGISTERED: 'primary',
    DEPLOYED: 'success',
    ARCHIVED: 'info',
    FAILED: 'danger'
  }
  return map[status ?? ''] ?? 'info'
}

/** 推理服务状态 → 中文 */
function svcStatusLabel(status: string): string {
  const map: Record<string, string> = {
    DEPLOYING: '部署中',
    RUNNING: '运行中',
    STOPPED: '已停止',
    FAILED: '失败',
    SCALING: '扩缩容'
  }
  return map[status] ?? status
}

/** 推理服务状态 → tag 类型 */
function svcStatusType(
  status: string
): 'primary' | 'success' | 'danger' | 'info' | 'warning' {
  const map: Record<string, 'primary' | 'success' | 'danger' | 'info' | 'warning'> = {
    DEPLOYING: 'warning',
    RUNNING: 'success',
    STOPPED: 'info',
    FAILED: 'danger',
    SCALING: 'primary'
  }
  return map[status] ?? 'info'
}

/** 指标格式化 */
function formatMetrics(metrics: Record<string, number>): string {
  return Object.entries(metrics)
    .map(([k, v]) => `${k}=${typeof v === 'number' ? v.toFixed(4) : v}`)
    .join(' · ')
}

/* ------------------------------ 生命周期 ------------------------------ */

let timer: ReturnType<typeof setInterval> | null = null

onMounted(() => {
  void loadTrain()
  void loadModels()
  void loadServices()
  // 15s 轮询刷新
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
</script>

<style scoped>
.dev-ml-page {
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
  font-size: 28px;
  font-weight: 700;
  color: #232a2e;
  line-height: 1.2;
}
.kpi.s {
  color: #2f9e6f;
}
.kpi.d {
  color: #c0504d;
}
.meta {
  font-size: 12px;
  color: #717a80;
  margin-top: 6px;
}
.page-card {
  border: 1px solid #e4e8ea;
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
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
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