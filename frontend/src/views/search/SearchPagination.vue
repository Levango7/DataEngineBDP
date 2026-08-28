<!--
  SearchPagination.vue — 分页 / 无限滚动切换

  功能：
  - 两种模式切换：分页（el-pagination）/ 无限滚动（滚动到底部自动加载）
  - 分页模式：支持页码、每页条数、跳转
  - 无限滚动模式：显示"加载更多"按钮 + 滚动监听
  - 显示总数与当前范围

  事件：
  - page-change(page) 页码变化
  - size-change(size) 每页条数变化
  - load-more() 加载更多
  - mode-change(mode) 模式切换
-->
<template>
  <div class="search-pagination">
    <!-- 模式切换 + 总数 -->
    <div class="pagination-header">
      <div class="total-info">
        共
        <span class="total-num">{{ total }}</span>
        条
        <template v-if="mode === 'page'">，当前第 {{ page }} / {{ totalPages }} 页</template>
        <template v-else>，已加载 {{ loadedCount }} 条</template>
      </div>
      <el-radio-group v-model="mode" size="small" @change="onModeChangeRaw">
        <el-radio-button value="page">分页</el-radio-button>
        <el-radio-button value="infinite">无限滚动</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 分页模式 -->
    <div v-if="mode === 'page'" class="page-mode">
      <el-pagination
        v-model:current-page="innerPage"
        v-model:page-size="innerSize"
        :page-sizes="pageSizes"
        :total="total"
        layout="total, sizes, prev, pager, next, jumper"
        background
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </div>

    <!-- 无限滚动模式 -->
    <div v-else class="infinite-mode">
      <div v-if="loadingMore" class="loading-more">
        <el-icon class="is-loading"><Loading /></el-icon>
        <span>加载中…</span>
      </div>
      <el-button v-else-if="hasMore" type="primary" plain :icon="ArrowDown" @click="emitLoadMore">
        加载更多
      </el-button>
      <div v-else class="no-more">
        <el-divider>已加载全部</el-divider>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import {
  ElPagination,
  ElRadioGroup,
  ElRadioButton,
  ElButton,
  ElIcon,
  ElDivider
} from 'element-plus'
import { Loading, ArrowDown } from '@element-plus/icons-vue'
import type { PagingMode } from '@/types/search'

/* ------------------------------ Props / Emits ------------------------------ */
interface Props {
  /** 当前页码 */
  page: number
  /** 每页条数 */
  pageSize: number
  /** 总条数 */
  total: number
  /** 是否还有更多（无限滚动） */
  hasMore?: boolean
  /** 是否正在加载更多 */
  loadingMore?: boolean
  /** 已加载条数（无限滚动，默认 page*pageSize） */
  loadedCount?: number
  /** 初始模式 */
  mode?: PagingMode
  /** 可选每页条数 */
  pageSizes?: number[]
}

const props = withDefaults(defineProps<Props>(), {
  hasMore: false,
  loadingMore: false,
  loadedCount: 0,
  mode: 'page',
  pageSizes: () => [10, 20, 50, 100]
})

const emit = defineEmits<{
  (e: 'page-change', page: number): void
  (e: 'size-change', size: number): void
  (e: 'load-more'): void
  (e: 'mode-change', mode: PagingMode): void
}>()

/* ------------------------------ 本地状态 ------------------------------ */
const innerPage = ref(props.page)
const innerSize = ref(props.pageSize)
const mode = ref<PagingMode>(props.mode)

watch(
  () => props.page,
  (v) => {
    innerPage.value = v
  }
)
watch(
  () => props.pageSize,
  (v) => {
    innerSize.value = v
  }
)
watch(
  () => props.mode,
  (v) => {
    mode.value = v
  }
)

/* ------------------------------ 计算 ------------------------------ */
const totalPages = computed(() => {
  if (props.total <= 0) return 1
  return Math.ceil(props.total / props.pageSize)
})

const loadedCount = computed(() => {
  if (props.loadedCount > 0) return props.loadedCount
  return props.page * props.pageSize
})

/* ------------------------------ 事件 ------------------------------ */
function onPageChange(p: number): void {
  emit('page-change', p)
}

function onSizeChange(s: number): void {
  innerPage.value = 1
  emit('size-change', s)
}

function emitLoadMore(): void {
  emit('load-more')
}

function onModeChange(m: PagingMode): void {
  emit('mode-change', m)
}

/** el-radio-group change 事件参数为联合类型，需收窄为 PagingMode */
function onModeChangeRaw(val: string | number | boolean | undefined): void {
  if (val === 'page' || val === 'infinite') {
    onModeChange(val)
  }
}
</script>

<style scoped>
.search-pagination {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px 0;
}
.pagination-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.total-info {
  font-size: 13px;
  color: var(--muted, #717a80);
}
.total-num {
  color: var(--primary, #2f6f6a);
  font-weight: 700;
}
.page-mode {
  display: flex;
  justify-content: center;
}
.infinite-mode {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 40px;
}
.loading-more {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--muted, #717a80);
  font-size: 13px;
}
.no-more {
  width: 100%;
}
</style>
