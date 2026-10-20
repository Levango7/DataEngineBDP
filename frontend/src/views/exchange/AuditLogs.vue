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
        <el-input v-model="filters.action" clearable style="width: 200px" />
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
      </el-table>
      <EmptyState v-else :message="t('exchange.audit.emptyAudit')" />
    </PageCard>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import { shortId, formatTime as fmtTime } from './format'
import {
  listAuditLogs,
  verifyAuditIntegrity,
  type AuditIntegrityReport,
  type AuditLog
} from '@/api/exchange'

const { t, locale } = useI18n()

const filters = reactive({ assetId: '', action: '', limit: 100 })
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
      action: filters.action.trim() || undefined,
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
</style>
