<template>
  <div>
    <h1>数据资产流通</h1>
    <div class="sub">
      L5.6 · 数据集 / 数据服务 / 数据模型 /
      大模型统一登记、上架、流通、变现，构建"提供方—平台—消费方"三方市场。
    </div>

    <!-- 顶部 KPI -->
    <div class="grid g4">
      <div class="card">
        <h3>在架资产</h3>
        <div class="kpi s">{{ assets?.length ?? 0 }}</div>
        <div class="meta">可流通</div>
      </div>
      <div class="card">
        <h3>我的订阅</h3>
        <div class="kpi s">{{ mySubscriptions?.length ?? 0 }}</div>
        <div class="meta">生效中 {{ activeSubCount }}</div>
      </div>
      <div class="card">
        <h3>累计收益</h3>
        <div class="kpi s">¥{{ totalRevenue.toFixed(2) }}</div>
        <div class="meta">提供方入账</div>
      </div>
      <div class="card">
        <h3>平台抽成</h3>
        <div class="kpi s">¥{{ totalPlatformRevenue.toFixed(2) }}</div>
        <div class="meta">20% 分账</div>
      </div>
    </div>

    <!-- 加载与错误状态 -->
    <div v-if="loading" class="card" style="text-align: center; padding: 24px; color: #888">
      正在加载资产列表...
    </div>
    <div v-else-if="error" class="card" style="text-align: center; padding: 24px; color: #d4380d">
      加载失败：{{ error.message }}
      <button class="btn ghost sm" style="margin-left: 8px" @click="loadAssets">重试</button>
    </div>

    <!-- Tab 切换 -->
    <div class="toolbar" style="margin-top: 14px">
      <button :class="['btn', 'sm', tab === 'market' ? '' : 'ghost']" @click="tab = 'market'">
        资产市场
      </button>
      <button :class="['btn', 'sm', tab === 'mine' ? '' : 'ghost']" @click="tab = 'mine'">
        我的订阅
      </button>
      <button :class="['btn', 'sm', tab === 'listed' ? '' : 'ghost']" @click="tab = 'listed'">
        我上架的
      </button>
      <div class="spacer"></div>
      <button class="btn sm" @click="listModalVisible = true">+ 上架资产</button>
    </div>

    <!-- 资产市场：卡片式浏览 -->
    <div v-if="tab === 'market'">
      <!-- 筛选 -->
      <div class="card" style="margin-bottom: 14px">
        <div class="row" style="gap: 12px; align-items: center">
          <input v-model="searchQuery" placeholder="搜索资产名称..." style="flex: 1" />
          <select v-model="filterType" style="width: 140px">
            <option value="">全部类型</option>
            <option value="table">数据集</option>
            <option value="api">数据服务</option>
            <option value="model">数据模型</option>
            <option value="dashboard">仪表盘</option>
            <option value="stream">实时流</option>
          </select>
          <select v-model="filterSecurity" style="width: 120px">
            <option value="">全部分级</option>
            <option value="public">公开</option>
            <option value="internal">内部</option>
            <option value="sensitive">敏感</option>
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
          <p class="asset-desc">{{ a.description || '暂无描述' }}</p>
          <div class="asset-meta">
            <span>提供方: {{ a.owner }}</span>
            <span>质量: {{ a.qualityScore }}分</span>
          </div>
          <div class="asset-footer">
            <span class="price">¥{{ a.pricing.price }} / {{ a.pricing.unit }}</span>
            <span class="sub-count">{{ a.subscriberCount }} 订阅</span>
          </div>
        </div>
      </div>
      <div
        v-if="filteredAssets.length === 0"
        class="card"
        style="text-align: center; padding: 32px"
      >
        暂无资产，点击右上角"上架资产"
      </div>
    </div>

    <!-- 我的订阅 -->
    <div v-if="tab === 'mine'">
      <div class="card">
        <h3>订阅列表</h3>
        <div v-if="subsLoading" style="text-align: center; padding: 24px; color: #888">
          正在加载订阅列表...
        </div>
        <div v-else-if="subsError" style="text-align: center; padding: 24px; color: #d4380d">
          加载失败：{{ subsError.message }}
          <button class="btn ghost sm" style="margin-left: 8px" @click="loadMySubscriptions">
            重试
          </button>
        </div>
        <template v-else-if="mySubscriptions">
          <table>
            <thead>
              <tr>
                <th>资产</th>
                <th>提供方</th>
                <th>状态</th>
                <th>生效时间</th>
                <th>交付状态</th>
                <th>操作</th>
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
                <td>{{ formatDate(s.startTime) }} ~ {{ formatDate(s.endTime) }}</td>
                <td>
                  <span class="pill" :class="deliveryStatusClass(s.deliveryStatus)">
                    {{ deliveryStatusLabel(s.deliveryStatus) }}
                  </span>
                </td>
                <td>
                  <button v-if="s.status === 'active'" class="btn ghost sm" @click="openDeliver(s)">
                    交付
                  </button>
                  <button class="btn ghost sm" @click="openBilling(s)">账单</button>
                </td>
              </tr>
            </tbody>
          </table>
          <div
            v-if="mySubscriptions.length === 0"
            style="text-align: center; padding: 24px; color: #888"
          >
            暂无订阅，去资产市场看看吧
          </div>
        </template>
      </div>
    </div>

    <!-- 我上架的 -->
    <div v-if="tab === 'listed'">
      <div class="card">
        <h3>我上架的资产</h3>
        <table>
          <thead>
            <tr>
              <th>名称</th>
              <th>类型</th>
              <th>状态</th>
              <th>订阅数</th>
              <th>累计收益</th>
              <th>操作</th>
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
              <td>¥{{ (a.subscriberCount * a.pricing.price).toFixed(2) }}</td>
              <td>
                <button class="btn ghost sm" @click="openDetail(a)">详情</button>
                <button v-if="a.status === 'listed'" class="btn ghost sm" @click="offlineAsset(a)">
                  下架
                </button>
                <button v-if="a.status === 'offline'" class="btn ghost sm" @click="relistAsset(a)">
                  重新上架
                </button>
              </td>
            </tr>
          </tbody>
        </table>
        <div v-if="myAssets.length === 0" style="text-align: center; padding: 24px; color: #888">
          暂未上架任何资产
        </div>
      </div>
    </div>

    <!-- 资产详情弹窗 -->
    <Modal
      :visible="detailVisible"
      :title="detailAsset?.name || '资产详情'"
      @close="detailVisible = false"
    >
      <div v-if="detailAsset" class="detail-content">
        <div class="kv">
          <span>类型</span>
          <span>{{ typeLabel(detailAsset.type) }}</span>
        </div>
        <div class="kv">
          <span>提供方</span>
          <span>{{ detailAsset.owner }}</span>
        </div>
        <div class="kv">
          <span>安全分级</span>
          <span>{{ securityLabel(detailAsset.securityLevel) }}</span>
        </div>
        <div class="kv">
          <span>质量评分</span>
          <span>{{ detailAsset.qualityScore }} / 100</span>
        </div>
        <div class="kv">
          <span>更新频率</span>
          <span>{{ detailAsset.updateFrequency }}</span>
        </div>
        <div class="kv">
          <span>价格</span>
          <span>
            ¥{{ detailAsset.pricing.price }} / {{ detailAsset.pricing.unit }}（{{
              billingModeLabel(detailAsset.pricing.mode)
            }}）
          </span>
        </div>
        <div class="kv">
          <span>订阅者</span>
          <span>{{ detailAsset.subscriberCount }}</span>
        </div>

        <h4 style="margin-top: 16px">Schema</h4>
        <table v-if="detailAsset.schema?.fields?.length">
          <thead>
            <tr>
              <th>字段</th>
              <th>类型</th>
              <th>说明</th>
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
        <div v-else style="color: #888">无 schema 信息</div>

        <h4 style="margin-top: 16px">样本数据</h4>
        <pre v-if="detailAsset.sample?.length" class="sample">{{
          JSON.stringify(detailAsset.sample, null, 2)
        }}</pre>
        <div v-else style="color: #888">无样本数据</div>
      </div>
      <template #footer>
        <button class="btn ghost" @click="detailVisible = false">关闭</button>
        <button
          v-if="detailAsset && detailAsset.status === 'listed'"
          class="btn"
          @click="subscribeAsset(detailAsset)"
        >
          订阅
        </button>
      </template>
    </Modal>

    <!-- 上架表单 -->
    <Modal :visible="listModalVisible" title="上架资产" @close="listModalVisible = false">
      <label>资产名称</label>
      <input v-model="newAsset.name" placeholder="如 user-events" />
      <label>资产类型</label>
      <select v-model="newAsset.type">
        <option value="table">数据集</option>
        <option value="api">数据服务</option>
        <option value="model">数据模型</option>
        <option value="dashboard">仪表盘</option>
        <option value="stream">实时流</option>
      </select>
      <label>安全分级</label>
      <select v-model="newAsset.securityLevel">
        <option value="public">公开</option>
        <option value="internal">内部</option>
        <option value="sensitive">敏感</option>
      </select>
      <label>描述</label>
      <input v-model="newAsset.description" placeholder="资产描述" />
      <label>计费方式</label>
      <select v-model="newAsset.pricing.mode">
        <option value="by_call">按调用量</option>
        <option value="by_data">按数据量</option>
        <option value="by_time">按时间（月）</option>
        <option value="one_time">一次性买断</option>
      </select>
      <label>单价（元）</label>
      <input v-model.number="newAsset.pricing.price" type="number" step="0.01" />
      <label>交付方式</label>
      <select v-model="newAsset.deliveryMethod">
        <option value="api">API 交付</option>
        <option value="file">文件交付</option>
        <option value="database_direct">数据库直连</option>
      </select>
      <template #footer>
        <button class="btn ghost" @click="listModalVisible = false">取消</button>
        <button class="btn" @click="submitListAsset">上架</button>
      </template>
    </Modal>

    <!-- 交付弹窗 -->
    <Modal :visible="deliverModalVisible" title="数据交付" @close="deliverModalVisible = false">
      <div v-if="deliverSub">
        <div class="kv">
          <span>订阅资产</span>
          <span>{{ assetName(deliverSub.assetId) }}</span>
        </div>
        <div class="kv">
          <span>订阅方</span>
          <span>{{ deliverSub.subscriberId }}</span>
        </div>
      </div>
      <label>交付方式</label>
      <select v-model="deliverReq.method">
        <option value="api">API 交付</option>
        <option value="file">文件交付</option>
        <option value="database_direct">数据库直连</option>
      </select>
      <div v-if="deliverReq.method === 'api'">
        <label>API 端点</label>
        <input v-model="deliverReq.config.endpoint" placeholder="/api/v1/data/query" />
      </div>
      <div v-if="deliverReq.method === 'file'">
        <label>文件格式</label>
        <select v-model="deliverReq.config.format">
          <option value="csv">CSV</option>
          <option value="parquet">Parquet</option>
          <option value="json">JSON</option>
        </select>
      </div>
      <div v-if="deliverReq.method === 'database_direct'">
        <label>JDBC URL</label>
        <input v-model="deliverReq.config.jdbcUrl" placeholder="jdbc:postgresql://host:5432/db" />
        <label>表名</label>
        <input v-model="deliverReq.config.tableName" placeholder="table_name" />
      </div>
      <template #footer>
        <button class="btn ghost" @click="deliverModalVisible = false">取消</button>
        <button class="btn" @click="submitDeliver">交付</button>
      </template>
    </Modal>

    <!-- 账单弹窗 -->
    <Modal :visible="billingModalVisible" title="计费记录" @close="billingModalVisible = false">
      <div v-if="billingLoading" style="color: #888; text-align: center; padding: 24px">
        正在加载计费记录...
      </div>
      <div v-else-if="billingRecords.length">
        <table>
          <thead>
            <tr>
              <th>周期</th>
              <th>计费方式</th>
              <th>使用量</th>
              <th>金额</th>
              <th>提供方收益</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in billingRecords" :key="r.id">
              <td>{{ r.period }}</td>
              <td>{{ billingModeLabel(r.mode) }}</td>
              <td>{{ r.usage }} {{ r.unit }}</td>
              <td>¥{{ r.amount.toFixed(2) }}</td>
              <td>¥{{ r.providerRevenue.toFixed(2) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else style="color: #888; text-align: center; padding: 24px">暂无计费记录</div>
      <template #footer>
        <button class="btn ghost" @click="billingModalVisible = false">关闭</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useAuthStore } from '@/stores/auth'
import { useApi } from '@/composables/useApi'
import Modal from '@/components/Modal.vue'
import * as assetMarketApi from '@/api/assetMarket'
import type { Asset, Subscription, BillingRecord } from '@/api/assetMarket'

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
function typeLabel(t: string): string {
  const map: Record<string, string> = {
    table: '数据集',
    api: '数据服务',
    model: '数据模型',
    dashboard: '仪表盘',
    stream: '实时流'
  }
  return map[t] || t
}

function securityLabel(s: string): string {
  const map: Record<string, string> = { public: '公开', internal: '内部', sensitive: '敏感' }
  return map[s] || s
}

function securityClass(s: string): string {
  const map: Record<string, string> = { public: 'g', internal: 'a', sensitive: 'p' }
  return map[s] || ''
}

function subStatusLabel(s: string): string {
  const map: Record<string, string> = {
    pending: '待审批',
    approved: '已批准',
    active: '生效中',
    expired: '已到期',
    rejected: '已驳回'
  }
  return map[s] || s
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
  if (!s) return '未交付'
  const map: Record<string, string> = {
    pending: '待交付',
    running: '交付中',
    succeeded: '已交付',
    failed: '交付失败'
  }
  return map[s] || s
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
  const map: Record<string, string> = {
    draft: '草稿',
    listed: '已上架',
    offline: '已下架',
    rejected: '已驳回'
  }
  return map[s] || s
}

function assetStatusClass(s: string): string {
  const map: Record<string, string> = { listed: 'g', offline: 'p', rejected: 'p' }
  return map[s] || ''
}

function billingModeLabel(m: string): string {
  const map: Record<string, string> = {
    by_call: '按调用量',
    by_data: '按数据量',
    by_time: '按时间',
    one_time: '一次性买断'
  }
  return map[m] || m
}

function assetName(id: string): string {
  return (assets.value ?? []).find((a) => a.id === id)?.name || id
}

function assetOwner(id: string): string {
  return (assets.value ?? []).find((a) => a.id === id)?.owner || '—'
}

function formatDate(d?: string): string {
  if (!d) return '—'
  return new Date(d).toLocaleDateString('zh-CN')
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
    store.showToast(`已订阅 ${a.name}，等待审批`)
  } catch (e) {
    store.showToast(`订阅失败：${(e as Error).message}`)
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
    store.showToast('数据交付完成')
  } catch (e) {
    store.showToast(`交付失败：${(e as Error).message}`)
  }
}

async function openBilling(s: Subscription) {
  billingModalVisible.value = true
  billingLoading.value = true
  try {
    billingRecords.value = await assetMarketApi.getBillingRecords(s.id)
  } catch (e) {
    store.showToast(`加载计费记录失败：${(e as Error).message}`)
    billingRecords.value = []
  } finally {
    billingLoading.value = false
  }
}

async function submitListAsset() {
  if (!newAsset.value.name) {
    store.showToast('请填写资产名称')
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
    store.showToast(`资产 ${newAsset.value.name} 已上架`)
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
    store.showToast(`上架失败：${(e as Error).message}`)
  }
}

async function offlineAsset(a: Asset) {
  try {
    const updated = await assetMarketApi.offlineAsset(a.id)
    Object.assign(a, updated)
    store.showToast(`资产 ${a.name} 已下架`)
  } catch (e) {
    store.showToast(`下架失败：${(e as Error).message}`)
  }
}

async function relistAsset(a: Asset) {
  try {
    const updated = await assetMarketApi.relistAsset(a.id)
    Object.assign(a, updated)
    store.showToast(`资产 ${a.name} 已重新上架`)
  } catch (e) {
    store.showToast(`重新上架失败：${(e as Error).message}`)
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
