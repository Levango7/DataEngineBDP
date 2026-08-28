<template>
  <div class="quota-page">
    <h1>配额管理</h1>
    <div class="sub">
      管理 Workspace 资源配额，底层自动翻译为 K8s ResourceQuota + LimitRange，控制
      CPU/内存/存储/Pod/PVC/Service 总量与 per-Pod 上下限。
    </div>

    <!-- 顶部操作栏 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="openCreateDialog">+ 设置配额</el-button>
        <el-select
          v-model="filterTenantId"
          placeholder="租户筛选"
          clearable
          style="width: 180px"
          @change="handleSearch"
        >
          <el-option v-for="t in tenantOptions" :key="t.id" :label="t.name" :value="t.id" />
        </el-select>
        <el-select
          v-model="filterWorkspaceId"
          placeholder="Workspace 筛选"
          clearable
          filterable
          style="width: 220px"
          @change="handleSearch"
        >
          <el-option v-for="w in workspaceOptions" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="loadList" />
      </div>

      <!-- 配额列表表格 -->
      <el-table
        v-loading="loading"
        :data="quotaList"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? '加载失败，请重试' : '暂无配额数据'"
      >
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="Workspace" width="160">
          <template #default="{ row }">
            {{ workspaceName(row.workspaceId) }}
          </template>
        </el-table-column>
        <el-table-column label="CPU 限制" width="100">
          <template #default="{ row }">
            <span class="mono">{{ row.cpuLimit }}</span>
          </template>
        </el-table-column>
        <el-table-column label="内存限制" width="110">
          <template #default="{ row }">
            <span class="mono">{{ row.memoryLimit }}</span>
          </template>
        </el-table-column>
        <el-table-column label="存储限制" width="110">
          <template #default="{ row }">
            <span class="mono">{{ row.storageLimit }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Pod/PVC/Svc" width="140">
          <template #default="{ row }">
            <span class="mono">
              {{ row.podLimit }} / {{ row.pvcLimit }} / {{ row.serviceLimit }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="per-Pod max" width="140">
          <template #default="{ row }">
            <span class="mono">
              {{ row.maxCpuPerPod || '-' }} / {{ row.maxMemoryPerPod || '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="更新时间" width="180" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleViewUsage(row)">用量</el-button>
            <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 设置/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? '编辑配额' : '设置配额'"
      width="640px"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="140px"
        label-position="right"
      >
        <el-divider content-position="left">Workspace 选择</el-divider>
        <el-form-item label="Workspace" prop="workspaceId">
          <el-select
            v-model="formData.workspaceId"
            placeholder="选择 Workspace"
            style="width: 100%"
            :disabled="isEdit"
            filterable
          >
            <el-option v-for="w in workspaceOptions" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>

        <el-divider content-position="left">ResourceQuota（Workspace 总量）</el-divider>
        <el-form-item label="CPU 限制" prop="cpuLimit">
          <el-input v-model="formData.cpuLimit" placeholder="如 10" />
        </el-form-item>
        <el-form-item label="内存限制" prop="memoryLimit">
          <el-input v-model="formData.memoryLimit" placeholder="如 20Gi" />
        </el-form-item>
        <el-form-item label="存储限制" prop="storageLimit">
          <el-input v-model="formData.storageLimit" placeholder="如 100Gi" />
        </el-form-item>
        <el-form-item label="Pod 数量限制" prop="podLimit">
          <el-input v-model="formData.podLimit" placeholder="如 100" />
        </el-form-item>
        <el-form-item label="PVC 数量限制" prop="pvcLimit">
          <el-input v-model="formData.pvcLimit" placeholder="如 50" />
        </el-form-item>
        <el-form-item label="Service 数量限制" prop="serviceLimit">
          <el-input v-model="formData.serviceLimit" placeholder="如 20" />
        </el-form-item>

        <el-divider content-position="left">LimitRange（per-Pod 限制，可选）</el-divider>
        <el-form-item label="单 Pod 最大 CPU" prop="maxCpuPerPod">
          <el-input v-model="formData.maxCpuPerPod" placeholder="如 4（留空则不限制）" />
        </el-form-item>
        <el-form-item label="单 Pod 最大内存" prop="maxMemoryPerPod">
          <el-input v-model="formData.maxMemoryPerPod" placeholder="如 8Gi" />
        </el-form-item>
        <el-form-item label="单 Pod 最小 CPU" prop="minCpuPerPod">
          <el-input v-model="formData.minCpuPerPod" placeholder="如 100m" />
        </el-form-item>
        <el-form-item label="单 Pod 最小内存" prop="minMemoryPerPod">
          <el-input v-model="formData.minMemoryPerPod" placeholder="如 256Mi" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ isEdit ? '保存' : '设置' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 用量展示弹窗 -->
    <el-dialog v-model="usageDialogVisible" title="资源用量" width="640px">
      <div v-loading="usageLoading">
        <div v-if="usageData && Object.keys(usageData.hard || {}).length > 0">
          <div v-for="key in Object.keys(usageData.hard)" :key="key" class="usage-row">
            <span class="usage-label">{{ usageLabel(key) }}</span>
            <el-progress
              :percentage="usagePercent(key)"
              :color="usageColor(key)"
              :stroke-width="14"
              :text-inside="true"
            />
            <span class="usage-text">
              {{ usageData.used?.[key] || '0' }} / {{ usageData.hard?.[key] }}
            </span>
          </div>
        </div>
        <el-empty v-else description="暂无用量数据（可能未设置配额或 K8s ResourceQuota 不存在）" />
      </div>
      <template #footer>
        <el-button @click="usageDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="handleViewUsage(usageQuota!)">刷新</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as quotaApi from '@/api/quota'
import * as tenantApi from '@/api/tenant'
import * as workspaceApi from '@/api/workspace'
import type { Quota, Tenant, Workspace, QuotaUsage } from '@/api/types'

/* ------------------------------ 列表查询 ------------------------------ */

const filterTenantId = ref<string | ''>('')
const filterWorkspaceId = ref<string | ''>('')

// 配额列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: quotaList,
  loading,
  error,
  execute: loadList
} = useApi<Quota[]>(
  () =>
    quotaApi.listQuotas({
      tenantId: filterTenantId.value || undefined,
      workspaceId: filterWorkspaceId.value || undefined
    }),
  {
    initialData: [],
    onError: () => ElMessage.error('配额列表加载失败')
  }
)

// 下拉选项：通过 useApi 包装并行加载，失败时不阻塞页面
const { data: optionsData, execute: loadOptions } = useApi<[Tenant[], Workspace[]]>(
  () => Promise.all([tenantApi.listAllTenants(), workspaceApi.listAllWorkspaces()]),
  { initialData: [[], []] }
)
const tenantOptions = computed<Tenant[]>(() => optionsData.value?.[0] ?? [])
const workspaceOptions = computed<Workspace[]>(() => optionsData.value?.[1] ?? [])

/** Workspace ID → 名称 */
function workspaceName(id: string): string {
  const w = workspaceOptions.value.find((x) => x.id === id)
  return w?.name || `Workspace#${id}`
}

/** 搜索按钮 */
function handleSearch() {
  void loadList()
}

/* ------------------------------ 设置/编辑 ------------------------------ */

const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<string>('')

interface QuotaForm {
  workspaceId: string
  cpuLimit: string
  memoryLimit: string
  storageLimit: string
  podLimit: string
  pvcLimit: string
  serviceLimit: string
  maxCpuPerPod: string
  maxMemoryPerPod: string
  minCpuPerPod: string
  minMemoryPerPod: string
}

const formData = reactive<QuotaForm>({
  workspaceId: '',
  cpuLimit: '10',
  memoryLimit: '20Gi',
  storageLimit: '100Gi',
  podLimit: '100',
  pvcLimit: '50',
  serviceLimit: '20',
  maxCpuPerPod: '',
  maxMemoryPerPod: '',
  minCpuPerPod: '',
  minMemoryPerPod: ''
})

const formRules: FormRules = {
  workspaceId: [{ required: true, message: '请选择 Workspace', trigger: 'change' }],
  cpuLimit: [{ required: true, message: '请输入 CPU 限制', trigger: 'blur' }],
  memoryLimit: [{ required: true, message: '请输入内存限制', trigger: 'blur' }],
  storageLimit: [{ required: true, message: '请输入存储限制', trigger: 'blur' }],
  podLimit: [{ required: true, message: '请输入 Pod 数量限制', trigger: 'blur' }],
  pvcLimit: [{ required: true, message: '请输入 PVC 数量限制', trigger: 'blur' }],
  serviceLimit: [{ required: true, message: '请输入 Service 数量限制', trigger: 'blur' }]
}

/** 打开设置弹窗 */
function openCreateDialog() {
  isEdit.value = false
  editingId.value = ''
  resetForm()
  dialogVisible.value = true
}

/** 打开编辑弹窗 */
function openEditDialog(row: Quota) {
  isEdit.value = true
  editingId.value = row.id
  formData.workspaceId = row.workspaceId
  formData.cpuLimit = row.cpuLimit
  formData.memoryLimit = row.memoryLimit
  formData.storageLimit = row.storageLimit
  formData.podLimit = row.podLimit
  formData.pvcLimit = row.pvcLimit
  formData.serviceLimit = row.serviceLimit
  formData.maxCpuPerPod = row.maxCpuPerPod || ''
  formData.maxMemoryPerPod = row.maxMemoryPerPod || ''
  formData.minCpuPerPod = row.minCpuPerPod || ''
  formData.minMemoryPerPod = row.minMemoryPerPod || ''
  dialogVisible.value = true
}

/** 重置表单 */
function resetForm() {
  formData.workspaceId = ''
  formData.cpuLimit = '10'
  formData.memoryLimit = '20Gi'
  formData.storageLimit = '100Gi'
  formData.podLimit = '100'
  formData.pvcLimit = '50'
  formData.serviceLimit = '20'
  formData.maxCpuPerPod = ''
  formData.maxMemoryPerPod = ''
  formData.minCpuPerPod = ''
  formData.minMemoryPerPod = ''
  formRef.value?.clearValidate()
}

/** 提交表单 */
async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const payload = {
        cpuLimit: formData.cpuLimit,
        memoryLimit: formData.memoryLimit,
        storageLimit: formData.storageLimit,
        podLimit: formData.podLimit,
        pvcLimit: formData.pvcLimit,
        serviceLimit: formData.serviceLimit,
        maxCpuPerPod: formData.maxCpuPerPod || undefined,
        maxMemoryPerPod: formData.maxMemoryPerPod || undefined,
        minCpuPerPod: formData.minCpuPerPod || undefined,
        minMemoryPerPod: formData.minMemoryPerPod || undefined
      }
      if (isEdit.value) {
        await quotaApi.updateQuota(editingId.value, payload)
        ElMessage.success('配额已更新，K8s ResourceQuota + LimitRange 已同步')
      } else {
        // 设置配额需要 workspaceId/tenantId
        const ws = workspaceOptions.value.find((w) => w.id === formData.workspaceId)
        await quotaApi.setQuota({
          workspaceId: formData.workspaceId,
          tenantId: ws?.tenantId || '',
          ...payload
        })
        ElMessage.success('配额已设置，底层已翻译为 K8s ResourceQuota + LimitRange')
      }
      dialogVisible.value = false
      await loadList()
    } catch {
      // 错误提示已由拦截器统一处理
    } finally {
      submitting.value = false
    }
  })
}

/* ------------------------------ 删除 ------------------------------ */

/** 删除配额 */
async function handleDelete(row: Quota) {
  try {
    await ElMessageBox.confirm(
      `确定删除 Workspace「${workspaceName(row.workspaceId)}」的配额吗？\n该操作将级联删除 K8s ResourceQuota + LimitRange，不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await quotaApi.deleteQuota(row.id)
    ElMessage.success('配额已删除，K8s 资源已清除')
    await loadList()
  } catch (e) {
    // 用户取消或删除失败，不提示
  }
}

/* ------------------------------ 用量查询 ------------------------------ */

const usageDialogVisible = ref(false)
const usageQuota = ref<Quota | null>(null)

// 用量数据：通过 useApi 包装按需加载
const {
  data: usageData,
  loading: usageLoading,
  execute: loadUsage
} = useApi<QuotaUsage, [string]>((workspaceId: string) => quotaApi.getQuotaUsage(workspaceId), {
  onError: () => ElMessage.error('用量查询失败')
})

/** 查询用量 */
async function handleViewUsage(row: Quota) {
  usageQuota.value = row
  usageDialogVisible.value = true
  await loadUsage(row.workspaceId)
}

/** 用量百分比 */
function usagePercent(key: string): number {
  if (!usageData.value) return 0
  const used = parseQuantity(usageData.value.used?.[key] || '0')
  const hard = parseQuantity(usageData.value.hard?.[key] || '0')
  if (hard === 0) return 0
  return Math.min(100, Math.round((used / hard) * 100))
}

/** 用量颜色 */
function usageColor(key: string): string {
  const pct = usagePercent(key)
  if (pct >= 90) return '#f56c6c'
  if (pct >= 70) return '#e6a23c'
  return '#409eff'
}

/** 解析 K8s Quantity 为数字（粗略解析，仅用于百分比展示） */
function parseQuantity(s: string): number {
  if (!s) return 0
  // 毫核
  if (s.endsWith('m')) return parseFloat(s.slice(0, -1)) / 1000
  // 二进制后缀
  const binarySuffixes: Record<string, number> = {
    Ki: 1024,
    Mi: 1024 ** 2,
    Gi: 1024 ** 3,
    Ti: 1024 ** 4,
    Pi: 1024 ** 5,
    Ei: 1024 ** 6
  }
  for (const [suffix, factor] of Object.entries(binarySuffixes)) {
    if (s.endsWith(suffix)) return parseFloat(s.slice(0, -suffix.length)) * factor
  }
  // 十进制后缀
  const decimalSuffixes: Record<string, number> = {
    k: 1000,
    M: 1000 ** 2,
    G: 1000 ** 3,
    T: 1000 ** 4,
    P: 1000 ** 5,
    E: 1000 ** 6
  }
  for (const [suffix, factor] of Object.entries(decimalSuffixes)) {
    if (s.endsWith(suffix)) return parseFloat(s.slice(0, -suffix.length)) * factor
  }
  return parseFloat(s) || 0
}

/** 用量键名 → 中文标签 */
function usageLabel(key: string): string {
  const map: Record<string, string> = {
    'requests.cpu': 'CPU 请求',
    'requests.memory': '内存请求',
    'requests.storage': '存储请求',
    pods: 'Pod 数量',
    persistentvolumeclaims: 'PVC 数量',
    services: 'Service 数量'
  }
  return map[key] || key
}

/* ------------------------------ 标签辅助 ------------------------------ */

/** 状态 → 中文 */
function statusLabel(status: Quota['status']): string {
  const map: Record<string, string> = {
    SETTING: '设置中',
    ACTIVE: '活跃',
    UPDATING: '更新中',
    DELETING: '删除中',
    DELETED: '已删除',
    FAILED: '失败'
  }
  return map[status] || status
}

/** 状态 → tag 类型 */
function statusTagType(
  status: Quota['status']
): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  const map: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'primary'> = {
    SETTING: 'primary',
    ACTIVE: 'success',
    UPDATING: 'warning',
    DELETING: 'warning',
    DELETED: 'info',
    FAILED: 'danger'
  }
  return map[status] || 'info'
}

/* ------------------------------ 初始化 ------------------------------ */

const appStore = useAppStore()

// 工作空间切换时刷新配额列表（修复 #4）
watch(
  () => appStore.workspace,
  () => {
    filterWorkspaceId.value = ''
    void loadList()
  }
)

onMounted(() => {
  void loadList()
  void loadOptions()
})
</script>

<style scoped>
.quota-page {
  padding: 0;
}
.sub {
  color: #717a80;
  font-size: 13px;
  margin-bottom: 16px;
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
.mono {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #5a6470;
}
.usage-row {
  display: flex;
  align-items: center;
  margin-bottom: 16px;
  gap: 12px;
}
.usage-label {
  color: #606266;
  min-width: 100px;
  font-size: 13px;
}
.usage-text {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #5a6470;
  min-width: 140px;
  text-align: right;
}
</style>
