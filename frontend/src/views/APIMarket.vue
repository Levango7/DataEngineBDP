<template>
  <div>
    <h1>开放 API 服务目录</h1>
    <div class="sub">
      L5.5 · 将平台数据能力封装为 REST/gRPC API，经 APISIX 网关对外暴露；配套服务目录支持浏览、搜索、订阅、调用与计量。
    </div>

    <!-- KPI 概览 -->
    <div class="grid g4">
      <div class="card">
        <h3>已发布 API</h3>
        <div class="kpi s">{{ apiList?.length ?? 0 }}</div>
        <div class="meta">运行中 {{ runningCount }} · 草稿 {{ draftCount }}</div>
      </div>
      <div class="card">
        <h3>月调用量</h3>
        <div class="kpi s">{{ formatNumber(totalCalls) }}</div>
        <div class="meta">成功率 {{ (totalSuccessRate * 100).toFixed(1) }}%</div>
      </div>
      <div class="card">
        <h3>活跃订阅</h3>
        <div class="kpi s">{{ activeSubscriptions }}</div>
        <div class="meta">待审批 {{ pendingSubscriptions }}</div>
      </div>
      <div class="card">
        <h3>SLA 铂金</h3>
        <div class="kpi s">{{ platinumCount }}</div>
        <div class="meta">金 {{ goldCount }} · 银 {{ silverCount }}</div>
      </div>
    </div>

    <!-- 工具栏 -->
    <div class="toolbar" style="margin-top: 14px">
      <input
        v-model="keyword"
        class="search-input"
        placeholder="搜索 API 名称 / 描述 / 标签"
        @input="refreshList"
      />
      <select v-model="categoryFilter" @change="refreshList">
        <option value="">全部分类</option>
        <option v-for="cat in categories" :key="cat" :value="cat">{{ cat }}</option>
      </select>
      <select v-model="statusFilter" @change="refreshList">
        <option value="">全部状态</option>
        <option value="running">运行中</option>
        <option value="draft">草稿</option>
        <option value="deprecated">已废弃</option>
      </select>
      <div class="spacer"></div>
      <button class="btn sm" @click="registerModal = true">+ 注册 API</button>
    </div>

    <!-- API 卡片网格 -->
    <div v-if="loading" class="card" style="margin-top: 14px">
      <div class="meta" style="color: var(--muted)">加载中…</div>
    </div>
    <div v-else-if="error" class="card" style="margin-top: 14px" role="alert">
      <div class="meta" style="color: var(--danger)">
        API 列表加载失败：{{ error.message }}
        <button class="btn ghost sm" style="margin-left: 8px" @click="refreshList">重试</button>
      </div>
    </div>
    <div v-else-if="apiList && apiList.length === 0" class="card" style="margin-top: 14px">
      <div class="meta" style="color: var(--muted)">暂无 API，点击「+ 注册 API」创建</div>
    </div>
    <div v-else-if="apiList" class="api-grid" style="margin-top: 14px">
      <div
        v-for="api in apiList"
        :key="api.id"
        class="card api-card"
        @click="openDetail(api)"
      >
        <div class="api-card-header">
          <span class="api-name">{{ api.name }}</span>
          <span :class="['pill', slaClass(api.sla)]">{{ slaLabel(api.sla) }}</span>
        </div>
        <div class="api-card-desc">{{ api.description || '（无描述）' }}</div>
        <div class="api-card-meta">
          <code>{{ api.method }} {{ api.path }}</code>
          <span class="version">v{{ api.version }}</span>
        </div>
        <div class="api-card-tags">
          <span class="tag">{{ api.category }}</span>
          <span v-for="t in api.tags" :key="t" class="tag">{{ t }}</span>
        </div>
        <div class="api-card-footer">
          <span :class="['pill', statusClass(api.status)]">{{ statusLabel(api.status) }}</span>
          <span class="meta">{{ formatNumber(api.callCount) }} 次调用</span>
        </div>
      </div>
    </div>

    <!-- API 详情抽屉 -->
    <div v-if="selectedApi" class="overlay show" @click.self="selectedApi = null"></div>
    <div v-if="selectedApi" class="modal show" style="width: 800px; max-width: 95vw">
      <div class="mh">
        <span>{{ selectedApi.name }} · 详情</span>
        <span class="x" @click="selectedApi = null">×</span>
      </div>
      <div class="mb">
        <!-- Tab 切换 -->
        <div class="tab-bar">
          <button
            v-for="tab in detailTabs"
            :key="tab"
            :class="['tab', { active: activeTab === tab }]"
            @click="activeTab = tab"
          >
            {{ tab }}
          </button>
        </div>

        <!-- 文档 Tab -->
        <div v-if="activeTab === '文档'" class="tab-content">
          <div class="kv"><span>名称</span><span>{{ selectedApi.name }}</span></div>
          <div class="kv"><span>版本</span><span>{{ selectedApi.version }}</span></div>
          <div class="kv"><span>方法</span><span><code>{{ selectedApi.method }} {{ selectedApi.path }}</code></span></div>
          <div class="kv"><span>描述</span><span>{{ selectedApi.description || '—' }}</span></div>
          <div class="kv"><span>认证</span><span>{{ authLabel(selectedApi.authType) }}</span></div>
          <div class="kv"><span>SLA</span><span>{{ slaLabel(selectedApi.sla) }}</span></div>
          <div class="kv"><span>计费</span><span>{{ costLabel(selectedApi.costStrategy) }} · 单价 {{ selectedApi.costUnitPrice }}</span></div>
          <div class="kv"><span>状态</span><span>{{ statusLabel(selectedApi.status) }}</span></div>
          <h4 style="margin-top: 12px">参数</h4>
          <table v-if="selectedApi.params.length > 0">
            <thead>
              <tr><th>名称</th><th>位置</th><th>类型</th><th>必填</th><th>描述</th></tr>
            </thead>
            <tbody>
              <tr v-for="p in selectedApi.params" :key="p.name">
                <td><code>{{ p.name }}</code></td>
                <td>{{ p.location }}</td>
                <td>{{ p.type }}</td>
                <td>{{ p.required ? '是' : '否' }}</td>
                <td>{{ p.description || '—' }}</td>
              </tr>
            </tbody>
          </table>
          <div v-else class="meta" style="color: var(--muted)">无参数</div>
        </div>

        <!-- 试调 Tab -->
        <div v-if="activeTab === '试调'" class="tab-content">
          <label>API Key</label>
          <input v-model="testApiKey" placeholder="输入订阅的 Access Key" />
          <label>请求体 (JSON)</label>
          <textarea v-model="testPayload" rows="5" placeholder='{"key": "value"}'></textarea>
          <button class="btn sm" style="margin-top: 8px" @click="executeCall" :disabled="calling">
            {{ calling ? '调用中…' : '发起调用' }}
          </button>
          <div v-if="callResult" class="call-result" style="margin-top: 12px">
            <div class="kv">
              <span>状态码</span>
              <span :class="['pill', callResult.statusCode === 200 ? 'g' : 'r']">
                {{ callResult.statusCode }}
              </span>
            </div>
            <div class="kv"><span>延迟</span><span>{{ callResult.latencyMs.toFixed(2) }} ms</span></div>
            <div class="kv"><span>费用</span><span>{{ callResult.costAmount }}</span></div>
            <div v-if="callResult.error" class="kv"><span>错误</span><span style="color: var(--danger)">{{ callResult.error }}</span></div>
            <div v-if="callResult.result">
              <label>响应</label>
              <pre class="code-block">{{ JSON.stringify(callResult.result, null, 2) }}</pre>
            </div>
          </div>
        </div>

        <!-- 订阅 Tab -->
        <div v-if="activeTab === '订阅'" class="tab-content">
          <h4>申请订阅</h4>
          <label>订阅者 ID</label>
          <input v-model="subForm.subscriberId" placeholder="如 svc-risk" />
          <label>租户 ID</label>
          <input v-model="subForm.subscriberTenantId" placeholder="如 tenant-consumer" />
          <label>用途</label>
          <input v-model="subForm.purpose" placeholder="如 风控数据查询" />
          <label>期望配额（次/分钟）</label>
          <input v-model.number="subForm.quotaExpect" type="number" />
          <button class="btn sm" style="margin-top: 8px" @click="applySubscribe">提交申请</button>

          <h4 style="margin-top: 16px">已有订阅者</h4>
          <table v-if="subscribers.length > 0">
            <thead>
              <tr><th>订阅者</th><th>状态</th><th>配额</th><th>调用次数</th><th>AK</th></tr>
            </thead>
            <tbody>
              <tr v-for="s in subscribers" :key="s.id">
                <td>{{ s.subscriberId }}</td>
                <td><span :class="['pill', subStatusClass(s.status)]">{{ subStatusLabel(s.status) }}</span></td>
                <td>{{ s.grantedQuota || s.quotaExpect }}</td>
                <td>{{ s.callCount }}</td>
                <td><code v-if="s.accessKey">{{ s.accessKey.substring(0, 12) }}…</code><span v-else>—</span></td>
              </tr>
            </tbody>
          </table>
          <div v-else class="meta" style="color: var(--muted)">暂无订阅者</div>
        </div>

        <!-- 计量 Tab -->
        <div v-if="activeTab === '计量'" class="tab-content">
          <div v-if="metricsLoading" class="meta" style="color: var(--muted)">加载中…</div>
          <template v-else-if="metrics">
            <div class="grid g4">
              <div class="card"><h3>调用次数</h3><div class="kpi s">{{ formatNumber(metrics.callCount) }}</div></div>
              <div class="card"><h3>成功率</h3><div class="kpi s">{{ (metrics.successRate * 100).toFixed(1) }}%</div></div>
              <div class="card"><h3>P99 延迟</h3><div class="kpi s">{{ metrics.p99LatencyMs.toFixed(1) }} ms</div></div>
              <div class="card"><h3>总费用</h3><div class="kpi s">{{ metrics.totalCost.toFixed(4) }}</div></div>
            </div>
            <h4 style="margin-top: 12px">时间序列</h4>
            <div v-if="metrics.timeseries.length > 0" class="chart-placeholder">
              <div
                v-for="(point, i) in metrics.timeseries"
                :key="i"
                class="bar"
                :style="{ height: barHeight(point.callCount) + 'px' }"
                :title="`${point.timestamp}: ${point.callCount} 次`"
              ></div>
            </div>
            <div v-else class="meta" style="color: var(--muted)">无时间序列数据</div>
            <h4 style="margin-top: 12px">按消费者</h4>
            <table v-if="metrics.byConsumer.length > 0">
              <thead>
                <tr><th>消费者租户</th><th>调用次数</th><th>错误次数</th><th>平均延迟</th><th>费用</th></tr>
              </thead>
              <tbody>
                <tr v-for="c in metrics.byConsumer" :key="c.consumerTenantId">
                  <td>{{ c.consumerTenantId }}</td>
                  <td>{{ c.callCount }}</td>
                  <td>{{ c.errorCount }}</td>
                  <td>{{ c.avgLatencyMs.toFixed(1) }} ms</td>
                  <td>{{ c.totalCost.toFixed(4) }}</td>
                </tr>
              </tbody>
            </table>
            <div v-else class="meta" style="color: var(--muted)">无消费者数据</div>
          </template>
        </div>
      </div>
      <div class="mf">
        <button class="btn ghost" @click="selectedApi = null">关闭</button>
        <button v-if="selectedApi.status === 'draft'" class="btn" @click="publishFlow(selectedApi)">提交审核并发布</button>
      </div>
    </div>

    <!-- 注册 API Modal -->
    <Modal :visible="registerModal" title="注册 API" @close="registerModal = false">
      <label>名称</label>
      <input v-model="newApi.name" placeholder="如 weather-query" />
      <label>版本</label>
      <input v-model="newApi.version" placeholder="1.0.0" />
      <label>描述</label>
      <input v-model="newApi.description" placeholder="API 描述" />
      <label>分类</label>
      <input v-model="newApi.category" placeholder="如 weather" />
      <label>HTTP 方法</label>
      <select v-model="newApi.method">
        <option>GET</option><option>POST</option><option>PUT</option><option>DELETE</option>
      </select>
      <label>路径</label>
      <input v-model="newApi.path" placeholder="/weather" />
      <label>认证方式</label>
      <select v-model="newApi.authType">
        <option value="api_key">API Key</option>
        <option value="jwt">JWT</option>
        <option value="oauth2">OAuth2</option>
      </select>
      <label>SLA 等级</label>
      <select v-model="newApi.sla">
        <option value="silver">银</option>
        <option value="gold">金</option>
        <option value="platinum">铂金</option>
      </select>
      <label>后端类型</label>
      <select v-model="newApi.upstreamType">
        <option value="trino">Trino</option>
        <option value="doris">Doris</option>
        <option value="llm">大模型</option>
        <option value="http">HTTP</option>
      </select>
      <label>后端 URL</label>
      <input v-model="newApi.upstreamUrl" placeholder="http://trino:8080/v1/statement" />
      <label>提供方租户</label>
      <input v-model="newApi.providerTenantId" placeholder="tenant-provider" />
      <template #footer>
        <button class="btn ghost" @click="registerModal = false">取消</button>
        <button class="btn" @click="doRegister">注册</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
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

const categories = computed(() => {
  const set = new Set<string>()
  ;(apiList.value ?? []).forEach((a) => set.add(a.category))
  return Array.from(set).sort()
})

const runningCount = computed(() => (apiList.value ?? []).filter((a) => a.status === 'running').length)
const draftCount = computed(() => (apiList.value ?? []).filter((a) => a.status === 'draft').length)
const totalCalls = computed(() => (apiList.value ?? []).reduce((sum, a) => sum + a.callCount, 0))
const totalSuccessRate = computed(() => {
  const list = apiList.value ?? []
  const total = list.reduce((sum, a) => sum + a.callCount, 0)
  const errors = list.reduce((sum, a) => sum + a.errorCount, 0)
  return total > 0 ? (total - errors) / total : 1
})
const platinumCount = computed(() => (apiList.value ?? []).filter((a) => a.sla === 'platinum').length)
const goldCount = computed(() => (apiList.value ?? []).filter((a) => a.sla === 'gold').length)
const silverCount = computed(() => (apiList.value ?? []).filter((a) => a.sla === 'silver').length)
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
const activeTab = ref('文档')
const detailTabs = ['文档', '试调', '订阅', '计量']

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
  activeTab.value = '文档'
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
    store.showToast('请输入 API Key')
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
    callResult.value = {
      callId: '',
      statusCode: 500,
      latencyMs: 0,
      error: (e as Error).message,
      costAmount: 0
    }
  } finally {
    calling.value = false
  }
}

async function applySubscribe() {
  if (!selectedApi.value) return
  if (!subForm.subscriberId || !subForm.purpose) {
    store.showToast('请填写订阅者和用途')
    return
  }
  try {
    await subscribeApi(selectedApi.value.id, { ...subForm })
    store.showToast('订阅申请已提交')
    subscribers.value = await listSubscribers(selectedApi.value.id)
    subForm.subscriberId = ''
    subForm.purpose = ''
  } catch (e: unknown) {
    store.showToast(`订阅失败: ${(e as Error).message}`)
  }
}


async function publishFlow(api: APIDefinition) {
  try {
    await submitReview(api.id)
    await approveApi(api.id)
    const updated = await publishApi(api.id)
    selectedApi.value = updated
    store.showToast('API 已发布')
    refreshList()
  } catch (e: unknown) {
    store.showToast(`发布失败: ${(e as Error).message}`)
  }
}

// 监听 tab 切换加载计量
import { watch } from 'vue'
watch(activeTab, (tab) => {
  if (tab === '计量' && selectedApi.value && !metrics.value) {
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
    store.showToast('请填写名称和路径')
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
    store.showToast('API 注册成功')
    registerModal.value = false
    newApi.name = ''
    newApi.path = ''
    newApi.description = ''
    refreshList()
  } catch (e: unknown) {
    store.showToast(`注册失败: ${(e as Error).message}`)
  }
}

// ---------- 格式化 ----------
function formatNumber(n: number): string {
  if (n >= 1_000_000) return (n / 1_000_000).toFixed(1) + 'M'
  if (n >= 1_000) return (n / 1_000).toFixed(1) + 'K'
  return String(n)
}

function slaLabel(sla: SLALevel): string {
  return { platinum: '铂金', gold: '金', silver: '银' }[sla]
}
function slaClass(sla: SLALevel): string {
  return { platinum: 'p', gold: 'g', silver: 's' }[sla]
}
function statusLabel(s: APIStatus): string {
  return {
    draft: '草稿',
    reviewing: '审核中',
    approved: '已审核',
    rejected: '已驳回',
    published: '已发布',
    running: '运行中',
    deprecated: '已废弃',
    archived: '已归档',
    offline: '已下线'
  }[s]
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
  return { api_key: 'API Key', jwt: 'JWT', oauth2: 'OAuth2', none: '无' }[a]
}
function costLabel(c: string): string {
  return { by_call: '按次', by_bytes: '按量', monthly_package: '月包' }[c] || c
}
function subStatusLabel(s: string): string {
  return {
    pending: '待审批',
    approved: '已审批',
    active: '已激活',
    suspended: '已暂停',
    rejected: '已驳回',
    revoked: '已吊销'
  }[s] || s
}
function subStatusClass(s: string): string {
  return {
    pending: 'a',
    approved: 'g',
    active: 'g',
    suspended: 'r',
    rejected: 'r',
    revoked: 'r'
  }[s] || 'a'
}
function barHeight(count: number): number {
  if (!metrics.value || metrics.value.timeseries.length === 0) return 0
  const max = Math.max(...metrics.value.timeseries.map((p) => p.callCount), 1)
  return Math.max(2, (count / max) * 80)
}

onMounted(() => {
  void refreshList()
})
</script>

<style scoped>
.api-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 14px;
}
.api-card {
  cursor: pointer;
  transition: border-color 0.2s, box-shadow 0.2s;
}
.api-card:hover {
  border-color: var(--primary, #409eff);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.08);
}
.api-card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.api-name {
  font-weight: 600;
  font-size: 15px;
}
.api-card-desc {
  color: var(--muted, #909399);
  font-size: 13px;
  margin-bottom: 10px;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.api-card-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.api-card-meta code {
  font-size: 12px;

  background: var(--bg-alt, #f5f7fa);
  padding: 2px 6px;
  border-radius: 3px;
}
.version {
  color: var(--muted, #909399);
  font-size: 12px;
}
.api-card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-bottom: 10px;
}
.tag {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  background: var(--bg-alt, #f0f2f5);
  color: var(--muted, #606266);
}
.api-card-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.search-input {
  width: 280px;
}
.tab-bar {
  display: flex;
  border-bottom: 1px solid var(--border, #e4e7ed);
  margin-bottom: 12px;
}
.tab {
  padding: 6px 16px;
  border: none;
  background: none;
  cursor: pointer;
  font-size: 14px;
  color: var(--muted, #909399);
  border-bottom: 2px solid transparent;
}
.tab.active {
  color: var(--primary, #409eff);
  border-bottom-color: var(--primary, #409eff);
  font-weight: 500;
}
.tab-content {
  min-height: 200px;
}
.call-result {
  background: var(--bg-alt, #f5f7fa);
  padding: 10px;
  border-radius: 4px;
}
.code-block {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 10px;
  border-radius: 4px;
  font-size: 12px;
  overflow-x: auto;
  max-height: 240px;
}
.chart-placeholder {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 100px;
  padding: 8px;
  background: var(--bg-alt, #f5f7fa);
  border-radius: 4px;
}
.bar {
  flex: 1;
  background: var(--primary, #409eff);
  border-radius: 2px 2px 0 0;
  min-width: 4px;
}
</style>