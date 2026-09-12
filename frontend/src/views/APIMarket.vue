<template>
  <div>
    <PageHeader :title="t('apiMarket.title')" :subtitle="t('apiMarket.subtitle')" />

    <!-- KPI 概览 -->
    <div class="grid g4">
      <div class="card">
        <h3>{{ t('apiMarket.kpi.published') }}</h3>
        <div class="kpi s">{{ safeApiList.length }}</div>
        <div class="meta">
          {{ t('apiMarket.kpi.runningDraft', { running: runningCount, draft: draftCount }) }}
        </div>
      </div>
      <div class="card">
        <h3>{{ t('apiMarket.kpi.monthlyCalls') }}</h3>
        <div class="kpi s">{{ formatNumber(totalCalls) }}</div>
        <div class="meta">
          {{ t('apiMarket.kpi.successRate', { rate: (totalSuccessRate * 100).toFixed(1) }) }}
        </div>
      </div>
      <div class="card">
        <h3>{{ t('apiMarket.kpi.activeSubs') }}</h3>
        <div class="kpi s">{{ activeSubscriptions }}</div>
        <div class="meta">
          {{ t('apiMarket.kpi.pendingSubs', { count: pendingSubscriptions }) }}
        </div>
      </div>
      <div class="card">
        <h3>{{ t('apiMarket.kpi.slaPlatinum') }}</h3>
        <div class="kpi s">{{ platinumCount }}</div>
        <div class="meta">
          {{ t('apiMarket.kpi.slaBreakdown', { gold: goldCount, silver: silverCount }) }}
        </div>
      </div>
    </div>

    <!-- 工具栏 -->
    <Toolbar
      v-model:search-value="keyword"
      style="margin-top: 14px"
      :show-create="true"
      :create-label="t('apiMarket.toolbar.register')"
      :create-aria-label="t('apiMarket.toolbar.register')"
      :search-placeholder="t('apiMarket.toolbar.searchPlaceholder')"
      :search-aria-label="t('apiMarket.toolbar.searchPlaceholder')"
      :show-refresh="false"
      @create="registerModal = true"
      @search="debouncedRefreshList"
    >
      <template #filters>
        <el-select
          v-model="categoryFilter"
          :placeholder="t('apiMarket.toolbar.categoryPlaceholder')"
          @change="refreshList"
        >
          <el-option :label="t('apiMarket.toolbar.allCategories')" value="" />
          <el-option v-for="cat in categories" :key="cat" :label="cat" :value="cat" />
        </el-select>
        <el-select
          v-model="statusFilter"
          :placeholder="t('apiMarket.toolbar.statusPlaceholder')"
          @change="refreshList"
        >
          <el-option :label="t('apiMarket.toolbar.allStatuses')" value="" />
          <el-option :label="t('apiMarket.status.api.running')" value="running" />
          <el-option :label="t('apiMarket.status.api.draft')" value="draft" />
          <el-option :label="t('apiMarket.status.api.deprecated')" value="deprecated" />
        </el-select>
      </template>
    </Toolbar>

    <!-- API 卡片网格 -->
    <div v-if="loading" class="card" style="margin-top: 14px">
      <div class="meta muted-text">{{ t('apiMarket.list.loading') }}</div>
    </div>
    <div v-else-if="error" class="card" style="margin-top: 14px" role="alert">
      <div class="meta error-text">
        {{ t('apiMarket.list.loadFailed', { message: error.message }) }}
        <el-button size="small" style="margin-left: 8px" @click="refreshList">
          {{ t('apiMarket.list.retry') }}
        </el-button>
      </div>
    </div>
    <div v-else-if="apiList && apiList.length === 0" class="card" style="margin-top: 14px">
      <div class="meta muted-text">{{ t('apiMarket.list.empty') }}</div>
    </div>
    <div v-else-if="apiList" class="api-grid" style="margin-top: 14px">
      <div v-for="api in apiList" :key="api.id" class="card api-card" @click="openDetail(api)">
        <div class="api-card-header">
          <span class="api-name">{{ api.name }}</span>
          <span :class="['pill', slaClass(api.sla)]">{{ slaLabel(api.sla) }}</span>
        </div>
        <div class="api-card-desc">{{ api.description || t('apiMarket.list.noDesc') }}</div>
        <div class="api-card-meta">
          <code>{{ api.method }} {{ api.path }}</code>
          <span class="version">v{{ api.version }}</span>
        </div>
        <div class="api-card-tags">
          <span class="tag">{{ api.category }}</span>
          <span v-for="tag in api.tags" :key="tag" class="tag">{{ tag }}</span>
        </div>
        <div class="api-card-footer">
          <span :class="['pill', statusClass(api.status)]">{{ statusLabel(api.status) }}</span>
          <span class="meta">
            {{ t('apiMarket.list.callCount', { count: formatNumber(api.callCount) }) }}
          </span>
        </div>
      </div>
    </div>

    <!-- API 详情抽屉 -->
    <div v-if="selectedApi" class="overlay show" @click.self="selectedApi = null"></div>
    <div v-if="selectedApi" class="modal show" style="width: 800px; max-width: 95vw">
      <div class="mh">
        <span>{{ selectedApi.name }}{{ t('apiMarket.detail.titleSuffix') }}</span>
        <span class="x" @click="selectedApi = null">×</span>
      </div>
      <div class="mb">
        <!-- Tab 切换 -->
        <div class="tab-bar">
          <el-button
            v-for="tab in detailTabs"
            :key="tab.key"
            :type="activeTab === tab.key ? 'primary' : 'default'"
            size="small"
            @click="activeTab = tab.key"
          >
            {{ t(tab.labelKey) }}
          </el-button>
        </div>

        <!-- 文档 Tab -->
        <div v-if="activeTab === 'doc'" class="tab-content">
          <div class="kv">
            <span>{{ t('apiMarket.detail.doc.name') }}</span>
            <span>{{ selectedApi.name }}</span>
          </div>
          <div class="kv">
            <span>{{ t('apiMarket.detail.doc.version') }}</span>
            <span>{{ selectedApi.version }}</span>
          </div>
          <div class="kv">
            <span>{{ t('apiMarket.detail.doc.method') }}</span>
            <span>
              <code>{{ selectedApi.method }} {{ selectedApi.path }}</code>
            </span>
          </div>
          <div class="kv">
            <span>{{ t('apiMarket.detail.doc.description') }}</span>
            <span>{{ selectedApi.description || '—' }}</span>
          </div>
          <div class="kv">
            <span>{{ t('apiMarket.detail.doc.auth') }}</span>
            <span>{{ authLabel(selectedApi.authType) }}</span>
          </div>
          <div class="kv">
            <span>{{ t('apiMarket.detail.doc.sla') }}</span>
            <span>{{ slaLabel(selectedApi.sla) }}</span>
          </div>
          <div class="kv">
            <span>{{ t('apiMarket.detail.doc.cost') }}</span>
            <span>
              {{
                t('apiMarket.detail.doc.costUnitPrice', {
                  label: costLabel(selectedApi.costStrategy),
                  price: selectedApi.costUnitPrice
                })
              }}
            </span>
          </div>
          <div class="kv">
            <span>{{ t('apiMarket.detail.doc.status') }}</span>
            <span>{{ statusLabel(selectedApi.status) }}</span>
          </div>
          <h4 style="margin-top: 12px">{{ t('apiMarket.detail.doc.paramsTitle') }}</h4>
          <el-table v-if="selectedApi.params.length > 0" :data="selectedApi.params" stripe>
            <el-table-column :label="t('apiMarket.detail.doc.paramColumns.name')">
              <template #default="{ row }">
                <code>{{ row.name }}</code>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('apiMarket.detail.doc.paramColumns.location')"
              prop="location"
            />
            <el-table-column :label="t('apiMarket.detail.doc.paramColumns.type')" prop="type" />
            <el-table-column :label="t('apiMarket.detail.doc.paramColumns.required')">
              <template #default="{ row }">
                {{ row.required ? t('apiMarket.detail.doc.yes') : t('apiMarket.detail.doc.no') }}
              </template>
            </el-table-column>
            <el-table-column :label="t('apiMarket.detail.doc.paramColumns.description')">
              <template #default="{ row }">{{ row.description || '—' }}</template>
            </el-table-column>
          </el-table>
          <div v-else class="meta muted-text">
            {{ t('apiMarket.detail.doc.noParams') }}
          </div>
        </div>

        <!-- 试调 Tab -->
        <div v-if="activeTab === 'try'" class="tab-content">
          <label>{{ t('apiMarket.detail.try.apiKey') }}</label>
          <el-input
            v-model="testApiKey"
            :placeholder="t('apiMarket.detail.try.apiKeyPlaceholder')"
          />
          <label>{{ t('apiMarket.detail.try.payload') }}</label>
          <el-input
            v-model="testPayload"
            type="textarea"
            :rows="5"
            :placeholder="t('apiMarket.detail.try.payloadPlaceholder')"
          />
          <el-button
            size="small"
            style="margin-top: 8px"
            type="primary"
            :disabled="calling"
            @click="executeCall"
          >
            {{ calling ? t('apiMarket.detail.try.submitting') : t('apiMarket.detail.try.submit') }}
          </el-button>
          <div v-if="callResult" class="call-result" style="margin-top: 12px">
            <div class="kv">
              <span>{{ t('apiMarket.detail.try.result.statusCode') }}</span>
              <span :class="['pill', callResult.statusCode === 200 ? 'g' : 'r']">
                {{ callResult.statusCode }}
              </span>
            </div>
            <div class="kv">
              <span>{{ t('apiMarket.detail.try.result.latency') }}</span>
              <span>
                {{ callResult.latencyMs.toFixed(2)
                }}{{ t('apiMarket.detail.try.result.latencyUnit') }}
              </span>
            </div>
            <div class="kv">
              <span>{{ t('apiMarket.detail.try.result.cost') }}</span>
              <span>{{ callResult.costAmount }}</span>
            </div>
            <div v-if="callResult.error" class="kv">
              <span>{{ t('apiMarket.detail.try.result.error') }}</span>
              <span class="error-text">{{ callResult.error }}</span>
            </div>
            <div v-if="callResult.result">
              <label>{{ t('apiMarket.detail.try.result.response') }}</label>
              <pre class="code-block">{{ JSON.stringify(callResult.result, null, 2) }}</pre>
            </div>
          </div>
        </div>

        <!-- 订阅 Tab -->
        <div v-if="activeTab === 'subscribe'" class="tab-content">
          <h4>{{ t('apiMarket.detail.subscribe.applyTitle') }}</h4>
          <label>{{ t('apiMarket.detail.subscribe.subscriberId') }}</label>
          <el-input
            v-model="subForm.subscriberId"
            :placeholder="t('apiMarket.detail.subscribe.subscriberIdPlaceholder')"
          />
          <label>{{ t('apiMarket.detail.subscribe.tenantId') }}</label>
          <el-input
            v-model="subForm.subscriberTenantId"
            :placeholder="t('apiMarket.detail.subscribe.tenantIdPlaceholder')"
          />
          <label>{{ t('apiMarket.detail.subscribe.purpose') }}</label>
          <el-input
            v-model="subForm.purpose"
            :placeholder="t('apiMarket.detail.subscribe.purposePlaceholder')"
          />
          <label>{{ t('apiMarket.detail.subscribe.quotaExpect') }}</label>
          <el-input-number v-model="subForm.quotaExpect" :min="0" />
          <el-button size="small" style="margin-top: 8px" type="primary" @click="applySubscribe">
            {{ t('apiMarket.detail.subscribe.submit') }}
          </el-button>

          <h4 style="margin-top: 16px">{{ t('apiMarket.detail.subscribe.listTitle') }}</h4>
          <el-table v-if="subscribers.length > 0" :data="subscribers" stripe>
            <el-table-column
              :label="t('apiMarket.detail.subscribe.listColumns.subscriber')"
              prop="subscriberId"
            />
            <el-table-column :label="t('apiMarket.detail.subscribe.listColumns.status')">
              <template #default="{ row }">
                <span :class="['pill', subStatusClass(row.status)]">
                  {{ subStatusLabel(row.status) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column :label="t('apiMarket.detail.subscribe.listColumns.quota')">
              <template #default="{ row }">{{ row.grantedQuota || row.quotaExpect }}</template>
            </el-table-column>
            <el-table-column
              :label="t('apiMarket.detail.subscribe.listColumns.callCount')"
              prop="callCount"
            />
            <el-table-column :label="t('apiMarket.detail.subscribe.listColumns.ak')">
              <template #default="{ row }">
                <code v-if="row.accessKey">
                  {{
                    t('apiMarket.detail.subscribe.akMask', { ak: row.accessKey.substring(0, 12) })
                  }}
                </code>
                <span v-else>—</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-else class="meta muted-text">
            {{ t('apiMarket.detail.subscribe.empty') }}
          </div>
        </div>

        <!-- 计量 Tab -->
        <div v-if="activeTab === 'metrics'" class="tab-content">
          <div v-if="metricsLoading" class="meta muted-text">
            {{ t('apiMarket.detail.metrics.loading') }}
          </div>
          <template v-else-if="metrics">
            <div class="grid g4">
              <div class="card">
                <h3>{{ t('apiMarket.detail.metrics.callCount') }}</h3>
                <div class="kpi s">{{ formatNumber(metrics.callCount) }}</div>
              </div>
              <div class="card">
                <h3>{{ t('apiMarket.detail.metrics.successRate') }}</h3>
                <div class="kpi s">{{ (metrics.successRate * 100).toFixed(1) }}%</div>
              </div>
              <div class="card">
                <h3>{{ t('apiMarket.detail.metrics.p99') }}</h3>
                <div class="kpi s">{{ metrics.p99LatencyMs.toFixed(1) }} ms</div>
              </div>
              <div class="card">
                <h3>{{ t('apiMarket.detail.metrics.totalCost') }}</h3>
                <div class="kpi s">{{ metrics.totalCost.toFixed(4) }}</div>
              </div>
            </div>
            <h4 style="margin-top: 12px">{{ t('apiMarket.detail.metrics.timeseriesTitle') }}</h4>
            <div v-if="metrics.timeseries.length > 0" class="chart-placeholder">
              <div
                v-for="(point, i) in metrics.timeseries"
                :key="i"
                class="bar"
                :style="{ height: barHeight(point.callCount) + 'px' }"
                :title="
                  t('apiMarket.detail.metrics.tsPoint', {
                    ts: point.timestamp,
                    count: point.callCount
                  })
                "
              ></div>
            </div>
            <div v-else class="meta muted-text">
              {{ t('apiMarket.detail.metrics.noTimeseries') }}
            </div>
            <h4 style="margin-top: 12px">{{ t('apiMarket.detail.metrics.byConsumerTitle') }}</h4>
            <el-table v-if="metrics.byConsumer.length > 0" :data="metrics.byConsumer" stripe>
              <el-table-column
                :label="t('apiMarket.detail.metrics.byConsumerColumns.tenant')"
                prop="consumerTenantId"
              />
              <el-table-column
                :label="t('apiMarket.detail.metrics.byConsumerColumns.callCount')"
                prop="callCount"
              />
              <el-table-column
                :label="t('apiMarket.detail.metrics.byConsumerColumns.errorCount')"
                prop="errorCount"
              />
              <el-table-column :label="t('apiMarket.detail.metrics.byConsumerColumns.avgLatency')">
                <template #default="{ row }">{{ row.avgLatencyMs.toFixed(1) }} ms</template>
              </el-table-column>
              <el-table-column :label="t('apiMarket.detail.metrics.byConsumerColumns.cost')">
                <template #default="{ row }">{{ row.totalCost.toFixed(4) }}</template>
              </el-table-column>
            </el-table>
            <div v-else class="meta muted-text">
              {{ t('apiMarket.detail.metrics.noConsumer') }}
            </div>
          </template>
        </div>
      </div>
      <div class="mf">
        <el-button @click="selectedApi = null">
          {{ t('apiMarket.detail.actions.close') }}
        </el-button>
        <el-button
          v-if="selectedApi.status === 'draft'"
          type="primary"
          @click="publishFlow(selectedApi)"
        >
          {{ t('apiMarket.detail.actions.publish') }}
        </el-button>
      </div>
    </div>

    <!-- 注册 API Modal -->
    <Modal
      :visible="registerModal"
      :title="t('apiMarket.register.title')"
      @close="registerModal = false"
    >
      <label>{{ t('apiMarket.register.name') }}</label>
      <el-input v-model="newApi.name" :placeholder="t('apiMarket.register.namePlaceholder')" />
      <label>{{ t('apiMarket.register.version') }}</label>
      <el-input
        v-model="newApi.version"
        :placeholder="t('apiMarket.register.versionPlaceholder')"
      />
      <label>{{ t('apiMarket.register.description') }}</label>
      <el-input
        v-model="newApi.description"
        :placeholder="t('apiMarket.register.descriptionPlaceholder')"
      />
      <label>{{ t('apiMarket.register.category') }}</label>
      <el-input
        v-model="newApi.category"
        :placeholder="t('apiMarket.register.categoryPlaceholder')"
      />
      <label>{{ t('apiMarket.register.method') }}</label>
      <el-select v-model="newApi.method">
        <el-option label="GET" value="GET" />
        <el-option label="POST" value="POST" />
        <el-option label="PUT" value="PUT" />
        <el-option label="DELETE" value="DELETE" />
      </el-select>
      <label>{{ t('apiMarket.register.path') }}</label>
      <el-input v-model="newApi.path" :placeholder="t('apiMarket.register.pathPlaceholder')" />
      <label>{{ t('apiMarket.register.authType') }}</label>
      <el-select v-model="newApi.authType">
        <el-option :label="t('apiMarket.status.auth.api_key')" value="api_key" />
        <el-option :label="t('apiMarket.status.auth.jwt')" value="jwt" />
        <el-option :label="t('apiMarket.status.auth.oauth2')" value="oauth2" />
      </el-select>
      <label>{{ t('apiMarket.register.sla') }}</label>
      <el-select v-model="newApi.sla">
        <el-option :label="t('apiMarket.status.sla.silver')" value="silver" />
        <el-option :label="t('apiMarket.status.sla.gold')" value="gold" />
        <el-option :label="t('apiMarket.status.sla.platinum')" value="platinum" />
      </el-select>
      <label>{{ t('apiMarket.register.upstreamType') }}</label>
      <el-select v-model="newApi.upstreamType">
        <el-option :label="t('apiMarket.upstreamType.trino')" value="trino" />
        <el-option :label="t('apiMarket.upstreamType.doris')" value="doris" />
        <el-option :label="t('apiMarket.upstreamType.llm')" value="llm" />
        <el-option :label="t('apiMarket.upstreamType.http')" value="http" />
      </el-select>
      <label>{{ t('apiMarket.register.upstreamUrl') }}</label>
      <el-input
        v-model="newApi.upstreamUrl"
        :placeholder="t('apiMarket.register.upstreamUrlPlaceholder')"
      />
      <label>{{ t('apiMarket.register.providerTenantId') }}</label>
      <el-input
        v-model="newApi.providerTenantId"
        :placeholder="t('apiMarket.register.providerTenantIdPlaceholder')"
      />
      <template #footer>
        <el-button @click="registerModal = false">
          {{ t('apiMarket.register.cancel') }}
        </el-button>
        <el-button type="primary" @click="doRegister">
          {{ t('apiMarket.register.submit') }}
        </el-button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader, Toolbar } from '@/components/ui'
import Modal from '@/components/Modal.vue'
import {
  listApis,
  getApi,
  registerApi,
  publishApi,
  submitReview,
  approveApi,
  subscribeApi,
  listSubscribers,
  callApi,
  getMetrics,
  type APIDefinition,
  type APISubscription,
  type APIMetrics,
  type CallResult,
  type SLALevel,
  type APIStatus,
  type AuthType,
  type HttpMethod
} from '@/api/apiCatalog'

const { t, te } = useI18n()
const store = useAppStore()

// ---------- 列表 ----------
const keyword = ref('')
const categoryFilter = ref('')
const statusFilter = ref('')

// API 列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: apiList,
  loading,
  error,
  execute: refreshList
} = useApi<APIDefinition[]>(
  () => {
    const params: Record<string, unknown> = {}
    if (keyword.value) params.keyword = keyword.value
    if (categoryFilter.value) params.category = categoryFilter.value
    if (statusFilter.value) params.status = statusFilter.value
    return listApis(params)
  },
  {
    initialData: []
  }
)

// P2-10: 搜索输入防抖（300ms），避免每次按键都触发请求
let searchDebounceTimer: ReturnType<typeof setTimeout> | null = null
function debouncedRefreshList(): void {
  if (searchDebounceTimer !== null) {
    clearTimeout(searchDebounceTimer)
  }
  searchDebounceTimer = setTimeout(() => {
    searchDebounceTimer = null
    void refreshList()
  }, 300)
}

const categories = computed(() => {
  const set = new Set<string>()
  ;(apiList.value ?? []).forEach((a) => set.add(a.category))
  return Array.from(set).sort()
})

// P2-9: 错误态时 KPI 概览不得展示旧数据，统一以空列表计算，保证 UI 一致性
const safeApiList = computed(() => (error.value ? [] : (apiList.value ?? [])))
const runningCount = computed(() => safeApiList.value.filter((a) => a.status === 'running').length)
const draftCount = computed(() => safeApiList.value.filter((a) => a.status === 'draft').length)
const totalCalls = computed(() => safeApiList.value.reduce((sum, a) => sum + a.callCount, 0))
const totalSuccessRate = computed(() => {
  const list = safeApiList.value
  const total = list.reduce((sum, a) => sum + a.callCount, 0)
  const errors = list.reduce((sum, a) => sum + a.errorCount, 0)
  return total > 0 ? (total - errors) / total : 1
})
const platinumCount = computed(() => safeApiList.value.filter((a) => a.sla === 'platinum').length)
const goldCount = computed(() => safeApiList.value.filter((a) => a.sla === 'gold').length)
const silverCount = computed(() => safeApiList.value.filter((a) => a.sla === 'silver').length)
const activeSubscriptions = ref(0)
const pendingSubscriptions = ref(0)

async function loadMetrics() {
  if (!selectedApi.value) return
  metricsLoading.value = true
  try {
    metrics.value = await getMetrics(selectedApi.value.id, { range: '7d' })
  } catch {
    metrics.value = null
  } finally {
    metricsLoading.value = false
  }
}

// ---------- 详情 ----------
const selectedApi = ref<APIDefinition | null>(null)
const activeTab = ref('doc')
const detailTabs: { key: string; labelKey: string }[] = [
  { key: 'doc', labelKey: 'apiMarket.detail.tabs.doc' },
  { key: 'try', labelKey: 'apiMarket.detail.tabs.try' },
  { key: 'subscribe', labelKey: 'apiMarket.detail.tabs.subscribe' },
  { key: 'metrics', labelKey: 'apiMarket.detail.tabs.metrics' }
]

// 试调
const testApiKey = ref('')
const testPayload = ref('{}')
const calling = ref(false)
const callResult = ref<CallResult | null>(null)

// 订阅
const subscribers = ref<APISubscription[]>([])
const subForm = reactive({
  subscriberId: '',
  subscriberTenantId: '',
  purpose: '',
  quotaExpect: 100
})

// 计量
const metrics = ref<APIMetrics | null>(null)
const metricsLoading = ref(false)

async function openDetail(api: APIDefinition) {
  selectedApi.value = api
  activeTab.value = 'doc'
  callResult.value = null
  metrics.value = null
  // 加载订阅者
  try {
    subscribers.value = await listSubscribers(api.id)
  } catch {
    subscribers.value = []
  }
}

async function executeCall() {
  if (!selectedApi.value || !testApiKey.value) {
    store.showToast(t('apiMarket.messages.needApiKey'))
    return
  }
  calling.value = true
  try {
    let payload: Record<string, unknown> = {}
    try {
      payload = JSON.parse(testPayload.value)
    } catch {
      // 非 JSON 则原样传递
    }
    callResult.value = await callApi(selectedApi.value.id, { payload }, testApiKey.value)
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    callResult.value = {
      callId: '',
      statusCode: 500,
      latencyMs: 0,
      error: msg,
      costAmount: 0
    }
  } finally {
    calling.value = false
  }
}

async function applySubscribe() {
  if (!selectedApi.value) return
  if (!subForm.subscriberId || !subForm.purpose) {
    store.showToast(t('apiMarket.messages.needSubAndPurpose'))
    return
  }
  try {
    await subscribeApi(selectedApi.value.id, { ...subForm })
    store.showToast(t('apiMarket.messages.subscribeSubmitted'))
    subscribers.value = await listSubscribers(selectedApi.value.id)
    subForm.subscriberId = ''
    subForm.purpose = ''
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    store.showToast(t('apiMarket.messages.subscribeFailed', { message: msg }))
  }
}

async function publishFlow(api: APIDefinition) {
  try {
    await submitReview(api.id)
    await approveApi(api.id)
    const updated = await publishApi(api.id)
    selectedApi.value = updated
    store.showToast(t('apiMarket.messages.published'))
    refreshList()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    store.showToast(t('apiMarket.messages.publishFailed', { message: msg }))
  }
}

// 监听 tab 切换加载计量

watch(activeTab, (tab) => {
  if (tab === 'metrics' && selectedApi.value && !metrics.value) {
    loadMetrics()
  }
})

// ---------- 注册 ----------
const registerModal = ref(false)
const newApi = reactive({
  name: '',
  version: '1.0.0',
  description: '',
  category: 'default',
  method: 'GET' as HttpMethod,
  path: '',
  authType: 'api_key' as AuthType,
  sla: 'silver' as SLALevel,
  upstreamType: 'trino',
  upstreamUrl: 'http://trino:8080/v1/statement',
  providerTenantId: 'tenant-provider'
})

async function doRegister() {
  if (!newApi.name || !newApi.path) {
    store.showToast(t('apiMarket.messages.needNameAndPath'))
    return
  }
  try {
    await registerApi({
      name: newApi.name,
      version: newApi.version,
      description: newApi.description,
      category: newApi.category,
      method: newApi.method,
      path: newApi.path,
      authType: newApi.authType,
      sla: newApi.sla,
      providerTenantId: newApi.providerTenantId,
      upstream: {
        type: newApi.upstreamType,
        url: newApi.upstreamUrl,
        method: newApi.method
      }
    })
    store.showToast(t('apiMarket.messages.registered'))
    registerModal.value = false
    newApi.name = ''
    newApi.path = ''
    newApi.description = ''
    refreshList()
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e)
    store.showToast(t('apiMarket.messages.registerFailed', { message: msg }))
  }
}

// ---------- 格式化 ----------
function formatNumber(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return String(n)
}

function slaLabel(sla: SLALevel): string {
  return t(`apiMarket.status.sla.${sla}`)
}
function slaClass(sla: SLALevel): string {
  return { platinum: 'p', gold: 'g', silver: 's' }[sla]
}
function statusLabel(s: APIStatus): string {
  return t(`apiMarket.status.api.${s}`)
}
function statusClass(s: APIStatus): string {
  return {
    draft: 'a',
    reviewing: 'a',
    approved: 'g',
    rejected: 'r',
    published: 'g',
    running: 'g',
    deprecated: 'r',
    archived: 'r',
    offline: 'r'
  }[s]
}
function authLabel(a: AuthType): string {
  return t(`apiMarket.status.auth.${a}`)
}
function costLabel(c: string): string {
  const key = `apiMarket.status.cost.${c}`
  return te(key) ? t(key) : c
}
function subStatusLabel(s: string): string {
  const key = `apiMarket.status.subscription.${s}`
  return te(key) ? t(key) : s
}
function subStatusClass(s: string): string {
  return (
    {
      pending: 'a',
      approved: 'g',
      active: 'g',
      suspended: 'r',
      rejected: 'r',
      revoked: 'r'
    }[s] || 'a'
  )
}
function barHeight(count: number): number {
  if (!metrics.value || metrics.value.timeseries.length === 0) return 0
  const max = Math.max(...metrics.value.timeseries.map((p) => p.callCount), 1)
  return Math.max(2, (count / max) * 80)
}

onMounted(() => {
  void refreshList()
})

onUnmounted(() => {
  // 清理防抖定时器，避免组件卸载后仍触发请求
  if (searchDebounceTimer !== null) {
    clearTimeout(searchDebounceTimer)
    searchDebounceTimer = null
  }
})
</script>

<style scoped>
/* 通用文字色辅助类（使用 design tokens） */
.muted-text {
  color: var(--ds-text-tertiary);
}
.error-text {
  color: var(--ds-color-error-600);
}

.api-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
}
.api-card {
  cursor: pointer;
  transition:
    border-color var(--ds-transition-fast),
    box-shadow var(--ds-transition-fast);
}
.api-card:hover {
  border-color: var(--ds-color-primary-500);
  box-shadow: var(--ds-shadow-md);
}
.api-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--ds-spacing-2);
}
.api-name {
  font-weight: var(--ds-font-weight-semibold);
  font-size: var(--ds-font-size-lg);
}
.api-card-desc {
  color: var(--ds-text-secondary);
  font-size: var(--ds-font-size-sm);
  margin-bottom: 10px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.api-card-meta {
  display: flex;
  align-items: center;
  gap: var(--ds-spacing-2);
  margin-bottom: var(--ds-spacing-2);
}
.api-card-meta code {
  font-size: var(--ds-font-size-xs);
  background: var(--ds-bg-subtle);
  padding: 2px 6px;
  border-radius: var(--ds-radius-sm);
}
.version {
  color: var(--ds-text-secondary);
  font-size: var(--ds-font-size-xs);
}
.api-card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ds-spacing-1);
  margin-bottom: 10px;
}
.tag {
  font-size: var(--ds-font-size-xs);
  padding: 1px 6px;
  border-radius: var(--ds-radius-sm);
  background: var(--ds-bg-muted);
  color: var(--ds-text-secondary);
}
.api-card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.search-input {
  width: 280px;
}
/* Tab 切换栏：使用 Element Plus 按钮组，保留底部间距 */
.tab-bar {
  display: flex;
  gap: var(--ds-spacing-2);
  border-bottom: 1px solid var(--ds-border-subtle);
  margin-bottom: var(--ds-spacing-3);
  padding-bottom: var(--ds-spacing-2);
}
.tab-content {
  min-height: 200px;
}
.call-result {
  background: var(--ds-bg-subtle);
  padding: 10px;
  border-radius: var(--ds-radius-sm);
}
/* 代码块：深色主题色使用 design tokens 的灰色系 */
.code-block {
  background: var(--ds-color-gray-900);
  color: var(--ds-color-gray-300);
  padding: 10px;
  border-radius: var(--ds-radius-sm);
  font-size: var(--ds-font-size-xs);
  overflow-x: auto;
  max-height: 240px;
}
.chart-placeholder {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 100px;
  padding: var(--ds-spacing-2);
  background: var(--ds-bg-subtle);
  border-radius: var(--ds-radius-sm);
}
.bar {
  flex: 1;
  background: var(--ds-color-primary-500);
  border-radius: var(--ds-radius-sm) var(--ds-radius-sm) 0 0;
  min-width: 4px;
}

/* 响应式断点：中等屏幕收窄卡片网格最小宽度 */
@media (max-width: 1024px) {
  .api-grid {
    grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
    gap: var(--ds-spacing-3);
  }
  :deep(.el-table) {
    font-size: var(--ds-font-size-sm);
  }
}

/* 响应式断点：小屏幕单列布局 */
@media (max-width: 640px) {
  .api-grid {
    grid-template-columns: 1fr;
    gap: 10px;
  }
  :deep(.el-table) {
    font-size: var(--ds-font-size-xs);
  }
  :deep(.el-table .cell) {
    padding-left: var(--ds-spacing-2);
    padding-right: var(--ds-spacing-2);
  }
  .tab-bar {
    flex-wrap: wrap;
  }
  .search-input {
    width: 100%;
  }
}
</style>
