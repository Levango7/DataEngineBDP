<!--
  ChatPanel.vue — AI 助手聊天面板（T011）

  功能：
  - 渲染消息列表（用户 / 助手 / 卡片）
  - 输入框：回车发送、Shift+Enter 换行、自动调整高度
  - 流式接收时显示打字光标
  - 卡片消息内嵌 SQL 预览 / 表格 / 图表 / 摘要
  - 消息反馈（点赞 / 点踩）
  - 中断按钮（流式时）
  - 空状态：示例提问引导

  事件：
  - send(text) 发送消息
  - abort 中断
  - example(text) 点击示例
  - feedback(messageId, fb) 反馈
-->
<template>
  <div class="chat-panel">
    <!-- 消息列表 -->
    <div ref="scrollRef" class="chat-messages">
      <!-- 空状态 -->
      <div v-if="messages.length === 0" class="chat-empty">
        <div class="empty-icon">
          <el-icon :size="48"><ChatDotRound /></el-icon>
        </div>
        <h2>{{ t.emptyTitle }}</h2>
        <p class="empty-desc">{{ t.emptyDesc }}</p>
        <div class="example-prompts">
          <button
            v-for="prompt in examplePrompts"
            :key="prompt"
            class="example-chip"
            @click="emit('example', prompt)"
          >
            <el-icon><MagicStick /></el-icon>
            <span>{{ prompt }}</span>
          </button>
        </div>
      </div>

      <!-- 消息项 -->
      <div
        v-for="msg in messages"
        :key="msg.id"
        class="chat-msg"
        :class="`msg-${msg.role}`"
      >
        <!-- 头像 -->
        <div class="msg-avatar">
          <el-icon v-if="msg.role === 'user'"><User /></el-icon>
          <el-icon v-else><ChatDotRound /></el-icon>
        </div>

        <!-- 内容 -->
        <div class="msg-body">
          <div class="msg-role">{{ roleLabel(msg.role) }}</div>

          <!-- 内容块 -->
          <template v-for="(content, idx) in msg.contents" :key="idx">
            <!-- 纯文本 -->
            <div v-if="content.type === 'text'" class="content-text">
              {{ content.text }}
              <span
                v-if="msg.status === 'streaming' && idx === msg.contents.length - 1"
                class="cursor"
              ></span>
            </div>

            <!-- 错误 -->
            <div v-else-if="content.type === 'error'" class="content-error">
              <el-icon><WarningFilled /></el-icon>
              <span>{{ content.text }}</span>
            </div>

            <!-- SQL 单独块 -->
            <div v-else-if="content.type === 'sql'" class="content-sql">
              <SqlPreview
                :sql="content.text ?? ''"
                :meta="content.sqlMeta"
                :locale="locale"
              />
            </div>

            <!-- 表格单独块 -->
            <div v-else-if="content.type === 'table'" class="content-table">
              <DataTable :table="content.table!" :locale="locale" />
            </div>

            <!-- 图表单独块 -->
            <div v-else-if="content.type === 'chart'" class="content-chart">
              <ChartView :config="content.chart!" />
            </div>

            <!-- 摘要单独块 -->
            <div v-else-if="content.type === 'summary'" class="content-summary">
              <DataSummary
                :summary="content.text ?? ''"
                :meta="content.summaryMeta"
                :locale="locale"
              />
            </div>

            <!-- 卡片：合并 SQL + 表格 + 图表 + 摘要 -->
            <div v-else-if="content.type === 'card'" class="content-card">
              <!-- SQL 预览 -->
              <SqlPreview
                v-if="content.text"
                :sql="content.text"
                :meta="content.sqlMeta"
                :locale="locale"
                @reexecute="emit('reexecute')"
              />

              <!-- 数据表格 -->
              <DataTable
                v-if="content.table"
                :table="content.table"
                :locale="locale"
              />

              <!-- 图表 -->
              <ChartView
                v-if="content.chart"
                :config="content.chart"
              />

              <!-- 数据解读 -->
              <DataSummary
                v-if="content.summaryMeta"
                :summary="summaryText(msg, idx)"
                :meta="content.summaryMeta"
                :locale="locale"
              />
            </div>
          </template>

          <!-- pending 占位 -->
          <div v-if="msg.status === 'pending'" class="content-pending">
            <span class="dot"></span>
            <span class="dot"></span>
            <span class="dot"></span>
          </div>

          <!-- 反馈 -->
          <div v-if="msg.role === 'assistant' && msg.status === 'done'" class="msg-feedback">
            <el-button
              :icon="CaretTop"
              circle
              text
              size="small"
              :type="msg.feedback === 'like' ? 'primary' : 'default'"
              @click="emit('feedback', msg.id, msg.feedback === 'like' ? null : 'like')"
            />
            <el-button
              :icon="CaretBottom"
              circle
              text
              size="small"
              :type="msg.feedback === 'dislike' ? 'danger' : 'default'"
              @click="emit('feedback', msg.id, msg.feedback === 'dislike' ? null : 'dislike')"
            />
          </div>
        </div>
      </div>
    </div>

    <!-- 输入区 -->
    <div class="chat-input-area">
      <!-- 工具栏 -->
      <div class="input-toolbar">
        <el-tooltip :content="t.tipExecute" placement="top">
          <el-switch v-model="autoExecute" size="small" />
        </el-tooltip>
        <span class="toolbar-label">{{ t.autoExecute }}</span>

        <el-tooltip :content="t.tipChart" placement="top">
          <el-switch v-model="autoRecommendChart" size="small" />
        </el-tooltip>
        <span class="toolbar-label">{{ t.autoChart }}</span>

        <el-tooltip :content="t.tipSummary" placement="top">
          <el-switch v-model="autoSummarize" size="small" />
        </el-tooltip>
        <span class="toolbar-label">{{ t.autoSummary }}</span>

        <div class="spacer"></div>

        <el-button
          v-if="streaming"
          type="danger"
          size="small"
          :icon="VideoPause"
          @click="emit('abort')"
        >
          {{ t.stop }}
        </el-button>
      </div>

      <!-- 输入框 -->
      <div class="input-box">
        <el-input
          v-model="inputText"
          type="textarea"
          :rows="1"
          autosize
          resize="none"
          :placeholder="t.placeholder"
          :disabled="loading"
          @keydown.enter="onEnter"
        />
        <el-button
          type="primary"
          :icon="Promotion"
          :loading="loading"
          :disabled="inputText.trim().length === 0"
          @click="onSend"
        >
          {{ t.send }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, computed, onMounted } from 'vue'
import {
  ElInput,
  ElButton,
  ElIcon,
  ElSwitch,
  ElTooltip
} from 'element-plus'
import {
  ChatDotRound,
  User,
  MagicStick,
  WarningFilled,
  Promotion,
  VideoPause,
  CaretTop,
  CaretBottom
} from '@element-plus/icons-vue'
import SqlPreview from './SqlPreview.vue'
import DataSummary from './DataSummary.vue'
import ChartView from './ChartView.vue'
import DataTable from './DataTable.vue'
import type {
  ChatMessage,
  Locale,
  MessageContent,
  SqlMeta,
  SummaryMeta,
  TableData,
  ChartConfig
} from '@/types/ai-assistant'

/* ------------------------------ Props / Emits ------------------------------ */
interface Props {
  /** 消息列表 */
  messages: ChatMessage[]
  /** 语言 */
  locale: Locale
  /** 加载状态 */
  loading: boolean
  /** 流式状态 */
  streaming: boolean
  /** 示例提问 */
  examplePrompts: string[]
  /** 自动执行 SQL */
  autoExecute: boolean
  /** 自动推荐图表 */
  autoRecommendChart: boolean
  /** 自动解读 */
  autoSummarize: boolean
}

const props = defineProps<Props>()

const emit = defineEmits<{
  (e: 'send', text: string): void
  (e: 'abort'): void
  (e: 'example', text: string): void
  (e: 'feedback', messageId: string, fb: 'like' | 'dislike' | null): void
  (e: 'reexecute'): void
  (e: 'update:autoExecute', value: boolean): void
  (e: 'update:autoRecommendChart', value: boolean): void
  (e: 'update:autoSummarize', value: boolean): void
}>()

/* ------------------------------ 双向绑定代理 ------------------------------ */
const autoExecute = computed({
  get: () => props.autoExecute,
  set: (v) => emit('update:autoExecute', v)
})
const autoRecommendChart = computed({
  get: () => props.autoRecommendChart,
  set: (v) => emit('update:autoRecommendChart', v)
})
const autoSummarize = computed({
  get: () => props.autoSummarize,
  set: (v) => emit('update:autoSummarize', v)
})

/* ------------------------------ 输入 ------------------------------ */
const inputText = ref('')
const scrollRef = ref<HTMLElement | null>(null)

function onSend(): void {
  const text = inputText.value.trim()
  if (text.length === 0 || props.loading) return
  emit('send', text)
  inputText.value = ''
}

function onEnter(e: Event): void {
  const ke = e as KeyboardEvent
  // Shift+Enter 换行，Enter 发送
  if (ke.shiftKey) return
  e.preventDefault()
  onSend()
}

/* ------------------------------ 自动滚动到底部 ------------------------------ */
function scrollToBottom() {
  if (scrollRef.value) {
    scrollRef.value.scrollTop = scrollRef.value.scrollHeight
  }
}

watch(() => props.messages.length, () => {
  void nextTick(scrollToBottom)
})

// 流式时持续滚动
let scrollTimer: ReturnType<typeof setInterval> | null = null
watch(() => props.streaming, (streaming) => {
  if (scrollTimer) {
    clearInterval(scrollTimer)
    scrollTimer = null
  }
  if (streaming) {
    scrollTimer = setInterval(scrollToBottom, 100)
  }
})

onMounted(scrollToBottom)

/* ------------------------------ 文案 ------------------------------ */
const t = computed(() => {
  if (props.locale === 'zh') {
    return {
      emptyTitle: 'AI 数据助手',
      emptyDesc: '用自然语言提问，自动生成 SQL、推荐图表、解读数据。',
      placeholder: '请输入您的问题，如：查询最近 7 天订单金额趋势',
      send: '发送',
      stop: '停止',
      autoExecute: '自动执行',
      autoChart: '推荐图表',
      autoSummary: '数据解读',
      tipExecute: '生成 SQL 后是否自动执行',
      tipChart: '是否自动推荐图表类型',
      tipSummary: '是否自动生成数据解读摘要'
    }
  }
  return {
    emptyTitle: 'AI Data Assistant',
    emptyDesc: 'Ask in natural language. SQL, charts and insights are generated automatically.',
    placeholder: 'Ask anything, e.g. show order trend for last 7 days',
    send: 'Send',
    stop: 'Stop',
    autoExecute: 'Auto Execute',
    autoChart: 'Recommend Chart',
    autoSummary: 'Summarize',
    tipExecute: 'Whether to execute SQL automatically after generation',
    tipChart: 'Whether to recommend chart type automatically',
    tipSummary: 'Whether to summarize results automatically'
  }
})

function roleLabel(role: string): string {
  if (props.locale === 'zh') {
    return role === 'user' ? '我' : 'AI 助手'
  }
  return role === 'user' ? 'You' : 'AI Assistant'
}

/** 从下一条 summary 内容取出文本 */
function summaryText(msg: ChatMessage, cardIdx: number): string {
  // 卡片后通常紧跟一个 summary 内容块
  for (let i = cardIdx + 1; i < msg.contents.length; i++) {
    if (msg.contents[i].type === 'summary') {
      return msg.contents[i].text ?? ''
    }
  }
  return ''
}
</script>

<style scoped>
.chat-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--bg);
}
.chat-messages {
  flex: 1;
  overflow-y: auto;
  padding: 18px 22px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

/* 空状态 */
.chat-empty {
  margin: auto;
  text-align: center;
  max-width: 520px;
}
.empty-icon {
  color: var(--primary);
  margin-bottom: 12px;
}
.chat-empty h2 {
  font-size: 22px;
  margin-bottom: 8px;
}
.empty-desc {
  color: var(--muted);
  font-size: 13px;
  margin-bottom: 22px;
}
.example-prompts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: center;
}
.example-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid var(--line);
  background: var(--c-white);
  border-radius: 20px;
  font-size: 13px;
  color: var(--c-slate-700);
  cursor: pointer;
  transition: all 0.15s;
}
.example-chip:hover {
  border-color: var(--primary);
  color: var(--primary);
  background: var(--primary-soft);
}

/* 消息项 */
.chat-msg {
  display: flex;
  gap: 12px;
  max-width: 100%;
}
.chat-msg.msg-user {
  flex-direction: row-reverse;
}
.msg-avatar {
  width: 36px;
  height: 36px;
  border-radius: 50%;
  background: var(--primary-soft);
  color: var(--primary);
  display: flex;
  align-items: center;
  justify-content: center;
  flex: none;
  font-size: 18px;
}
.msg-user .msg-avatar {
  background: var(--primary);
  color: #fff;
}
.msg-body {
  max-width: 78%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.msg-user .msg-body {
  align-items: flex-end;
}
.msg-role {
  font-size: 11px;
  color: var(--muted);
  margin-bottom: 2px;
}

/* 文本 */
.content-text {
  background: var(--c-white);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg-user .content-text {
  background: var(--primary);
  color: #fff;
  border-color: var(--primary);
}
.cursor {
  display: inline-block;
  width: 7px;
  height: 14px;
  background: var(--primary);
  margin-left: 2px;
  vertical-align: -2px;
  animation: blink 1s infinite;
}
@keyframes blink {
  50% {
    opacity: 0;
  }
}

/* 错误 */
.content-error {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  background: var(--c-red-50);
  color: var(--red);
  border: 1px solid var(--red);
  border-radius: 8px;
  padding: 8px 12px;
  font-size: 13px;
}

/* 卡片 */
.content-card {
  background: var(--c-white);
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  width: 100%;
  box-shadow: var(--shadow);
}
.content-sql,
.content-table,
.content-chart,
.content-summary {
  background: var(--c-white);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px;
  width: 100%;
}

/* pending 三点动画 */
.content-pending {
  display: inline-flex;
  gap: 4px;
  padding: 8px 12px;
  background: var(--c-white);
  border: 1px solid var(--line);
  border-radius: 10px;
}
.content-pending .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--primary);
  animation: bounce 1.2s infinite ease-in-out;
}
.content-pending .dot:nth-child(2) {
  animation-delay: 0.2s;
}
.content-pending .dot:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes bounce {
  0%,
  80%,
  100% {
    transform: scale(0.6);
    opacity: 0.5;
  }
  40% {
    transform: scale(1);
    opacity: 1;
  }
}

/* 反馈 */
.msg-feedback {
  display: flex;
  gap: 4px;
  margin-top: 4px;
}

/* 输入区 */
.chat-input-area {
  border-top: 1px solid var(--line);
  background: var(--c-white);
  padding: 10px 16px 14px;
}
.input-toolbar {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 12px;
  color: var(--muted);
}
.toolbar-label {
  margin-right: 10px;
}
.input-toolbar .spacer {
  flex: 1;
}
.input-box {
  display: flex;
  gap: 8px;
  align-items: flex-end;
}
.input-box :deep(.el-textarea) {
  flex: 1;
}
.input-box :deep(.el-textarea__inner) {
  border-radius: 10px;
  padding: 10px 12px;
  font-size: 13.5px;
  max-height: 140px;
}
</style>