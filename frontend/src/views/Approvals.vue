<template>
  <div class="appr-page" role="main" :aria-label="t('approvals.title')">
    <PageHeader :title="t('approvals.title')" :subtitle="t('approvals.subtitle')" />

    <div class="appr-stats">
      <div class="stat" role="listitem">
        <div class="stat-num">{{ admin.allPendingRegs.length }}</div>
        <div class="stat-lbl">{{ t('approvals.stats.pendingAll') }}</div>
      </div>
      <div class="stat stat-on" role="listitem">
        <div class="stat-num">{{ regCountByStatus('PENDING') }}</div>
        <div class="stat-lbl">{{ t('approvals.stats.processing') }}</div>
      </div>
      <div class="stat" role="listitem">
        <div class="stat-num">{{ regCountByStatus('APPROVED') }}</div>
        <div class="stat-lbl">{{ t('approvals.stats.approved') }}</div>
      </div>
      <div class="stat stat-warn" role="listitem">
        <div class="stat-num">{{ regCountByStatus('REJECTED') }}</div>
        <div class="stat-lbl">{{ t('approvals.stats.rejected') }}</div>
      </div>
    </div>

    <PageCard>
      <Toolbar :show-create="false" :show-refresh="true" @refresh="loadList">
        <template #filters>
          <el-select
            v-model="filterStatus"
            :placeholder="t('approvals.filter.status')"
            clearable
            style="width: 140px"
          >
            <el-option :label="t('approvals.filter.pending')" value="PENDING" />
            <el-option :label="t('approvals.filter.approved')" value="APPROVED" />
            <el-option :label="t('approvals.filter.rejected')" value="REJECTED" />
          </el-select>
          <el-select
            v-model="filterTenant"
            :placeholder="t('approvals.filter.tenant')"
            clearable
            style="width: 180px"
          >
            <el-option
              v-for="t in admin.allTenants"
              :key="t.id"
              :label="`${t.displayName}（${t.name}）`"
              :value="t.id"
            />
          </el-select>
        </template>
      </Toolbar>

      <el-table
        v-loading="loading"
        :data="filteredRegs"
        stripe
        border
        style="width: 100%"
        :empty-text="t('approvals.table.empty')"
      >
        <el-table-column label="ID" prop="id" width="70" />
        <el-table-column
          :label="t('approvals.table.columns.username')"
          prop="username"
          width="130"
        />
        <el-table-column
          :label="t('approvals.table.columns.fullName')"
          prop="fullName"
          width="100"
        />
        <el-table-column :label="t('approvals.table.columns.email')" prop="email" min-width="180" />
        <el-table-column
          :label="t('approvals.table.columns.department')"
          prop="department"
          width="140"
        />
        <el-table-column
          :label="t('approvals.table.columns.employeeId')"
          prop="employeeId"
          width="90"
        />
        <el-table-column :label="t('approvals.table.columns.tenant')" width="160">
          <template #default="{ row }">
            <span>{{ tenantNameOf(row.tenantId) }}</span>
            <code class="appr-tenant-code">{{ tenantCodeOf(row.tenantId) }}</code>
          </template>
        </el-table-column>
        <el-table-column :label="t('approvals.table.columns.role')" width="110">
          <template #default="{ row }">
            <el-tag :type="row.role === 'TENANT_ADMIN' ? 'warning' : 'primary'" size="small">
              {{ roleLabel(row.role) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="t('approvals.table.columns.inviteCode')" width="110">
          <template #default="{ row }">
            <code class="appr-code">{{ row.inviteCode }}</code>
          </template>
        </el-table-column>
        <el-table-column :label="t('approvals.table.columns.status')" width="100">
          <template #default="{ row }">
            <StatusTag
              :status="row.status"
              :label="regStatusLabel(row.status)"
              :status-map="REG_STATUS_MAP"
            />
          </template>
        </el-table-column>
        <el-table-column :label="t('approvals.table.columns.createdAt')" width="170">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column :label="t('approvals.table.columns.actions')" width="180" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button link type="success" @click="openApprove(row, true)">
                {{ t('approvals.table.approve') }}
              </el-button>
              <el-button link type="danger" @click="openApprove(row, false)">
                {{ t('approvals.table.reject') }}
              </el-button>
            </template>
            <template v-else>
              <span class="appr-approved-by">
                {{ row.approvedBy }} · {{ fmtTime(row.approvedAt!) }}
              </span>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </PageCard>

    <!-- 审批对话框 -->
    <el-dialog v-model="decisionVisible" :title="decisionTitle" width="480px">
      <p class="appr-confirm">
        {{
          decisionApproved
            ? t('approvals.dialog.confirmApprove', {
                user: currentRow?.username,
                tenant: currentRow && tenantNameOf(currentRow.tenantId)
              })
            : t('approvals.dialog.confirmReject', {
                user: currentRow?.username,
                tenant: currentRow && tenantNameOf(currentRow.tenantId)
              })
        }}
      </p>
      <el-form label-position="top">
        <el-form-item :label="t('approvals.dialog.note')">
          <el-input
            v-model="decisionNote"
            type="textarea"
            :rows="3"
            :placeholder="
              decisionApproved
                ? t('approvals.dialog.noteApproveHint')
                : t('approvals.dialog.noteRejectHint')
            "
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="decisionVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button
          :type="decisionApproved ? 'success' : 'danger'"
          :loading="submitting"
          @click="submitDecision"
        >
          {{
            decisionApproved
              ? t('approvals.dialog.confirmApproveBtn')
              : t('approvals.dialog.confirmRejectBtn')
          }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { PageHeader, PageCard, Toolbar, StatusTag } from '@/components/ui'
import { useTenantAdminStore, type AdminRegistration } from '@/stores/tenantAdmin'

const { t } = useI18n()
const admin = useTenantAdminStore()

const loading = ref(false)
const submitting = ref(false)
const filterStatus = ref<'PENDING' | 'APPROVED' | 'REJECTED' | ''>('')
const filterTenant = ref<number | null>(null)

/* 全部注册（去重，演示用：模拟根据用户名 + 租户查询） */
const filteredRegs = computed(() => {
  let list = admin.registrations.slice()
  if (filterStatus.value) list = list.filter((r) => r.status === filterStatus.value)
  if (filterTenant.value != null) list = list.filter((r) => r.tenantId === filterTenant.value)
  return list.sort((a, b) => b.createdAt.localeCompare(a.createdAt))
})

function regCountByStatus(s: 'PENDING' | 'APPROVED' | 'REJECTED'): number {
  return admin.registrations.filter((r) => r.status === s).length
}

const REG_STATUS_MAP = {
  PENDING: 'warning' as const,
  APPROVED: 'success' as const,
  REJECTED: 'danger' as const
}

function regStatusLabel(s: string): string {
  const m: Record<string, string> = {
    PENDING: t('approvals.labels.statusPending'),
    APPROVED: t('approvals.labels.statusApproved'),
    REJECTED: t('approvals.labels.statusRejected')
  }
  return m[s] || s
}

function roleLabel(r: string): string {
  const m: Record<string, string> = {
    PLATFORM_ADMIN: t('approvals.labels.rolePlatformAdmin'),
    TENANT_ADMIN: t('approvals.labels.roleTenantAdmin'),
    USER: t('approvals.labels.roleUser')
  }
  return m[r] || r
}

function tenantNameOf(id: number): string {
  return admin.allTenants.find((t) => t.id === id)?.displayName || `#${id}`
}

function tenantCodeOf(id: number): string {
  return admin.allTenants.find((t) => t.id === id)?.name || ''
}

function fmtTime(iso: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function loadList() {
  loading.value = true
  setTimeout(() => (loading.value = false), 200)
}

/* 审批对话框 */
const decisionVisible = ref(false)
const decisionApproved = ref(true)
const decisionNote = ref('')
const currentRow = ref<AdminRegistration | null>(null)

const decisionTitle = computed(() =>
  decisionApproved.value ? t('approvals.dialog.approveTitle') : t('approvals.dialog.rejectTitle')
)

function openApprove(row: AdminRegistration, approved: boolean) {
  currentRow.value = row
  decisionApproved.value = approved
  decisionNote.value = approved ? t('approvals.dialog.noteApproveHint') : ''
  decisionVisible.value = true
}

function submitDecision() {
  if (!currentRow.value) return
  submitting.value = true
  const result = admin.decideRegistration(
    currentRow.value.id,
    decisionApproved.value,
    'platform-admin',
    decisionNote.value
  )
  submitting.value = false
  if (!result.ok) {
    ElMessage.error(result.error || t('common.operateFailed'))
    return
  }
  decisionVisible.value = false
  ElMessage.success(
    decisionApproved.value ? t('approvals.messages.approved') : t('approvals.messages.rejected')
  )
}

onMounted(() => loadList())
</script>

<style scoped>
.appr-stats {
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
  background: linear-gradient(120deg, #3b82f6 0%, #6366f1 100%);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}
.stat-lbl {
  font-size: 12px;
  color: var(--ds-text-tertiary);
  margin-top: 2px;
}
.appr-tenant-code,
.appr-code {
  margin-left: 6px;
  font-family: var(--ds-font-family-mono);
  font-size: 12px;
  color: var(--ds-text-secondary);
  background: var(--ds-bg-subtle);
  border: 1px solid var(--ds-border-subtle);
  border-radius: 4px;
  padding: 1px 6px;
}
.appr-approved-by {
  font-size: 12px;
  color: var(--ds-text-tertiary);
}
.appr-confirm {
  margin: 0 0 12px;
  font-size: 14px;
  color: var(--ds-text-secondary);
  line-height: 1.7;
}
.appr-confirm b {
  color: var(--ds-color-primary-700);
  font-weight: 700;
}
</style>
