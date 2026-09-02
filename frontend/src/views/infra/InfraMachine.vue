<template>
  <div class="infra-machine-page">
    <h1>机器供应</h1>
    <div class="sub">信创集群供应 · 创建 / 销毁 / 扩缩容 · 30 秒自动刷新</div>

    <!-- KPI 卡片区：三态 loading / error / data -->
    <div class="grid g4">
      <template v-if="loading">
        <div v-for="i in 4" :key="i" class="card">
          <h3>加载中…</h3>
          <div class="kpi">--</div>
          <div class="meta">正在拉取数据</div>
        </div>
      </template>
      <template v-else-if="error">
        <div class="card" style="grid-column: span 4">
          <h3>加载失败</h3>
          <div class="meta" style="color: var(--muted)">
            {{ error.message }}，
            <a href="javascript:void(0)" @click="reload">重试</a>
          </div>
        </div>
      </template>
      <template v-else-if="clusters">
        <div class="card">
          <h3>集群总数</h3>
          <div class="kpi">{{ kpi.total }}</div>
          <div class="meta">信创环境</div>
        </div>
        <div class="card">
          <h3>运行中</h3>
          <div class="kpi s">{{ kpi.running }}</div>
          <div class="meta">状态正常</div>
        </div>
        <div class="card">
          <h3>创建中</h3>
          <div class="kpi w">{{ kpi.creating }}</div>
          <div class="meta">异步供应中</div>
        </div>
        <div class="card">
          <h3>异常</h3>
          <div class="kpi d">{{ kpi.failed }}</div>
          <div class="meta">需人工介入</div>
        </div>
      </template>
    </div>

    <!-- 操作栏 + 集群列表 -->
    <el-card shadow="never" class="page-card" style="margin-top: 16px">
      <div class="toolbar">
        <el-button type="primary" @click="openCreateDialog">+ 新建集群</el-button>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="reload" />
      </div>

      <el-table
        v-loading="loading"
        :data="clusters ?? []"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? '加载失败，请重试' : '暂无集群'"
      >
        <el-table-column prop="clusterName" label="集群名称" min-width="180" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="k8sVersion" label="K8s 版本" width="120" />
        <el-table-column label="节点数" width="140" align="center">
          <template #default="{ row }">
            {{ row.controlPlaneCount }} (控制面) / {{ row.workerCount }} (工作)
          </template>
        </el-table-column>
        <el-table-column prop="podCidr" label="Pod 网段" min-width="160" />
        <el-table-column prop="serviceCidr" label="Service 网段" min-width="160" />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openScaleDialog(row)">扩缩容</el-button>
            <el-button link type="danger" @click="handleDestroy(row)">销毁</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 新建集群向导 -->
    <el-dialog
      v-model="createDialogVisible"
      title="新建信创集群"
      width="640px"
      :close-on-click-modal="false"
      @closed="resetCreateForm"
    >
      <el-steps :active="createStep" finish-status="success" simple style="margin-bottom: 20px">
        <el-step title="基础信息" />
        <el-step title="节点规格" />
        <el-step title="网络配置" />
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
          <el-form-item label="集群名称" prop="clusterName">
            <el-input v-model="createForm.clusterName" placeholder="如 prod-xinchang-01" />
          </el-form-item>
          <el-form-item label="K8s 版本" prop="k8sVersion">
            <el-select v-model="createForm.k8sVersion" style="width: 100%">
              <el-option label="v1.28" value="v1.28" />
              <el-option label="v1.27" value="v1.27" />
              <el-option label="v1.26" value="v1.26" />
            </el-select>
          </el-form-item>
        </template>

        <!-- 步骤 2：节点规格 -->
        <template v-else-if="createStep === 1">
          <el-form-item label="控制面节点数" prop="masterCount">
            <el-input-number v-model="createForm.masterCount" :min="1" :max="9" />
          </el-form-item>
          <el-form-item label="工作节点数" prop="workerCount">
            <el-input-number v-model="createForm.workerCount" :min="1" :max="200" />
          </el-form-item>
          <el-form-item label="CPU 核数" prop="cpu">
            <el-input-number v-model="createForm.cpu" :min="2" :max="128" />
          </el-form-item>
          <el-form-item label="内存 (GB)" prop="memory">
            <el-input-number v-model="createForm.memory" :min="4" :max="1024" />
          </el-form-item>
          <el-form-item label="系统盘 (GB)" prop="disk">
            <el-input-number v-model="createForm.disk" :min="50" :max="2000" />
          </el-form-item>
        </template>

        <!-- 步骤 3：网络配置 -->
        <template v-else-if="createStep === 2">
          <el-form-item label="Pod CIDR" prop="podCidr">
            <el-input v-model="createForm.podCidr" placeholder="如 10.244.0.0/16" />
          </el-form-item>
          <el-form-item label="Service CIDR" prop="serviceCidr">
            <el-input v-model="createForm.serviceCidr" placeholder="如 10.96.0.0/12" />
          </el-form-item>
        </template>
      </el-form>

      <template #footer>
        <el-button v-if="createStep > 0" @click="createStep--">上一步</el-button>
        <el-button v-if="createStep < 2" type="primary" @click="nextCreateStep">下一步</el-button>
        <el-button v-else type="primary" :loading="submitting" @click="handleCreate">
          提交
        </el-button>
        <el-button @click="createDialogVisible = false">取消</el-button>
      </template>
    </el-dialog>

    <!-- 扩缩容弹窗 -->
    <el-dialog
      v-model="scaleDialogVisible"
      title="集群扩缩容"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form label-width="120px" label-position="right">
        <el-form-item label="集群名称">
          <el-input :model-value="scalingTarget?.clusterName" disabled />
        </el-form-item>
        <el-form-item label="当前节点数">
          <el-input :model-value="scalingTarget?.workerCount" disabled />
        </el-form-item>
        <el-form-item label="目标节点数">
          <el-input-number v-model="scaleTargetCount" :min="1" :max="500" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="scaleDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="scaling" @click="handleScale">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as infraApi from '@/api/infra'
import type { ClusterInfo, ClusterStatus, ClusterCreateRequest } from '@/api/infra'

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

const createRules: FormRules = {
  clusterName: [{ required: true, message: '请输入集群名称', trigger: 'blur' }],
  k8sVersion: [{ required: true, message: '请选择 K8s 版本', trigger: 'change' }],
  podCidr: [{ required: true, message: '请输入 Pod CIDR', trigger: 'blur' }],
  serviceCidr: [{ required: true, message: '请输入 Service CIDR', trigger: 'blur' }]
}

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
      ElMessage.success('集群创建已提交')
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
    ElMessage.success('扩缩容请求已提交')
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
    await ElMessageBox.confirm(`确认销毁集群「${row.clusterName}」？该操作不可恢复。`, '危险操作', {
      type: 'warning',
      confirmButtonText: '销毁',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await infraApi.destroyXinchangCluster(row.clusterId)
    ElMessage.success('销毁请求已提交')
    await reload()
  } catch {
    // 用户取消或销毁失败，不重复提示
  }
}

/* ------------------------------ 辅助函数 ------------------------------ */

/** 状态 → 中文 */
function statusLabel(status: ClusterStatus): string {
  const map: Record<ClusterStatus, string> = {
    CREATING: '创建中',
    RUNNING: '运行中',
    FAILED: '异常',
    DESTROYED: '已销毁',
    UPDATING: '更新中'
  }
  return map[status] ?? status
}

/** 状态 → tag 类型 */
function statusTagType(status: ClusterStatus): 'success' | 'warning' | 'danger' | 'info' {
  const map: Record<ClusterStatus, 'success' | 'warning' | 'danger' | 'info'> = {
    CREATING: 'warning',
    RUNNING: 'success',
    FAILED: 'danger',
    DESTROYED: 'info',
    UPDATING: 'warning'
  }
  return map[status] ?? 'info'
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
