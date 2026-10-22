<template>
  <div
    class="oa-page"
    role="main"
    data-design-contract="v2"
    :aria-label="t('apiMarket.subs.title')"
  >
    <PageHeader :title="t('apiMarket.subs.title')" :subtitle="t('apiMarket.subs.subtitle')">
      <template #actions>
        <el-button :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
      </template>
    </PageHeader>

    <p v-if="error" class="oa-error" role="alert">
      {{ error }}
      <el-button link type="primary" @click="load">{{ t('apiMarket.list.retry') }}</el-button>
    </p>

    <PageCard :title="t('apiMarket.subs.listTitle')">
      <div class="oa-filter">
        <el-select
          v-model="statusFilter"
          clearable
          :placeholder="t('apiMarket.subs.statusPlaceholder')"
          class="oa-status-select"
          @change="load"
        >
          <el-option
            v-for="s in SUB_STATUSES"
            :key="s"
            :label="t(`apiMarket.status.subscription.${s}`)"
            :value="s"
          />
        </el-select>
      </div>

      <el-table v-if="rows.length" v-loading="loading" :data="rows" stripe>
        <el-table-column :label="t('apiMarket.subs.colApi')" min-width="150" prop="apiId" />
        <el-table-column
          :label="t('apiMarket.subs.colSubscriber')"
          min-width="130"
          prop="subscriberId"
        />
        <el-table-column
          :label="t('apiMarket.subs.colTenant')"
          min-width="140"
          prop="subscriberTenantId"
        />
        <el-table-column :label="t('apiMarket.subs.colStatus')" width="110">
          <template #default="{ row }">
            <StatusTag :status="row.status" :label="statusLabel(row.status)" />
          </template>
        </el-table-column>
        <el-table-column :label="t('apiMarket.subs.colQuota')" width="110">
          <template #default="{ row }">{{ row.grantedQuota || row.quotaExpect }}</template>
        </el-table-column>
        <el-table-column :label="t('apiMarket.subs.colCalls')" width="110" prop="callCount" />
        <el-table-column :label="t('apiMarket.subs.colActions')" width="300" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 'pending'"
              link
              type="primary"
              @click="openApprove(row)"
            >
              {{ t('apiMarket.subs.actionApprove') }}
            </el-button>
            <el-button
              v-if="row.status === 'approved' || row.status === 'active'"
              link
              @click="openKeys(row)"
            >
              {{ t('apiMarket.subs.actionKeys') }}
            </el-button>
            <el-button
              v-if="row.status === 'approved' || row.status === 'active'"
              link
              @click="openRate(row)"
            >
              {{ t('apiMarket.subs.actionRate') }}
            </el-button>
            <el-button
              v-if="row.status === 'approved' || row.status === 'active'"
              link
              type="warning"
              @click="act(row, 'suspend')"
            >
              {{ t('apiMarket.subs.actionSuspend') }}
            </el-button>
            <el-button
              v-if="row.status === 'suspended'"
              link
              type="primary"
              @click="act(row, 'resume')"
            >
              {{ t('apiMarket.subs.actionResume') }}
            </el-button>
            <el-button
              v-if="row.status !== 'revoked' && row.status !== 'rejected'"
              link
              type="danger"
              @click="act(row, 'revoke')"
            >
              {{ t('apiMarket.subs.actionRevoke') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else-if="!error" :message="t('apiMarket.subs.empty')" />
    </PageCard>

    <PageCard v-if="approving" :title="t('apiMarket.subs.approveTitle')">
      <el-form class="oa-form" label-width="150px" label-position="left">
        <el-form-item :label="t('apiMarket.subs.colApi')">
          <span class="oa-mono">{{ approving.apiId }}</span>
        </el-form-item>
        <el-form-item :label="t('apiMarket.subs.fieldApprove')">
          <el-switch v-model="approve.approve" />
        </el-form-item>
        <el-form-item :label="t('apiMarket.subs.fieldGrantedQuota')">
          <el-input-number v-model="approve.grantedQuota" :min="1" :max="100000000" />
        </el-form-item>
        <el-form-item :label="t('apiMarket.subs.fieldReason')">
          <el-input v-model="approve.reason" />
        </el-form-item>
        <el-form-item :label="t('apiMarket.subs.fieldApprover')">
          <el-input
            v-model="approve.approver"
            :placeholder="t('apiMarket.subs.approverPlaceholder')"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="acting" @click="doApprove">
            {{ t('apiMarket.subs.actionSubmit') }}
          </el-button>
          <el-button @click="approving = null">{{ t('common.cancel') }}</el-button>
        </el-form-item>
      </el-form>
      <p v-if="actionError" class="oa-error" role="alert">{{ actionError }}</p>
    </PageCard>

    <PageCard v-if="keyFor" :title="t('apiMarket.subs.keysTitle')">
      <dl class="oa-detail">
        <div class="oa-kv">
          <dt>{{ t('apiMarket.subs.colSubscriber') }}</dt>
          <dd class="oa-mono">{{ keyFor.subscriberId }}</dd>
        </div>
        <div class="oa-kv">
          <dt>AK</dt>
          <dd class="oa-mono">{{ keyInfo?.accessKey || '—' }}</dd>
        </div>
        <div class="oa-kv">
          <dt>{{ t('apiMarket.subs.hasSecretKey') }}</dt>
          <dd>{{ keyInfo ? String(keyInfo.hasSecretKey) : '—' }}</dd>
        </div>
        <div v-if="issued" class="oa-kv">
          <dt>SK</dt>
          <dd class="oa-mono">{{ issued.secretKey }}</dd>
        </div>
      </dl>
      <p v-if="issued" class="oa-warn" role="status">{{ t('apiMarket.subs.skOnceOnly') }}</p>
      <el-form class="oa-form" label-width="150px" label-position="left">
        <el-form-item :label="t('apiMarket.subs.fieldReason')">
          <el-input v-model="keyForm.reason" />
        </el-form-item>
        <el-form-item :label="t('apiMarket.subs.fieldOperator')">
          <el-input
            v-model="keyForm.operator"
            :placeholder="t('apiMarket.subs.approverPlaceholder')"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="acting" @click="doIssueKey">
            {{ t('apiMarket.subs.actionIssue') }}
          </el-button>
          <el-button @click="closeKeys">{{ t('common.close') }}</el-button>
        </el-form-item>
      </el-form>
      <p v-if="actionError" class="oa-error" role="alert">{{ actionError }}</p>
    </PageCard>

    <PageCard v-if="rateFor" :title="t('apiMarket.subs.rateTitle')">
      <el-form class="oa-form" label-width="150px" label-position="left">
        <el-form-item :label="t('apiMarket.subs.fieldQps')">
          <el-input-number v-model="rate.qps" :min="1" :max="100000" />
        </el-form-item>
        <el-form-item :label="t('apiMarket.subs.fieldConcurrent')">
          <el-input-number v-model="rate.concurrent" :min="0" :max="10000" />
          <span class="oa-hint">{{ t('apiMarket.subs.concurrentHint') }}</span>
        </el-form-item>
        <el-form-item :label="t('apiMarket.subs.fieldBurst')">
          <el-input-number v-model="rate.burst" :min="0" :max="100000" />
          <span class="oa-hint">{{ t('apiMarket.subs.burstHint') }}</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="acting" @click="doRate">
            {{ t('common.save') }}
          </el-button>
          <el-button @click="rateFor = null">{{ t('common.cancel') }}</el-button>
        </el-form-item>
      </el-form>
      <p v-if="actionError" class="oa-error" role="alert">{{ actionError }}</p>
    </PageCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { useAuthStore } from '@/stores/auth'
import {
  approveSubscription,
  getRateLimit,
  getSubscriptionKeys,
  issueSubscriptionKey,
  listSubscriptions,
  resumeSubscription,
  revokeSubscription,
  setRateLimit,
  suspendSubscription,
  type APISubscription,
  type IssueKeyResponse,
  type KeyInfoResponse,
  type RateLimitConfigResponse,
  type SubscriptionStatus
} from '@/api/apiCatalog'

const { t } = useI18n()
const auth = useAuthStore()

// 词表取后端枚举：`models/subscription.py` 的 SubscriptionStatus 六个值。
// 少了 `active` 会让已发放 AK/SK 的订阅在过滤下拉里无从选中。
const SUB_STATUSES: SubscriptionStatus[] = [
  'pending',
  'approved',
  'active',
  'suspended',
  'rejected',
  'revoked'
]

const rows = ref<APISubscription[]>([])
const loading = ref(false)
const acting = ref(false)
const error = ref('')
const actionError = ref('')
const statusFilter = ref<SubscriptionStatus | ''>('')

const approving = ref<APISubscription | null>(null)
const keyFor = ref<APISubscription | null>(null)
const rateFor = ref<APISubscription | null>(null)
const keyInfo = ref<KeyInfoResponse | null>(null)
const issued = ref<IssueKeyResponse | null>(null)

const approve = reactive({ approve: true, grantedQuota: 1000, reason: '', approver: '' })
const keyForm = reactive({ reason: '重新颁发', operator: '' })
const rate = reactive({ qps: 100, concurrent: 0, burst: 0 })

function statusLabel(status: SubscriptionStatus): string {
  return t(`apiMarket.status.subscription.${status}`)
}

/** 审批人与操作人都是后端必填字段；默认取登录态，用户可覆盖（演练环境常用 admin） */
function identity(): string {
  return auth.user?.username ?? ''
}

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    rows.value = await listSubscriptions({
      status: statusFilter.value || undefined,
      limit: 200
    })
  } catch (e) {
    error.value = `${t('apiMarket.list.loadFailed')}${(e as Error)?.message ?? ''}`
    rows.value = []
  } finally {
    loading.value = false
  }
}

function openApprove(row: APISubscription): void {
  actionError.value = ''
  approving.value = row
  rateFor.value = null
  keyFor.value = null
  approve.approve = true
  approve.grantedQuota = row.quotaExpect > 0 ? row.quotaExpect : 1000
  approve.reason = ''
  approve.approver = identity()
}

async function doApprove(): Promise<void> {
  if (!approving.value) return
  actionError.value = ''
  if (!approve.approver.trim()) {
    actionError.value = t('apiMarket.subs.needApprover')
    return
  }
  // 后端 `grantedQuota: int | None = Field(ge=1)`，驳回时不该带配额——
  // 连 `undefined` 的键都不发：Pydantic 收到显式 null 会按"未授予"处理，
  // 而页面这里是"根本没这个意思"，语义上应当缺键。
  const payload: {
    approve: boolean
    reason?: string
    grantedQuota?: number
    approver: string
  } = {
    approve: approve.approve,
    reason: approve.reason.trim() || undefined,
    approver: approve.approver.trim()
  }
  if (approve.approve) {
    const quota = Math.trunc(Number(approve.grantedQuota) || 0)
    if (quota > 0) payload.grantedQuota = quota
  }
  acting.value = true
  try {
    await approveSubscription(approving.value.id, payload)
    approving.value = null
    await load()
  } catch (e) {
    actionError.value = `${t('apiMarket.subs.actionFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    acting.value = false
  }
}

async function openKeys(row: APISubscription): Promise<void> {
  actionError.value = ''
  keyFor.value = row
  approving.value = null
  rateFor.value = null
  issued.value = null
  keyInfo.value = null
  keyForm.operator = identity()
  try {
    keyInfo.value = await getSubscriptionKeys(row.id)
  } catch (e) {
    actionError.value = `${t('apiMarket.subs.keysFailed')}${(e as Error)?.message ?? ''}`
  }
}

function closeKeys(): void {
  keyFor.value = null
  issued.value = null
  keyInfo.value = null
}

async function doIssueKey(): Promise<void> {
  if (!keyFor.value) return
  actionError.value = ''
  if (!keyForm.operator.trim()) {
    actionError.value = t('apiMarket.subs.needOperator')
    return
  }
  acting.value = true
  try {
    // SK 只在 201 响应里出现一次，GET /keys 只回 hasSecretKey ⇒ 必须就地展示并提醒保存
    issued.value = await issueSubscriptionKey(keyFor.value.id, {
      reason: keyForm.reason.trim() || undefined,
      operator: keyForm.operator.trim()
    })
    keyInfo.value = await getSubscriptionKeys(keyFor.value.id)
    await load()
  } catch (e) {
    actionError.value = `${t('apiMarket.subs.issueFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    acting.value = false
  }
}

async function openRate(row: APISubscription): Promise<void> {
  actionError.value = ''
  rateFor.value = row
  approving.value = null
  keyFor.value = null
  try {
    const current: RateLimitConfigResponse = await getRateLimit(row.id)
    rate.qps = current.qps
    rate.concurrent = current.concurrent
    rate.burst = current.burst
  } catch (e) {
    actionError.value = `${t('apiMarket.subs.rateFailed')}${(e as Error)?.message ?? ''}`
  }
}

async function doRate(): Promise<void> {
  if (!rateFor.value) return
  actionError.value = ''
  if (!Number(rate.qps) || Number(rate.qps) < 1) {
    actionError.value = t('apiMarket.subs.needQps')
    return
  }
  acting.value = true
  try {
    await setRateLimit(rateFor.value.id, {
      qps: Math.trunc(Number(rate.qps)),
      concurrent: Math.trunc(Number(rate.concurrent) || 0),
      burst: Math.trunc(Number(rate.burst) || 0)
    })
    rateFor.value = null
  } catch (e) {
    actionError.value = `${t('apiMarket.subs.rateFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    acting.value = false
  }
}

async function act(row: APISubscription, op: 'suspend' | 'resume' | 'revoke'): Promise<void> {
  error.value = ''
  actionError.value = ''
  try {
    if (op === 'suspend') await suspendSubscription(row.id)
    else if (op === 'resume') await resumeSubscription(row.id)
    else await revokeSubscription(row.id)
    await load()
  } catch (e) {
    error.value = `${t('apiMarket.subs.actionFailed')}${(e as Error)?.message ?? ''}`
  }
}

onMounted(() => {
  void load()
})
</script>

<style scoped>
.oa-page {
  padding: var(--ds-spacing-4);
}

.oa-form {
  max-width: 720px;
}

.oa-filter {
  margin-bottom: var(--ds-spacing-3);
}

.oa-status-select {
  width: 200px;
}

.oa-hint {
  margin-left: var(--ds-spacing-2);
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.oa-error {
  margin: 0 0 var(--ds-spacing-4);
  color: var(--ds-danger);
  font-size: var(--ds-font-size-sm);
}

.oa-warn {
  margin: 0 0 var(--ds-spacing-4);
  color: var(--ds-warning);
  font-size: var(--ds-font-size-sm);
}

.oa-detail {
  margin: 0 0 var(--ds-spacing-3);
  display: flex;
  flex-direction: column;
  gap: var(--ds-spacing-2);
}

.oa-kv {
  display: flex;
  gap: var(--ds-spacing-2);
  align-items: baseline;
}

.oa-kv dt {
  flex: 0 0 150px;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.oa-kv dd {
  margin: 0;
  word-break: break-all;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-primary);
}

.oa-mono {
  font-family: var(--ds-font-family-numeric);
  font-variant-numeric: var(--ds-font-variant-numeric);
}
</style>
