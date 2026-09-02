<template>
  <div>
    <h1>{{ t('govern.title') }}</h1>
    <div class="sub">{{ t('govern.subtitle') }}</div>
    <div class="toolbar">
      <input style="width: 280px" :placeholder="t('govern.searchPlaceholder')" />
      <select>
        <option>{{ t('govern.allLayers') }}</option>
      </select>
      <div class="spacer"></div>
      <button class="btn sm" @click="modalVisible = true">{{ t('govern.registerAsset') }}</button>
    </div>
    <div class="card">
      <div v-if="loading" style="padding: 16px; color: var(--muted)">{{ t('common.loading') }}</div>
      <div v-else-if="error" style="padding: 16px; color: var(--red)">
        {{ error.message }}，
        <a href="javascript:void(0)" @click="loadAssets">{{ t('common.retry') }}</a>
      </div>
      <table v-else>
        <thead>
          <tr>
            <th>{{ t('govern.cols.name') }}</th>
            <th>{{ t('govern.cols.layer') }}</th>
            <th>{{ t('govern.cols.owner') }}</th>
            <th>{{ t('govern.cols.score') }}</th>
            <th>{{ t('govern.cols.sensitive') }}</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="a in assets" :key="a.id" class="click" @click="openDrawer(a)">
            <td>{{ a.name }}</td>
            <td>{{ a.layer }}</td>
            <td>{{ a.owner }}</td>
            <td>{{ a.score }}</td>
            <td>
              <span class="pill" :class="sensitivityPillClass(a.sensitivity)">
                {{ sensitivityPillText(a.sensitivity) }}
              </span>
            </td>
            <td><span class="pill b">{{ t('govern.cols.detail') }}</span></td>
          </tr>
          <tr v-if="assets.length === 0">
            <td colspan="6" style="text-align: center; color: var(--muted)">
              {{ t('govern.empty') }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <Drawer :visible="drawerVisible" @close="drawerVisible = false">
      <template #header>
        {{ t('govern.drawerTitle', { name: current?.name }) }}
        <span class="pill r">{{ current ? sensitivityPillText(current.sensitivity) : '' }}</span>
      </template>
      <div class="tabbar">
        <div class="t" :class="{ on: tab === 0 }" @click="tab = 0">
          {{ t('govern.tabs.metadata') }}
        </div>
        <div class="t" :class="{ on: tab === 1 }" @click="tab = 1">{{ t('govern.tabs.schema') }}</div>
        <div class="t" :class="{ on: tab === 2 }" @click="tab = 2">
          {{ t('govern.tabs.quality') }}
        </div>
        <div class="t" :class="{ on: tab === 3 }" @click="tab = 3">
          {{ t('govern.tabs.permissions') }}
        </div>
      </div>
      <div v-if="tab === 0">
        <div class="kv">
          <span>{{ t('govern.meta.layer') }}</span>
          <span>{{ current?.layer }}</span>
        </div>
        <div class="kv">
          <span>{{ t('govern.meta.owner') }}</span>
          <span>{{ current?.owner }}</span>
        </div>
        <div class="kv">
          <span>{{ t('govern.meta.score') }}</span>
          <span>{{ current?.score }}</span>
        </div>
        <div class="kv">
          <span>{{ t('govern.meta.refresh') }}</span>
          <span>{{ current?.refreshFrequency || t('govern.meta.refreshDefault') }}</span>
        </div>
      </div>
      <div v-if="tab === 1">
        <div v-if="schemaLoading" style="color: var(--muted)">{{ t('govern.schema.loading') }}</div>
        <table v-else>
          <thead>
            <tr>
              <th>{{ t('govern.schema.colField') }}</th>
              <th>{{ t('govern.schema.colType') }}</th>
              <th>{{ t('govern.schema.colSensitive') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="f in schemaFields" :key="f.name">
              <td>{{ f.name }}</td>
              <td>{{ f.type }}</td>
              <td v-if="f.sensitive">
                <span class="pill r">{{ f.sensitivity || 'PII' }}</span>
              </td>
              <td v-else>—</td>
            </tr>
            <tr v-if="schemaFields.length === 0">
              <td colspan="3" style="text-align: center; color: var(--muted)">
                {{ t('govern.schema.empty') }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="tab === 2">
        <div v-if="qualityLoading" style="color: var(--muted)">
          {{ t('govern.quality.loading') }}
        </div>
        <div v-for="(q, idx) in qualityItems" v-else :key="idx" class="kv">
          <span>{{ q.ruleName }}</span>
          <span>
            <span class="pill" :class="q.passed ? 'g' : 'r'">
              {{ q.passed ? t('govern.quality.passed') : t('govern.quality.failed') }}
            </span>
          </span>
        </div>
        <div v-if="!qualityLoading && qualityItems.length === 0" style="color: var(--muted)">
          {{ t('govern.quality.empty') }}
        </div>
      </div>
      <div v-if="tab === 3">
        <div v-if="permLoading" style="color: var(--muted)">{{ t('govern.perms.loading') }}</div>
        <div v-else>
          <div class="kv">
            <span>{{ t('govern.perms.current') }}</span>
            <span>
              {{ permissions.map((p) => `${p.user}(${p.permission})`).join(' · ') || t('govern.perms.none') }}
            </span>
          </div>
          <button class="btn sm" style="margin-top: 10px" @click="applyReadPermission">
            {{ t('govern.perms.applyRead') }}
          </button>
        </div>
        <div class="note">{{ t('govern.perms.note') }}</div>
      </div>
    </Drawer>

    <Modal :visible="modalVisible" :title="t('govern.registerModal.title')" @close="modalVisible = false">
      <label>{{ t('govern.registerModal.name') }}</label>
      <input :placeholder="t('govern.registerModal.namePlaceholder')" />
      <label>{{ t('govern.registerModal.layer') }}</label>
      <select>
        <option>ODS</option>
        <option>DWD</option>
        <option>DWS</option>
        <option>ADS</option>
      </select>
      <label>{{ t('govern.registerModal.owner') }}</label>
      <input />
      <label>{{ t('govern.registerModal.sensitivity') }}</label>
      <select>
        <option>{{ t('govern.registerModal.sensNone') }}</option>
        <option>{{ t('govern.registerModal.sensRestricted') }}</option>
        <option>{{ t('govern.registerModal.sensPii') }}</option>
      </select>
      <template #footer>
        <button class="btn ghost" @click="modalVisible = false">{{ t('common.cancel') }}</button>
        <button class="btn" @click="ok(t('govern.registerModal.registered'))">
          {{ t('govern.registerModal.register') }}
        </button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import Drawer from '@/components/Drawer.vue'
import Modal from '@/components/Modal.vue'
import * as governanceApi from '@/api/governance'
import type {
  Asset,
  AssetSchemaField,
  AssetQualityItem,
  AssetPermission,
  AssetSchema
} from '@/api/governance'
import type { PagedResult } from '@/api/types'

const { t } = useI18n()
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
      return t('govern.sensitivity.restricted')
    default:
      return t('govern.sensitivity.none')
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
} = useApi<[AssetSchemaField[], AssetQualityItem[], AssetPermission[]], [string]>((id: string) =>
  Promise.all([
    governanceApi
      .getAssetSchema(id)
      .then((s: AssetSchema) => s.fields)
      .catch(() => [] as AssetSchemaField[]),
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
    store.showToast(t('govern.perms.applied'))
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
