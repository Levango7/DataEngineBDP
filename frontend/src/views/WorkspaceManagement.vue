<template>
  <div class="workspace-page">
    <h1>工作空间管理</h1>
    <div class="sub">
      管理租户下的工作空间（隔离边界），底层自动翻译为 K8s Namespace + NetworkPolicy + RBAC + ResourceQuota，客户无需感知容器编排。
    </div>

    <!-- 顶部操作栏 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="openCreateDialog">+ 新建工作空间</el-button>
        <el-input
          v-model="searchKeyword"
          placeholder="按名称搜索"
          clearable
          style="width: 220px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="filterTenantId"
          placeholder="租户筛选"
          clearable
          style="width: 180px"
          @change="handleSearch"
        >
          <el-option
            v-for="t in tenantOptions"
            :key="t.id"
            :label="t.name"
            :value="t.id"
          />
        </el-select>
        <el-select
          v-model="filterStatus"
          placeholder="状态筛选"
          clearable
          style="width: 140px"
          @change="handleSearch"
        >
          <el-option label="创建中" value="creating" />
          <el-option label="运行中" value="running" />
          <el-option label="删除中" value="deleting" />
          <el-option label="已删除" value="deleted" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="loadList" />
      </div>

      <!-- 工作空间列表表格 -->
      <el-table
        v-loading="loading"
        :data="workspaceList"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? '加载失败，请重试' : '暂无工作空间数据'"
      >
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="名称" min-width="160" />
        <el-table-column label="租户" width="120">
          <template #default="{ row }">
            {{ row.tenantName || `租户#${row.tenantId}` }}
          </template>
        </el-table-column>
        <el-table-column prop="namespace" label="K8s Namespace" width="200" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="资源配额" min-width="200">
          <template #default="{ row }">
            <span class="quota-text">{{ row.resourceQuota || '默认' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="networkPolicy" label="网络策略" width="140" />
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleViewStatus(row)">K8s状态</el-button>
            <el-button link type="primary" @click="openEditDialog(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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
      :title="isEdit ? '编辑工作空间' : '新建工作空间'"
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
        <el-form-item label="名称" prop="name">
          <el-input v-model="formData.name" placeholder="如 华南生产工作空间" />
        </el-form-item>
        <el-form-item label="所属租户" prop="tenantId">
          <el-select
            v-model="formData.tenantId"
            placeholder="选择租户"
            style="width: 100%"
            :disabled="isEdit"
          >
            <el-option
              v-for="t in tenantOptions"
              :key="t.id"
              :label="t.name"
              :value="t.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input
            v-model="formData.description"
            type="textarea"
            :rows="2"
            placeholder="工作空间用途描述"
          />
        </el-form-item>
        <el-form-item label="资源配额" prop="resourceQuota">
          <el-input
            v-model="formData.resourceQuota"
            placeholder="cpu=4,memory=8Gi,storage=100Gi"
          />
          <div class="form-tip">格式：cpu=4,memory=8Gi,storage=100Gi（留空使用默认配额）</div>
        </el-form-item>
        <el-form-item label="网络隔离策略" prop="networkPolicy">
          <el-select v-model="formData.networkPolicy" style="width: 100%">
            <el-option label="租户内互通（tenant-isolated）" value="tenant-isolated" />
            <el-option label="全部拒绝（deny-all）" value="deny-all" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- K8s 状态对话框 -->
    <el-dialog v-model="statusDialogVisible" title="K8s Namespace 实时状态" width="420px">
      <div v-loading="statusLoading">
        <div class="status-row">
          <span class="status-label">工作空间：</span>
          <span>{{ statusWorkspace?.name }}</span>
        </div>
        <div class="status-row">
          <span class="status-label">Namespace：</span>
          <span>{{ statusWorkspace?.namespace }}</span>
        </div>
        <div class="status-row">
          <span class="status-label">K8s 状态：</span>
          <el-tag :type="k8sStatusTagType(k8sStatus)" effect="light">
            {{ k8sStatus }}
          </el-tag>
        </div>
      </div>
      <template #footer>
        <el-button @click="statusDialogVisible = false">关闭</el-button>
        <el-button type="primary" @click="handleViewStatus(statusWorkspace!)">刷新</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as workspaceApi from '@/api/workspace'
import * as tenantApi from '@/api/tenant'
import type { Workspace, Tenant, PagedResult } from '@/api/types'

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
    onError: () => ElMessage.error('工作空间列表加载失败')
  }
)

const workspaceList = computed<Workspace[]>(() => wsPaged.value?.list ?? [])
const total = computed<number>(() => wsPaged.value?.total ?? 0)

/** 租户下拉选项：通过 useApi 包装，失败时不阻塞页面 */
const {
  data: tenantOptions,
  execute: loadTenantOptions
} = useApi<Tenant[]>(() => tenantApi.listAllTenants(), { initialData: [] })

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

const formRules: FormRules = {
  name: [{ required: true, message: '请输入工作空间名称', trigger: 'blur' }],
  tenantId: [{ required: true, message: '请选择所属租户', trigger: 'change' }]
}

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
        ElMessage.success('工作空间已更新')
      } else {
        await workspaceApi.createWorkspace({
          name: formData.name,
          tenantId: formData.tenantId,
          plan: 'enterprise',
          env: 'onprem'
        })
        ElMessage.success('工作空间已创建，底层已翻译为 K8s Namespace + NetworkPolicy + RBAC + ResourceQuota')
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
      `确定删除工作空间「${row.name}」吗？\n该操作将级联删除 K8s Namespace 及其下全部资源，不可恢复。`,
      '删除确认',
      {
        type: 'warning',
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger'
      }
    )
    await workspaceApi.deleteWorkspace(row.id)
    ElMessage.success('工作空间已删除，K8s Namespace 已级联清除')
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
} = useApi<{ status: string }, [string]>(
  (id: string) => workspaceApi.getWorkspaceK8sStatus(id)
)
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
    ElMessage.error('K8s 状态查询失败')
  }
}

/* ------------------------------ 标签辅助 ------------------------------ */

/** 状态 → 中文 */
function statusLabel(status: Workspace['status']): string {
  const map: Record<string, string> = {
    creating: '创建中',
    running: '运行中',
    active: '运行中',
    limited: '受限',
    stopped: '已停止',
    deleting: '删除中',
    deleted: '已删除',
    failed: '失败'
  }
  return map[status] || status
}

/** 状态 → tag 类型 */
function statusTagType(status: Workspace['status']): 'success' | 'warning' | 'info' | 'danger' | 'primary' {
  const map: Record<string, 'success' | 'warning' | 'info' | 'danger' | 'primary'> = {
    creating: 'primary',
    running: 'success',
    active: 'success',
    limited: 'warning',
    stopped: 'info',
    deleting: 'warning',
    deleted: 'info',
    failed: 'danger'
  }
  return map[status] || 'info'
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
  color: #909399;
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
  color: #606266;
  min-width: 90px;
}
</style>