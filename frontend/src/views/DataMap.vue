<template>
  <div class="page-merged">
    <PageHeader :title="t('dataMap.title')" :subtitle="t('dataMap.subtitle')" />
    <Toolbar
      :search-placeholder="t('dataMap.searchPlaceholder')"
      :search-value="keyword"
      :search-aria-label="t('dataMap.search')"
      :show-refresh="true"
      :refresh-aria-label="t('common.refresh')"
      @update:search-value="keyword = $event"
      @search="runSearch"
      @refresh="refresh"
    >
      <template #actions>
        <span class="pill b">
          {{ t('dataMap.tables') }}: {{ searchMode ? hits.length : tables.length }}
        </span>
        <el-button v-if="searchMode" size="small" @click="clearSearch">
          {{ t('dataMap.clear') }}
        </el-button>
      </template>
    </Toolbar>

    <div v-if="loading" class="state-tip state-loading">{{ t('common.loading') }}</div>
    <div v-else-if="error" class="state-tip state-error">
      {{ error.message }}
      <button type="button" class="link-btn" @click="refresh">{{ t('common.retry') }}</button>
    </div>

    <!-- 搜索模式：分词检索命中（表名 + 相关度） -->
    <div v-else-if="searchMode" class="card">
      <div v-if="!hits.length" class="state-tip">{{ t('dataMap.searchEmpty') }}</div>
      <el-table
        v-else
        :data="hits"
        stripe
        border
        role="table"
        :aria-label="t('dataMap.title')"
        @row-click="openDetail"
      >
        <el-table-column :label="t('dataMap.cols.name')" min-width="160">
          <template #default="{ row }">
            {{ row.table.tableName }}
          </template>
        </el-table-column>
        <el-table-column :label="t('dataMap.cols.database')" min-width="120">
          <template #default="{ row }">
            {{ row.table.databaseName }}
          </template>
        </el-table-column>
        <el-table-column :label="t('dataMap.cols.columns')" width="90">
          <template #default="{ row }">
            {{ row.table.columns ? row.table.columns.length : 0 }}
          </template>
        </el-table-column>
        <el-table-column :label="t('dataMap.cols.score')" width="100">
          <template #default="{ row }">
            {{ formatScore(row.score) }}
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 浏览模式：左库列表 + 右表列表 -->
    <div v-else class="dm-grid">
      <div class="card dm-dbs">
        <div class="dm-section-title">{{ t('dataMap.databases') }}</div>
        <div v-if="!databases.length" class="state-tip">{{ t('dataMap.empty') }}</div>
        <ul v-else class="dm-db-list" role="list">
          <li>
            <button
              type="button"
              class="dm-db"
              :class="{ active: selectedDb === '' }"
              @click="selectDb('')"
            >
              {{ t('dataMap.allDatabases') }}
            </button>
          </li>
          <li v-for="db in databases" :key="db.id">
            <button
              type="button"
              class="dm-db"
              :class="{ active: selectedDb === db.name }"
              @click="selectDb(db.name)"
            >
              <span class="dm-db-name">{{ db.name }}</span>
              <span v-if="db.owner" class="dm-db-owner">{{ db.owner }}</span>
            </button>
          </li>
        </ul>
      </div>

      <div class="card dm-tables">
        <div class="dm-section-title">{{ t('dataMap.tables') }}</div>
        <div v-if="!tables.length" class="state-tip">{{ t('dataMap.empty') }}</div>
        <el-table
          v-else
          :data="tables"
          stripe
          border
          role="table"
          :aria-label="t('dataMap.tables')"
          @row-click="openDetail"
        >
          <el-table-column prop="tableName" :label="t('dataMap.cols.name')" min-width="160" />
          <el-table-column
            prop="databaseName"
            :label="t('dataMap.cols.database')"
            min-width="120"
          />
          <el-table-column :label="t('dataMap.cols.columns')" width="90">
            <template #default="{ row }">
              {{ row.columns ? row.columns.length : 0 }}
            </template>
          </el-table-column>
          <el-table-column
            prop="description"
            :label="t('dataMap.cols.description')"
            min-width="160"
          />
          <el-table-column prop="updatedAt" :label="t('dataMap.cols.updatedAt')" min-width="150" />
        </el-table>
      </div>
    </div>

    <!-- 表结构详情 -->
    <Modal :visible="detailVisible" :title="t('dataMap.detailTitle')" @close="closeDetail">
      <template v-if="detailTable">
        <div class="dm-detail-meta">
          <span class="pill b">{{ detailTable.databaseName }}.{{ detailTable.tableName }}</span>
          <span v-if="detailTable.partitionKeys && detailTable.partitionKeys.length" class="pill">
            {{ t('dataMap.partitionKeys') }}: {{ detailTable.partitionKeys.join(', ') }}
          </span>
        </div>
        <p v-if="detailTable.description" class="dm-detail-desc">{{ detailTable.description }}</p>
        <el-table
          :data="detailTable.columns || []"
          stripe
          border
          size="small"
          :aria-label="t('dataMap.detailTitle')"
        >
          <el-table-column prop="name" :label="t('dataMap.colCols.name')" min-width="140" />
          <el-table-column prop="type" :label="t('dataMap.colCols.type')" min-width="110" />
          <el-table-column :label="t('dataMap.colCols.nullable')" width="90">
            <template #default="{ row }">
              {{ row.nullable ? t('dataMap.yes') : t('dataMap.no') }}
            </template>
          </el-table-column>
          <el-table-column
            prop="description"
            :label="t('dataMap.colCols.description')"
            min-width="160"
          />
        </el-table>
      </template>
      <template #footer>
        <el-button @click="closeDetail">{{ t('common.cancel') }}</el-button>
      </template>
    </Modal>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { PageHeader, Toolbar } from '@/components/ui'
import Modal from '@/components/Modal.vue'
import * as catalogApi from '@/api/catalog'
import type { CatalogDatabase, CatalogSearchHit, CatalogTable } from '@/api/catalog'

const { t } = useI18n()

const databases = ref<CatalogDatabase[]>([])
const tables = ref<CatalogTable[]>([])
const hits = ref<CatalogSearchHit[]>([])
const loading = ref(false)
const error = ref<Error | null>(null)

// 搜索
const keyword = ref('')
const searchMode = ref(false)
const searching = ref(false)

// 浏览（按库过滤）
const selectedDb = ref('')

// 表结构详情
const detailVisible = ref(false)
const detailTable = ref<CatalogTable | null>(null)

/** 加载库列表 + 表列表（首屏 / 手动刷新） */
async function refresh() {
  loading.value = true
  error.value = null
  try {
    const dbPage = await catalogApi.listDatabases({ page: 1, pageSize: 100 })
    databases.value = dbPage.list
    await loadTables()
  } catch (e) {
    error.value = e as Error
  } finally {
    loading.value = false
  }
}

/** 加载表列表（带当前库过滤） */
async function loadTables() {
  const page = await catalogApi.listTables({
    database: selectedDb.value || undefined,
    page: 1,
    pageSize: 100
  })
  tables.value = page.list
}

/** 切换库过滤 */
async function selectDb(name: string) {
  selectedDb.value = name
  error.value = null
  try {
    await loadTables()
  } catch (e) {
    error.value = e as Error
  }
}

/** 分词全文检索（表名 + 描述） */
async function runSearch() {
  const q = keyword.value.trim()
  if (!q) return
  searching.value = true
  searchMode.value = true
  error.value = null
  try {
    hits.value = await catalogApi.searchTables(q)
  } catch (e) {
    error.value = e as Error
  } finally {
    searching.value = false
  }
}

/** 退出搜索回到浏览 */
function clearSearch() {
  searchMode.value = false
  hits.value = []
}

/** 打开表结构详情（浏览行为 CatalogTable，搜索行为 CatalogSearchHit） */
function openDetail(row: CatalogTable | CatalogSearchHit) {
  detailTable.value = 'table' in row ? row.table : row
  detailVisible.value = true
}

function closeDetail() {
  detailVisible.value = false
  detailTable.value = null
}

/** 相关度分数展示（0~1 保留两位） */
function formatScore(score?: number): string {
  return typeof score === 'number' ? score.toFixed(2) : '--'
}

onMounted(() => {
  void refresh()
})
</script>

<style scoped>
.state-tip {
  padding: var(--ds-spacing-4);
}
.state-loading {
  color: var(--ds-text-tertiary);
}
.state-error {
  color: var(--ds-color-error-500);
}
.link-btn {
  background: none;
  border: none;
  padding: 0;
  color: var(--ds-color-primary-500);
  text-decoration: underline;
  cursor: pointer;
  font: inherit;
}

/* 浏览模式：左库右表 */
.dm-grid {
  display: grid;
  grid-template-columns: minmax(200px, 260px) 1fr;
  gap: var(--ds-spacing-4);
  align-items: start;
}
@media (max-width: 900px) {
  .dm-grid {
    grid-template-columns: 1fr;
  }
}
.dm-section-title {
  font-weight: var(--ds-font-weight-medium);
  color: var(--ds-text-secondary);
  margin-bottom: var(--ds-spacing-2);
}
.dm-db-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: var(--ds-spacing-1);
}
.dm-db {
  width: 100%;
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: var(--ds-spacing-2);
  background: none;
  border: 1px solid transparent;
  border-radius: var(--ds-radius-md, 6px);
  padding: var(--ds-spacing-2);
  color: var(--ds-text-primary);
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.dm-db:hover {
  background: var(--ds-color-primary-50);
}
.dm-db.active {
  border-color: var(--ds-color-primary-600);
  color: var(--ds-color-primary-700);
  font-weight: var(--ds-font-weight-medium);
}
.dm-db-owner {
  color: var(--ds-text-tertiary);
  font-size: var(--ds-font-size-sm);
}
.dm-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: var(--ds-spacing-2);
  margin-bottom: var(--ds-spacing-3);
}
.dm-detail-desc {
  margin: 0 0 var(--ds-spacing-3);
  color: var(--ds-text-secondary);
}
</style>
