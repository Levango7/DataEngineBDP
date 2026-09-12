<template>
  <div class="tenant-page" role="main" :aria-label="t('tenantManagement.title')">
    <PageHeader :title="t('tenantManagement.title')" :subtitle="t('tenantManagement.subtitle')" />

    <!-- 平台超管四联指标卡（数据平台质感） -->
    <div class="tenant-stats" role="list" :aria-label="t('tenantManagement.stats.aria')">
      <div class="stat" role="listitem">
        <div class="stat-num">{{ admin.totalTenantCount }}</div>
        <div class="stat-lbl">{{ t('tenantManagement.stats.total') }}</div>
      </div>
      <div class="stat stat-on" role="listitem">
        <div class="stat-num">{{ admin.activeTenantCount }}</div>
        <div class="stat-lbl">{{ t('tenantManagement.stats.active') }}</div>
      </div>
      <div class="stat" role="listitem">
        <div class="stat-num">{{ admin.pendingInviteCount }}</div>
        <div class="stat-lbl">{{ t('tenantManagement.stats.pendingInvite') }}</div>
      </div>
      <div class="stat stat-warn" role="listitem">
        <div class="stat-num">{{ admin.pendingRegCount }}</div>
        <div class="stat-lbl">{{ t('tenantManagement.stats.pendingReg') }}</div>
      </div>
    </div>

    <PageCard>
      <Toolbar
        v-model:search-value="searchKeyword"
        :aria-label="t('tenantManagement.title')"
        :show-create="true"
        :create-label="t('tenantManagement.toolbar.create')"
        :create-aria-label="t('tenantManagement.dialog.createTitle')"
        :search-placeholder="t('tenantManagement.toolbar.searchPlaceholder')"
        :show-refresh="true"
        @create="openCreateDialog"
        @search="handleSearch"
        @refresh="loadList"
      >
        <template #filters>
          <el-select
            v-model="filterType"
            :placeholder="t('tenantManagement.typeFilter.placeholder')"
            clearable
            style="width: 140px"
          >
            <el-option :label="t('tenantManagement.type.xinchuang')" value="XINCHUANG" />
            <el-option :label="t('tenantManagement.type.private')" value="PRIVATE" />
            <el-option :label="t('tenantManagement.type.public')" value="PUBLIC" />
            <el-option :label="t('tenantManagement.type.government')" value="GOVERNMENT" />
            <el-option :label="t('tenantManagement.type.internal')" value="INTERNAL" />
          </el-select>
          <el-select
            v-model="filterStatus"
            :placeholder="t('tenantManagement.toolbar.statusFilterPlaceholder')"
            clearable
            style="width: 140px"
          >
            <el-option :label="t('tenantManagement.statusFilter.active')" value="ACTIVE" />
            <el-option :label="t('tenantManagement.statusFilter.inactive')" value="INACTIVE" />
            <el-option :label="t('tenantManagement.statusFilter.creating')" value="CREATING" />
            <el-option :label="t('tenantManagement.statusFilter.suspended')" value="SUSPENDED" />
          </el-select>
        </template>
      </Toolbar>

      <!-- 租户列表表格 -->
      <el-table
        v-loading="loading"
        :data="filteredTenants"
        stripe
        border
        style="width: 100%"
        role="table"
        :aria-label="t('tenantManagement.table.aria')"
        :empty-text="t('tenantManagement.table.empty')"
      >
        <el-table-column label="ID" prop="id" width="80" />
        <el-table-column
          :label="t('tenantManagement.table.columns.code')"
          prop="name"
          width="140"
        />
        <el-table-column
          :label="t('tenantManagement.table.columns.displayName')"
          prop="displayName"
          min-width="160"
        />
        <el-table-column :label="t('tenantManagement.table.columns.type')" width="120">
          <template #default="{ row }">
            <StatusTag :status="row.type" :label="typeLabel(row.type)" :status-map="TYPE_TAG_MAP" />
          </template>
        </el-table-column>
        <el-table-column
          :label="t('tenantManagement.table.columns.quotaProfile')"
          prop="quotaProfile"
          width="120"
        >
          <template #default="{ row }">
            <el-tag effect="light">{{ row.quotaProfile }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('tenantManagement.table.columns.userCount')"
          prop="userCount"
          width="90"
          align="center"
        />
        <el-table-column
          :label="t('tenantManagement.table.columns.storageQuota')"
          width="110"
          align="center"
        >
          <template #default="{ row }">{{ row.storageQuotaGb }} GB</template>
        </el-table-column>
        <el-table-column :label="t('tenantManagement.table.columns.status')" width="110">
          <template #default="{ row }">
            <StatusTag
              :status="row.status"
              :label="statusLabel(row.status)"
              :status-map="STATUS_TAG_MAP"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('tenantManagement.table.columns.createdAt')" width="180">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column
          :label="t('tenantManagement.table.columns.actions')"
          width="220"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">
              {{ t('tenantManagement.table.actions.view') }}
            </el-button>
            <el-button link type="success" @click="openInviteDialog(row)">
              {{ t('tenantManagement.table.actions.invite') }}
            </el-button>
            <el-button
              v-if="row.status === 'ACTIVE'"
              link
              type="warning"
              @click="handleSuspend(row)"
            >
              {{ t('tenantManagement.table.actions.suspend') }}
            </el-button>
            <el-button
              v-else-if="row.status === 'SUSPENDED' || row.status === 'INACTIVE'"
              link
              type="success"
              @click="handleActivate(row)"
            >
              {{ t('tenantManagement.table.actions.resume') }}
            </el-button>
            <el-button link type="danger" @click="handleDelete(row)">
              {{ t('tenantManagement.table.actions.delete') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <!-- 创建租户弹窗 -->
    <el-dialog
      v-model="createVisible"
      :title="t('tenantManagement.dialog.createTitle')"
      width="540px"
      :close-on-click-modal="false"
      @closed="resetCreateForm"
    >
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="100px">
        <el-form-item :label="t('tenantManagement.dialog.code')" prop="name">
          <el-input
            v-model="createForm.name"
            :placeholder="t('tenantManagement.dialog.codeHint')"
          />
        </el-form-item>
        <el-form-item :label="t('tenantManagement.dialog.displayName')" prop="displayName">
          <el-input
            v-model="createForm.displayName"
            :placeholder="t('tenantManagement.dialog.displayNameHint')"
          />
        </el-form-item>
        <el-form-item :label="t('tenantManagement.dialog.type')" prop="type">
          <el-select v-model="createForm.type" style="width: 100%">
            <el-option :label="t('tenantManagement.type.xinchuang')" value="XINCHUANG" />
            <el-option :label="t('tenantManagement.type.private')" value="PRIVATE" />
            <el-option :label="t('tenantManagement.type.public')" value="PUBLIC" />
            <el-option :label="t('tenantManagement.type.government')" value="GOVERNMENT" />
            <el-option :label="t('tenantManagement.type.internal')" value="INTERNAL" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('tenantManagement.dialog.quotaProfile')" prop="quotaProfile">
          <el-select v-model="createForm.quotaProfile" style="width: 100%">
            <el-option :label="t('tenantManagement.quota.small')" value="small" />
            <el-option :label="t('tenantManagement.quota.medium')" value="medium" />
            <el-option :label="t('tenantManagement.quota.large')" value="large" />
            <el-option :label="t('tenantManagement.quota.xlarge')" value="xlarge" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('tenantManagement.dialog.storageQuota')" prop="storageQuotaGb">
          <el-input-number
            v-model="createForm.storageQuotaGb"
            :min="10"
            :max="100000"
            :step="100"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreate">
          {{ t('tenantManagement.dialog.createSubmit') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 发邀请弹窗 -->
    <el-dialog
      v-model="inviteVisible"
      :title="t('tenantManagement.invite.title')"
      width="480px"
      :close-on-click-modal="false"
    >
      <el-form :model="inviteForm" label-width="100px">
        <el-form-item :label="t('tenantManagement.invite.tenant')">
          <el-tag type="info">{{ inviteForm.tenantName }}（ID: {{ inviteForm.tenantId }}）</el-tag>
        </el-form-item>
        <el-form-item :label="t('tenantManagement.invite.role')">
          <el-select v-model="inviteForm.role" style="width: 100%">
            <el-option :label="t('tenantManagement.invite.roleTenantAdmin')" value="TENANT_ADMIN" />
            <el-option :label="t('tenantManagement.invite.roleUser')" value="USER" />
            <el-option
              :label="t('tenantManagement.invite.rolePlatformAdmin')"
              value="PLATFORM_ADMIN"
            />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('tenantManagement.invite.days')">
          <el-input-number v-model="inviteForm.ttlDays" :min="1" :max="30" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('tenantManagement.invite.note')">
          <el-input
            v-model="inviteForm.note"
            :placeholder="t('tenantManagement.invite.noteHint')"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="inviteVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="handleCreateInvite">
          {{ t('tenantManagement.invite.generate') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 创建成功 → 展示新邀请码（可复制/重发） -->
    <el-dialog v-model="successVisible" :title="t('tenantManagement.success.title')" width="480px">
      <div class="invite-result">
        <p class="invite-hint">{{ t('tenantManagement.success.hint') }}</p>
        <div
          class="invite-code-box"
          :aria-label="t('tenantManagement.success.codeAria', { code: successCode })"
        >
          <span class="invite-code-text">{{ successCode }}</span>
          <el-button type="primary" link @click="copyCode">
            {{ copied ? t('tenantManagement.success.copied') : t('tenantManagement.success.copy') }}
          </el-button>
        </div>
        <ul class="invite-meta">
          <li>{{ t('tenantManagement.success.tenant') }}：{{ successTenant }}</li>
          <li>{{ t('tenantManagement.success.role') }}：{{ successRole }}</li>
          <li>{{ t('tenantManagement.success.expiry') }}：{{ successExpiry }}</li>
        </ul>
        <p class="invite-hint-sm">{{ t('tenantManagement.success.hintSm') }}</p>
      </div>
      <template #footer>
        <el-button @click="successVisible = false">
          {{ t('tenantManagement.success.close') }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" :title="detail?.displayName" width="640px">
      <div v-if="detail" class="tenant-detail">
        <h4>{{ t('tenantManagement.detail.basic') }}</h4>
        <el-descriptions :column="2" border>
          <el-descriptions-item :label="t('tenantManagement.detail.tenantId')">
            {{ detail.id }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('tenantManagement.detail.code')">
            {{ detail.name }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('tenantManagement.detail.displayName')">
            {{ detail.displayName }}
          </el-descriptions-item>
          <el-descriptions-item label="K8s namespace">{{ detail.namespace }}</el-descriptions-item>
          <el-descriptions-item :label="t('tenantManagement.detail.type')">
            <StatusTag
              :status="detail.type"
              :label="typeLabel(detail.type)"
              :status-map="TYPE_TAG_MAP"
            />
          </el-descriptions-item>
          <el-descriptions-item :label="t('tenantManagement.detail.status')">
            <StatusTag
              :status="detail.status"
              :label="statusLabel(detail.status)"
              :status-map="STATUS_TAG_MAP"
            />
          </el-descriptions-item>
          <el-descriptions-item :label="t('tenantManagement.detail.quotaProfile')">
            {{ detail.quotaProfile }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('tenantManagement.detail.storage')">
            {{ detail.storageQuotaGb }} GB
          </el-descriptions-item>
          <el-descriptions-item :label="t('tenantManagement.detail.userCount')">
            {{ detail.userCount }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('tenantManagement.detail.adminUser')">
            {{ detail.adminUsername || t('tenantManagement.detail.adminUnset') }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('tenantManagement.detail.createdAt')">
            {{ fmtTime(detail.createdAt) }}
          </el-descriptions-item>
          <el-descriptions-item :label="t('tenantManagement.detail.updatedAt')">
            {{ fmtTime(detail.updatedAt) }}
          </el-descriptions-item>
        </el-descriptions>

        <h4>
          {{ t('tenantManagement.detail.invites', { n: admin.invitesByTenant(detail.id).length }) }}
        </h4>
        <el-table
          :data="admin.invitesByTenant(detail.id)"
          size="small"
          :empty-text="t('tenantManagement.detail.empty')"
        >
          <el-table-column :label="t('tenantManagement.detail.colCode')" prop="code" width="120" />
          <el-table-column :label="t('tenantManagement.detail.colRole')" prop="role" width="110" />
          <el-table-column :label="t('tenantManagement.detail.colStatus')" width="100">
            <template #default="{ row }">
              <el-tag :type="row.status === 'PENDING' ? 'primary' : 'info'" size="small">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column :label="t('tenantManagement.detail.colCreatedAt')" min-width="170">
            <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('tenantManagement.detail.colExpiry')" min-width="170">
            <template #default="{ row }">{{ fmtTime(row.expiresAt) }}</template>
          </el-table-column>
          <el-table-column :label="t('tenantManagement.detail.colNote')" prop="note" />
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, reactive } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, type FormInstance } from 'element-plus'
import { PageHeader, PageCard, Toolbar, StatusTag } from '@/components/ui'
import { useTenantAdminStore, type AdminTenant } from '@/stores/tenantAdmin'

const { t } = useI18n()
const admin = useTenantAdminStore()

const searchKeyword = ref('')
const filterType = ref<string>('')
const filterStatus = ref<string>('')
const loading = ref(false)
const submitting = ref(false)

/* ============== Tag type maps ============== */
const STATUS_TAG_MAP: Record<string, 'success' | 'info' | 'warning' | 'danger'> = {
  ACTIVE: 'success',
  INACTIVE: 'info',
  CREATING: 'warning',
  SUSPENDED: 'danger'
}

const TYPE_TAG_MAP: Record<string, 'success' | 'info' | 'warning' | 'primary' | 'danger'> = {
  XINCHUANG: 'success',
  GOVERNMENT: 'primary',
  PRIVATE: 'info',
  PUBLIC: 'warning',
  INTERNAL: 'danger'
}

function statusLabel(s: string): string {
  const m: Record<string, string> = {
    ACTIVE: t('tenantManagement.statusFilter.active'),
    INACTIVE: t('tenantManagement.statusFilter.inactive'),
    CREATING: t('tenantManagement.statusFilter.creating'),
    SUSPENDED: t('tenantManagement.statusFilter.suspended')
  }
  return m[s] || s
}
function typeLabel(v: string): string {
  const m: Record<string, string> = {
    XINCHUANG: t('tenantManagement.type.xinchuang'),
    GOVERNMENT: t('tenantManagement.type.government'),
    PRIVATE: t('tenantManagement.type.private'),
    PUBLIC: t('tenantManagement.type.public'),
    INTERNAL: t('tenantManagement.type.internal')
  }
  return m[v] || v
}

function fmtTime(iso: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

/* ============== 列表 + 过滤 ============== */
const filteredTenants = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  return admin.allTenants.filter((t) => {
    if (filterType.value && t.type !== filterType.value) return false
    if (filterStatus.value && t.status !== filterStatus.value) return false
    if (!kw) return true
    return t.name.toLowerCase().includes(kw) || t.displayName.toLowerCase().includes(kw)
  })
})

function loadList() {
  loading.value = true
  setTimeout(() => (loading.value = false), 200)
}

function handleSearch() {
  /* filteredTenants 是计算属性，自动响应 */
}

/* ============== 创建租户 ============== */
const createVisible = ref(false)
const createFormRef = ref<FormInstance>()
const createForm = reactive({
  name: '',
  displayName: '',
  type: 'XINCHUANG' as AdminTenant['type'],
  quotaProfile: 'medium' as AdminTenant['quotaProfile'],
  storageQuotaGb: 100
})
const createRules = {
  name: [
    { required: true, message: t('tenantManagement.rules2.codeRequired'), trigger: 'blur' },
    {
      pattern: /^[a-z][a-z0-9-]{2,30}$/,
      message: t('tenantManagement.rules2.codePattern'),
      trigger: 'blur'
    }
  ],
  displayName: [
    { required: true, message: t('tenantManagement.rules2.displayNameRequired'), trigger: 'blur' }
  ],
  type: [{ required: true, message: t('tenantManagement.rules2.typeRequired'), trigger: 'change' }],
  quotaProfile: [
    { required: true, message: t('tenantManagement.rules2.quotaRequired'), trigger: 'change' }
  ]
}

function openCreateDialog() {
  createVisible.value = true
}

async function handleCreate() {
  if (!createFormRef.value) return
  const valid = await createFormRef.value.validate().catch(() => false)
  if (!valid) return
  if (admin.allTenants.find((x) => x.name === createForm.name)) {
    ElMessage.error(t('tenantManagement.msg2.codeExists'))
    return
  }
  submitting.value = true
  const tenant = admin.createTenant({ ...createForm })
  // 自动生成首账号邀请码（TENANT_ADMIN，7 天）
  const invite = admin.createInvite({
    tenantId: tenant.id,
    role: 'TENANT_ADMIN',
    note: t('tenantManagement.msg2.defaultNote'),
    invitedBy: 'platform-admin',
    ttlDays: 7
  })
  submitting.value = false
  createVisible.value = false
  ElMessage.success({
    message: t('tenantManagement.msg2.createdWithInvite', {
      name: tenant.displayName,
      code: invite.code
    }),
    duration: 6000
  })
  successCode.value = invite.code
  successTenant.value = tenant.displayName
  successRole.value = t('tenantManagement.labels.roleTenantAdmin')
  successExpiry.value = fmtTime(invite.expiresAt)
  successVisible.value = true
}

function resetCreateForm() {
  createFormRef.value?.resetFields()
}

/* ============== 邀请码 ============== */
const inviteVisible = ref(false)
const inviteForm = reactive({
  tenantId: 0,
  tenantName: '',
  role: 'USER' as 'USER' | 'TENANT_ADMIN' | 'PLATFORM_ADMIN',
  ttlDays: 7,
  note: ''
})

function openInviteDialog(row: AdminTenant) {
  inviteForm.tenantId = row.id
  inviteForm.tenantName = row.displayName
  inviteForm.role = 'USER'
  inviteForm.ttlDays = 7
  inviteForm.note = ''
  inviteVisible.value = true
}

/* 邀请码成功展示 */
const successVisible = ref(false)
const successCode = ref('')
const successTenant = ref('')
const successRole = ref('')
const successExpiry = ref('')
const copied = ref(false)

function handleCreateInvite() {
  if (inviteForm.tenantId === 0) return
  const inv = admin.createInvite({
    tenantId: inviteForm.tenantId,
    role: inviteForm.role,
    note: inviteForm.note,
    ttlDays: inviteForm.ttlDays,
    invitedBy: 'platform-admin'
  })
  inviteVisible.value = false
  successCode.value = inv.code
  successTenant.value = inviteForm.tenantName
  successRole.value = inviteForm.role
  successExpiry.value = fmtTime(inv.expiresAt)
  successVisible.value = true
}

async function copyCode() {
  try {
    await navigator.clipboard.writeText(successCode.value)
    copied.value = true
    setTimeout(() => (copied.value = false), 1500)
  } catch {
    ElMessage.warning(t('tenantManagement.msg2.copyDenied'))
  }
}

/* ============== 启停/删除 ============== */
async function handleSuspend(row: AdminTenant) {
  await ElMessageBox.confirm(
    t('tenantManagement.msg2.suspendConfirm', { name: row.displayName }),
    t('tenantManagement.msg2.suspendTitle'),
    { type: 'warning' }
  ).catch(() => null)
  admin.setTenantStatus(row.id, 'SUSPENDED')
  ElMessage.success(t('tenantManagement.msg2.suspended'))
}

async function handleActivate(row: AdminTenant) {
  admin.setTenantStatus(row.id, 'ACTIVE')
  ElMessage.success(t('tenantManagement.msg2.resumed'))
}

async function handleDelete(row: AdminTenant) {
  await ElMessageBox.confirm(
    t('tenantManagement.msg2.deleteConfirm', { name: row.displayName }),
    t('tenantManagement.msg2.deleteTitle'),
    { type: 'error' }
  ).catch(() => null)
  admin.deleteTenant(row.id)
  ElMessage.success(t('tenantManagement.msg2.deleted'))
}

/* ============== 详情 ============== */
const detailVisible = ref(false)
const detail = ref<AdminTenant | null>(null)

function openDetail(row: AdminTenant) {
  detail.value = row
  detailVisible.value = true
}

onMounted(() => loadList())
</script>

<style scoped>
.tenant-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-bottom: 18px;
}
.stat {
  padding: 16px 18px;
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-default);
  border-radius: 10px;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
  transition:
    transform 0.2s var(--ease-smooth),
    box-shadow 0.2s var(--ease-smooth);
}
.stat:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 14px rgba(15, 23, 42, 0.07);
}
.stat-num {
  font-size: 24px;
  font-weight: 700;
  color: var(--ds-text-primary);
  background: linear-gradient(120deg, #3b82f6 0%, #6366f1 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}
.stat-lbl {
  font-size: 12px;
  color: var(--ds-text-tertiary);
  margin-top: 2px;
}
.tenant-detail h4 {
  font-size: 14px;
  font-weight: 700;
  color: var(--ds-text-primary);
  margin: 18px 0 10px;
  padding-left: 8px;
  border-left: 3px solid var(--ds-color-info-500);
}
.invite-result {
  padding: 8px 4px;
}
.invite-hint {
  font-size: 14px;
  color: var(--ds-text-secondary);
  margin: 0 0 12px;
}
.invite-hint-sm {
  font-size: 12px;
  color: var(--ds-text-tertiary);
  margin: 12px 0 0;
}
.invite-code-box {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 18px;
  background: linear-gradient(135deg, rgba(59, 130, 246, 0.06) 0%, rgba(99, 102, 241, 0.04) 100%);
  border: 2px dashed var(--ds-color-primary-300);
  border-radius: 10px;
  margin-bottom: 12px;
}
.invite-code-text {
  font-family: var(--ds-font-family-mono);
  font-size: 20px;
  font-weight: 700;
  letter-spacing: 4px;
  color: var(--ds-color-primary-700);
}
.invite-meta {
  list-style: none;
  padding: 0;
  margin: 0;
  font-size: 12px;
  color: var(--ds-text-secondary);
}
.invite-meta li {
  padding: 4px 0;
  border-bottom: 1px dashed var(--ds-border-subtle);
}
</style>
