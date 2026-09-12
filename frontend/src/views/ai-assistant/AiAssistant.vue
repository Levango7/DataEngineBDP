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
        <h1>{{ t('aiAssistant.page.title') }}</h1>
        <span class="ai-sub">{{ t('aiAssistant.page.subtitle') }}</span>
      </div>
      <div class="ai-actions">
        <!-- 数据源选择 -->
        <el-select
          v-model="selectedDatasource"
          :placeholder="t('aiAssistant.page.datasource')"
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
          <el-option v-for="d in dialectOptions" :key="d.value" :label="d.label" :value="d.value" />
        </el-select>

        <!-- 语言切换 -->
        <el-button :icon="langIcon" circle @click="toggleLocale" />

        <!-- 新建会话 -->
        <el-button type="primary" :icon="Plus" @click="newSession">
          {{ t('aiAssistant.page.newChat') }}
        </el-button>
      </div>
    </div>

    <!-- 主体三栏 -->
    <div class="ai-body">
      <!-- 左侧：会话列表 -->
      <aside class="ai-sessions">
        <div class="sessions-header">
          <span>{{ t('aiAssistant.page.sessions') }}</span>
          <el-button :icon="RefreshRight" circle text size="small" @click="loadSessions" />
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
                {{ formatDate(s.updatedAt) }} · {{ s.messageCount }} {{ t('aiAssistant.page.msgs') }}
              </div>
            </div>
            <el-dropdown trigger="click" @command="(cmd: string) => onSessionCommand(cmd, s.id)">
              <el-icon class="session-more" @click.stop><MoreFilled /></el-icon>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="pin">
                    {{ s.pinned ? t('aiAssistant.page.unpin') : t('aiAssistant.page.pin') }}
                  </el-dropdown-item>
                  <el-dropdown-item command="delete" divided>
                    {{ t('aiAssistant.page.delete') }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </div>
          <div v-if="sessions.length === 0" class="sessions-empty">
            {{ t('aiAssistant.page.noSessions') }}
          </div>
        </div>
      </aside>

      <!-- 中部：聊天面板 -->
      <main class="ai-chat">
        <ChatPanel
          :messages="messages"

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
            {{ t('aiAssistant.page.sqlPreview') }}
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
            @reexecute="reexecute"
          />
          <div v-else class="side-empty">{{ t('aiAssistant.page.sqlEmpty') }}</div>
        </div>

        <!-- 图表推荐 -->
        <div class="side-section">
          <div class="side-section-title">
            <el-icon><DataAnalysis /></el-icon>
            {{ t('aiAssistant.page.chartRec') }}
          </div>
          <ChartRecommendationPanel
            v-if="lastChartRecommendation && lastChartRecommendation.recommendations.length > 0"
            :recommendations="lastChartRecommendation.recommendations"
            :data-profile="lastChartRecommendation.dataProfile"
            :selected-id="lastChart?.recommendationId"
            @select="onChartSelect"
          />
          <div v-else class="side-empty">{{ t('aiAssistant.page.chartEmpty') }}</div>
        </div>

        <!-- 数据解读 -->
        <div class="side-section">
          <div class="side-section-title">
            <el-icon><DocumentChecked /></el-icon>
            {{ t('aiAssistant.page.summary') }}
          </div>
          <DataSummary
            v-if="lastSummary"
            :summary="aiLocale === 'zh' ? lastSummary.summary.zh : lastSummary.summary.en"
            :insights="lastSummary.insights"
            :metrics="lastSummary.metrics"
          />
          <div v-else class="side-empty">{{ t('aiAssistant.page.summaryEmpty') }}</div>
        </div>

        <!-- Superset 仪表盘 -->
        <div class="side-section">
          <div class="side-section-title">
            <el-icon><Histogram /></el-icon>
            {{ t('aiAssistant.page.dashboard') }}
          </div>
          <el-button
            type="primary"
            :icon="Promotion"
            :loading="loading"
            :disabled="!lastSql || !selectedDatasource"
            style="width: 100%"
            @click="onCreateDashboard"
          >
            {{ t('aiAssistant.page.createDashboard') }}
          </el-button>
          <div v-if="dashboardUrl" class="dashboard-link">
            <el-link :href="dashboardUrl" target="_blank" type="primary">
              <el-icon><Link /></el-icon>
              {{ t('aiAssistant.page.openDashboard') }}
            </el-link>
          </div>
          <div v-if="!lastSql" class="side-empty">{{ t('aiAssistant.page.dashboardEmpty') }}</div>
        </div>
      </aside>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
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
import type { SqlDialect, ChartRecommendation, SupersetDatasource } from '@/types/ai-assistant'

const { t, locale: i18nLocale } = useI18n()
const aiLocale = computed(() => (i18nLocale.value.startsWith('zh') ? 'zh' : 'en') as 'zh' | 'en')

/* ------------------------------ 组合式函数 ------------------------------ */
const {
  currentSession,
  messages,
  sessions,

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
const SQL_DIALECTS: SqlDialect[] = ['ANSI', 'HIVE', 'DORIS', 'TRINO', 'MYSQL', 'POSTGRESQL']
const dialectOptions = computed(() =>
  SQL_DIALECTS.map((value) => ({
    value,
    label: t(`aiAssistant.dialect.${value}`)
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
    ElMessage.success(t('aiAssistant.page.deleted'))
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
    ElMessage.success(t('aiAssistant.page.chartSwitched'))
  } else if (!lastExecution.value) {
    ElMessage.warning(t('aiAssistant.page.noData'))
  }
}

/* ------------------------------ Superset 仪表盘 ------------------------------ */
const dashboardUrl = ref<string>('')

async function onCreateDashboard(): Promise<void> {
  const result = await createDashboard()
  if (result) {
    dashboardUrl.value = result.url
    ElMessage.success(t('aiAssistant.page.dashboardCreated'))
  } else {
    ElMessage.error(t('aiAssistant.page.dashboardFailed'))
  }
}

/* ------------------------------ 错误监听 ------------------------------ */
watch(error, (err) => {
  if (err) {
    ElMessage.error(err.message)
  }
})

/* ------------------------------ 工具 ------------------------------ */
function formatDate(iso: string): string {
  const d = new Date(iso)
  if (isNaN(d.getTime())) return iso
  const now = Date.now()
  const diff = now - d.getTime()
  const day = 24 * 60 * 60 * 1000
  if (diff < day) {
    return d.toLocaleTimeString(i18nLocale.value, {
      hour: '2-digit',
      minute: '2-digit'
    })
  }
  if (diff < 7 * day) {
    const days = Math.floor(diff / day)
    return t('aiAssistant.page.daysAgo', { days })
  }
  return d.toLocaleDateString(i18nLocale.value)
}

const langIcon = computed(() => (aiLocale.value === 'zh' ? Comment : ChatDotRound))

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
  border-bottom: 1px solid var(--ds-border-subtle);
  margin-bottom: 16px;
}
.ai-title {
  display: flex;
  align-items: baseline;
  gap: 12px;
}
.ai-title h1 {
  font-size: var(--ds-font-size-3xl);
  margin: 0;
}
.ai-sub {
  font-size: 14px;
  color: var(--ds-text-tertiary);
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
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-subtle);
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
  font-size: 14px;
  font-weight: 600;
  border-bottom: 1px solid var(--ds-border-subtle);
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
  background: var(--ds-color-primary-50);
  color: var(--ds-color-primary-500);
}
.session-pin {
  color: var(--c-slate-300);
  flex: none;
}
.session-pin.pinned {
  color: var(--ds-color-warning-500);
}
.session-info {
  flex: 1;
  min-width: 0;
}
.session-title {
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.session-meta {
  font-size: 12px;
  color: var(--ds-text-tertiary);
  margin-top: 2px;
}
.session-more {
  color: var(--ds-text-tertiary);
  cursor: pointer;
  flex: none;
}
.sessions-empty {
  padding: 24px 12px;
  text-align: center;
  font-size: 12px;
  color: var(--ds-text-tertiary);
}

/* 中部聊天 */
.ai-chat {
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-subtle);
  border-radius: 10px;
  overflow: hidden;
  min-width: 0;
}
.ai-chat :deep(.chat-panel) {
  height: 100%;
}

/* 右侧分析面板 */
.ai-side {
  background: var(--ds-bg-surface);
  border: 1px solid var(--ds-border-subtle);
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
  font-size: 14px;
  font-weight: 600;
  color: var(--ds-text-primary);
  padding-bottom: 6px;
  border-bottom: 1px solid var(--ds-border-subtle);
}
.side-empty {
  font-size: 12px;
  color: var(--ds-text-tertiary);
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
