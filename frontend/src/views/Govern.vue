<template>
  <div>
    <h1>资产目录</h1>
    <div class="sub">统一检索、申请、订阅数据资产；血缘、质量、脱敏策略随资产沉淀。</div>
    <div class="toolbar">
      <input style="width: 280px" placeholder="搜索表 / 主题 / 标签…" />
      <select><option>全部分层</option></select>
      <div class="spacer"></div>
      <button class="btn sm" @click="modalVisible = true">+ 登记资产</button>
    </div>
    <div class="card">
      <div v-if="loading" style="padding: 16px; color: var(--muted)">加载中…</div>
      <div v-else-if="error" style="padding: 16px; color: var(--red)">
        {{ error.message }}，<a href="javascript:void(0)" @click="loadAssets">重试</a>
      </div>
      <table v-else>
        <thead>
          <tr><th>资产名</th><th>分层</th><th>负责人</th><th>质量分</th><th>敏感</th><th></th></tr>
        </thead>
        <tbody>
          <tr class="click" v-for="a in assets" :key="a.id" @click="openDrawer(a)">
            <td>{{ a.name }}</td>
            <td>{{ a.layer }}</td>
            <td>{{ a.owner }}</td>
            <td>{{ a.score }}</td>
            <td><span class="pill" :class="sensitivityPillClass(a.sensitivity)">{{ sensitivityPillText(a.sensitivity) }}</span></td>
            <td><span class="pill b">详情</span></td>
          </tr>
          <tr v-if="assets.length === 0">
            <td colspan="6" style="text-align: center; color: var(--muted)">暂无资产</td>
          </tr>
        </tbody>
      </table>
    </div>

    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>
        资产：{{ current?.name }}
        <span class="pill r">{{ current ? sensitivityPillText(current.sensitivity) : '' }}</span>
      </template>
      <div class="tabbar">
        <div class="t" :class="{ on: tab === 0 }" @click="tab = 0">元数据</div>
        <div class="t" :class="{ on: tab === 1 }" @click="tab = 1">Schema</div>
        <div class="t" :class="{ on: tab === 2 }" @click="tab = 2">质量</div>
        <div class="t" :class="{ on: tab === 3 }" @click="tab = 3">权限</div>
      </div>
      <div v-if="tab === 0">
        <div class="kv"><span>分层</span><span>{{ current?.layer }}</span></div>
        <div class="kv"><span>负责人</span><span>{{ current?.owner }}</span></div>
        <div class="kv"><span>质量分</span><span>{{ current?.score }}</span></div>
        <div class="kv"><span>更新频率</span><span>{{ current?.refreshFrequency || '日' }}</span></div>
      </div>
      <div v-if="tab === 1">
        <div v-if="schemaLoading" style="color: var(--muted)">加载 Schema…</div>
        <table v-else>
          <thead>
            <tr><th>字段</th><th>类型</th><th>敏感</th></tr>
          </thead>
          <tbody>
            <tr v-for="f in schemaFields" :key="f.name">
              <td>{{ f.name }}</td>
              <td>{{ f.type }}</td>
              <td v-if="f.sensitive"><span class="pill r">{{ f.sensitivity || 'PII' }}</span></td>
              <td v-else>—</td>
            </tr>
            <tr v-if="schemaFields.length === 0">
              <td colspan="3" style="text-align: center; color: var(--muted)">暂无 Schema</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="tab === 2">
        <div v-if="qualityLoading" style="color: var(--muted)">加载质量检查…</div>
        <div v-else v-for="(q, idx) in qualityItems" :key="idx" class="kv">
          <span>{{ q.ruleName }}</span>
          <span><span class="pill" :class="q.passed ? 'g' : 'r'">{{ q.passed ? '通过' : '未通过' }}</span></span>
        </div>
        <div v-if="!qualityLoading && qualityItems.length === 0" style="color: var(--muted)">暂无质量检查结果</div>
      </div>
      <div v-if="tab === 3">
        <div v-if="permLoading" style="color: var(--muted)">加载权限…</div>
        <div v-else>
          <div class="kv"><span>当前权限</span><span>{{ permissions.map(p => `${p.user}(${p.permission})`).join(' · ') || '无' }}</span></div>
          <button class="btn sm" style="margin-top: 10px" @click="applyReadPermission">申请读权限</button>
        </div>
        <div class="note">申请经审批流，不直连底层存储。</div>
      </div>
    </Drawer>

    <Modal :visible="modalVisible" title="登记数据资产" @close="modalVisible = false">
      <label>资产名</label><input placeholder="如 dws.xxx" />
      <label>分层</label>
      <select><option>ODS</option><option>DWD</option><option>DWS</option><option>ADS</option></select>
      <label>负责人</label><input />
      <label>敏感级别</label>
      <select><option>无</option><option>受限</option><option>PII</option></select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">取消</button>
        <button class="btn" @click="ok('资产已登记')">登记</button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Drawer from '@/components/Drawer.vue'
import Modal from '@/components/Modal.vue'
import * as governanceApi from '@/api/governance'
import type { Asset, AssetSchemaField, AssetQualityItem, AssetPermission, AssetSchema } from '@/api/governance'
import type { PagedResult } from '@/api/types'

const store = useAppStore()

// 资产列表：通过 useApi 包装 API 调用，自动维护 loading / error / data 三态
const {
  data: paged,
  loading,
  error,
  execute: loadAssets
} = useApi<PagedResult<Asset>>(() => governanceApi.listAssets({ page: 1, pageSize: 100 }))
const assets = computed<Asset[]>(() => paged.value?.list ?? [])

/** 敏感级别 → pill 样式 */
function sensitivityPillClass(s: string): string {
  switch (s) {
    case 'PII':
      return 'r'
    case 'restricted':
      return 'a'
    default:
      return 'g'
  }
}

/** 敏感级别 → pill 文案 */
function sensitivityPillText(s: string): string {
  switch (s) {
    case 'PII':
      return 'PII'
    case 'restricted':
      return '受限'
    default:
      return '无'
  }
}

const drawerVisible = ref(false)
const modalVisible = ref(false)
const tab = ref(0)
const current = ref<Asset | null>(null)

// Schema、质量、权限：通过 useApi 包装并行加载
const {
  data: detailData,
  loading: detailLoading,
  execute: loadDetail
} = useApi<[AssetSchemaField[], AssetQualityItem[], AssetPermission[]], [string]>(
  (id: string) =>
    Promise.all([
      governanceApi.getAssetSchema(id).then((s: AssetSchema) => s.fields).catch(() => [] as AssetSchemaField[]),
      governanceApi.getAssetQuality(id).catch(() => [] as AssetQualityItem[]),
      governanceApi.getAssetPermissions(id).catch(() => [] as AssetPermission[])
    ])
)

// Schema 字段
const schemaFields = computed<AssetSchemaField[]>(() => detailData.value?.[0] ?? [])
// 质量检查结果
const qualityItems = computed<AssetQualityItem[]>(() => detailData.value?.[1] ?? [])
// 权限列表
const permissions = computed<AssetPermission[]>(() => detailData.value?.[2] ?? [])
// 各 tab 的 loading 状态（统一由 detailLoading 控制）
const schemaLoading = computed(() => detailLoading.value)
const qualityLoading = computed(() => detailLoading.value)
const permLoading = computed(() => detailLoading.value)

/** 打开抽屉并加载详情 */
async function openDrawer(a: Asset) {
  current.value = a
  tab.value = 0
  drawerVisible.value = true
  await loadDetail(a.id)
}

/** 申请读权限 */
async function applyReadPermission() {
  if (!current.value) return
  try {
    await governanceApi.applyAssetPermission(current.value.id, 'read')
    store.showToast('权限申请已提交，等待审批')
  } catch {
    // 错误提示已由拦截器统一处理
  }
}

function ok(msg: string) {
  modalVisible.value = false
  store.showToast(msg)
}

onMounted(() => {
  void loadAssets()
})
</script>