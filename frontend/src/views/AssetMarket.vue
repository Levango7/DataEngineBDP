<template>
  <div>
    <h1>{{ t('assetMarket.title') }}</h1>
    <div class="sub">
      {{ t('assetMarket.subtitle') }}
    </div>

    <!-- 顶部 KPI -->
    <div class="grid g4">
      <div class="card">
        <h3>{{ t('assetMarket.kpi.listed') }}</h3>
        <div class="kpi s">{{ assets?.length ?? 0 }}</div>
        <div class="meta">{{ t('assetMarket.kpi.circulatable') }}</div>
      </div>
      <div class="card">
        <h3>{{ t('assetMarket.kpi.mySubs') }}</h3>
        <div class="kpi s">{{ mySubscriptions?.length ?? 0 }}</div>
        <div class="meta">{{ t('assetMarket.kpi.activeSubs', { count: activeSubCount }) }}</div>
      </div>
      <div class="card">
        <h3>{{ t('assetMarket.kpi.revenue') }}</h3>
        <div class="kpi s">¥{{ totalRevenue.toFixed(2) }}</div>
        <div class="meta">{{ t('assetMarket.kpi.providerSettle') }}</div>
      </div>
      <div class="card">
        <h3>{{ t('assetMarket.kpi.platformFee') }}</h3>
        <div class="kpi s">¥{{ totalPlatformRevenue.toFixed(2) }}</div>
        <div class="meta">{{ t('assetMarket.kpi.feeShare') }}</div>
      </div>
    </div>

    <!-- 加载与错误状态 -->
    <div v-if="loading" class="card" style="text-align: center; padding: 24px; color: #888">
      {{ t('assetMarket.loading') }}
    </div>
    <div v-else-if="error" class="card" style="text-align: center; padding: 24px; color: #d4380d">
      {{ t('assetMarket.loadFailed', { message: error.message }) }}
      <button class="btn ghost sm" style="margin-left: 8px" @click="loadAssets">{{ t('assetMarket.retry') }}</button>
    </div>

    <!-- Tab 切换 -->
    <div class="toolbar" style="margin-top: 14px">
      <button :class="['btn', 'sm', tab === 'market' ? '' : 'ghost']" @click="tab = 'market'">
        {{ t('assetMarket.tabs.market') }}
      </button>
      <button :class="['btn', 'sm', tab === 'mine' ? '' : 'ghost']" @click="tab = 'mine'">
        {{ t('assetMarket.tabs.mine') }}
      </button>
      <button :class="['btn', 'sm', tab === 'listed' ? '' : 'ghost']" @click="tab = 'listed'">
        {{ t('assetMarket.tabs.listed') }}
      </button>
      <div class="spacer"></div>
      <button class="btn sm" @click="listModalVisible = true">{{ t('assetMarket.listAsset') }}</button>
    </div>

    <!-- 资产市场：卡片式浏览 -->
    <div v-if="tab === 'market'">
      <!-- 筛选 -->
      <div class="card" style="margin-bottom: 14px">
        <div class="row" style="gap: 12px; align-items: center">
          <input v-model="searchQuery" :placeholder="t('assetMarket.market.searchPlaceholder')" style="flex: 1" />
          <select v-model="filterType" style="width: 140px">
            <option value="">{{ t('assetMarket.market.allTypes') }}</option>
            <option value="table">{{ t('assetMarket.assetType.table') }}</option>
            <option value="api">{{ t('assetMarket.assetType.api') }}</option>
            <option value="model">{{ t('assetMarket.assetType.model') }}</option>
            <option value="dashboard">{{ t('assetMarket.assetType.dashboard') }}</option>
            <option value="stream">{{ t('assetMarket.assetType.stream') }}</option>
          </select>
          <select v-model="filterSecurity" style="width: 120px">
            <option value="">{{ t('assetMarket.market.allLevels') }}</option>
            <option value="public">{{ t('assetMarket.security.public') }}</option>
            <option value="internal">{{ t('assetMarket.security.internal') }}</option>
            <option value="sensitive">{{ t('assetMarket.security.sensitive') }}</option>
          </select>
        </div>
      </div>

      <!-- 资产卡片网格 -->
      <div class="grid g3">
        <div v-for="a in filteredAssets" :key="a.id" class="card asset-card" @click="openDetail(a)">
          <div class="asset-header">
            <span class="asset-type" :class="a.type">{{ typeLabel(a.type) }}</span>
            <span class="pill" :class="securityClass(a.securityLevel)">
              {{ securityLabel(a.securityLevel) }}
            </span>
          </div>
          <h3>{{ a.name }}</h3>
          <p class="asset-desc">{{ a.description || t('assetMarket.market.card.noDesc') }}</p>
          <div class="asset-meta">
            <span>{{ t('assetMarket.market.card.owner', { owner: a.owner }) }}</span>
            <span>{{ t('assetMarket.market.card.quality', { score: a.qualityScore }) }}</span>
          </div>
          <div class="asset-footer">
            <span class="price">{{ t('assetMarket.market.card.priceUnit', { price: a.pricing.price, unit: a.pricing.unit }) }}</span>
            <span class="sub-count">{{ t('assetMarket.market.card.subCount', { count: a.subscriberCount }) }}</span>
          </div>
        </div>
      </div>
      <div
        v-if="filteredAssets.length === 0"
        class="card"
        style="text-align: center; padding: 32px"
      >
        {{ t('assetMarket.market.empty') }}
      </div>
    </div>

    <!-- 我的订阅 -->
    <div v-if="tab === 'mine'">
      <div class="card">
        <h3>{{ t('assetMarket.mySubs.title') }}</h3>
        <div v-if="subsLoading" style="text-align: center; padding: 24px; color: #888">
          {{ t('assetMarket.mySubs.loading') }}
        </div>
        <div v-else-if="subsError" style="text-align: center; padding: 24px; color: #d4380d">
          {{ t('assetMarket.mySubs.loadFailed', { message: subsError.message }) }}
          <button class="btn ghost sm" style="margin-left: 8px" @click="loadMySubscriptions">
            {{ t('assetMarket.mySubs.retry') }}
          </button>
        </div>
        <template v-else-if="mySubscriptions">
          <table>
            <thead>
              <tr>
                <th>{{ t('assetMarket.mySubs.columns.asset') }}</th>
                <th>{{ t('assetMarket.mySubs.columns.owner') }}</th>
                <th>{{ t('assetMarket.mySubs.columns.status') }}</th>
                <th>{{ t('assetMarket.mySubs.columns.period') }}</th>
                <th>{{ t('assetMarket.mySubs.columns.delivery') }}</th>
                <th>{{ t('assetMarket.mySubs.columns.actions') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="s in mySubscriptions" :key="s.id">
                <td>{{ assetName(s.assetId) }}</td>
                <td>{{ assetOwner(s.assetId) }}</td>
                <td>
                  <span class="pill" :class="subStatusClass(s.status)">
                    {{ subStatusLabel(s.status) }}
                  </span>
                </td>
                <td>{{ t('assetMarket.mySubs.period', { start: formatDate(s.startTime), end: formatDate(s.endTime) }) }}</td>
                <td>
                  <span class="pill" :class="deliveryStatusClass(s.deliveryStatus)">
                    {{ deliveryStatusLabel(s.deliveryStatus) }}
                  </span>
                </td>
                <td>
                  <button v-if="s.status === 'active'" class="btn ghost sm" @click="openDeliver(s)">
                    {{ t('assetMarket.mySubs.deliver') }}
                  </button>
                  <button class="btn ghost sm" @click="openBilling(s)">{{ t('assetMarket.mySubs.billing') }}</button>
                </td>
              </tr>
            </tbody>
          </table>
          <div
            v-if="mySubscriptions.length === 0"
            style="text-align: center; padding: 24px; color: #888"
          >
            {{ t('assetMarket.mySubs.empty') }}
          </div>
        </template>
      </div>
    </div>

    <!-- 我上架的 -->
    <div v-if="tab === 'listed'">
      <div class="card">
        <h3>{{ t('assetMarket.myListed.title') }}</h3>
        <table>
          <thead>
            <tr>
              <th>{{ t('assetMarket.myListed.columns.name') }}</th>
              <th>{{ t('assetMarket.myListed.columns.type') }}</th>
              <th>{{ t('assetMarket.myListed.columns.status') }}</th>
              <th>{{ t('assetMarket.myListed.columns.subCount') }}</th>
              <th>{{ t('assetMarket.myListed.columns.revenue') }}</th>
              <th>{{ t('assetMarket.myListed.columns.actions') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="a in myAssets" :key="a.id">
              <td>{{ a.name }}</td>
              <td>{{ typeLabel(a.type) }}</td>
              <td>
                <span class="pill" :class="assetStatusClass(a.status)">
                  {{ assetStatusLabel(a.status) }}
                </span>
              </td>
              <td>{{ a.subscriberCount }}</td>
              <td>{{ t('assetMarket.myListed.revenueFmt', { amount: (a.subscriberCount * a.pricing.price).toFixed(2) }) }}</td>
              <td>
                <button class="btn ghost sm" @click="openDetail(a)">{{ t('assetMarket.myListed.detail') }}</button>
                <button v-if="a.status === 'listed'" class="btn ghost sm" @click="offlineAsset(a)">
                  {{ t('assetMarket.myListed.offline') }}
                </button>
                <button v-if="a.status === 'offline'" class="btn ghost sm" @click="relistAsset(a)">
                  {{ t('assetMarket.myListed.relist') }}
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="myAssets.length === 0" style="text-align: center; padding: 24px; color: #888">
          {{ t('assetMarket.myListed.empty') }}
        </div>
      </div>
    </div>

    <!-- 资产详情弹窗 -->
    <Modal
      :visible="detailVisible"
      :title="detailAsset?.name || t('assetMarket.detail.titleFallback')"
      @close="detailVisible = false"
    >
      <div v-if="detailAsset" class="detail-content">
        <div class="kv">
          <span>{{ t('assetMarket.detail.fields.type') }}</span>
          <span>{{ typeLabel(detailAsset.type) }}</span>
        </div>
        <div class="kv">
          <span>{{ t('assetMarket.detail.fields.owner') }}</span>
          <span>{{ detailAsset.owner }}</span>
        </div>
        <div class="kv">
          <span>{{ t('assetMarket.detail.fields.security') }}</span>
          <span>{{ securityLabel(detailAsset.securityLevel) }}</span>
        </div>
        <div class="kv">
          <span>{{ t('assetMarket.detail.fields.quality') }}</span>
          <span>{{ detailAsset.qualityScore }} / 100</span>
        </div>
        <div class="kv">
          <span>{{ t('assetMarket.detail.fields.updateFreq') }}</span>
          <span>{{ detailAsset.updateFrequency }}</span>
        </div>
        <div class="kv">
          <span>{{ t('assetMarket.detail.fields.price') }}</span>
          <span>
            {{ t('assetMarket.detail.fields.priceFmt', { price: detailAsset.pricing.price, unit: detailAsset.pricing.unit, mode: billingModeLabel(detailAsset.pricing.mode) }) }}
          </span>
        </div>
        <div class="kv">
          <span>{{ t('assetMarket.detail.fields.subscriber') }}</span>
          <span>{{ detailAsset.subscriberCount }}</span>
        </div>

        <h4 style="margin-top: 16px">{{ t('assetMarket.detail.schemaTitle') }}</h4>
        <table v-if="detailAsset.schema?.fields?.length">
          <thead>
            <tr>
              <th>{{ t('assetMarket.detail.schemaColumns.field') }}</th>
              <th>{{ t('assetMarket.detail.schemaColumns.type') }}</th>
              <th>{{ t('assetMarket.detail.schemaColumns.description') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="f in detailAsset.schema.fields" :key="f.name">
              <td>{{ f.name }}</td>
              <td>{{ f.type }}</td>
              <td>{{ f.description || '—' }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else style="color: #888">{{ t('assetMarket.detail.noSchema') }}</div>

        <h4 style="margin-top: 16px">{{ t('assetMarket.detail.sampleTitle') }}</h4>
        <pre v-if="detailAsset.sample?.length" class="sample">{{
          JSON.stringify(detailAsset.sample, null, 2)
        }}</pre>
        <div v-else style="color: #888">{{ t('assetMarket.detail.noSample') }}</div>
      </div>
      <template #footer>
        <button class="btn ghost" @click="detailVisible = false">{{ t('assetMarket.detail.close') }}</button>
        <button
          v-if="detailAsset && detailAsset.status === 'listed'"
          class="btn"
          @click="subscribeAsset(detailAsset)"
        >
          {{ t('assetMarket.detail.subscribe') }}
        </button>
      </template>
    </Modal>

    <!-- 上架表单 -->
    <Modal :visible="listModalVisible" :title="t('assetMarket.listForm.title')" @close="listModalVisible = false">
      <label>{{ t('assetMarket.listForm.name') }}</label>
      <input v-model="newAsset.name" :placeholder="t('assetMarket.listForm.namePlaceholder')" />
      <label>{{ t('assetMarket.listForm.type') }}</label>
      <select v-model="newAsset.type">
        <option value="table">{{ t('assetMarket.assetType.table') }}</option>
        <option value="api">{{ t('assetMarket.assetType.api') }}</option>
        <option value="model">{{ t('assetMarket.assetType.model') }}</option>
        <option value="dashboard">{{ t('assetMarket.assetType.dashboard') }}</option>
        <option value="stream">{{ t('assetMarket.assetType.stream') }}</option>
      </select>
      <label>{{ t('assetMarket.listForm.securityLevel') }}</label>
      <select v-model="newAsset.securityLevel">
        <option value="public">{{ t('assetMarket.security.public') }}</option>
        <option value="internal">{{ t('assetMarket.security.internal') }}</option>
        <option value="sensitive">{{ t('assetMarket.security.sensitive') }}</option>
      </select>
      <label>{{ t('assetMarket.listForm.description') }}</label>
      <input v-model="newAsset.description" :placeholder="t('assetMarket.listForm.descriptionPlaceholder')" />
      <label>{{ t('assetMarket.listForm.billingMode') }}</label>
      <select v-model="newAsset.pricing.mode">
        <option value="by_call">{{ t('assetMarket.billingMode.by_call') }}</option>
        <option value="by_data">{{ t('assetMarket.billingMode.by_data') }}</option>
        <option value="by_time">{{ t('assetMarket.billingMode.by_time_unit') }}</option>
        <option value="one_time">{{ t('assetMarket.billingMode.one_time') }}</option>
      </select>
      <label>{{ t('assetMarket.listForm.unitPrice') }}</label>
      <input v-model.number="newAsset.pricing.price" type="number" step="0.01" />
      <label>{{ t('assetMarket.listForm.deliveryMethod') }}</label>
      <select v-model="newAsset.deliveryMethod">
        <option value="api">{{ t('assetMarket.deliveryMethod.api') }}</option>
        <option value="file">{{ t('assetMarket.deliveryMethod.file') }}</option>
        <option value="database_direct">{{ t('assetMarket.deliveryMethod.database_direct') }}</option>
      </select>
      <template #footer>
        <button class="btn ghost" @click="listModalVisible = false">{{ t('assetMarket.listForm.cancel') }}</button>
        <button class="btn" @click="submitListAsset">{{ t('assetMarket.listForm.submit') }}</button>
      </template>
    </Modal>

    <!-- 交付弹窗 -->
    <Modal :visible="deliverModalVisible" :title="t('assetMarket.deliver.title')" @close="deliverModalVisible = false">
      <div v-if="deliverSub">
        <div class="kv">
          <span>{{ t('assetMarket.deliver.assetName') }}</span>
          <span>{{ assetName(deliverSub.assetId) }}</span>
        </div>
        <div class="kv">
          <span>{{ t('assetMarket.deliver.subscriberId') }}</span>
          <span>{{ deliverSub.subscriberId }}</span>
        </div>
      </div>
      <label>{{ t('assetMarket.deliver.method') }}</label>
      <select v-model="deliverReq.method">
        <option value="api">{{ t('assetMarket.deliveryMethod.api') }}</option>
        <option value="file">{{ t('assetMarket.deliveryMethod.file') }}</option>
        <option value="database_direct">{{ t('assetMarket.deliveryMethod.database_direct') }}</option>
      </select>
      <div v-if="deliverReq.method === 'api'">
        <label>{{ t('assetMarket.deliver.api.endpoint') }}</label>
        <input v-model="deliverReq.config.endpoint" :placeholder="t('assetMarket.deliver.api.endpointPlaceholder')" />
      </div>
      <div v-if="deliverReq.method === 'file'">
        <label>{{ t('assetMarket.deliver.file.format') }}</label>
        <select v-model="deliverReq.config.format">
          <option value="csv">{{ t('assetMarket.fileFormat.csv') }}</option>
          <option value="parquet">{{ t('assetMarket.fileFormat.parquet') }}</option>
          <option value="json">{{ t('assetMarket.fileFormat.json') }}</option>
        </select>
      </div>
      <div v-if="deliverReq.method === 'database_direct'">
        <label>{{ t('assetMarket.deliver.database.jdbcUrl') }}</label>
        <input v-model="deliverReq.config.jdbcUrl" :placeholder="t('assetMarket.deliver.database.jdbcUrlPlaceholder')" />
        <label>{{ t('assetMarket.deliver.database.tableName') }}</label>
        <input v-model="deliverReq.config.tableName" :placeholder="t('assetMarket.deliver.database.tableNamePlaceholder')" />
      </div>
      <template #footer>
        <button class="btn ghost" @click="deliverModalVisible = false">{{ t('assetMarket.deliver.cancel') }}</button>
        <button class="btn" @click="submitDeliver">{{ t('assetMarket.deliver.submit') }}</button>
      </template>
    </Modal>

    <!-- 账单弹窗 -->
    <Modal :visible="billingModalVisible" :title="t('assetMarket.billing.title')" @close="billingModalVisible = false">
      <div v-if="billingLoading" style="color: #888; text-align: center; padding: 24px">
        {{ t('assetMarket.billing.loading') }}
      </div>
      <div v-else-if="billingRecords.length">
        <table>
          <thead>
            <tr>
              <th>{{ t('assetMarket.billing.columns.period') }}</th>
              <th>{{ t('assetMarket.billing.columns.mode') }}</th>
              <th>{{ t('assetMarket.billing.columns.usage') }}</th>
              <th>{{ t('assetMarket.billing.columns.amount') }}</th>
              <th>{{ t('assetMarket.billing.columns.providerRevenue') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in billingRecords" :key="r.id">
              <td>{{ r.period }}</td>
              <td>{{ billingModeLabel(r.mode) }}</td>
              <td>{{ t('assetMarket.billing.usageFmt', { usage: r.usage, unit: r.unit }) }}</td>
              <td>{{ t('assetMarket.billing.amountFmt', { amount: r.amount.toFixed(2) }) }}</td>
              <td>{{ t('assetMarket.billing.amountFmt', { amount: r.providerRevenue.toFixed(2) }) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else style="color: #888; text-align: center; padding: 24px">{{ t('assetMarket.billing.empty') }}</div>
      <template #footer>
        <button class="btn ghost" @click="billingModalVisible = false">{{ t('assetMarket.billing.close') }}</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as assetMarketApi from '@/api/assetMarket'
import type { Asset, Subscription, BillingRecord } from '@/api/assetMarket'

const { t, te, locale } = useI18n()
const store = useAppStore()
const authStore = useAuthStore()

// Tab 状态
const tab = ref<'market' | 'mine' | 'listed'>('market')

// 筛选
const searchQuery = ref('')
const filterType = ref('')
const filterSecurity = ref('')

// 资产列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: assets,
  loading,
  error,
  execute: loadAssets
} = useApi<Asset[]>(() => assetMarketApi.listAssets(), { initialData: [] })

// 订阅列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: mySubscriptions,
  loading: subsLoading,
  error: subsError,
  execute: loadMySubscriptions
} = useApi<Subscription[]>(
  () => assetMarketApi.listSubscriptions({ subscriberId: currentTenant.value }),
  { initialData: [] }
)

// 计费记录
const billingRecords = ref<BillingRecord[]>([])
const billingLoading = ref(false)

// 当前租户 ID（从 auth store 获取，回退到 'tenant-B'）
const currentTenant = computed(() => authStore.user?.tenantId || 'tenant-B')

// 弹窗状态
const detailVisible = ref(false)
const detailAsset = ref<Asset | null>(null)
const listModalVisible = ref(false)
const deliverModalVisible = ref(false)
const deliverSub = ref<Subscription | null>(null)
const billingModalVisible = ref(false)

// 新建资产表单
const newAsset = ref<{
  name: string
  type: assetMarketApi.AssetType
  securityLevel: assetMarketApi.SecurityLevel
  description: string
  pricing: assetMarketApi.AssetPricing
  deliveryMethod: assetMarketApi.DeliveryMethod
}>({
  name: '',
  type: 'table',
  securityLevel: 'internal',
  description: '',
  pricing: { mode: 'by_call', price: 0.01, unit: '次' },
  deliveryMethod: 'api'
})

// 交付请求
const deliverReq = ref<{
  method: assetMarketApi.DeliveryMethod
  config: {
    endpoint: string
    format: string
    jdbcUrl: string
    tableName: string
  }
}>({
  method: 'api',
  config: {
    endpoint: '/api/v1/data/query',
    format: 'csv',
    jdbcUrl: '',
    tableName: ''
  }
})

// 计算属性
const filteredAssets = computed(() => {
  return (assets.value ?? []).filter((a) => {
    if (searchQuery.value && !a.name.includes(searchQuery.value)) return false
    if (filterType.value && a.type !== filterType.value) return false
    if (filterSecurity.value && a.securityLevel !== filterSecurity.value) return false
    return true
  })
})

const myAssets = computed(() => (assets.value ?? []).filter((a) => a.owner === currentTenant.value))

const activeSubCount = computed(
  () => (mySubscriptions.value ?? []).filter((s) => s.status === 'active').length
)

const totalRevenue = computed(() =>
  myAssets.value.reduce((sum, a) => sum + a.subscriberCount * a.pricing.price * 0.8, 0)
)

const totalPlatformRevenue = computed(() =>
  myAssets.value.reduce((sum, a) => sum + a.subscriberCount * a.pricing.price * 0.2, 0)
)

// 标签函数
function typeLabel(tp: string): string {
  return t(`assetMarket.assetType.${tp}`)
}

function securityLabel(s: string): string {
  return t(`assetMarket.security.${s}`)
}

function securityClass(s: string): string {
  const map: Record<string, string> = { public: 'g', internal: 'a', sensitive: 'p' }
  return map[s] || ''
}

function subStatusLabel(s: string): string {
  return t(`assetMarket.subStatus.${s}`)
}

function subStatusClass(s: string): string {
  const map: Record<string, string> = {
    pending: 'a',
    approved: 'g',
    active: 'g',
    expired: 'p',
    rejected: 'p'
  }
  return map[s] || ''
}

function deliveryStatusLabel(s?: string): string {
  if (!s) return t('assetMarket.deliveryStatus.none')
  const key = `assetMarket.deliveryStatus.${s}`
  return te(key) ? t(key) : s
}

function deliveryStatusClass(s?: string): string {
  if (!s) return ''
  const map: Record<string, string> = {
    pending: 'a',
    running: 'a',
    succeeded: 'g',
    failed: 'p'
  }
  return map[s] || ''
}

function assetStatusLabel(s: string): string {
  return t(`assetMarket.assetStatus.${s}`)
}

function assetStatusClass(s: string): string {
  const map: Record<string, string> = { listed: 'g', offline: 'p', rejected: 'p' }
  return map[s] || ''
}

function billingModeLabel(m: string): string {
  return t(`assetMarket.billingMode.${m}`)
}

function assetName(id: string): string {
  return (assets.value ?? []).find((a) => a.id === id)?.name || id
}

function assetOwner(id: string): string {
  return (assets.value ?? []).find((a) => a.id === id)?.owner || '—'
}

function formatDate(d?: string): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString(locale.value === 'zh-CN' ? 'zh-CN' : 'en-US')
}

// 操作函数
function openDetail(a: Asset) {
  detailAsset.value = a
  detailVisible.value = true
}

async function subscribeAsset(a: Asset) {
  try {
    const sub = await assetMarketApi.subscribeAsset(a.id, {
      subscriberId: currentTenant.value
    })
    mySubscriptions.value?.push(sub)
    detailVisible.value = false
    store.showToast(t('assetMarket.messages.subscribed', { name: a.name }))
  } catch (e) {
    store.showToast(t('assetMarket.messages.subscribeFailed', { message: (e as Error).message }))
  }
}

function openDeliver(s: Subscription) {
  deliverSub.value = s
  deliverModalVisible.value = true
}

async function submitDeliver() {
  if (!deliverSub.value) return
  try {
    const updated = await assetMarketApi.deliverAsset(deliverSub.value.id, {
      method: deliverReq.value.method,
      config: deliverReq.value.config
    })
    Object.assign(deliverSub.value, updated)
    deliverModalVisible.value = false
    store.showToast(t('assetMarket.deliver.success'))
  } catch (e) {
    store.showToast(t('assetMarket.deliver.failed', { message: (e as Error).message }))
  }
}

async function openBilling(s: Subscription) {
  billingModalVisible.value = true
  billingLoading.value = true
  try {
    billingRecords.value = await assetMarketApi.getBillingRecords(s.id)
  } catch (e) {
    store.showToast(t('assetMarket.billing.loadFailed', { message: (e as Error).message }))
    billingRecords.value = []
  } finally {
    billingLoading.value = false
  }
}

async function submitListAsset() {
  if (!newAsset.value.name) {
    store.showToast(t('assetMarket.listForm.needName'))
    return
  }
  try {
    const created = await assetMarketApi.listAsset({
      name: newAsset.value.name,
      type: newAsset.value.type,
      securityLevel: newAsset.value.securityLevel,
      description: newAsset.value.description,
      pricing: { ...newAsset.value.pricing },
      deliveryMethod: newAsset.value.deliveryMethod
    })
    assets.value?.push(created)
    listModalVisible.value = false
    store.showToast(t('assetMarket.listForm.submitted', { name: newAsset.value.name }))
    // 重置表单
    newAsset.value = {
      name: '',
      type: 'table',
      securityLevel: 'internal',
      description: '',
      pricing: { mode: 'by_call', price: 0.01, unit: '次' },
      deliveryMethod: 'api'
    }
  } catch (e) {
    store.showToast(t('assetMarket.listForm.failed', { message: (e as Error).message }))
  }
}

async function offlineAsset(a: Asset) {
  try {
    const updated = await assetMarketApi.offlineAsset(a.id)
    Object.assign(a, updated)
    store.showToast(t('assetMarket.messages.offlined', { name: a.name }))
  } catch (e) {
    store.showToast(t('assetMarket.messages.offlineFailed', { message: (e as Error).message }))
  }
}

async function relistAsset(a: Asset) {
  try {
    const updated = await assetMarketApi.relistAsset(a.id)
    Object.assign(a, updated)
    store.showToast(t('assetMarket.messages.relisted', { name: a.name }))
  } catch (e) {
    store.showToast(t('assetMarket.messages.relistFailed', { message: (e as Error).message }))
  }
}

// 初始化：从后端加载资产与订阅列表
onMounted(() => {
  void loadAssets()
  void loadMySubscriptions()
})
</script>

<style scoped>
.asset-card {
  cursor: pointer;
  transition:
    transform 0.15s,
    box-shadow 0.15s;
}
.asset-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
}
.asset-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.asset-type {
  font-size: 12px;
  padding: 2px 8px;
  border-radius: 4px;
  background: #e8f4fd;
  color: #1890ff;
}
.asset-type.model {
  background: #f6e8fd;
  color: #722ed1;
}
.asset-type.stream {
  background: #e8fdf6;
  color: #13c2c2;
}
.asset-type.dashboard {
  background: #fdf6e8;
  color: #fa8c16;
}
.asset-desc {
  color: #666;
  font-size: 13px;
  margin: 8px 0;
  min-height: 40px;
}
.asset-meta {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
  color: #888;
  margin-bottom: 8px;
}
.asset-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid #eee;
  padding-top: 8px;
}
.price {
  color: #1890ff;
  font-weight: 600;
}
.sub-count {
  font-size: 12px;
  color: #888;
}
.detail-content .kv {
  margin: 6px 0;
}
.sample {
  background: #f5f5f5;
  padding: 12px;
  border-radius: 4px;
  font-size: 12px;
  max-height: 200px;
  overflow: auto;
}
</style>
