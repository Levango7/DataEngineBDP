<template>
  <div class="quota-page">
    <h1>{{ t('quotaManagement.title') }}</h1>
    <div class="sub">
      {{ t('quotaManagement.subtitle') }}
    </div>

    <!-- 顶部操作栏 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="openCreateDialog">
          {{ t('quotaManagement.toolbar.create') }}
        </el-button>
        <el-select
          v-model="filterTenantId"
          :placeholder="t('quotaManagement.toolbar.tenantFilterPlaceholder')"
          clearable
          style="width: 180px"
          @change="handleSearch"
        >
          <el-option v-for="tn in tenantOptions" :key="tn.id" :label="tn.name" :value="tn.id" />
        </el-select>
        <el-select
          v-model="filterWorkspaceId"
          :placeholder="t('quotaManagement.toolbar.workspaceFilterPlaceholder')"
          clearable
          filterable
          style="width: 220px"
          @change="handleSearch"
        >
          <el-option v-for="w in workspaceOptions" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <div class="spacer"></div>
        <el-button
          :icon="Refresh"
          circle
          :aria-label="t('quotaManagement.toolbar.refreshAria')"
          @click="loadList"
        />
      </div>

      <!-- 配额列表表格 -->
      <el-table
        v-loading="loading"
        :data="quotaList"
        stripe
        border
        style="width: 100%"
        :empty-text="
          error ? t('quotaManagement.table.loadFailed') : t('quotaManagement.table.empty')
        "
      >
        <el-table-column prop="id" :label="t('quotaManagement.table.columns.id')" width="80" />
        <el-table-column :label="t('quotaManagement.table.columns.workspace')" width="160">
          <template #default="{ row }">
            {{ workspaceName(row.workspaceId) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('quotaManagement.table.columns.cpu')" width="100">
          <template #default="{ row }">
            <span class="mono">{{ row.cpuLimit }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('quotaManagement.table.columns.memory')" width="110">
          <template #default="{ row }">
            <span class="mono">{{ row.memoryLimit }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('quotaManagement.table.columns.storage')" width="110">
          <template #default="{ row }">
            <span class="mono">{{ row.storageLimit }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('quotaManagement.table.columns.objectLimits')" width="140">
          <template #default="{ row }">
            <span class="mono">
              {{ row.podLimit }} / {{ row.pvcLimit }} / {{ row.serviceLimit }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('quotaManagement.table.columns.perPodMax')" width="140">
          <template #default="{ row }">
            <span class="mono">
              {{ row.maxCpuPerPod || '-' }} / {{ row.maxMemoryPerPod || '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('quotaManagement.table.columns.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column
          prop="updatedAt"
          :label="t('quotaManagement.table.columns.updatedAt')"
          width="180"
        />
        <el-table-column
          :label="t('quotaManagement.table.columns.actions')"
          width="240"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button link type="primary" @click="handleViewUsage(row)">
              {{ t('quotaManagement.table.actions.usage') }}
            </el-button>
            <el-button link type="primary" @click="openEditDialog(row)">
              {{ t('quotaManagement.table.actions.edit') }}
            </el-button>
            <el-button link type="danger" @click="handleDelete(row)">
              {{ t('quotaManagement.table.actions.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 设置/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="
        isEdit ? t('quotaManagement.dialog.editTitle') : t('quotaManagement.dialog.createTitle')
      "
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
        <el-divider content-position="left">
          {{ t('quotaManagement.dialog.sections.selectWorkspace') }}
        </el-divider>
        <el-form-item :label="t('quotaManagement.dialog.fields.workspace')" prop="workspaceId">
          <el-select
            v-model="formData.workspaceId"
            :placeholder="t('quotaManagement.dialog.fields.workspacePlaceholder')"
            style="width: 100%"
            :disabled="isEdit"
            filterable
          >
            <el-option v-for="w in workspaceOptions" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>

        <el-divider content-position="left">
          {{ t('quotaManagement.dialog.sections.resourceQuota') }}
        </el-divider>
        <el-form-item :label="t('quotaManagement.dialog.fields.cpuLimit')" prop="cpuLimit">
          <el-input
            v-model="formData.cpuLimit"
            :placeholder="t('quotaManagement.dialog.fields.cpuLimitPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('quotaManagement.dialog.fields.memoryLimit')" prop="memoryLimit">
          <el-input
            v-model="formData.memoryLimit"
            :placeholder="t('quotaManagement.dialog.fields.memoryLimitPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('quotaManagement.dialog.fields.storageLimit')" prop="storageLimit">
          <el-input
            v-model="formData.storageLimit"
            :placeholder="t('quotaManagement.dialog.fields.storageLimitPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('quotaManagement.dialog.fields.podLimit')" prop="podLimit">
          <el-input
            v-model="formData.podLimit"
            :placeholder="t('quotaManagement.dialog.fields.podLimitPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('quotaManagement.dialog.fields.pvcLimit')" prop="pvcLimit">
          <el-input
            v-model="formData.pvcLimit"
            :placeholder="t('quotaManagement.dialog.fields.pvcLimitPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('quotaManagement.dialog.fields.serviceLimit')" prop="serviceLimit">
          <el-input
            v-model="formData.serviceLimit"
            :placeholder="t('quotaManagement.dialog.fields.serviceLimitPlaceholder')"
          />
        </el-form-item>

        <el-divider content-position="left">
          {{ t('quotaManagement.dialog.sections.limitRange') }}
        </el-divider>
        <el-form-item :label="t('quotaManagement.dialog.fields.maxCpuPerPod')" prop="maxCpuPerPod">
          <el-input
            v-model="formData.maxCpuPerPod"
            :placeholder="t('quotaManagement.dialog.fields.maxCpuPerPodPlaceholder')"
          />
        </el-form-item>
        <el-form-item
          :label="t('quotaManagement.dialog.fields.maxMemoryPerPod')"
          prop="maxMemoryPerPod"
        >
          <el-input
            v-model="formData.maxMemoryPerPod"
            :placeholder="t('quotaManagement.dialog.fields.maxMemoryPerPodPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('quotaManagement.dialog.fields.minCpuPerPod')" prop="minCpuPerPod">
          <el-input
            v-model="formData.minCpuPerPod"
            :placeholder="t('quotaManagement.dialog.fields.minCpuPerPodPlaceholder')"
          />
        </el-form-item>
        <el-form-item
          :label="t('quotaManagement.dialog.fields.minMemoryPerPod')"
          prop="minMemoryPerPod"
        >
          <el-input
            v-model="formData.minMemoryPerPod"
            :placeholder="t('quotaManagement.dialog.fields.minMemoryPerPodPlaceholder')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">
          {{ t('quotaManagement.dialog.actions.cancel') }}
        </el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{
            isEdit
              ? t('quotaManagement.dialog.actions.save')
              : t('quotaManagement.dialog.actions.set')
          }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 用量展示弹窗 -->
    <el-dialog v-model="usageDialogVisible" :title="t('quotaManagement.usage.title')" width="640px">
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
        <el-empty v-else :description="t('quotaManagement.usage.empty')" />
      </div>
      <template #footer>
        <el-button @click="usageDialogVisible = false">
          {{ t('quotaManagement.usage.close') }}
        </el-button>
        <el-button type="primary" @click="handleViewUsage(usageQuota!)">
          {{ t('quotaManagement.usage.refresh') }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as quotaApi from '@/api/quota'
import * as tenantApi from '@/api/tenant'
import * as workspaceApi from '@/api/workspace'
import type { Quota, Tenant, Workspace, QuotaUsage } from '@/api/types'

const { t, te } = useI18n()

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
    onError: () => ElMessage.error(t('quotaManagement.messages.listLoadFailed'))
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
  return w?.name || t('quotaManagement.messages.workspaceFallback', { id })
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

const formRules = computed<FormRules>(() => ({
  workspaceId: [
    { required: true, message: t('quotaManagement.rules.workspaceRequired'), trigger: 'change' }
  ],
  cpuLimit: [
    { required: true, message: t('quotaManagement.rules.cpuLimitRequired'), trigger: 'blur' }
  ],
  memoryLimit: [
    { required: true, message: t('quotaManagement.rules.memoryLimitRequired'), trigger: 'blur' }
  ],
  storageLimit: [
    { required: true, message: t('quotaManagement.rules.storageLimitRequired'), trigger: 'blur' }
  ],
  podLimit: [
    { required: true, message: t('quotaManagement.rules.podLimitRequired'), trigger: 'blur' }
  ],
  pvcLimit: [
    { required: true, message: t('quotaManagement.rules.pvcLimitRequired'), trigger: 'blur' }
  ],
  serviceLimit: [
    { required: true, message: t('quotaManagement.rules.serviceLimitRequired'), trigger: 'blur' }
  ]
}))

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
        ElMessage.success(t('quotaManagement.messages.updated'))
      } else {
        // 设置配额需要 workspaceId/tenantId
        const ws = workspaceOptions.value.find((w) => w.id === formData.workspaceId)
        await quotaApi.setQuota({
          workspaceId: formData.workspaceId,
          tenantId: ws?.tenantId || '',
          ...payload
        })
        ElMessage.success(t('quotaManagement.messages.created'))
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
      t('quotaManagement.messages.deleteConfirm', { name: workspaceName(row.workspaceId) }),
      t('quotaManagement.messages.deleteConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('quotaManagement.messages.deleteConfirmOk'),
        cancelButtonText: t('quotaManagement.messages.deleteConfirmCancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await quotaApi.deleteQuota(row.id)
    ElMessage.success(t('quotaManagement.messages.deleted'))
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
  onError: () => ElMessage.error(t('quotaManagement.messages.usageFailed'))
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

/** 用量键名 → 词条（K8s key 含 `.`，映射为驼峰避免 i18n 嵌套） */
function usageLabel(key: string): string {
  const safeKey = key.replace(/\./g, '_').replace(/-/g, '_')
  const i18nKey = `quotaManagement.usageLabel.${safeKey}`
  return te(i18nKey) ? t(i18nKey) : key
}

/* ------------------------------ 标签辅助 ------------------------------ */

/** 状态 → 词条 */
function statusLabel(status: Quota['status']): string {
  const i18nKey = `quotaManagement.status.${status}`
  return te(i18nKey) ? t(i18nKey) : status
}

/** 状态 → tag 类型 */
const STATUS_TAG_TYPE_MAP: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'primary'> = {
  SETTING: 'primary',
  ACTIVE: 'success',
  UPDATING: 'warning',
  DELETING: 'warning',
  DELETED: 'info',
  FAILED: 'danger'
}

function statusTagType(
  status: Quota['status']
): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  return STATUS_TAG_TYPE_MAP[status] || 'info'
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
  color: var(--ds-text-secondary);
  font-size: 13px;
  margin-bottom: 16px;
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
  color: var(--ds-text-secondary);
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
