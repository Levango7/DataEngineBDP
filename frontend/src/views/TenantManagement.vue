<template>
  <div class="tenant-page" role="main" :aria-label="t('tenantManagement.title')">
    <h1>{{ t('tenantManagement.title') }}</h1>
    <div class="sub">
      {{ t('tenantManagement.subtitle') }}
    </div>

    <!-- 顶部操作栏 -->
    <el-card shadow="never" class="page-card">
      <div class="toolbar" role="toolbar" :aria-label="t('tenantManagement.title')">
        <el-button type="primary" :aria-label="t('tenantManagement.dialog.createTitle')" @click="openCreateDialog">
          {{ t('tenantManagement.toolbar.create') }}
        </el-button>
        <el-input
          v-model="searchKeyword"
          :placeholder="t('tenantManagement.toolbar.searchPlaceholder')"
          clearable
          style="width: 240px"
          :aria-label="t('tenantManagement.toolbar.searchPlaceholder')"
          @keyup.enter="handleSearch"
          @clear="handleSearch"
        />
        <el-select
          v-model="filterStatus"
          :placeholder="t('tenantManagement.toolbar.statusFilterPlaceholder')"
          clearable
          style="width: 140px"
          :aria-label="t('tenantManagement.toolbar.statusFilterPlaceholder')"
          @change="handleSearch"
        >
          <el-option :label="t('tenantManagement.status.active')" value="active" />
          <el-option :label="t('tenantManagement.status.suspended')" value="suspended" />
          <el-option :label="t('tenantManagement.status.deleted')" value="deleted" />
        </el-select>
        <div class="spacer"></div>
        <el-button :icon="Refresh" circle :aria-label="t('tenantManagement.toolbar.refreshAria')" @click="loadList" />
      </div>

      <!-- 租户列表表格 -->
      <el-table
        v-loading="loading"
        :data="tenantList"
        stripe
        border
        style="width: 100%"
        role="table"
        :aria-label="t('tenantManagement.table.aria')"
        :empty-text="error ? t('tenantManagement.table.loadFailed') : t('tenantManagement.table.empty')"
      >
        <el-table-column prop="id" :label="t('tenantManagement.table.columns.id')" width="120" />
        <el-table-column prop="name" :label="t('tenantManagement.table.columns.name')" min-width="160" />
        <el-table-column prop="code" :label="t('tenantManagement.table.columns.code')" width="140" />
        <el-table-column :label="t('tenantManagement.table.columns.plan')" width="120">
          <template #default="{ row }">
            <el-tag :type="planTagType(row.plan)" effect="light">
              {{ planLabel(row.plan) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('tenantManagement.table.columns.status')" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="light">
              {{ statusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="workspaceCount" :label="t('tenantManagement.table.columns.workspaceCount')" width="120" align="center" />
        <el-table-column prop="userCount" :label="t('tenantManagement.table.columns.userCount')" width="100" align="center" />
        <el-table-column :label="t('tenantManagement.table.columns.resourceUsage')" width="160">
          <template #default="{ row }">
            <el-progress
              :percentage="row.resourceUsage || 0"
              :color="usageColor(row.resourceUsage)"
              :stroke-width="8"
            />
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" :label="t('tenantManagement.table.columns.createdAt')" width="180" />
        <el-table-column :label="t('tenantManagement.table.columns.actions')" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              :aria-label="t('tenantManagement.table.actions.editAria', { name: row.name })"
              @click="openEditDialog(row)"
            >
              {{ t('tenantManagement.table.actions.edit') }}
            </el-button>
            <el-button
              link
              type="danger"
              :aria-label="t('tenantManagement.table.actions.deleteAria', { name: row.name })"
              @click="handleDelete(row)"
            >
              {{ t('tenantManagement.table.actions.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <div class="pagination-wrap" role="navigation" :aria-label="t('tenantManagement.table.paginationAria')">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          background
          :aria-label="t('tenantManagement.table.paginationAria')"
          @size-change="loadList"
          @current-change="loadList"
        />
      </div>
    </el-card>

    <!-- 创建/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="isEdit ? t('tenantManagement.dialog.editTitle') : t('tenantManagement.dialog.createTitle')"
      width="520px"
      :close-on-click-modal="false"
      role="dialog"
      aria-modal="true"
      :aria-label="isEdit ? t('tenantManagement.dialog.editAria') : t('tenantManagement.dialog.createAria')"
      @closed="resetForm"
    >
      <el-form
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="100px"
        label-position="right"
      >
        <el-form-item :label="t('tenantManagement.dialog.fields.name')" prop="name">
          <el-input v-model="formData.name" :placeholder="t('tenantManagement.dialog.fields.namePlaceholder')" :aria-label="t('tenantManagement.dialog.fields.name')" />
        </el-form-item>
        <el-form-item :label="t('tenantManagement.dialog.fields.code')" prop="code">
          <el-input
            v-model="formData.code"
            :placeholder="t('tenantManagement.dialog.fields.codePlaceholder')"
            :disabled="isEdit"
            :aria-label="t('tenantManagement.dialog.fields.code')"
          />
        </el-form-item>
        <el-form-item :label="t('tenantManagement.dialog.fields.plan')" prop="plan">
          <el-select v-model="formData.plan" style="width: 100%" :aria-label="t('tenantManagement.dialog.fields.plan')">
            <el-option :label="t('tenantManagement.plan.standard')" value="standard" />
            <el-option :label="t('tenantManagement.plan.enterprise')" value="enterprise" />
            <el-option :label="t('tenantManagement.plan.flagship')" value="flagship" />
            <el-option :label="t('tenantManagement.plan.internal')" value="internal" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isEdit" :label="t('tenantManagement.dialog.fields.status')" prop="status">
          <el-select v-model="formData.status" style="width: 100%" :aria-label="t('tenantManagement.dialog.fields.status')">
            <el-option :label="t('tenantManagement.status.active')" value="active" />
            <el-option :label="t('tenantManagement.status.suspended')" value="suspended" />
            <el-option :label="t('tenantManagement.status.deleted')" value="deleted" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('tenantManagement.dialog.fields.contact')" prop="contact">
          <el-input v-model="formData.contact" :placeholder="t('tenantManagement.dialog.fields.contactPlaceholder')" :aria-label="t('tenantManagement.dialog.fields.contact')" />
        </el-form-item>
        <el-form-item :label="t('tenantManagement.dialog.fields.contactPhone')" prop="contactPhone">
          <el-input v-model="formData.contactPhone" :placeholder="t('tenantManagement.dialog.fields.contactPhonePlaceholder')" :aria-label="t('tenantManagement.dialog.fields.contactPhone')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :aria-label="t('tenantManagement.dialog.actions.cancelAria')" @click="dialogVisible = false">{{ t('tenantManagement.dialog.actions.cancel') }}</el-button>
        <el-button
          type="primary"
          :loading="submitting"
          :aria-label="isEdit ? t('tenantManagement.dialog.actions.saveAria') : t('tenantManagement.dialog.actions.createAriaBtn')"
          @click="handleSubmit"
        >
          {{ isEdit ? t('tenantManagement.dialog.actions.save') : t('tenantManagement.dialog.actions.create') }}
        </el-button>
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
import * as tenantApi from '@/api/tenant'
import type { Tenant, PlanTier, TenantStatus, PagedResult } from '@/api/types'

const { t } = useI18n()

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
    onError: () => ElMessage.error(t('tenantManagement.messages.listLoadFailed'))
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

const formRules = computed<FormRules>(() => ({
  name: [{ required: true, message: t('tenantManagement.rules.nameRequired'), trigger: 'blur' }],
  code: [
    { required: true, message: t('tenantManagement.rules.codeRequired'), trigger: 'blur' },
    {
      pattern: /^[a-z][a-z0-9-]*$/,
      message: t('tenantManagement.rules.codePattern'),
      trigger: 'blur'
    }
  ],
  plan: [{ required: true, message: t('tenantManagement.rules.planRequired'), trigger: 'change' }]
}))

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
        ElMessage.success(t('tenantManagement.messages.updated'))
      } else {
        await tenantApi.createTenant({
          name: formData.name,
          code: formData.code,
          plan: formData.plan,
          contact: formData.contact || undefined,
          contactPhone: formData.contactPhone || undefined
        })
        ElMessage.success(t('tenantManagement.messages.created'))
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
    await ElMessageBox.confirm(
      t('tenantManagement.messages.deleteConfirm', { name: row.name }),
      t('tenantManagement.messages.deleteConfirmTitle'),
      {
        type: 'warning',
        confirmButtonText: t('tenantManagement.messages.deleteConfirmOk'),
        cancelButtonText: t('tenantManagement.messages.deleteConfirmCancel'),
        confirmButtonClass: 'el-button--danger'
      }
    )
    await tenantApi.deleteTenant(row.id)
    ElMessage.success(t('tenantManagement.messages.deleted'))
    await loadList()
  } catch (e) {
    // 用户取消或删除失败，不提示
  }
}

/* ------------------------------ 标签辅助 ------------------------------ */

const PLAN_TAG_TYPE_MAP: Record<PlanTier, 'primary' | 'success' | 'warning' | 'info'> = {
  standard: 'info',
  enterprise: 'primary',
  flagship: 'warning',
  internal: 'success'
}

function planLabel(plan: PlanTier): string {
  return t(`tenantManagement.plan.${plan}`)
}

function planTagType(plan: PlanTier): 'primary' | 'success' | 'warning' | 'info' {
  return PLAN_TAG_TYPE_MAP[plan] ?? 'info'
}

const STATUS_TAG_TYPE_MAP: Record<TenantStatus, 'success' | 'warning' | 'info' | 'danger'> = {
  active: 'success',
  suspended: 'warning',
  deleted: 'info'
}

function statusLabel(status: TenantStatus): string {
  return t(`tenantManagement.status.${status}`)
}

function statusTagType(status: TenantStatus): 'success' | 'warning' | 'info' | 'danger' {
  return STATUS_TAG_TYPE_MAP[status] ?? 'info'
}

/** 资源消耗 → 进度条颜色 */
function usageColor(percentage: number): string {
  if (percentage >= 90) return 'var(--ds-color-error-600)'
  if (percentage >= 70) return 'var(--ds-color-warning-600)'
  return 'var(--ds-color-success-600)'
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
</style>
