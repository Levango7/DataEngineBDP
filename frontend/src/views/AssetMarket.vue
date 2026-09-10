<template>
  <div>
    <PageHeader :title="t('assetMarket.title')" :subtitle="t('assetMarket.subtitle')" />

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
    <div v-if="loading" class="card state-tip">
      {{ t('assetMarket.loading') }}
    </div>
    <div v-else-if="error" class="card state-tip error">
      {{ t('assetMarket.loadFailed', { message: error.message }) }}
      <el-button size="small" style="margin-left: 8px" @click="loadAssets">
        {{ t('assetMarket.retry') }}
      </el-button>
    </div>

    <!-- Tab 切换 -->
    <Toolbar
      style="margin-top: 14px"
      :show-create="true"
      :create-label="t('assetMarket.listAsset')"
      :create-aria-label="t('assetMarket.listAsset')"
      :show-refresh="false"
      @create="listModalVisible = true"
    >
      <template #filters>
        <el-button
          size="small"
          :type="tab === 'market' ? 'primary' : 'default'"
          @click="tab = 'market'"
        >
          {{ t('assetMarket.tabs.market') }}
        </el-button>
        <el-button
          size="small"
          :type="tab === 'mine' ? 'primary' : 'default'"
          @click="tab = 'mine'"
        >
          {{ t('assetMarket.tabs.mine') }}
        </el-button>
        <el-button
          size="small"
          :type="tab === 'listed' ? 'primary' : 'default'"
          @click="tab = 'listed'"
        >
          {{ t('assetMarket.tabs.listed') }}
        </el-button>
      </template>
    </Toolbar>

    <!-- 资产市场：卡片式浏览 -->
    <div v-if="tab === 'market'">
      <!-- 筛选 -->
      <div class="card" style="margin-bottom: 14px">
        <div class="row" style="gap: 12px; align-items: center">
          <el-input
            v-model="searchQuery"
            :placeholder="t('assetMarket.market.searchPlaceholder')"
            style="flex: 1"
          />
          <el-select v-model="filterType" style="width: 140px">
            <el-option :label="t('assetMarket.market.allTypes')" value="" />
            <el-option :label="t('assetMarket.assetType.table')" value="table" />
            <el-option :label="t('assetMarket.assetType.api')" value="api" />
            <el-option :label="t('assetMarket.assetType.model')" value="model" />
            <el-option :label="t('assetMarket.assetType.dashboard')" value="dashboard" />
            <el-option :label="t('assetMarket.assetType.stream')" value="stream" />
          </el-select>
          <el-select v-model="filterSecurity" style="width: 120px">
            <el-option :label="t('assetMarket.market.allLevels')" value="" />
            <el-option :label="t('assetMarket.security.public')" value="public" />
            <el-option :label="t('assetMarket.security.internal')" value="internal" />
            <el-option :label="t('assetMarket.security.sensitive')" value="sensitive" />
          </el-select>
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
            <span class="price">
              {{
                t('assetMarket.market.card.priceUnit', {
                  price: a.pricing.price,
                  unit: a.pricing.unit
                })
              }}
            </span>
            <span class="sub-count">
              {{ t('assetMarket.market.card.subCount', { count: a.subscriberCount }) }}
            </span>
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
        <div v-if="subsLoading" class="state-tip">
          {{ t('assetMarket.mySubs.loading') }}
        </div>
        <div v-else-if="subsError" class="state-tip error">
          {{ t('assetMarket.mySubs.loadFailed', { message: subsError.message }) }}
          <el-button size="small" style="margin-left: 8px" @click="loadMySubscriptions">
            {{ t('assetMarket.mySubs.retry') }}
          </el-button>
        </div>
        <template v-else-if="mySubscriptions">
          <el-table :data="mySubscriptions" stripe>
            <el-table-column :label="t('assetMarket.mySubs.columns.asset')">
              <template #default="{ row }">{{ assetName(row.assetId) }}</template>
            </el-table-column>
            <el-table-column :label="t('assetMarket.mySubs.columns.owner')">
              <template #default="{ row }">{{ assetOwner(row.assetId) }}</template>
            </el-table-column>
            <el-table-column :label="t('assetMarket.mySubs.columns.status')">
              <template #default="{ row }">
                <span class="pill" :class="subStatusClass(row.status)">
                  {{ subStatusLabel(row.status) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('assetMarket.mySubs.columns.period')">
              <template #default="{ row }">
                {{
                  t('assetMarket.mySubs.period', {
                    start: formatDate(row.startTime),
                    end: formatDate(row.endTime)
                  })
                }}
              </template>
            </el-table-column>
            <el-table-column :label="t('assetMarket.mySubs.columns.delivery')">
              <template #default="{ row }">
                <span class="pill" :class="deliveryStatusClass(row.deliveryStatus)">
                  {{ deliveryStatusLabel(row.deliveryStatus) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('assetMarket.mySubs.columns.actions')">
              <template #default="{ row }">
                <el-button v-if="row.status === 'active'" size="small" @click="openDeliver(row)">
                  {{ t('assetMarket.mySubs.deliver') }}
                </el-button>
                <el-button size="small" @click="openBilling(row)">
                  {{ t('assetMarket.mySubs.billing') }}
                </el-button>
              </template>
            </el-table-column>
            <template #empty>
              <div class="empty-cell">{{ t('assetMarket.mySubs.empty') }}</div>
            </template>
          </el-table>
        </template>
      </div>
    </div>

    <!-- 我上架的 -->
    <div v-if="tab === 'listed'">
      <div class="card">
        <h3>{{ t('assetMarket.myListed.title') }}</h3>
        <el-table :data="myAssets" stripe>
          <el-table-column :label="t('assetMarket.myListed.columns.name')" prop="name" />
          <el-table-column :label="t('assetMarket.myListed.columns.type')">
            <template #default="{ row }">{{ typeLabel(row.type) }}</template>
          </el-table-column>
          <el-table-column :label="t('assetMarket.myListed.columns.status')">
            <template #default="{ row }">
              <span class="pill" :class="assetStatusClass(row.status)">
                {{ assetStatusLabel(row.status) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('assetMarket.myListed.columns.subCount')"
            prop="subscriberCount"
          />
          <el-table-column :label="t('assetMarket.myListed.columns.revenue')">
            <template #default="{ row }">
              {{
                t('assetMarket.myListed.revenueFmt', {
                  amount: (row.subscriberCount * row.pricing.price).toFixed(2)
                })
              }}
            </template>
          </el-table-column>
          <el-table-column :label="t('assetMarket.myListed.columns.actions')">
            <template #default="{ row }">
              <el-button size="small" @click="openDetail(row)">
                {{ t('assetMarket.myListed.detail') }}
              </el-button>
              <el-button v-if="row.status === 'listed'" size="small" @click="offlineAsset(row)">
                {{ t('assetMarket.myListed.offline') }}
              </el-button>
              <el-button v-if="row.status === 'offline'" size="small" @click="relistAsset(row)">
                {{ t('assetMarket.myListed.relist') }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="empty-cell">{{ t('assetMarket.myListed.empty') }}</div>
          </template>
        </el-table>
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
            {{
              t('assetMarket.detail.fields.priceFmt', {
                price: detailAsset.pricing.price,
                unit: detailAsset.pricing.unit,
                mode: billingModeLabel(detailAsset.pricing.mode)
              })
            }}
          </span>
        </div>
        <div class="kv">
          <span>{{ t('assetMarket.detail.fields.subscriber') }}</span>
          <span>{{ detailAsset.subscriberCount }}</span>
        </div>

        <h4 style="margin-top: 16px">{{ t('assetMarket.detail.schemaTitle') }}</h4>
        <el-table
          v-if="detailAsset.schema?.fields?.length"
          :data="detailAsset.schema.fields"
          stripe
        >
          <el-table-column :label="t('assetMarket.detail.schemaColumns.field')" prop="name" />
          <el-table-column :label="t('assetMarket.detail.schemaColumns.type')" prop="type" />
          <el-table-column :label="t('assetMarket.detail.schemaColumns.description')">
            <template #default="{ row }">{{ row.description || '—' }}</template>
          </el-table-column>
        </el-table>
        <div v-else class="muted-text">{{ t('assetMarket.detail.noSchema') }}</div>

        <h4 style="margin-top: 16px">{{ t('assetMarket.detail.sampleTitle') }}</h4>
        <pre v-if="detailAsset.sample?.length" class="sample">{{
          JSON.stringify(detailAsset.sample, null, 2)
        }}</pre>
        <div v-else class="muted-text">{{ t('assetMarket.detail.noSample') }}</div>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">
          {{ t('assetMarket.detail.close') }}
        </el-button>
        <el-button
          v-if="detailAsset && detailAsset.status === 'listed'"
          type="primary"
          @click="subscribeAsset(detailAsset)"
        >
          {{ t('assetMarket.detail.subscribe') }}
        </el-button>
      </template>
    </Modal>

    <!-- 上架表单 -->
    <Modal
      :visible="listModalVisible"
      :title="t('assetMarket.listForm.title')"
      @close="listModalVisible = false"
    >
      <label>{{ t('assetMarket.listForm.name') }}</label>
      <el-input v-model="newAsset.name" :placeholder="t('assetMarket.listForm.namePlaceholder')" />
      <label>{{ t('assetMarket.listForm.type') }}</label>
      <el-select v-model="newAsset.type">
        <el-option :label="t('assetMarket.assetType.table')" value="table" />
        <el-option :label="t('assetMarket.assetType.api')" value="api" />
        <el-option :label="t('assetMarket.assetType.model')" value="model" />
        <el-option :label="t('assetMarket.assetType.dashboard')" value="dashboard" />
        <el-option :label="t('assetMarket.assetType.stream')" value="stream" />
      </el-select>
      <label>{{ t('assetMarket.listForm.securityLevel') }}</label>
      <el-select v-model="newAsset.securityLevel">
        <el-option :label="t('assetMarket.security.public')" value="public" />
        <el-option :label="t('assetMarket.security.internal')" value="internal" />
        <el-option :label="t('assetMarket.security.sensitive')" value="sensitive" />
      </el-select>
      <label>{{ t('assetMarket.listForm.description') }}</label>
      <el-input
        v-model="newAsset.description"
        :placeholder="t('assetMarket.listForm.descriptionPlaceholder')"
      />
      <label>{{ t('assetMarket.listForm.billingMode') }}</label>
      <el-select v-model="newAsset.pricing.mode">
        <el-option :label="t('assetMarket.billingMode.by_call')" value="by_call" />
        <el-option :label="t('assetMarket.billingMode.by_data')" value="by_data" />
        <el-option :label="t('assetMarket.billingMode.by_time_unit')" value="by_time" />
        <el-option :label="t('assetMarket.billingMode.one_time')" value="one_time" />
      </el-select>
      <label>{{ t('assetMarket.listForm.unitPrice') }}</label>
      <el-input-number v-model="newAsset.pricing.price" :step="0.01" :min="0" />
      <label>{{ t('assetMarket.listForm.deliveryMethod') }}</label>
      <el-select v-model="newAsset.deliveryMethod">
        <el-option :label="t('assetMarket.deliveryMethod.api')" value="api" />
        <el-option :label="t('assetMarket.deliveryMethod.file')" value="file" />
        <el-option
          :label="t('assetMarket.deliveryMethod.database_direct')"
          value="database_direct"
        />
      </el-select>
      <template #footer>
        <el-button @click="listModalVisible = false">
          {{ t('assetMarket.listForm.cancel') }}
        </el-button>
        <el-button type="primary" @click="submitListAsset">
          {{ t('assetMarket.listForm.submit') }}
        </el-button>
      </template>
    </Modal>

    <!-- 交付弹窗 -->
    <Modal
      :visible="deliverModalVisible"
      :title="t('assetMarket.deliver.title')"
      @close="deliverModalVisible = false"
    >
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
      <el-select v-model="deliverReq.method">
        <el-option :label="t('assetMarket.deliveryMethod.api')" value="api" />
        <el-option :label="t('assetMarket.deliveryMethod.file')" value="file" />
        <el-option
          :label="t('assetMarket.deliveryMethod.database_direct')"
          value="database_direct"
        />
      </el-select>
      <div v-if="deliverReq.method === 'api'">
        <label>{{ t('assetMarket.deliver.api.endpoint') }}</label>
        <el-input
          v-model="deliverReq.config.endpoint"
          :placeholder="t('assetMarket.deliver.api.endpointPlaceholder')"
        />
      </div>
      <div v-if="deliverReq.method === 'file'">
        <label>{{ t('assetMarket.deliver.file.format') }}</label>
        <el-select v-model="deliverReq.config.format">
          <el-option :label="t('assetMarket.fileFormat.csv')" value="csv" />
          <el-option :label="t('assetMarket.fileFormat.parquet')" value="parquet" />
          <el-option :label="t('assetMarket.fileFormat.json')" value="json" />
        </el-select>
      </div>
      <div v-if="deliverReq.method === 'database_direct'">
        <label>{{ t('assetMarket.deliver.database.jdbcUrl') }}</label>
        <el-input
          v-model="deliverReq.config.jdbcUrl"
          :placeholder="t('assetMarket.deliver.database.jdbcUrlPlaceholder')"
        />
        <label>{{ t('assetMarket.deliver.database.tableName') }}</label>
        <el-input
          v-model="deliverReq.config.tableName"
          :placeholder="t('assetMarket.deliver.database.tableNamePlaceholder')"
        />
      </div>
      <template #footer>
        <el-button @click="deliverModalVisible = false">
          {{ t('assetMarket.deliver.cancel') }}
        </el-button>
        <el-button type="primary" @click="submitDeliver">
          {{ t('assetMarket.deliver.submit') }}
        </el-button>
      </template>
    </Modal>

    <!-- 账单弹窗 -->
    <Modal
      :visible="billingModalVisible"
      :title="t('assetMarket.billing.title')"
      @close="billingModalVisible = false"
    >
      <div v-if="billingLoading" class="state-tip">
        {{ t('assetMarket.billing.loading') }}
      </div>
      <el-table v-else-if="billingRecords.length" :data="billingRecords" stripe>
        <el-table-column :label="t('assetMarket.billing.columns.period')" prop="period" />
        <el-table-column :label="t('assetMarket.billing.columns.mode')">
          <template #default="{ row }">{{ billingModeLabel(row.mode) }}</template>
        </el-table-column>
        <el-table-column :label="t('assetMarket.billing.columns.usage')">
          <template #default="{ row }">
            {{ t('assetMarket.billing.usageFmt', { usage: row.usage, unit: row.unit }) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('assetMarket.billing.columns.amount')">
          <template #default="{ row }">
            {{ t('assetMarket.billing.amountFmt', { amount: row.amount.toFixed(2) }) }}
          </template>
        </el-table-column>
        <el-table-column :label="t('assetMarket.billing.columns.providerRevenue')">
          <template #default="{ row }">
            {{ t('assetMarket.billing.amountFmt', { amount: row.providerRevenue.toFixed(2) }) }}
          </template>
        </el-table-column>
      </el-table>
      <div v-else class="state-tip">
        {{ t('assetMarket.billing.empty') }}
      </div>
      <template #footer>
        <el-button @click="billingModalVisible = false">
          {{ t('assetMarket.billing.close') }}
        </el-button>
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
import { PageHeader, Toolbar } from '@/components/ui'
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
/* 通用文字色辅助类（使用 design tokens） */
.muted-text {
  color: var(--ds-text-tertiary);
}
/* 状态提示：加载/错误统一样式 */
.state-tip {
  text-align: center;
  padding: 24px;
  color: var(--ds-text-tertiary);
}
.state-tip.error {
  color: var(--ds-color-error-600);
}
/* 空状态单元格 */
.empty-cell {
  text-align: center;
  color: var(--ds-text-tertiary);
  padding: 16px;
}

.asset-card {
  cursor: pointer;
  transition:
    transform var(--ds-transition-fast),
    box-shadow var(--ds-transition-fast);
}
.asset-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--ds-shadow-md);
}
.asset-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
/* 资产类型标签：使用 design tokens 语义色 */
.asset-type {
  font-size: var(--ds-font-size-xs);
  padding: 2px 8px;
  border-radius: var(--ds-radius-sm);
  background: var(--ds-color-primary-50);
  color: var(--ds-color-primary-600);
}
.asset-type.model {
  background: var(--ds-color-info-50);
  color: var(--ds-color-info-600);
}
.asset-type.stream {
  background: var(--ds-color-success-50);
  color: var(--ds-color-success-600);
}
.asset-type.dashboard {
  background: var(--ds-color-warning-50);
  color: var(--ds-color-warning-600);
}
.asset-desc {
  color: var(--ds-text-secondary);
  font-size: var(--ds-font-size-sm);
  margin: 8px 0;
  min-height: 40px;
}
.asset-meta {
  display: flex;
  justify-content: space-between;
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
  margin-bottom: 8px;
}
.asset-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-top: 1px solid var(--ds-border-subtle);
  padding-top: 8px;
}
.price {
  color: var(--ds-color-primary-600);
  font-weight: var(--ds-font-weight-semibold);
}
.sub-count {
  font-size: var(--ds-font-size-xs);
  color: var(--ds-text-tertiary);
}
.detail-content .kv {
  margin: 6px 0;
}
/* 样本数据展示：使用 design tokens 次级表面色 */
.sample {
  background: var(--ds-bg-subtle);
  padding: 12px;
  border-radius: var(--ds-radius-sm);
  font-size: var(--ds-font-size-xs);
  max-height: 200px;
  overflow: auto;
}

/* 响应式断点：中等屏幕收窄卡片网格 */
@media (max-width: 1100px) {
  :deep(.el-table) {
    font-size: var(--ds-font-size-sm);
  }
}

/* 响应式断点：小屏幕单列布局 */
@media (max-width: 720px) {
  :deep(.el-table) {
    font-size: var(--ds-font-size-xs);
  }
  :deep(.el-table .cell) {
    padding-left: 8px;
    padding-right: 8px;
  }
  /* 筛选行在小屏幕下垂直排列 */
  .row {
    flex-direction: column;
    align-items: stretch !important;
  }
  .row .el-select {
    width: 100% !important;
  }
}
</style>
