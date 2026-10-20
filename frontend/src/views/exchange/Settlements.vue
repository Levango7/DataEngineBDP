<template>
  <div
    class="ex-page"
    role="main"
    data-design-contract="v2"
    :aria-label="t('exchange.settlements.title')"
  >
    <PageHeader
      :title="t('exchange.settlements.title')"
      :subtitle="t('exchange.settlements.subtitle')"
    />

    <section class="ex-toolbar">
      <label class="ex-field">
        <span class="ex-field__label">{{ t('exchange.fieldAssetId') }}</span>
        <el-input
          v-model="assetId"
          :placeholder="t('exchange.fieldAssetIdPlaceholder')"
          clearable
          style="width: 320px"
          @keyup.enter="load"
        />
      </label>
      <el-button type="primary" :loading="loading" :disabled="!assetId.trim()" @click="load">
        {{ t('exchange.actionQuery') }}
      </el-button>
      <!-- 结算是分账的前置：没有结算单就没有可分配的对象。后端该端点仅 admin 可执行
           （assets.py 的 requireAdmin），非 admin 点下去会拿 403，此处原样呈现错误。 -->
      <el-button type="success" :loading="settling" :disabled="!assetId.trim()" @click="settle">
        {{ t('exchange.actionSettle') }}
      </el-button>
      <el-button
        :loading="allocating"
        :disabled="!assetId.trim() || !settlements.length"
        @click="allocate"
      >
        {{ t('exchange.actionAllocate') }}
      </el-button>
    </section>

    <p v-if="error" class="ex-error" role="alert">
      {{ error }}
      <el-button link type="primary" @click="load">{{ t('exchange.actionRetry') }}</el-button>
    </p>

    <PageCard :title="t('exchange.settlements.listTitle')">
      <el-table v-if="settlements.length" :data="settlements" :height="undefined">
        <el-table-column :label="t('exchange.colSettlementId')" min-width="120">
          <template #default="{ row }">
            <span class="ex-mono">{{ shortId(row.id) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="period" :label="t('exchange.colPeriod')" width="110" />
        <el-table-column :label="t('exchange.colStatus')" width="120">
          <template #default="{ row }">
            <StatusTag :status="row.status" :label="row.status" :status-map="EXCHANGE_STATUS_MAP" />
          </template>
        </el-table-column>
        <el-table-column :label="t('exchange.colTotalAmount')" width="120" align="right">
          <template #default="{ row }">{{ money(row.totalAmount) }}</template>
        </el-table-column>
        <el-table-column :label="t('exchange.colProviderRevenue')" width="140" align="right">
          <template #default="{ row }">{{ money(row.providerRevenue) }}</template>
        </el-table-column>
        <el-table-column :label="t('exchange.colPlatformRevenue')" width="130" align="right">
          <template #default="{ row }">{{ money(row.platformRevenue) }}</template>
        </el-table-column>
        <el-table-column :label="t('exchange.colShare')" width="130" align="right">
          <template #default="{ row }">{{ share(row.providerShare, row.platformShare) }}</template>
        </el-table-column>
        <el-table-column :label="t('exchange.colSettledAt')" min-width="170">
          <template #default="{ row }">
            <span class="ex-mono">{{ row.settledAt ? formatTime(row.settledAt) : '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
      <EmptyState
        v-else
        :message="assetId ? t('exchange.settlements.emptySettlements') : t('exchange.needAssetId')"
      />
    </PageCard>

    <PageCard v-if="allocations.length" :title="t('exchange.settlements.allocationsTitle')">
      <el-table :data="allocations">
        <el-table-column :label="t('exchange.colAllocationId')" min-width="120">
          <template #default="{ row }">
            <span class="ex-mono">{{ shortId(row.id) }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" :label="t('exchange.colStatus')" width="120" />
        <el-table-column :label="t('exchange.colProviderAmount')" width="150" align="right">
          <template #default="{ row }">{{ money(row.providerAmount) }}</template>
        </el-table-column>
        <el-table-column :label="t('exchange.colPlatformAmount')" width="140" align="right">
          <template #default="{ row }">{{ money(row.platformAmount) }}</template>
        </el-table-column>
      </el-table>
    </PageCard>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import EmptyState from '@/components/ui/EmptyState.vue'
import PageCard from '@/components/ui/PageCard.vue'
import PageHeader from '@/components/ui/PageHeader.vue'
import StatusTag from '@/components/ui/StatusTag.vue'
import {
  allocateAsset,
  listAssetAllocations,
  listAssetSettlements,
  settleAsset,
  type Allocation,
  type Settlement
} from '@/api/exchange'
import {
  EXCHANGE_STATUS_MAP,
  formatTime as fmtTime,
  money as fmtMoney,
  share as fmtShare,
  shortId
} from './format'

const { t, locale } = useI18n()

const assetId = ref('')
const settlements = ref<Settlement[]>([])
const allocations = ref<Allocation[]>([])
const loading = ref(false)
const settling = ref(false)
const allocating = ref(false)
const error = ref('')

const money = (v: number | null | undefined): string => fmtMoney(v, locale.value)
const share = (p: number | null | undefined, q: number | null | undefined): string => fmtShare(p, q)
const formatTime = (iso: string | null | undefined): string => fmtTime(iso, locale.value)

async function load(): Promise<void> {
  const id = assetId.value.trim()
  if (!id) {
    error.value = ''
    settlements.value = []
    allocations.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    const [settle, alloc] = await Promise.all([listAssetSettlements(id), listAssetAllocations(id)])
    settlements.value = settle
    allocations.value = alloc
  } catch (e) {
    error.value = `${t('exchange.loadFailed')}${(e as Error)?.message ?? ''}`
    settlements.value = []
    allocations.value = []
  } finally {
    loading.value = false
  }
}

async function settle(): Promise<void> {
  const id = assetId.value.trim()
  if (!id) return
  settling.value = true
  error.value = ''
  try {
    // 不传 period / 分成比例：账期取服务端当前年月，比例取配置默认值
    // （SettleRequest 两字段皆 Optional，缺省即走 Settings.providerShare）。
    await settleAsset(id)
    ElMessage.success(t('exchange.settleOk'))
    await load()
  } catch (e) {
    error.value = `${t('exchange.settleFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    settling.value = false
  }
}

async function allocate(): Promise<void> {
  const id = assetId.value.trim()
  if (!id) return
  allocating.value = true
  error.value = ''
  try {
    await allocateAsset(id)
    ElMessage.success(t('exchange.allocateOk'))
    await load()
  } catch (e) {
    error.value = `${t('exchange.allocateFailed')}${(e as Error)?.message ?? ''}`
  } finally {
    allocating.value = false
  }
}
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
