<template>
  <div
    class="ex-page"
    role="main"
    data-design-contract="v2"
    :aria-label="t('exchange.audit.title')"
  >
    <PageHeader :title="t('exchange.audit.title')" :subtitle="t('exchange.audit.subtitle')" />

    <section class="ex-toolbar">
      <label class="ex-field">
        <span class="ex-field__label">{{ t('exchange.fieldAssetId') }}</span>
        <el-input v-model="filters.assetId" clearable style="width: 260px" />
      </label>
      <label class="ex-field">
        <span class="ex-field__label">{{ t('exchange.fieldAction') }}</span>
        <!-- 后端把 action 按 AuditAction 枚举校验，自由输入会直接 422 ⇒ 只能从这个闭包里选 -->
        <el-select
          v-model="filters.action"
          clearable
          :placeholder="t('exchange.actionAll')"
          style="width: 200px"
        >
          <el-option v-for="a in AUDIT_ACTIONS" :key="a" :label="a" :value="a" />
        </el-select>
      </label>
      <label class="ex-field">
        <span class="ex-field__label">{{ t('exchange.fieldLimit') }}</span>
        <el-input
          v-model.number="filters.limit"
          type="number"
          min="1"
          max="1000"
          style="width: 120px"
        />
      </label>
      <el-button type="primary" :loading="loading" @click="load">
        {{ t('exchange.actionQuery') }}
      </el-button>
      <el-button :loading="verifying" @click="verify">
        {{ t('exchange.actionVerifyIntegrity') }}
      </el-button>
    </section>

    <div
      v-if="integrity"
      class="ex-integrity"
      :class="{ 'is-broken': !integrity.verified }"
      role="status"
    >
      {{ t('exchange.audit.integritySummary', { n: integrity.totalLogs }) }}
      <template v-if="!integrity.verified">
        · {{ t('exchange.audit.integrityBrokenAt') }}
        <span class="ex-mono">{{ integrity.brokenAt || '—' }}</span>
      </template>
      · {{ integrity.message }}
    </div>

    <p v-if="error" class="ex-error" role="alert">
      {{ error }}
      <el-button link type="primary" @click="load">{{ t('exchange.actionRetry') }}</el-button>
    </p>

    <PageCard :title="t('exchange.audit.listTitle')">
      <el-table v-if="rows.length" :data="rows">
        <el-table-column :label="t('exchange.colLogId')" min-width="120">
          <template #default="{ row }">
            <span class="ex-mono">{{ shortId(row.id) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="action" :label="t('exchange.colAction')" width="160" />
        <el-table-column :label="t('exchange.colActor')" min-width="140">
          <template #default="{ row }">
            <span class="ex-mono">{{ row.actorId }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('exchange.colAsset')" min-width="140">
          <template #default="{ row }">
            <span class="ex-mono">{{ row.assetId || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('exchange.colCreatedAt')" width="190">
          <template #default="{ row }">
            <span class="ex-mono">{{ formatTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('exchange.colResult')" width="110">
          <template #default="{ row }">
            <StatusTag :status="row.result" :label="row.result" :status-map="EXCHANGE_STATUS_MAP" />
          </template>
        </el-table-column>
        <el-table-column :label="t('exchange.colHash')" min-width="150">
          <template #default="{ row }">
            <span class="ex-mono">{{ row.hash ? `${row.hash.slice(0, 16)}…` : '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('exchange.colDetail')" width="90">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row.id)">
              {{ t('exchange.actionDetail') }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState v-else :message="t('exchange.audit.emptyAudit')" />
    </PageCard>

    <Modal :visible="detailVisible" :title="t('exchange.audit.detailTitle')" @close="closeDetail">
      <dl v-if="detail" class="ex-detail">
        <div class="ex-detail__row">
          <dt>{{ t('exchange.colLogId') }}</dt>
          <dd class="ex-mono">{{ detail.id }}</dd>
        </div>
        <div class="ex-detail__row">
          <dt>{{ t('exchange.colResult') }}</dt>
          <dd>{{ detail.result }}</dd>
        </div>
        <div class="ex-detail__row">
          <dt>{{ t('exchange.audit.prevHash') }}</dt>
          <dd class="ex-mono">{{ detail.prevHash || '—' }}</dd>
        </div>
        <div class="ex-detail__row">
          <dt>{{ t('exchange.audit.selfHash') }}</dt>
          <dd class="ex-mono">{{ detail.hash || '—' }}</dd>
        </div>
        <div class="ex-detail__row">
          <dt>{{ t('exchange.audit.detailPayload') }}</dt>
          <dd class="ex-mono">{{ JSON.stringify(detail.detail) }}</dd>
        </div>
      </dl>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import EmptyState from '@/components/ui/EmptyState.vue'
import Modal from '@/components/Modal.vue'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import { EXCHANGE_STATUS_MAP, shortId, formatTime as fmtTime } from './format'
import {
  AUDIT_ACTIONS,
  getAuditLog,
  listAuditLogs,
  verifyAuditIntegrity,
  type AuditActionValue,
  type AuditIntegrityReport,
  type AuditLog
} from '@/api/exchange'

const { t, locale } = useI18n()

const filters = reactive({ assetId: '', action: '' as '' | AuditActionValue, limit: 100 })
const rows = ref<AuditLog[]>([])
const integrity = ref<AuditIntegrityReport | null>(null)
const loading = ref(false)
const verifying = ref(false)
const error = ref('')

const formatTime = (iso: string | null | undefined): string => fmtTime(iso, locale.value)

async function load(): Promise<void> {
  loading.value = true
  error.value = ''
  try {
    rows.value = await listAuditLogs({
      assetId: filters.assetId.trim() || undefined,
      action: filters.action || undefined,
      limit: Math.min(Math.max(Number(filters.limit) || 100, 1), 1000)
    })
  } catch (e) {
    error.value = `${t('exchange.loadFailed')}${(e as Error)?.message ?? ''}`
    rows.value = []
  } finally {
    loading.value = false
  }
}

async function verify(): Promise<void> {
  verifying.value = true
  error.value = ''
  try {
    integrity.value = await verifyAuditIntegrity()
  } catch (e) {
    error.value = `${t('exchange.verifyFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    verifying.value = false
  }
}

// 单条详情走 `GET /audit-logs/{id}` 再取一次，而不是把列表行直接摊开：
// 列表与详情是两个端点，这样也能顺带验证按 ID 取数那条路没坏。
const detailVisible = ref(false)
const detail = ref<AuditLog | null>(null)

async function openDetail(logId: string): Promise<void> {
  error.value = ''
  try {
    detail.value = await getAuditLog(logId)
    detailVisible.value = true
  } catch (e) {
    error.value = `${t('exchange.detailFailed')}${(e as Error)?.message ?? ''}`
  }
}

function closeDetail(): void {
  detailVisible.value = false
  detail.value = null
}

// 挂载即拉列表 + 做一次哈希链校验：这个页面的意义是"能自证没被改过"，
// 需要用户再点一下才出结论的话，绝大多数人永远看不到断裂提示。
onMounted(() => {
  void load()
  void verify()
})
</script>

<style scoped>
.ex-page {
  padding: var(--ds-spacing-4);
}

.ex-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: var(--ds-spacing-2);
  margin-bottom: var(--ds-spacing-4);
}

.ex-field {
  display: flex;
  flex-direction: column;
  gap: var(--ds-spacing-1);
}

.ex-field__label {
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.ex-integrity {
  margin-bottom: var(--ds-spacing-4);
  padding: var(--ds-spacing-2) var(--ds-spacing-3);
  border: 1px solid var(--ds-border-default);
  border-radius: var(--ds-radius-sm);
  background: var(--ds-surface-2);
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.ex-integrity.is-broken {
  color: var(--ds-danger);
}

.ex-error {
  margin: 0 0 var(--ds-spacing-4);
  color: var(--ds-danger);
  font-size: var(--ds-font-size-sm);
}

.ex-mono {
  font-family: var(--ds-font-family-numeric);
  font-variant-numeric: var(--ds-font-variant-numeric);
}

.ex-detail {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ds-spacing-2);
}

.ex-detail__row {
  display: flex;
  gap: var(--ds-spacing-2);
  align-items: baseline;
}

.ex-detail__row dt {
  flex: 0 0 120px;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-secondary);
}

.ex-detail__row dd {
  margin: 0;
  word-break: break-all;
  font-size: var(--ds-font-size-sm);
  color: var(--ds-text-primary);
}
</style>
