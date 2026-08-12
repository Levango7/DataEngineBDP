<!--
  AiAssistant.vue — AI 助手主页面（T011）

  布局：
  - 左侧：会话列表（历史 / 新建 / 置顶 / 删除）
  - 中部：聊天面板（ChatPanel）
  - 右侧：分析面板（SQL 预览 / 图表推荐 / 数据解读 / Superset 仪表盘创建）

  功能：
  - 自然语言 → SQL → 数据 → 图表 → 解读 全链路
  - 中英双语切换
  - 数据源选择
  - SQL 方言选择
  - 自动执行 / 自动推荐图表 / 自动解读 三开关
  - Superset 仪表盘一键创建
-->
<template>
  <div class="ai-assistant">
    <!-- 顶部标题栏 -->
    <div class="ai-header">
      <div class="ai-title">
        <h1>{{ t.title }}</h1>
        <span class="ai-sub">{{ t.subtitle }}</span>
      </div>
      <div class="ai-actions">
        <!-- 数据源选择 -->
        <el-select
          v-model="selectedDatasource"
          :placeholder="t.datasource"
          style="width: 200px"
          filterable
          clearable
          @change="onDatasourceChange"
        >
          <el-option
            v-for="ds in supersetDatasources"
            :key="ds.id"
            :label="ds.name"
            :value="ds.id"
          />
        </el-select>

        <!-- 方言 -->
        <el-select v-model="selectedDialect" style="width: 130px">
          <el-option
            v-for="d in dialectOptions"
            :key="d.value"
            :label="d.label"
            :value="d.value"
          />
        </el-select>

        <!-- 语言切换 -->
        <el-button :icon="langIcon" circle @click="toggleLocale" />

        <!-- 新建会话 -->
        <el-button type="primary" :icon="Plus" @click="newSession">
          {{ t.newChat }}
        </el-button>
      </div>
    </div>

    <!-- 主体三栏 -->
    <div class="ai-body">
      <!-- 左侧：会话列表 -->
      <aside class="ai-sessions">
        <div class="sessions-header">
          <span>{{ t.sessions }}</span>
          <el-button
            :icon="RefreshRight"
            circle
            text
            size="small"
            @click="loadSessions"
          />
        </div>
        <div class="sessions-list">
          <div
            v-for="s in sortedSessions"
            :key="s.id"
            class="session-item"
            :class="{ active: currentSession?.id === s.id }"
            @click="onSwitchSession(s.id)"
          >
            <el-icon class="session-pin" :class="{ pinned: s.pinned }">
              <Star v-if="s.pinned" />
              <StarFilled v-else />
            </el-icon>
            <div class="session-info">
              <div class="session-title">{{ s.title }}</div>
              <div class="session-meta">
                {{ formatDate(s.updatedAt) }} · {{ s.messageCount }} {{ t.msgs }}
              </div>
            </div>
            <el-dropdown trigger="click" @command="(cmd: string) => onSessionCommand(cmd, s.id)">
              <el-icon class="session-more" @click.stop><MoreFilled /></el-icon>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="pin">
                    {{ s.pinned ? t.unpin : t.pin }}
                  </el-dropdown-item>
                  <el-dropdown-item command="delete" divided>
                    {{ t.delete }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <div v-if="sessions.length === 0" class="sessions-empty">
            {{ t.noSessions }}
          </div>
        </div>
      </aside>

      <!-- 中部：聊天面板 -->
      <main class="ai-chat">
        <ChatPanel
          :messages="messages"
          :locale="locale"
          :loading="loading"
          :streaming="streaming"
          :example-prompts="examplePrompts"
          :auto-execute="autoExecute"
          :auto-recommend-chart="autoRecommendChart"
          :auto-summarize="autoSummarize"
          @send="sendMessage"
          @abort="abort"
          @example="onExample"
          @feedback="feedback"
          @reexecute="reexecute"
          @update:auto-execute="autoExecute = $event"
          @update:auto-recommend-chart="autoRecommendChart = $event"
          @update:auto-summarize="autoSummarize = $event"
        />
      </main>

      <!-- 右侧：分析面板 -->
      <aside class="ai-side">
        <!-- SQL 预览 -->
        <div class="side-section">
          <div class="side-section-title">
            <el-icon><Document /></el-icon>
            {{ t.sqlPreview }}
          </div>
          <SqlPreview
            v-if="lastSql"
            :sql="lastSql.sql"
            :meta="{
              dialect: lastSql.dialect,
              tables: lastSql.tables,
              columns: lastSql.columns,
              crossSource: lastSql.crossSource,
              confidence: lastSql.confidence,
              durationMs: lastSql.durationMs
            }"
            :locale="locale"
            @reexecute="reexecute"
          />
          <div v-else class="side-empty">{{ t.sqlEmpty }}</div>
        </div>

        <!-- 图表推荐 -->
        <div class="side-section">
          <div class="side-section-title">
            <el-icon><DataAnalysis /></el-icon>
            {{ t.chartRec }}
          </div>
          <ChartRecommendationPanel
            v-if="lastChartRecommendation && lastChartRecommendation.recommendations.length > 0"
            :recommendations="lastChartRecommendation.recommendations"
            :data-profile="lastChartRecommendation.dataProfile"
            :locale="locale"
            :selected-id="lastChart?.recommendationId"
            @select="onChartSelect"
          />
          <div v-else class="side-empty">{{ t.chartEmpty }}</div>
        </div>

        <!-- 数据解读 -->
        <div class="side-section">
          <div class="side-section-title">
            <el-icon><DocumentChecked /></el-icon>
            {{ t.summary }}
          </div>
          <DataSummary
            v-if="lastSummary"
            :summary="locale === 'zh' ? lastSummary.summary.zh : lastSummary.summary.en"
            :insights="lastSummary.insights"
            :metrics="lastSummary.metrics"
            :locale="locale"
          />
          <div v-else class="side-empty">{{ t.summaryEmpty }}</div>
        </div>

        <!-- Superset 仪表盘 -->
        <div class="side-section">
          <div class="side-section-title">
            <el-icon><Histogram /></el-icon>
            {{ t.dashboard }}
          </div>
          <el-button
            type="primary"
            :icon="Promotion"
            :loading="loading"
            :disabled="!lastSql || !selectedDatasource"
            style="width: 100%"
            @click="onCreateDashboard"
          >
            {{ t.createDashboard }}
          </el-button>
          <div v-if="dashboardUrl" class="dashboard-link">
            <el-link :href="dashboardUrl" target="_blank" type="primary">
              <el-icon><Link /></el-icon>
              {{ t.openDashboard }}
            </el-link>
          </div>
          <div v-if="!lastSql" class="side-empty">{{ t.dashboardEmpty }}</div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import {
  ElButton,
  ElIcon,
  ElSelect,
  ElOption,
  ElDropdown,
  ElDropdownMenu,
  ElDropdownItem,
  ElLink,
  ElMessage
} from 'element-plus'
import {
  Plus,
  RefreshRight,
  MoreFilled,
  Star,
  StarFilled,
  Document,
  DocumentChecked,
  DataAnalysis,
  Histogram,
  Promotion,
  Link,
  ChatDotRound,
  Comment
} from '@element-plus/icons-vue'
import ChatPanel from './ChatPanel.vue'
import SqlPreview from './SqlPreview.vue'
import ChartRecommendationPanel from './ChartRecommendation.vue'
import DataSummary from './DataSummary.vue'
import { useAiAssistant, buildChartConfig } from '@/composables/useAiAssistant'
import * as aiApi from '@/api/ai-assistant'
import type {
  SqlDialect,
  ChartRecommendation,
  SupersetDatasource
} from '@/types/ai-assistant'
import { SQL_DIALECT_LABELS } from '@/types/ai-assistant'

/* ------------------------------ 组合式函数 ------------------------------ */
const {
  currentSession,
  messages,
  sessions,
  locale,
  datasourceId,
  dialect,
  autoExecute,
  autoRecommendChart,
  autoSummarize,
  loading,
  streaming,
  error,
  examplePrompts,
  lastSql,
  lastExecution,
  lastChartRecommendation,
  lastChart,
  lastSummary,
  sendMessage,
  abort,
  newSession,
  switchSession,
  deleteSession,
  pinSession,
  toggleLocale,
  setDatasource,
  setDialect,
  reexecute,
  switchChart,
  createDashboard,
  feedback,
  loadSessions
} = useAiAssistant()

/* ------------------------------ 数据源 ------------------------------ */
const supersetDatasources = ref<SupersetDatasource[]>([])
const selectedDatasource = ref<string | undefined>(datasourceId.value)
const selectedDialect = ref<SqlDialect>(dialect.value)

async function loadDatasources(): Promise<void> {
  try {
    supersetDatasources.value = await aiApi.listSupersetDatasources()
  } catch {
    supersetDatasources.value = []
  }
}

function onDatasourceChange(id: string | undefined): void {
  setDatasource(id)
}

watch(selectedDialect, (d) => setDialect(d))

/* ------------------------------ 方言选项 ------------------------------ */
const dialectOptions = computed(() =>
  Object.entries(SQL_DIALECT_LABELS).map(([value, label]) => ({
    value: value as SqlDialect,
    label: locale.value === 'zh' ? label.zh : label.en
  }))
)

/* ------------------------------ 会话排序 ------------------------------ */
const sortedSessions = computed(() => {
  return [...sessions.value].sort((a, b) => {
    if (a.pinned !== b.pinned) return a.pinned ? -1 : 1
    return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime()
  })
})

async function onSwitchSession(id: string): Promise<void> {
  await switchSession(id)
}

async function onSessionCommand(cmd: string, id: string): Promise<void> {
  if (cmd === 'delete') {
    await deleteSession(id)
    ElMessage.success(t.value.deleted)
  } else if (cmd === 'pin') {
    const s = sessions.value.find((x) => x.id === id)
    await pinSession(id, !s?.pinned)
  }
}

/* ------------------------------ 示例 / 图表切换 ------------------------------ */
function onExample(text: string): void {
  void sendMessage(text)
}

function onChartSelect(rec: ChartRecommendation): void {
  const cfg = switchChart(rec)
  if (cfg) {
    ElMessage.success(t.value.chartSwitched)
  } else if (!lastExecution.value) {
    ElMessage.warning(t.value.noData)
  }
}

/* ------------------------------ Superset 仪表盘 ------------------------------ */
const dashboardUrl = ref<string>('')

async function onCreateDashboard(): Promise<void> {
  const result = await createDashboard()
  if (result) {
    dashboardUrl.value = result.url
    ElMessage.success(t.value.dashboardCreated)
  } else {
    ElMessage.error(t.value.dashboardFailed)
  }
}

/* ------------------------------ 错误监听 ------------------------------ */
watch(error, (err) => {
  if (err) {
    ElMessage.error(err.message)
  }
})

/* ------------------------------ 文案 ------------------------------ */
const t = computed(() => {
  if (locale.value === 'zh') {
    return {
      title: 'AI 数据助手',
      subtitle: '自然语言 → SQL → 图表 → 解读 全链路智能分析',
      newChat: '新对话',
      sessions: '历史会话',
      msgs: '条',
      noSessions: '暂无历史会话',
      pin: '置顶',
      unpin: '取消置顶',
      delete: '删除',
      deleted: '已删除',
      datasource: '选择数据源',
      sqlPreview: 'SQL 预览',
      sqlEmpty: '提问后将生成 SQL',
      chartRec: '图表推荐',
      chartEmpty: '执行查询后推荐图表',
      summary: '数据解读',
      summaryEmpty: '执行查询后生成解读',
      dashboard: 'Superset 仪表盘',
      createDashboard: '一键创建仪表盘',
      openDashboard: '打开仪表盘',
      dashboardEmpty: '需先生成 SQL 并选择数据源',
      dashboardCreated: '仪表盘已创建',
      dashboardFailed: '仪表盘创建失败',
      chartSwitched: '图表已切换',
      noData: '暂无数据可绘图'
    }
  }
  return {
    title: 'AI Data Assistant',
    subtitle: 'Natural language → SQL → chart → insights, end-to-end',
    newChat: 'New Chat',
    sessions: 'Sessions',
    msgs: 'msgs',
    noSessions: 'No sessions yet',
    pin: 'Pin',
    unpin: 'Unpin',
    delete: 'Delete',
    deleted: 'Deleted',
    datasource: 'Select datasource',
    sqlPreview: 'SQL Preview',
    sqlEmpty: 'SQL will be generated after you ask',
    chartRec: 'Chart Recommendation',
    chartEmpty: 'Charts will be recommended after query',
    summary: 'Data Insights',
    summaryEmpty: 'Insights will be generated after query',
    dashboard: 'Superset Dashboard',
    createDashboard: 'Create Dashboard',
    openDashboard: 'Open Dashboard',
    dashboardEmpty: 'Generate SQL and select datasource first',
    dashboardCreated: 'Dashboard created',
    dashboardFailed: 'Failed to create dashboard',
    chartSwitched: 'Chart switched',
    noData: 'No data to plot'
  }
})

const langIcon = computed(() => (locale.value === 'zh' ? Comment : ChatDotRound))

/* ------------------------------ 工具 ------------------------------ */
function formatDate(iso: string): string {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const now = Date.now()
  const diff = now - d.getTime()
  const day = 24 * 60 * 60 * 1000
  if (diff < day) {
    return d.toLocaleTimeString(locale.value === 'zh' ? 'zh-CN' : 'en-US', {
      hour: '2-digit',
      minute: '2-digit'
    })
  }
  if (diff < 7 * day) {
    const days = Math.floor(diff / day)
    return locale.value === 'zh' ? `${days} 天前` : `${days}d ago`
  }
  return d.toLocaleDateString(locale.value === 'zh' ? 'zh-CN' : 'en-US')
}

/* ------------------------------ 挂载 ------------------------------ */
onMounted(() => {
  void loadDatasources()
})

// 显式标注 buildChartConfig 已通过 switchChart 间接使用
void buildChartConfig
</script>

<style scoped>
.ai-assistant {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 600px;
}

/* 顶部 */
.ai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 0 16px;
  border-bottom: 1px solid var(--line);
  margin-bottom: 16px;
}
.ai-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.ai-title h1 {
  font-size: 20px;
  margin: 0;
}
.ai-sub {
  font-size: 13px;
  color: var(--muted);
}
.ai-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

/* 主体三栏 */
.ai-body {
  display: grid;
  grid-template-columns: 220px 1fr 320px;
  gap: 14px;
  flex: 1;
  min-height: 0;
}

/* 左侧会话 */
.ai-sessions {
  background: var(--c-white);
  border: 1px solid var(--line);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.sessions-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 14px;
  font-size: 13px;
  font-weight: 600;
  border-bottom: 1px solid var(--line);
}
.sessions-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}
.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s;
}
.session-item:hover {
  background: var(--c-surface-hover);
}
.session-item.active {
  background: var(--primary-soft);
  color: var(--primary);
}
.session-pin {
  color: var(--c-slate-300);
  flex: none;
}
.session-pin.pinned {
  color: var(--amber);
}
.session-info {
  flex: 1;
  min-width: 0;
}
.session-title {
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.session-meta {
  font-size: 11px;
  color: var(--muted);
  margin-top: 2px;
}
.session-more {
  color: var(--muted);
  cursor: pointer;
  flex: none;
}
.sessions-empty {
  padding: 24px 12px;
  text-align: center;
  font-size: 12px;
  color: var(--muted);
}

/* 中部聊天 */
.ai-chat {
  background: var(--c-white);
  border: 1px solid var(--line);
  border-radius: 10px;
  overflow: hidden;
  min-width: 0;
}
.ai-chat :deep(.chat-panel) {
  height: 100%;
}

/* 右侧分析面板 */
.ai-side {
  background: var(--c-white);
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.side-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.side-section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--ink);
  padding-bottom: 6px;
  border-bottom: 1px solid var(--line);
}
.side-empty {
  font-size: 12px;
  color: var(--muted);
  padding: 12px;
  text-align: center;
  background: var(--c-surface-hover);
  border-radius: 8px;
}
.dashboard-link {
  margin-top: 6px;
  display: flex;
  justify-content: center;
}
</style>