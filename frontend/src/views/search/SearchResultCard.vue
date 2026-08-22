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
        <el-tooltip content="相关度评分" placement="top">
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
    <h3 class="card-title" v-html="sanitizedName" />

    <!-- 描述（高亮，已通过 DOMPurify 净化防 XSS） -->
    <p class="card-desc" v-html="sanitizedDesc" />

    <!-- 元信息 -->
    <div class="card-meta">
      <span v-if="item.owner" class="meta-item">
        <el-icon><User /></el-icon>
        {{ item.owner }}
      </span>
      <span class="meta-item">
        <el-icon><Clock /></el-icon>
        更新于 {{ formatDate(item.updatedAt) }}
      </span>
    </div>

    <!-- 标签 -->
    <div v-if="item.tags.length > 0" class="card-tags">
      <el-tag
        v-for="tag in displayTags"
        :key="tag"
        size="small"
        effect="plain"
        type="info"
      >
        {{ tag }}
      </el-tag>
      <el-tag v-if="item.tags.length > maxTags" size="small" effect="plain">
        +{{ item.tags.length - maxTags }}
      </el-tag>
    </div>

    <!-- 底部操作 -->
    <div class="card-footer">
      <el-button link type="primary" @click.stop="emitOpen">查看详情</el-button>
      <el-button v-if="item.url" link type="success" @click.stop="openUrl">打开资产</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElButton, ElIcon, ElTag, ElTooltip } from 'element-plus'
import { Star, User, Clock, Collection } from '@element-plus/icons-vue'
import DOMPurify from 'dompurify'
import type { SearchResultItem, AssetType } from '@/types/search'

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
  FORBID_TAGS: ['script', 'iframe', 'object', 'embed', 'form', 'input', 'textarea', 'style', 'link', 'meta', 'base'],
  FORBID_ATTR: ['onerror', 'onload', 'onclick', 'onmouseover', 'onmouseout', 'onfocus', 'onblur', 'onchange', 'onsubmit', 'onreset', 'onabort', 'onanimationstart', 'style', 'src', 'href', 'xlink:href']
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
const TYPE_LABELS: Record<AssetType, string> = {
  table: '数据集',
  view: '视图',
  api: '数据服务',
  model: '数据模型',
  dashboard: '仪表盘',
  stream: '实时流',
  job: '作业',
  notebook: '笔记本',
  metric: '指标',
  document: '文档'
}

function typeLabel(t: AssetType): string {
  return TYPE_LABELS[t] ?? t
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
  background: var(--panel, #fff);
  border: 1px solid var(--line, #e4e8ea);
  border-radius: 10px;
  padding: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.result-card:hover {
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
  transform: translateY(-1px);
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.type-badge {
  display: inline-block;
  font-size: 11px;
  padding: 2px 9px;
  border-radius: 20px;
  font-weight: 600;
  background: var(--primary-soft, #e9f1f0);
  color: var(--primary, #2f6f6a);
}
.type-badge.table,
.type-badge.view {
  background: var(--c-green-50, #ecfdf5);
  color: var(--green, #2f9e6f);
}
.type-badge.api,
.type-badge.stream {
  background: var(--c-indigo-50, #eef0fb);
  color: #4f6df5;
}
.type-badge.model,
.type-badge.notebook {
  background: #fff7ed;
  color: #c08a2e;
}
.type-badge.dashboard,
.type-badge.metric {
  background: #fef2f2;
  color: #c0504d;
}
.source-pill {
  font-size: 11px;
  color: var(--muted, #717a80);
  background: var(--c-surface-alt, #eaf0f1);
  padding: 2px 8px;
  border-radius: 4px;
}
.score-badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-size: 12px;
  font-weight: 600;
  color: var(--amber, #c08a2e);
}
.card-title {
  font-size: 15px;
  font-weight: 700;
  margin: 0;
  color: var(--ink, #232a2e);
  line-height: 1.4;
}
.card-title :deep(mark) {
  background: #fff3cd;
  color: #8a6d3b;
  padding: 0 2px;
  border-radius: 2px;
}
.card-desc {
  font-size: 13px;
  color: var(--muted, #717a80);
  margin: 0;
  line-height: 1.6;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.card-desc :deep(mark) {
  background: #fff3cd;
  color: #8a6d3b;
  padding: 0 2px;
  border-radius: 2px;
}
.card-meta {
  display: flex;
  gap: 16px;
  font-size: 12px;
  color: var(--muted, #717a80);
  flex-wrap: wrap;
}
.meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.card-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.card-footer {
  display: flex;
  gap: 8px;
  margin-top: 4px;
  padding-top: 8px;
  border-top: 1px dashed var(--line, #e4e8ea);
}
</style>