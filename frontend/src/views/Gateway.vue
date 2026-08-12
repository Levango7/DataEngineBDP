<template>
  <div>
    <h1>大模型网关</h1>
    <div class="sub">L4.5 · 统一 API 入口，路由多模型、限流、计费、审计，屏蔽底层部署差异。</div>
    <div class="grid g4">
      <div class="card"><h3>今日调用</h3><div class="kpi s">{{ stats?.todayCallCount?.toLocaleString() ?? '--' }}</div><div class="meta">请求数</div></div>
      <div class="card"><h3>平均时延</h3><div class="kpi s">{{ stats?.avgLatencyMs ?? '--' }}ms</div></div>
      <div class="card"><h3>成功率</h3><div class="kpi s">{{ stats?.successRate ?? '--' }}%</div></div>
      <div class="card"><h3>活跃 Key</h3><div class="kpi s">{{ stats?.activeKeyCount ?? '--' }}</div></div>
    </div>
    <div class="card" style="margin-top: 14px">
      <h3>API Key 与路由</h3>
      <div v-if="keysLoading" style="color: var(--muted)">加载中…</div>
      <div v-else-if="keysError" style="color: var(--red)">{{ keysError }}</div>
      <table v-else>
        <thead>
          <tr><th>Key 名称</th><th>路由模型</th><th>限流</th><th>状态</th></tr>
        </thead>
        <tbody>
          <tr v-for="k in apiKeys" :key="k.id">
            <td>{{ k.name }}</td>
            <td>{{ k.routeModel }}</td>
            <td>{{ k.rateLimit }}/s</td>
            <td><span class="pill" :class="keyStatusPillClass(k.status)">{{ keyStatusPillText(k.status) }}</span></td>
          </tr>
          <tr v-if="apiKeys.length === 0">
            <td colspan="4" style="text-align: center; color: var(--muted)">暂无 API Key</td>
          </tr>
        </tbody>
      </table>
      <button class="btn ghost sm" style="margin-top: 8px" @click="modalVisible = true">+ 新建 Key</button>
    </div>

    <Modal :visible="modalVisible" title="新建 API Key" @close="modalVisible = false">
      <label>Key 名称</label><input v-model="form.name" placeholder="如 mkt-exp" />
      <label>路由模型</label>
      <select v-model="form.routeModel"><option>qiong-7B</option><option>风控-领域-1.3B</option><option>营销-领域-3B</option></select>
      <label>限流(/s)</label><input v-model.number="form.rateLimit" type="number" />
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" :disabled="submitting" @click="handleSubmit">{{ submitting ? '生成中…' : '生成' }}</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import Modal from '@/components/Modal.vue'
import * as gatewayApi from '@/api/gateway'
import type { GatewayStats, ApiKey, KeyStatus } from '@/api/gateway'

const store = useAppStore()
const modalVisible = ref(false)
const submitting = ref(false)

// 统计
const stats = ref<GatewayStats | null>(null)

// API Key 列表
const apiKeys = ref<ApiKey[]>([])
const keysLoading = ref(false)
const keysError = ref('')

/** 加载统计 */
async function loadStats() {
  try {
    stats.value = await gatewayApi.getStats()
  } catch {
    // 统计加载失败不阻塞页面
  }
}

/** 加载 API Key 列表 */
async function loadApiKeys() {
  keysLoading.value = true
  keysError.value = ''
  try {
    apiKeys.value = await gatewayApi.listApiKeys()
  } catch (err) {
    keysError.value = (err as Error).message || 'API Key 列表加载失败'
  } finally {
    keysLoading.value = false
  }
}

/** Key 状态 → pill 样式 */
function keyStatusPillClass(s: KeyStatus): string {
  switch (s) {
    case 'enabled':
      return 'g'
    case 'pending':
      return 'a'
    default:
      return 'b'
  }
}

/** Key 状态 → pill 文案 */
function keyStatusPillText(s: KeyStatus): string {
  switch (s) {
    case 'enabled':
      return '启用'
    case 'pending':
      return '待上线'
    case 'disabled':
      return '已禁用'
    default:
      return s
  }
}

// 新建表单
const form = reactive<{
  name: string
  routeModel: string
  rateLimit: number
}>({
  name: '',
  routeModel: 'qiong-7B',
  rateLimit: 20
})

/** 提交创建 Key */
async function handleSubmit() {
  if (!form.name.trim()) {
    store.showToast('请填写 Key 名称')
    return
  }
  submitting.value = true
  try {
    await gatewayApi.createApiKey({
      name: form.name,
      routeModel: form.routeModel,
      rateLimit: form.rateLimit
    })
    modalVisible.value = false
    store.showToast('API Key 已生成')
    await loadApiKeys()
    await loadStats()
  } catch {
    // 错误提示已由拦截器统一处理
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadStats()
  loadApiKeys()
})
</script>