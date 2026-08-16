<template>
  <div class="tenant-page">
    <h1>租户管理</h1>
    <div class="sub">
      管理平台多租户的创建、配额、状态与基本信息，底层自动映射为 K8s Namespace 与资源配额。
    </div>

    <!-- 顶部操作栏 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar">
        <el-button type="primary" @click="openCreateDialog">+ 新建租户</el-button>
        <el-input
          v-model="searchKeyword"
          placeholder="按租户名称搜索"
          clearable
          style="width: 240px"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="filterStatus"
          placeholder="状态筛选"
          clearable
          style="width: 140px"
          @change="handleSearch"
        >
          <el-option label="活跃" value="active" />
          <el-option label="已暂停" value="suspended" />
          <el-option label="已删除" value="deleted" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle @click="loadList" />
      </div>

      <!-- 租户列表表格 -->
      <el-table
        v-loading="loading"
        :data="tenantList"
        stripe
        border
        style="width: 100%"
        :empty-text="error ? '加载失败，请重试' : '暂无租户数据'"
      >
        <el-table-column prop="id" label="ID" width="120" />
        <el-table-column prop="name" label="租户名称" min-width="160" />
        <el-table-column prop="code" label="租户编码" width="140" />
        <el-table-column label="套餐版本" width="120">
          <template #default="{ row }">
            <el-tag :type="planTagType(row.plan)" effect="light">
              {{ planLabel(row.plan) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="workspaceCount" label="工作空间数" width="120" align="center" />
        <el-table-column prop="userCount" label="用户数" width="100" align="center" />
        <el-table-column label="资源消耗" width="160">
          <template #default="{ row }">
            <el-progress
              :percentage="row.resourceUsage || 0"
              :color="usageColor(row.resourceUsage)"
              :stroke-width="8"
            />
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="180" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
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
      :title="isEdit ? '编辑租户' : '新建租户'"
      width="520px"
      :close-on-click-modal="false"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="100px"
        label-position="right"
      >
        <el-form-item label="租户名称" prop="name">
          <el-input v-model="formData.name" placeholder="如 华东生产集群" />
        </el-form-item>
        <el-form-item label="租户编码" prop="code">
          <el-input v-model="formData.code" placeholder="如 east-prod" :disabled="isEdit" />
        </el-form-item>
        <el-form-item label="套餐版本" prop="plan">
          <el-select v-model="formData.plan" style="width: 100%">
            <el-option label="标准版" value="standard" />
            <el-option label="企业版" value="enterprise" />
            <el-option label="旗舰版" value="flagship" />
            <el-option label="内部无限" value="internal" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isEdit" label="状态" prop="status">
          <el-select v-model="formData.status" style="width: 100%">
            <el-option label="活跃" value="active" />
            <el-option label="已暂停" value="suspended" />
            <el-option label="已删除" value="deleted" />
          </el-select>
        </el-form-item>
        <el-form-item label="联系人" prop="contact">
          <el-input v-model="formData.contact" placeholder="联系人姓名" />
        </el-form-item>
        <el-form-item label="联系电话" prop="contactPhone">
          <el-input v-model="formData.contactPhone" placeholder="联系电话" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="handleSubmit">
          {{ isEdit ? '保存' : '创建' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { useApi } from '@/composables/useApi'
import * as tenantApi from '@/api/tenant'
import type { Tenant, PlanTier, TenantStatus, PagedResult } from '@/api/types'

/* ------------------------------ 列表查询 ------------------------------ */

// 租户列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: tenantPaged,
  loading,
  error,
  execute: loadList
} = useApi<PagedResult<Tenant>>(
  () =>
    tenantApi.listTenants({
      keyword: searchKeyword.value || undefined,
      status: filterStatus.value || undefined,
      page: currentPage.value,
      pageSize: pageSize.value
    }),
  {
    onError: () => ElMessage.error('租户列表加载失败')
  }
)

const tenantList = computed<Tenant[]>(() => tenantPaged.value?.list ?? [])
const total = computed<number>(() => tenantPaged.value?.total ?? 0)
const currentPage = ref(1)
const pageSize = ref(20)
const searchKeyword = ref('')
const filterStatus = ref<TenantStatus | ''>('')

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

interface TenantForm {
  name: string
  code: string
  plan: PlanTier
  status: TenantStatus
  contact: string
  contactPhone: string
}

const formData = reactive<TenantForm>({
  name: '',
  code: '',
  plan: 'enterprise',
  status: 'active',
  contact: '',
  contactPhone: ''
})

const formRules: FormRules = {
  name: [{ required: true, message: '请输入租户名称', trigger: 'blur' }],
  code: [
    { required: true, message: '请输入租户编码', trigger: 'blur' },
    {
      pattern: /^[a-z][a-z0-9-]*$/,
      message: '小写字母开头，仅含小写字母、数字、连字符',
      trigger: 'blur'
    }
  ],
  plan: [{ required: true, message: '请选择套餐版本', trigger: 'change' }]
}

/** 打开新建弹窗 */
function openCreateDialog() {
  isEdit.value = false
  editingId.value = ''
  resetForm()
  dialogVisible.value = true
}

/** 打开编辑弹窗 */
function openEditDialog(row: Tenant) {
  isEdit.value = true
  editingId.value = row.id
  formData.name = row.name
  formData.code = row.code
  formData.plan = row.plan
  formData.status = row.status
  formData.contact = row.contact || ''
  formData.contactPhone = row.contactPhone || ''
  dialogVisible.value = true
}

/** 重置表单 */
function resetForm() {
  formData.name = ''
  formData.code = ''
  formData.plan = 'enterprise'
  formData.status = 'active'
  formData.contact = ''
  formData.contactPhone = ''
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
        await tenantApi.updateTenant(editingId.value, {
          name: formData.name,
          plan: formData.plan,
          status: formData.status,
          contact: formData.contact || undefined,
          contactPhone: formData.contactPhone || undefined
        })
        ElMessage.success('租户已更新')
      } else {
        await tenantApi.createTenant({
          name: formData.name,
          code: formData.code,
          plan: formData.plan,
          contact: formData.contact || undefined,
          contactPhone: formData.contactPhone || undefined
        })
        ElMessage.success('租户已创建')
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

/** 删除租户 */
async function handleDelete(row: Tenant) {
  try {
    await ElMessageBox.confirm(`确定删除租户「${row.name}」吗？该操作不可恢复。`, '删除确认', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
      confirmButtonClass: 'el-button--danger'
    })
    await tenantApi.deleteTenant(row.id)
    ElMessage.success('租户已删除')
    await loadList()
  } catch (e) {
    // 用户取消或删除失败，不提示
  }
}

/* ------------------------------ 标签辅助 ------------------------------ */

/** 套餐 → 中文 */
function planLabel(plan: PlanTier): string {
  const map: Record<PlanTier, string> = {
    standard: '标准版',
    enterprise: '企业版',
    flagship: '旗舰版',
    internal: '内部无限'
  }
  return map[plan] || plan
}

/** 套餐 → tag 类型 */
function planTagType(plan: PlanTier): 'primary' | 'success' | 'warning' | 'info' {
  const map: Record<PlanTier, 'primary' | 'success' | 'warning' | 'info'> = {
    standard: 'info',
    enterprise: 'primary',
    flagship: 'warning',
    internal: 'success'
  }
  return map[plan] || 'info'
}

/** 状态 → 中文 */
function statusLabel(status: TenantStatus): string {
  const map: Record<TenantStatus, string> = {
    active: '活跃',
    suspended: '已暂停',
    deleted: '已删除'
  }
  return map[status] || status
}

/** 状态 → tag 类型 */
function statusTagType(status: TenantStatus): 'success' | 'warning' | 'info' | 'danger' {
  const map: Record<TenantStatus, 'success' | 'warning' | 'info' | 'danger'> = {
    active: 'success',
    suspended: 'warning',
    deleted: 'info'
  }
  return map[status] || 'info'
}

/** 资源消耗 → 进度条颜色 */
function usageColor(percentage: number): string {
  if (percentage >= 90) return '#c0504d'
  if (percentage >= 70) return '#c08a2e'
  return '#2f9e6f'
}

/* ------------------------------ 初始化 ------------------------------ */

onMounted(() => {
  void loadList()
})
</script>

<style scoped>
.tenant-page {
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
</style>
