<!--
  SearchResultCard.vue — 检索结果卡片（带高亮）

  功能：
  - 卡片式展示单个检索结果
  - 名称 / 描述字段支持关键词高亮（来自 snippets）
  - 显示类型、来源、负责人、标签、时间、相关度评分
  - 点击卡片或"查看详情"触发 open 事件
  - 支持收藏（bookmark）操作

  事件：
  - open(item) 打开详情
  - bookmark(item, next) 收藏切换
-->
<template>
  <div class="result-card" @click="emitOpen">
    <!-- 头部：类型 + 评分 -->
    <div class="card-header">
      <div class="header-left">
        <span class="type-badge" :class="item.type">{{ typeLabel(item.type) }}</span>
        <span class="source-pill">{{ item.sourceName }}</span>
      </div>
      <div class="header-right">
        <el-tooltip :content="t('searchPortal.search.resultCard.scoreTip')" placement="top">
          <span class="score-badge">
            <el-icon><Star /></el-icon>
            {{ (item.score * 100).toFixed(0) }}%
          </span>
        </el-tooltip>
        <el-button
          :icon="Collection"
          circle
          text
          :type="bookmarked ? 'warning' : 'default'"
          @click.stop="toggleBookmark"
        />
      </div>
    </div>

    <!-- 标题（高亮，已通过 DOMPurify 净化防 XSS） -->
    <!-- eslint-disable-next-line vue/no-v-html -- 已通过 DOMPurify 净化，安全使用 v-html -->
    <h3 class="card-title" v-html="sanitizedName" />

    <!-- 描述（高亮，已通过 DOMPurify 净化防 XSS） -->
    <!-- eslint-disable-next-line vue/no-v-html -- 已通过 DOMPurify 净化，安全使用 v-html -->
    <p class="card-desc" v-html="sanitizedDesc" />

    <!-- 元信息 -->
    <div class="card-meta">
      <span v-if="item.owner" class="meta-item">
        <el-icon><User /></el-icon>
        {{ item.owner }}
      </span>
      <span class="meta-item">
        <el-icon><Clock /></el-icon>
        {{ t('searchPortal.search.resultCard.updatedAtFmt', { date: formatDate(item.updatedAt) }) }}
      </span>
    </div>

    <!-- 标签 -->
    <div v-if="item.tags.length > 0" class="card-tags">
      <el-tag v-for="tag in displayTags" :key="tag" size="small" effect="plain" type="info">
        {{ tag }}
      </el-tag>
      <el-tag v-if="item.tags.length > maxTags" size="small" effect="plain">
        +{{ item.tags.length - maxTags }}
      </el-tag>
    </div>

    <!-- 底部操作 -->
    <div class="card-footer">
      <el-button link type="primary" @click.stop="emitOpen">
        {{ t('searchPortal.search.resultCard.viewDetail') }}
      </el-button>
      <el-button v-if="item.url" link type="success" @click.stop="openUrl">
        {{ t('searchPortal.search.resultCard.openAsset') }}
      </el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElButton, ElIcon, ElTag, ElTooltip } from 'element-plus'
import { Star, User, Clock, Collection } from '@element-plus/icons-vue'
import DOMPurify from 'dompurify'
import type { SearchResultItem, AssetType } from '@/types/search'

const { t } = useI18n()

/* ------------------------------ Props / Emits ------------------------------ */
interface Props {
  /** 结果项 */
  item: SearchResultItem
  /** 最大显示标签数 */
  maxTags?: number
}

const props = withDefaults(defineProps<Props>(), {
  maxTags: 5
})

const emit = defineEmits<{
  (e: 'open', item: SearchResultItem): void
  (e: 'bookmark', item: SearchResultItem, next: boolean): void
}>()

/* ------------------------------ 收藏状态 ------------------------------ */
const bookmarked = ref(false)

function toggleBookmark(): void {
  bookmarked.value = !bookmarked.value
  emit('bookmark', props.item, bookmarked.value)
}

/* ------------------------------ 高亮渲染 ------------------------------ */
/**
 * 优先用后端返回的 snippets（已转义 HTML），否则降级为纯文本避免 XSS。
 * 仅保留 <mark> 标签，使用 DOMPurify 净化。
 *
 * 安全策略：白名单（ALLOWED_TAGS/ALLOWED_ATTR）+ 黑名单（FORBID_TAGS/FORBID_ATTR）双层防御。
 * 白名单已只允许 <mark>，黑名单显式禁止脚本/表单/嵌入等危险标签与事件属性，
 * 防止未来误扩展白名单时引入 XSS 向量。
 */
const ALLOWED_TAGS = ['mark']
const SANITIZE_OPTS = {
  ALLOWED_TAGS,
  ALLOWED_ATTR: [],
  FORBID_TAGS: [
    'script',
    'iframe',
    'object',
    'embed',
    'form',
    'input',
    'textarea',
    'style',
    'link',
    'meta',
    'base'
  ],
  FORBID_ATTR: [
    'onerror',
    'onload',
    'onclick',
    'onmouseover',
    'onmouseout',
    'onfocus',
    'onblur',
    'onchange',
    'onsubmit',
    'onreset',
    'onabort',
    'onanimationstart',
    'style',
    'src',
    'href',
    'xlink:href'
  ]
}

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

const sanitizedName = computed(() =>
  DOMPurify.sanitize(props.item.snippets?.name ?? escapeHtml(props.item.name), SANITIZE_OPTS)
)

const sanitizedDesc = computed(() => {
  const snip = props.item.snippets?.description
  if (snip) return DOMPurify.sanitize(snip, SANITIZE_OPTS)
  const desc = props.item.description || ''
  const truncated = desc.length > 200 ? desc.slice(0, 200) + '…' : desc
  return DOMPurify.sanitize(escapeHtml(truncated), SANITIZE_OPTS)
})

/* ------------------------------ 标签 ------------------------------ */
const displayTags = computed(() => props.item.tags.slice(0, props.maxTags))

/* ------------------------------ 工具 ------------------------------ */
const TYPE_LABELS = computed<Record<AssetType, string>>(() => ({
  table: t('searchPortal.search.typeOptions.table'),
  view: t('searchPortal.search.typeOptions.view'),
  api: t('searchPortal.search.typeOptions.api'),
  model: t('searchPortal.search.typeOptions.model'),
  dashboard: t('searchPortal.search.typeOptions.dashboard'),
  stream: t('searchPortal.search.typeOptions.stream'),
  job: t('searchPortal.search.typeOptions.job'),
  notebook: t('searchPortal.search.typeOptions.notebook'),
  metric: t('searchPortal.search.typeOptions.metric'),
  document: t('searchPortal.search.typeOptions.document')
}))

function typeLabel(v: AssetType): string {
  return TYPE_LABELS.value[v] ?? v
}

function formatDate(iso: string): string {
  if (!iso) return '—'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  // 简洁格式：YYYY-MM-DD HH:mm
  const pad = (n: number): string => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function emitOpen(): void {
  emit('open', props.item)
}

function openUrl(): void {
  if (props.item.url) {
    window.open(props.item.url, '_blank', 'noopener,noreferrer')
  }
}
</script>

<style scoped>
.result-card {
  background: var(--panel, var(--ds-bg-surface));
  border: 1px solid var(--line, var(--ds-border-default));
  border-radius: var(--ds-radius-md-plus);
  padding: var(--ds-spacing-4);
  box-shadow: var(--ds-shadow-sm);
  cursor: pointer;
  transition:
    box-shadow 0.2s,
    transform 0.2s;
  display: flex;
  flex-direction: column;
  gap: var(--ds-spacing-2);
}
.result-card:hover {
  box-shadow: var(--ds-shadow-md);
  transform: translateY(-1px);
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--ds-spacing-2);
}
.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: var(--ds-spacing-2);
}
.type-badge {
  display: inline-block;
  font-size: var(--ds-font-size-xs);
  padding: 2px 9px;
  border-radius: var(--ds-radius-2xl);
  font-weight: var(--ds-font-weight-semibold);
  background: var(--primary-soft, var(--ds-color-success-50));
  color: var(--primary, var(--ds-color-success-700));
}
.type-badge.table,
.type-badge.view {
  background: var(--c-green-50, var(--ds-color-success-50));
  color: var(--green, var(--ds-color-success-600));
}
.type-badge.api,
.type-badge.stream {
  background: var(--c-indigo-50, var(--ds-color-info-50));
  color: var(--ds-color-info-600);
}
.type-badge.model,
.type-badge.notebook {
  background: var(--ds-color-warning-50);
  color: var(--ds-color-warning-600);
}
.type-badge.dashboard,
.type-badge.metric {
  background: var(--ds-color-error-50);
  color: var(--ds-color-error-600);
}
.source-pill {
  font-size: var(--ds-font-size-xs);
  color: var(--muted, var(--ds-text-secondary));
  background: var(--c-surface-alt, var(--ds-bg-subtle));
  padding: 2px 8px;
  border-radius: var(--ds-radius-sm);
}
.score-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: var(--ds-font-size-xs);
  font-weight: var(--ds-font-weight-semibold);
  color: var(--amber, var(--ds-color-warning-600));
}
.card-title {
  font-size: var(--ds-font-size-lg);
  font-weight: var(--ds-font-weight-extrabold);
  margin: 0;
  color: var(--ink, var(--ds-text-primary));
  line-height: var(--ds-line-height-snug);
}
.card-title :deep(mark) {
  background: var(--ds-color-warning-100);
  color: var(--ds-color-warning-800);
  padding: 0 2px;
  border-radius: var(--ds-radius-sm);
}
.card-desc {
  font-size: var(--ds-font-size-base);
  color: var(--muted, var(--ds-text-secondary));
  margin: 0;
  line-height: var(--ds-line-height-loose);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-desc :deep(mark) {
  background: var(--ds-color-warning-100);
  color: var(--ds-color-warning-800);
  padding: 0 2px;
  border-radius: var(--ds-radius-sm);
}
.card-meta {
  display: flex;
  gap: var(--ds-spacing-4);
  font-size: var(--ds-font-size-xs);
  color: var(--muted, var(--ds-text-secondary));
  flex-wrap: wrap;
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: var(--ds-spacing-1);
}
.card-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.card-footer {
  display: flex;
  gap: var(--ds-spacing-2);
  margin-top: var(--ds-spacing-1);
  padding-top: var(--ds-spacing-2);
  border-top: 1px dashed var(--line, var(--ds-border-default));
}
</style>
