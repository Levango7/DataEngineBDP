<!--
  SearchPortal.vue — 检索门户主页面（T007）

  组合以下子组件：
  - SearchInput       检索输入（自然语言 + 结构化）
  - SearchFilter      多维过滤器（时间/来源/类型/标签）
  - SearchResultCard  结果卡片（高亮）
  - SearchPagination  分页 / 无限滚动
  - SearchExport      导出（CSV/JSON/Excel）

  布局：
  - 顶部：标题 + 检索输入
  - 中部：左侧过滤器 + 右侧结果列表
  - 底部：分页 / 无限滚动
-->
<template>
  <div class="search-portal">
    <h1>检索门户</h1>
    <div class="sub">
      统一检索入口，支持自然语言与结构化查询，覆盖数据集 / 服务 / 模型 / 作业等全资产类型。
    </div>

    <!-- 顶部检索输入 -->
    <div class="portal-search-bar">
      <SearchInput
        :model-mode="query.mode"
        :loading="loading"
        @search="onSearch"
        @clear="onClear"
        @mode-change="onModeChange"
      />
    </div>

    <!-- 主体：左过滤器 + 右结果 -->
    <div class="portal-body">
      <!-- 左侧过滤器 -->
      <aside class="portal-filter">
        <SearchFilter
          v-model="filterModel"
          :facets="facets"
          @change="onFilterChange"
          @reset="onFilterReset"
        />

        <!-- 排序选择 -->
        <div class="sort-box">
          <div class="sort-title">排序</div>
          <el-select v-model="sortModel" @change="onSortChange">
            <el-option label="相关度" value="relevance" />
            <el-option label="更新时间" value="updatedAt" />
            <el-option label="创建时间" value="createdAt" />
            <el-option label="评分" value="score" />
          </el-select>
          <el-radio-group v-model="sortOrder" size="small" @change="onSortChange">
            <el-radio-button value="desc">降序</el-radio-button>
            <el-radio-button value="asc">升序</el-radio-button>
          </el-radio-group>
        </div>
      </aside>

      <!-- 右侧结果区 -->
      <main class="portal-main">
        <!-- 工具栏：统计 + 导出 -->
        <div class="result-toolbar">
          <div class="result-stat">
            <template v-if="loading">检索中…</template>
            <template v-else-if="hasSearched">
              命中
              <span class="hit-num">{{ total }}</span>
              条 · 耗时 {{ tookMs }} ms
            </template>
            <template v-else>请输入检索条件</template>
          </div>
          <div class="toolbar-actions">
            <el-button
              v-if="suggestions.length > 0"
              size="small"
              :icon="MagicStick"
              @click="showSuggestions = !showSuggestions"
            >
              相关建议
            </el-button>
            <SearchExport
              :query="query"
              :results="results"
              :total="total"
              @exported="onExported"
              @error="onExportError"
            />
          </div>
        </div>

        <!-- 检索建议 -->
        <div v-if="showSuggestions && suggestions.length > 0" class="suggestion-bar">
          <span class="sug-label">您是否要找：</span>
          <span v-for="s in suggestions" :key="s" class="sug-chip" @click="applySuggestion(s)">
            {{ s }}
          </span>
        </div>

        <!-- 错误提示 -->
        <el-alert
          v-if="error"
          :title="error.message"
          type="error"
          show-icon
          :closable="false"
          style="margin-bottom: 12px"
        />

        <!-- 加载骨架 -->
        <div v-if="loading" class="result-grid">
          <div v-for="i in pageSize" :key="`sk-${i}`" class="skeleton-card">
            <div class="sk-line sk-title"></div>
            <div class="sk-line"></div>
            <div class="sk-line sk-short"></div>
          </div>
        </div>

        <!-- 空状态 -->
        <div v-else-if="isEmpty" class="empty-state">
          <el-icon :size="48"><Search /></el-icon>
          <p>未找到匹配结果</p>
          <span class="empty-tip">尝试调整检索词或过滤条件</span>
        </div>

        <!-- 未检索初始态 -->
        <div v-else-if="!hasSearched" class="init-state">
          <el-icon :size="48"><Document /></el-icon>
          <p>开始您的检索</p>
          <span class="empty-tip">支持自然语言描述或结构化条件构建</span>
        </div>

        <!-- 结果卡片网格 -->
        <div v-else class="result-grid">
          <SearchResultCard
            v-for="item in results"
            :key="item.id"
            :item="item"
            @open="onOpenItem"
            @bookmark="onBookmark"
          />
        </div>

        <!-- 分页 / 无限滚动 -->
        <SearchPagination
          v-if="hasSearched && results.length > 0"
          :page="page"
          :page-size="pageSize"
          :total="total"
          :has-more="hasMore"
          :loading-more="loadingMore"
          :loaded-count="results.length"
          :mode="pagingMode"
          @page-change="onPageChange"
          @size-change="onPageSizeChange"
          @load-more="onLoadMore"
          @mode-change="onPagingModeChange"
        />
      </main>
    </div>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" title="检索结果详情" size="480px" direction="rtl">
      <div v-if="detailItem" class="detail-content">
        <div class="detail-row">
          <span class="detail-label">ID</span>
          <span class="detail-value">{{ detailItem.id }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">名称</span>
          <span class="detail-value">{{ detailItem.name }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">类型</span>
          <span class="detail-value">{{ detailItem.type }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">数据源</span>
          <span class="detail-value">{{ detailItem.sourceName }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">负责人</span>
          <span class="detail-value">{{ detailItem.owner ?? '—' }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">评分</span>
          <span class="detail-value">{{ (detailItem.score * 100).toFixed(1) }}%</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">创建时间</span>
          <span class="detail-value">{{ detailItem.createdAt }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">更新时间</span>
          <span class="detail-value">{{ detailItem.updatedAt }}</span>
        </div>
        <div class="detail-row">
          <span class="detail-label">标签</span>
          <span class="detail-value">{{ detailItem.tags.join(', ') || '—' }}</span>
        </div>
        <div class="detail-desc">
          <div class="detail-label">描述</div>
          <p>{{ detailItem.description || '暂无描述' }}</p>
        </div>
        <div v-if="detailItem.url" class="detail-actions">
          <el-button type="primary" @click="openDetailUrl">打开资产</el-button>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import {
  ElMessage,
  ElAlert,
  ElDrawer,
  ElButton,
  ElIcon,
  ElSelect,
  ElOption,
  ElRadioGroup,
  ElRadioButton
} from 'element-plus'
import { Search, MagicStick, Document } from '@element-plus/icons-vue'
import SearchInput from './search/SearchInput.vue'
import SearchFilter from './search/SearchFilter.vue'
import SearchResultCard from './search/SearchResultCard.vue'
import SearchPagination from './search/SearchPagination.vue'
import SearchExport from './search/SearchExport.vue'
import { useSearch } from '@/composables/useSearch'
import type {
  SearchFilter as SearchFilterType,
  SearchSort,
  SearchMode,
  StructuredCondition,
  SearchResultItem,
  ExportResult,
  PagingMode,
  SearchSortField,
  SortOrder
} from '@/types/search'

/* ------------------------------ 检索状态 ------------------------------ */
const {
  query,
  results,
  loading,
  loadingMore,
  error,
  total,
  tookMs,
  hasMore,
  page,
  pageSize,
  pagingMode,
  facets,
  suggestions,
  hasSearched,
  isEmpty,
  setQueryText,
  setMode,
  setConditions,
  setFilter,
  resetFilter,
  setSort,
  setPagingMode,
  setPageSize,
  setPage,
  loadMore,
  refresh,
  loadFacets
} = useSearch({ initialPageSize: 20, initialPagingMode: 'page' })

/* ------------------------------ 双向绑定模型 ------------------------------ */
const filterModel = reactive<SearchFilterType>({
  time: { preset: '' },
  sources: [],
  types: [],
  tags: []
})

const sortModel = ref<SearchSortField>('relevance')
const sortOrder = ref<SortOrder>('desc')
const showSuggestions = ref(false)

/* ------------------------------ 详情抽屉 ------------------------------ */
const detailVisible = ref(false)
const detailItem = ref<SearchResultItem | null>(null)

/* ------------------------------ 事件处理 ------------------------------ */
function onSearch(payload: {
  text: string
  conditions: StructuredCondition[]
  mode: SearchMode
}): void {
  setMode(payload.mode)
  if (payload.mode === 'natural') {
    setQueryText(payload.text)
  } else {
    setConditions(payload.conditions)
  }
}

function onClear(): void {
  setQueryText('')
  setConditions([])
  resetFilter()
  ElMessage.info('已清空检索条件')
}

function onModeChange(mode: SearchMode): void {
  setMode(mode)
}

function onFilterChange(filter: SearchFilterType): void {
  setFilter(filter)
}

function onFilterReset(): void {
  resetFilter()
}

function onSortChange(): void {
  const sort: SearchSort = { field: sortModel.value, order: sortOrder.value }
  setSort(sort)
}

function onPageChange(p: number): void {
  setPage(p)
}

function onPageSizeChange(s: number): void {
  setPageSize(s)
}

function onLoadMore(): void {
  void loadMore()
}

function onPagingModeChange(m: PagingMode): void {
  setPagingMode(m)
}

function onOpenItem(item: SearchResultItem): void {
  detailItem.value = item
  detailVisible.value = true
}

function onBookmark(item: SearchResultItem, next: boolean): void {
  // 实际项目可调用收藏 API
  ElMessage.success(next ? `已收藏「${item.name}」` : `已取消收藏「${item.name}」`)
}

function onExported(result: ExportResult): void {
  // 导出成功已由子组件提示
  void result
}

function onExportError(err: Error): void {
  ElMessage.error(`导出失败：${err.message}`)
}

function applySuggestion(s: string): void {
  setQueryText(s)
  showSuggestions.value = false
}

function openDetailUrl(): void {
  if (detailItem.value?.url) {
    window.open(detailItem.value.url, '_blank', 'noopener,noreferrer')
  }
}

/* ------------------------------ 初始化 ------------------------------ */
onMounted(() => {
  void loadFacets()
})
</script>

<style scoped>
.search-portal {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.portal-search-bar {
  background: var(--panel, #fff);
  border: 1px solid var(--line, var(--ds-border-default));
  border-radius: 10px;
  padding: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}
.portal-body {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 14px;
  align-items: start;
}
.portal-filter {
  display: flex;
  flex-direction: column;
  gap: 14px;
  position: sticky;
  top: 14px;
}
.sort-box {
  background: var(--panel, #fff);
  border: 1px solid var(--line, var(--ds-border-default));
  border-radius: 10px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.sort-title {
  font-size: 13px;
  font-weight: 600;
}
.portal-main {
  min-width: 0;
}
.result-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 8px;
}
.result-stat {
  font-size: 13px;
  color: var(--muted, var(--ds-text-secondary));
}
.hit-num {
  color: var(--primary, var(--ds-color-success-700));
  font-weight: 700;
}
.toolbar-actions {
  display: flex;
  gap: 8px;
  align-items: center;
}
.suggestion-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: var(--c-amber-50, #fffbeb);
  border-radius: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}
.sug-label {
  font-size: 12px;
  color: var(--amber, var(--ds-color-warning-600));
}
.sug-chip {
  font-size: 12px;
  padding: 2px 10px;
  background: #fff;
  border: 1px solid var(--amber, var(--ds-color-warning-600));
  border-radius: 12px;
  color: var(--amber, var(--ds-color-warning-600));
  cursor: pointer;
}
.sug-chip:hover {
  background: var(--amber, var(--ds-color-warning-600));
  color: #fff;
}
.result-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 14px;
}
.skeleton-card {
  background: var(--panel, #fff);
  border: 1px solid var(--line, var(--ds-border-default));
  border-radius: 10px;
  padding: 16px;
  height: 180px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.sk-line {
  height: 14px;
  background: linear-gradient(90deg, #f0f2f5 25%, #e6e8eb 37%, #f0f2f5 63%);
  background-size: 400% 100%;
  animation: sk-loading 1.4s ease infinite;
  border-radius: 4px;
}
.sk-title {
  width: 60%;
  height: 18px;
}
.sk-short {
  width: 40%;
}
@keyframes sk-loading {
  0% {
    background-position: 100% 50%;
  }
  100% {
    background-position: 0 50%;
  }
}
.empty-state,
.init-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 60px 20px;
  color: var(--muted, var(--ds-text-secondary));
  gap: 8px;
}
.empty-state p,
.init-state p {
  font-size: 16px;
  font-weight: 600;
  margin: 8px 0 0;
}
.empty-tip {
  font-size: 13px;
}
.detail-content {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.detail-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px dashed var(--line, var(--ds-border-default));
  font-size: 13px;
}
.detail-label {
  color: var(--muted, var(--ds-text-secondary));
  flex-shrink: 0;
  margin-right: 12px;
}
.detail-value {
  text-align: right;
  word-break: break-all;
}
.detail-desc {
  margin-top: 8px;
}
.detail-desc p {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--ink, var(--ds-text-primary));
}
.detail-actions {
  margin-top: 12px;
}

/* 响应式：窄屏过滤器折叠到顶部 */
@media (max-width: 900px) {
  .portal-body {
    grid-template-columns: 1fr;
  }
  .portal-filter {
    position: static;
  }
}
</style>
