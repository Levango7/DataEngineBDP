<template>
  <div class="workspace-page">
    <h1>{{ t('workspaceManagement.title') }}</h1>
    <div class="sub">
      {{ t('workspaceManagement.subtitle') }}
    </div>

    <!-- 顶部操作栏 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="openCreateDialog">{{ t('workspaceManagement.toolbar.create') }}</el-button>
        <el-input
          v-model="searchKeyword"
          :placeholder="t('workspaceManagement.toolbar.searchPlaceholder')"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="filterTenantId"
          :placeholder="t('workspaceManagement.toolbar.tenantFilterPlaceholder')"
          clearable
          style="width: 180px"
          @change="handleSearch"
        >
          <el-option v-for="tn in tenantOptions" :key="tn.id" :label="tn.name" :value="tn.id" />
        </el-select>
        <el-select
          v-model="filterStatus"
          :placeholder="t('workspaceManagement.toolbar.statusFilterPlaceholder')"
          clearable
          style="width: 140px"
          @change="handleSearch"
        >
          <el-option :label="t('workspaceManagement.status.creating')" value="creating" />
          <el-option :label="t('workspaceManagement.status.running')" value="running" />
          <el-option :label="t('workspaceManagement.status.deleting')" value="deleting" />
          <el-option :label="t('workspaceManagement.status.deleted')" value="deleted" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle :aria-label="t('workspaceManagement.toolbar.refreshAria')" @click="loadList" />
      </div>

      <!-- 工作空间列表表格 -->
      <el-table
        v-loading="loading"
        :data="workspaceList"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? t('workspaceManagement.table.loadFailed') : t('workspaceManagement.table.empty')"
      >
        <el-table-column prop="id" :label="t('workspaceManagement.table.columns.id')" width="80" />
        <el-table-column prop="name" :label="t('workspaceManagement.table.columns.name')" min-width="160" />
        <el-table-column :label="t('workspaceManagement.table.columns.tenant')" width="120">
          <template #default="{ row }">
            {{ row.tenantName || t('workspaceManagement.table.columns.tenantFallback', { id: row.tenantId }) }}
          </template>
        </el-table-column>
        <el-table-column prop="namespace" :label="t('workspaceManagement.table.columns.namespace')" width="200" />
        <el-table-column :label="t('workspaceManagement.table.columns.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('workspaceManagement.table.columns.resourceQuota')" min-width="200">
          <template #default="{ row }">
            <span class="quota-text">{{ row.resourceQuota || t('workspaceManagement.table.columns.resourceQuotaDefault') }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="networkPolicy" :label="t('workspaceManagement.table.columns.networkPolicy')" width="140" />
        <el-table-column prop="createdAt" :label="t('workspaceManagement.table.columns.createdAt')" width="180" />
        <el-table-column :label="t('workspaceManagement.table.columns.actions')" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleViewStatus(row)">{{ t('workspaceManagement.table.actions.k8sStatus') }}</el-button>
            <el-button link type="primary" @click="openEditDialog(row)">{{ t('workspaceManagement.table.actions.edit') }}</el-button>
            <el-button link type="danger" @click="handleDelete(row)">{{ t('workspaceManagement.table.actions.delete') }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="loadList"
          @current-change="loadList"
        />
      </div>
    </el-card>

    <!-- 创建/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? t('workspaceManagement.dialog.editTitle') : t('workspaceManagement.dialog.createTitle')"
      width="560px"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="120px"
        label-position="right"
      >
        <el-form-item :label="t('workspaceManagement.dialog.fields.name')" prop="name">
          <el-input v-model="formData.name" :placeholder="t('workspaceManagement.dialog.fields.namePlaceholder')" />
        </el-form-item>
        <el-form-item :label="t('workspaceManagement.dialog.fields.tenant')" prop="tenantId">
          <el-select
            v-model="formData.tenantId"
            :placeholder="t('workspaceManagement.dialog.fields.tenantPlaceholder')"
            style="width: 100%"
            :disabled="isEdit"
          >
            <el-option v-for="tn in tenantOptions" :key="tn.id" :label="tn.name" :value="tn.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('workspaceManagement.dialog.fields.description')" prop="description">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="2"
            :placeholder="t('workspaceManagement.dialog.fields.descriptionPlaceholder')"
          />
        </el-form-item>
        <el-form-item :label="t('workspaceManagement.dialog.fields.resourceQuota')" prop="resourceQuota">
          <el-input v-model="formData.resourceQuota" :placeholder="t('workspaceManagement.dialog.fields.resourceQuotaPlaceholder')" />
          <div class="form-tip">{{ t('workspaceManagement.dialog.fields.resourceQuotaTip') }}</div>
        </el-form-item>
        <el-form-item :label="t('workspaceManagement.dialog.fields.networkPolicy')" prop="networkPolicy">
          <el-select v-model="formData.networkPolicy" style="width: 100%">
            <el-option :label="t('workspaceManagement.networkPolicy.tenantIsolated')" value="tenant-isolated" />
            <el-option :label="t('workspaceManagement.networkPolicy.denyAll')" value="deny-all" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('workspaceManagement.dialog.actions.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ isEdit ? t('workspaceManagement.dialog.actions.save') : t('workspaceManagement.dialog.actions.create') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- K8s 状态对话框 -->
    <el-dialog v-model="statusDialogVisible" :title="t('workspaceManagement.k8sDialog.title')" width="420px">
      <div v-loading="statusLoading">
        <div class="status-row">
          <span class="status-label">{{ t('workspaceManagement.k8sDialog.workspace') }}</span>
          <span>{{ statusWorkspace?.name }}</span>
        </div>
        <div class="status-row">
          <span class="status-label">{{ t('workspaceManagement.k8sDialog.namespace') }}</span>
          <span>{{ statusWorkspace?.namespace }}</span>
        </div>
        <div class="status-row">
          <span class="status-label">{{ t('workspaceManagement.k8sDialog.k8sStatus') }}</span>
          <el-tag :type="k8sStatusTagType(k8sStatus)" effect="light">
            {{ k8sStatus }}
          </el-tag>
        </div>
      </div>
      <template #footer>
        <el-button @click="statusDialogVisible = false">{{ t('workspaceManagement.k8sDialog.close') }}</el-button>
        <el-button type="primary" @click="handleViewStatus(statusWorkspace!)">{{ t('workspaceManagement.k8sDialog.refresh') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as workspaceApi from '@/api/workspace'
import * as tenantApi from '@/api/tenant'
import type { Workspace, Tenant, PagedResult } from '@/api/types'

const { t, te } = useI18n()

/* ------------------------------ 列表查询 ------------------------------ */

const currentPage = ref(1)
const pageSize = ref(20)
const searchKeyword = ref('')
const filterTenantId = ref<string | ''>('')
const filterStatus = ref<string | ''>('')

// 工作空间列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: wsPaged,
  loading,
  error,
  execute: loadList
} = useApi<PagedResult<Workspace>>(
  () =>
    workspaceApi.listWorkspaces({
      tenantId: filterTenantId.value || undefined,
      status: (filterStatus.value as Workspace['status']) || undefined,
      page: currentPage.value,
      pageSize: pageSize.value
    }),
  {
    onError: () => ElMessage.error(t('workspaceManagement.messages.listLoadFailed'))
  }
)

const workspaceList = computed<Workspace[]>(() => wsPaged.value?.list ?? [])
const total = computed<number>(() => wsPaged.value?.total ?? 0)

/** 租户下拉选项：通过 useApi 包装，失败时不阻塞页面 */
const { data: tenantOptions, execute: loadTenantOptions } = useApi<Tenant[]>(
  () => tenantApi.listAllTenants(),
  { initialData: [] }
)

/** 搜索按钮 */
function handleSearch() {
  currentPage.value = 1
  void loadList()
}

/* ------------------------------ 创建/编辑 ------------------------------ */

const dialogVisible = ref(false)
const isEdit = ref(false)
const submitting = ref(false)
const formRef = ref<FormInstance>()
const editingId = ref<string>('')

interface WorkspaceForm {
  name: string
  tenantId: string
  description: string
  resourceQuota: string
  networkPolicy: string
}

const formData = reactive<WorkspaceForm>({
  name: '',
  tenantId: '',
  description: '',
  resourceQuota: '',
  networkPolicy: 'tenant-isolated'
})

const formRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('workspaceManagement.rules.nameRequired'), trigger: 'blur' }],
  tenantId: [{ required: true, message: t('workspaceManagement.rules.tenantRequired'), trigger: 'change' }]
}))

/** 打开新建弹窗 */
function openCreateDialog() {
  isEdit.value = false
  editingId.value = ''
  resetForm()
  dialogVisible.value = true
}

/** 打开编辑弹窗 */
function openEditDialog(row: Workspace) {
  isEdit.value = true
  editingId.value = row.id
  formData.name = row.name
  formData.tenantId = row.tenantId
  formData.description = (row as { description?: string }).description || ''
  formData.resourceQuota = (row as { resourceQuota?: string }).resourceQuota || ''
  formData.networkPolicy = (row as { networkPolicy?: string }).networkPolicy || 'tenant-isolated'
  dialogVisible.value = true
}

/** 重置表单 */
function resetForm() {
  formData.name = ''
  formData.tenantId = ''
  formData.description = ''
  formData.resourceQuota = ''
  formData.networkPolicy = 'tenant-isolated'
  formRef.value?.clearValidate()
}

/** 提交表单 */
async function handleSubmit() {
  if (!formRef.value) return
  await formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      if (isEdit.value) {
        await workspaceApi.updateWorkspace(editingId.value, {
          name: formData.name,
          description: formData.description || undefined,
          resourceQuota: formData.resourceQuota || undefined,
          networkPolicy: formData.networkPolicy || undefined
        })
        ElMessage.success(t('workspaceManagement.messages.updated'))
      } else {
        await workspaceApi.createWorkspace({
          name: formData.name,
          tenantId: formData.tenantId,
          plan: 'enterprise',
          env: 'onprem'
        })
        ElMessage.success(t('workspaceManagement.messages.created'))
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

/** 删除工作空间 */
async function handleDelete(row: Workspace) {
  try {
    await ElMessageBox.confirm(
      t('workspaceManagement.messages.deleteConfirm', { name: row.name }),
      t('workspaceManagement.messages.deleteConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('workspaceManagement.messages.deleteConfirmOk'),
        cancelButtonText: t('workspaceManagement.messages.deleteConfirmCancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await workspaceApi.deleteWorkspace(row.id)
    ElMessage.success(t('workspaceManagement.messages.deleted'))
    await loadList()
  } catch (e) {
    // 用户取消或删除失败，不提示
  }
}

/* ------------------------------ K8s 状态查询 ------------------------------ */

const statusDialogVisible = ref(false)
const statusWorkspace = ref<Workspace | null>(null)

// K8s 状态：通过 useApi 包装按需加载
const {
  data: k8sStatusData,
  loading: statusLoading,
  execute: loadK8sStatus
} = useApi<{ status: string }, [string]>((id: string) => workspaceApi.getWorkspaceK8sStatus(id))
const k8sStatus = ref<string>('Unknown')

/** 查询 K8s Namespace 实时状态 */
async function handleViewStatus(row: Workspace) {
  statusWorkspace.value = row
  statusDialogVisible.value = true
  k8sStatus.value = 'Unknown'
  await loadK8sStatus(row.id)
  if (k8sStatusData.value) {
    k8sStatus.value = k8sStatusData.value.status
  } else {
    ElMessage.error(t('workspaceManagement.messages.k8sStatusFailed'))
  }
}

/* ------------------------------ 标签辅助 ------------------------------ */

/** 状态 → 词条 */
function statusLabel(status: Workspace['status']): string {
  const key = `workspaceManagement.status.${status}`
  return te(key) ? t(key) : status
}

/** 状态 → tag 类型 */
const STATUS_TAG_TYPE_MAP: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'primary'> = {
  creating: 'primary',
  running: 'success',
  active: 'success',
  limited: 'warning',
  stopped: 'info',
  deleting: 'warning',
  deleted: 'info',
  failed: 'danger'
}

function statusTagType(
  status: Workspace['status']
): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  return STATUS_TAG_TYPE_MAP[status] || 'info'
}

/** K8s 状态 → tag 类型 */
function k8sStatusTagType(status: string): 'success' | 'warning' | 'info' | 'danger' {
  switch (status) {
    case 'Active':
      return 'success'
    case 'Terminating':
      return 'warning'
    case 'NotFound':
      return 'info'
    default:
      return 'danger'
  }
}

/* ------------------------------ 初始化 ------------------------------ */

onMounted(() => {
  void loadList()
  void loadTenantOptions()
})
</script>

<style scoped>
.workspace-page {
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
.pagination-wrap {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
.quota-text {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  color: #5a6470;
}
.form-tip {
  font-size: 12px;
  color: var(--ds-text-muted, var(--ds-text-secondary));
  margin-top: 4px;
  line-height: 1.4;
}
.status-row {
  display: flex;
  align-items: center;
  margin-bottom: 12px;
  gap: 8px;
}
.status-label {
  color: var(--ds-text-secondary);
  min-width: 90px;
}
</style>
