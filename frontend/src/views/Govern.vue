<template>
  <div>
    <PageHeader :title="t('govern.title')" :subtitle="t('govern.subtitle')" />
    <Toolbar
      v-model:search-value="searchKeyword"
      :show-create="true"
      :create-label="t('govern.registerAsset')"
      :create-aria-label="t('govern.registerAsset')"
      :search-placeholder="t('govern.searchPlaceholder')"
      :search-aria-label="t('govern.searchPlaceholder')"
      :show-refresh="false"
      @create="modalVisible = true"
    >
      <template #filters>
        <el-select :placeholder="t('govern.allLayers')" style="width: 140px">
          <el-option :label="t('govern.allLayers')" value="" />
        </el-select>
      </template>
    </Toolbar>
    <div class="card">
      <div v-if="loading" style="padding: 16px; color: var(--ds-text-tertiary)">
        {{ t('common.loading') }}
      </div>
      <div v-else-if="error" style="padding: 16px; color: var(--ds-color-error-600)">
        {{ error.message }}，
        <a href="javascript:void(0)" @click="loadAssets">{{ t('common.retry') }}</a>
      </div>
      <el-table
        v-else
        :data="assets"
        stripe
        border
        style="width: 100%"
        :empty-text="t('govern.empty')"
        @row-click="openDrawer"
      >
        <el-table-column :label="t('govern.cols.name')" prop="name" min-width="160" />
        <el-table-column :label="t('govern.cols.layer')" prop="layer" width="100" />
        <el-table-column :label="t('govern.cols.owner')" prop="owner" min-width="120" />
        <el-table-column :label="t('govern.cols.score')" prop="score" width="80" />
        <el-table-column :label="t('govern.cols.sensitive')" width="120">
          <template #default="{ row }">
            <span class="pill" :class="sensitivityPillClass(row.sensitivity)">
              {{ sensitivityPillText(row.sensitivity) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column :label="t('govern.cols.detail')" width="100" align="center">
          <template #default>
            <span class="pill b">{{ t('govern.cols.detail') }}</span>
          </template>
        </el-table-column>
      </el-table>
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
        <div class="t" :class="{ on: tab === 1 }" @click="tab = 1">
          {{ t('govern.tabs.schema') }}
        </div>
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
        <div v-if="schemaLoading" style="color: var(--ds-text-tertiary)">
          {{ t('govern.schema.loading') }}
        </div>
        <el-table
          v-else
          :data="schemaFields"
          stripe
          border
          style="width: 100%"
          :empty-text="t('govern.schema.empty')"
        >
          <el-table-column :label="t('govern.schema.colField')" prop="name" min-width="140" />
          <el-table-column :label="t('govern.schema.colType')" prop="type" width="120" />
          <el-table-column :label="t('govern.schema.colSensitive')" width="120">
            <template #default="{ row }">
              <span v-if="row.sensitive" class="pill r">{{ row.sensitivity || 'PII' }}</span>
              <span v-else>—</span>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <div v-if="tab === 2">
        <div v-if="qualityLoading" style="color: var(--ds-text-tertiary)">
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
        <div
          v-if="!qualityLoading && qualityItems.length === 0"
          style="color: var(--ds-text-tertiary)"
        >
          {{ t('govern.quality.empty') }}
        </div>
      </div>
      <div v-if="tab === 3">
        <div v-if="permLoading" style="color: var(--ds-text-tertiary)">
          {{ t('govern.perms.loading') }}
        </div>
        <div v-else>
          <div class="kv">
            <span>{{ t('govern.perms.current') }}</span>
            <span>
              {{
                permissions.map((p) => `${p.user}(${p.permission})`).join(' · ') ||
                t('govern.perms.none')
              }}
            </span>
          </div>
          <el-button
            size="small"
            type="primary"
            style="margin-top: 10px"
            @click="applyReadPermission"
          >
            {{ t('govern.perms.applyRead') }}
          </el-button>
        </div>
        <div class="note">{{ t('govern.perms.note') }}</div>
      </div>
    </Drawer>

    <Modal
      :visible="modalVisible"
      :title="t('govern.registerModal.title')"
      @close="modalVisible = false"
    >
      <label>{{ t('govern.registerModal.name') }}</label>
      <el-input :placeholder="t('govern.registerModal.namePlaceholder')" style="width: 100%" />
      <label>{{ t('govern.registerModal.layer') }}</label>
      <el-select style="width: 100%">
        <el-option label="ODS" value="ODS" />
        <el-option label="DWD" value="DWD" />
        <el-option label="DWS" value="DWS" />
        <el-option label="ADS" value="ADS" />
      </el-select>
      <label>{{ t('govern.registerModal.owner') }}</label>
      <el-input style="width: 100%" />
      <label>{{ t('govern.registerModal.sensitivity') }}</label>
      <el-select style="width: 100%">
        <el-option :label="t('govern.registerModal.sensNone')" value="none" />
        <el-option :label="t('govern.registerModal.sensRestricted')" value="restricted" />
        <el-option :label="t('govern.registerModal.sensPii')" value="PII" />
      </el-select>
      <template #footer>
        <el-button @click="modalVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="ok(t('govern.registerModal.registered'))">
          {{ t('govern.registerModal.register') }}
        </el-button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAppStore } from '@/stores/app'
import { useApi } from '@/composables/useApi'
import { PageHeader, Toolbar } from '@/components/ui'
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
const searchKeyword = ref('')
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

<style scoped>
/* ============ 响应式断点 ============ */
/* 中等屏幕：tabbar 允许换行，避免标签挤压 */
@media (max-width: 1024px) {
  .tabbar {
    flex-wrap: wrap;
    gap: 4px;
  }
}

/* 小屏幕：tabbar 标签等宽分布，kv 键值对纵向排列 */
@media (max-width: 640px) {
  .tabbar {
    flex-wrap: wrap;
    gap: 4px;
  }
  .tabbar .t {
    flex: 1 1 calc(50% - 4px);
    text-align: center;
  }
  .kv {
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
  }
}
</style>
