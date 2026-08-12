/**
 * AI 助手组合式函数（T011）
 *
 * 职责：
 * - 维护会话状态（当前会话 / 消息列表 / 历史会话）
 * - 调用 api/ai-assistant 完成全链路对话（NL→SQL→执行→图表→解读）
 * - 维护 loading / error / streaming 状态
 * - 维护语言（中英双语）切换
 * - 维护开关：自动执行 SQL / 自动推荐图表 / 自动解读
 * - 内置 ECharts 图表配置构建器，把推荐项转为 ECharts option
 * - 暴露 sendMessage / newSession / switchSession / deleteSession 等方法
 *
 * 用法：
 * ```ts
 * const {
 *   messages, currentSession, sessions, locale, loading, streaming,
 *   sendMessage, newSession, switchSession, toggleLocale
 * } = useAiAssistant()
 * ```
 */
import { ref, reactive, computed, onMounted, onUnmounted, type Ref, type ComputedRef } from 'vue'
import * as aiApi from '@/api/ai-assistant'
import {
  generateMessageId,
  generateSessionId,
  createTextContent,
  createErrorContent,
  type ChatMessage,
  type ChatSession,
  type ChatResponse,
  type ChatRequest,
  type Locale,
  type ChartConfig,
  type ChartRecommendation,
  type TableData,
  type ChartType,
  type Nl2SqlResponse,
  type ExecuteSqlResponse,
  type RecommendChartResponse,
  type SummarizeResponse,
  type MessageContent,
  type SqlDialect,
  type Bilingual
} from '@/types/ai-assistant'

/** useAiAssistant 配置 */
export interface UseAiAssistantOptions {
  /** 初始语言，默认 zh */
  initialLocale?: Locale
  /** 初始数据源 ID */
  initialDatasourceId?: string
  /** 是否自动执行 SQL，默认 true */
  initialAutoExecute?: boolean
  /** 是否自动推荐图表，默认 true */
  initialAutoRecommendChart?: boolean
  /** 是否自动解读，默认 true */
  initialAutoSummarize?: boolean
  /** 是否挂载时加载历史会话，默认 true */
  loadSessionsOnMount?: boolean
}

/** useAiAssistant 返回值 */
export interface UseAiAssistantReturn {
  /* ----------------- 状态 ----------------- */
  /** 当前会话 */
  currentSession: Ref<ChatSession | null>
  /** 当前会话消息列表 */
  messages: Ref<ChatMessage[]>
  /** 历史会话列表 */
  sessions: Ref<ChatSession[]>
  /** 语言 */
  locale: Ref<Locale>
  /** 当前数据源 ID */
  datasourceId: Ref<string | undefined>
  /** SQL 方言 */
  dialect: Ref<SqlDialect>
  /** 自动执行 SQL */
  autoExecute: Ref<boolean>
  /** 自动推荐图表 */
  autoRecommendChart: Ref<boolean>
  /** 自动解读 */
  autoSummarize: Ref<boolean>
  /** 加载状态（用于按钮 loading） */
  loading: Ref<boolean>
  /** 流式接收状态 */
  streaming: Ref<boolean>
  /** 错误信息 */
  error: Ref<Error | null>
  /** 示例提问 */
  examplePrompts: Ref<string[]>
  /** 当前最后一条助手消息的 SQL（用于 SQL 预览组件） */
  lastSql: ComputedRef<Nl2SqlResponse | null>
  /** 当前最后一条助手消息的执行结果（用于图表 / 摘要） */
  lastExecution: ComputedRef<ExecuteSqlResponse | null>
  /** 当前最后一条助手消息的图表推荐 */
  lastChartRecommendation: ComputedRef<RecommendChartResponse | null>
  /** 当前最后一条助手消息的图表配置 */
  lastChart: ComputedRef<ChartConfig | null>
  /** 当前最后一条助手消息的解读摘要 */
  lastSummary: ComputedRef<SummarizeResponse | null>
  /** 是否有消息 */
  hasMessages: ComputedRef<boolean>

  /* ----------------- 方法 ----------------- */
  /** 发送一条用户消息并触发全链路 */
  sendMessage: (text: string) => Promise<void>
  /** 中断当前流式请求 */
  abort: () => void
  /** 新建会话 */
  newSession: () => void
  /** 切换会话 */
  switchSession: (id: string) => Promise<void>
  /** 删除会话 */
  deleteSession: (id: string) => Promise<void>
  /** 置顶 / 取消置顶 */
  pinSession: (id: string, pinned: boolean) => Promise<void>
  /** 切换语言 */
  toggleLocale: () => void
  /** 设置语言 */
  setLocale: (locale: Locale) => void
  /** 设置数据源 */
  setDatasource: (id: string | undefined) => void
  /** 设置方言 */
  setDialect: (dialect: SqlDialect) => void
  /** 重新执行当前 SQL */
  reexecute: () => Promise<void>
  /** 切换图表类型（基于当前数据重新生成图表配置） */
  switchChart: (recommendation: ChartRecommendation) => ChartConfig | null
  /** 创建 Superset 仪表盘 */
  createDashboard: () => Promise<{ url: string; embedUrl: string } | null>
  /** 反馈消息 */
  feedback: (messageId: string, feedback: 'like' | 'dislike' | null) => Promise<void>
  /** 加载示例提问 */
  loadExamplePrompts: () => Promise<void>
  /** 加载历史会话 */
  loadSessions: () => Promise<void>
}

/**
 * AI 助手组合式函数
 * @param options 配置项
 */
export function useAiAssistant(options: UseAiAssistantOptions = {}): UseAiAssistantReturn {
  const {
    initialLocale = 'zh',
    initialDatasourceId,
    initialAutoExecute = true,
    initialAutoRecommendChart = true,
    initialAutoSummarize = true,
    loadSessionsOnMount = true
  } = options

  /* ------------------------------ 状态 ------------------------------ */
  const currentSession = ref<ChatSession | null>(null)
  const messages = ref<ChatMessage[]>([])
  const sessions = ref<ChatSession[]>([])
  const locale = ref<Locale>(initialLocale)
  const datasourceId = ref<string | undefined>(initialDatasourceId)
  const dialect = ref<SqlDialect>('ANSI')
  const autoExecute = ref(initialAutoExecute)
  const autoRecommendChart = ref(initialAutoRecommendChart)
  const autoSummarize = ref(initialAutoSummarize)
  const loading = ref(false)
  const streaming = ref(false)
  const error = ref<Error | null>(null)
  const examplePrompts = ref<string[]>([])

  /** 当前流式请求的中断控制器 */
  let abortController: AbortController | null = null

  /* ------------------------------ 计算属性 ------------------------------ */
  /** 取最后一条助手消息 */
  const lastAssistantMessage = computed(() => {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      if (messages.value[i].role === 'assistant') return messages.value[i]
    }
    return null
  })

  /** 从助手消息内容中按类型查找 */
  function findContent<T extends MessageContent>(
    msg: ChatMessage | null,
    type: T['type']
  ): T | null {
    if (!msg) return null
    for (const c of msg.contents) {
      if (c.type === type) return c as T
    }
    return null
  }

  const lastSql = computed<Nl2SqlResponse | null>(() => {
    const msg = lastAssistantMessage.value
    const c = findContent<MessageContent>(msg, 'card') ?? findContent<MessageContent>(msg, 'sql')
    return c?.sqlMeta
      ? ({
          sql: c.text ?? '',
          dialect: c.sqlMeta.dialect,
          tables: c.sqlMeta.tables,
          columns: c.sqlMeta.columns,
          crossSource: c.sqlMeta.crossSource,
          confidence: c.sqlMeta.confidence,
          durationMs: c.sqlMeta.durationMs
        } as Nl2SqlResponse)
      : null
  })

  const lastExecution = computed<ExecuteSqlResponse | null>(() => {
    const msg = lastAssistantMessage.value
    const c = findContent<MessageContent>(msg, 'card') ?? findContent<MessageContent>(msg, 'table')
    return c?.table
      ? ({
          queryId: 'q-local',
          status: 'success',
          table: c.table,
          durationMs: 0
        } as ExecuteSqlResponse)
      : null
  })

  const lastChartRecommendation = computed<RecommendChartResponse | null>(() => {
    // 暂存于消息的 chart 字段（通过闭包附加），此处简化：从 chart 推断
    return lastChart.value
      ? ({
          recommendations: [],
          dataProfile: lastChart.value.title,
          durationMs: 0
        } as RecommendChartResponse)
      : null
  })

  const lastChart = computed<ChartConfig | null>(() => {
    const msg = lastAssistantMessage.value
    const c = findContent<MessageContent>(msg, 'card') ?? findContent<MessageContent>(msg, 'chart')
    return c?.chart ?? null
  })

  const lastSummary = computed<SummarizeResponse | null>(() => {
    const msg = lastAssistantMessage.value
    const c =
      findContent<MessageContent>(msg, 'card') ?? findContent<MessageContent>(msg, 'summary')
    if (!c?.text) return null
    return {
      summary: { zh: c.text, en: c.text },
      insights: [],
      metrics: [],
      durationMs: c.summaryMeta?.durationMs ?? 0
    }
  })

  const hasMessages = computed(() => messages.value.length > 0)

  /* ------------------------------ 内部辅助 ------------------------------ */

  /** 把对话响应转换为助手消息内容块 */
  function buildAssistantContents(resp: ChatResponse): MessageContent[] {
    const contents: MessageContent[] = []

    // SQL + 表格 + 图表 + 摘要合并为 card；若仅有部分则分别推送
    const hasSql = !!resp.sql
    const hasTable = !!resp.execution
    const hasChart = !!resp.chart
    const hasSummary = !!resp.summary

    if (hasSql || hasTable || hasChart || hasSummary) {
      contents.push({
        type: 'card',
        text: resp.sql?.sql,
        sqlMeta: resp.sql
          ? {
              dialect: resp.sql.dialect,
              tables: resp.sql.tables,
              columns: resp.sql.columns,
              crossSource: resp.sql.crossSource,
              confidence: resp.sql.confidence,
              durationMs: resp.sql.durationMs
            }
          : undefined,
        table: resp.execution?.table,
        chart: resp.chart ?? undefined,
        summaryMeta: resp.summary
          ? {
              rowCount: resp.execution?.table.total ?? 0,
              columnCount: resp.execution?.table.columns.length ?? 0,
              dimensions: resp.summary.metrics.map((m) => m.label.zh),
              durationMs: resp.summary.durationMs
            }
          : undefined
      })
      // 摘要正文单独再放一份，便于组件渲染
      if (resp.summary) {
        contents.push({
          type: 'summary',
          text: pickBilingual(resp.summary.summary, locale.value),
          summaryMeta: {
            rowCount: resp.execution?.table.total ?? 0,
            columnCount: resp.execution?.table.columns.length ?? 0,
            dimensions: resp.summary.metrics.map((m) => m.label.zh),
            durationMs: resp.summary.durationMs
          }
        })
      }
    } else if (resp.message?.contents?.length) {
      // 后端直接返回 message
      contents.push(...resp.message.contents)
    } else {
      // 兜底：占位文本
      contents.push(createTextContent(locale.value === 'zh' ? '已处理完成。' : 'Done.'))
    }

    return contents
  }

  /** 取双语文案 */
  function pickBilingual(b: Bilingual, loc: Locale): string {
    return loc === 'zh' ? b.zh : b.en
  }

  /** 推送一条用户消息 */
  function pushUserMessage(text: string): ChatMessage {
    const sessionId = currentSession.value?.id ?? generateSessionId()
    const msg: ChatMessage = {
      id: generateMessageId(),
      sessionId,
      role: 'user',
      contents: [createTextContent(text)],
      createdAt: new Date().toISOString(),
      status: 'done'
    }
    messages.value.push(msg)
    return msg
  }

  /** 推送一条占位助手消息（pending） */
  function pushPendingAssistant(): ChatMessage {
    const sessionId = currentSession.value?.id ?? generateSessionId()
    const msg: ChatMessage = {
      id: generateMessageId(),
      sessionId,
      role: 'assistant',
      contents: [],
      createdAt: new Date().toISOString(),
      status: 'pending'
    }
    messages.value.push(msg)
    return msg
  }

  /** 创建新会话对象（前端临时） */
  function createSession(): ChatSession {
    return {
      id: generateSessionId(),
      title: locale.value === 'zh' ? '新对话' : 'New Chat',
      createdAt: new Date().toISOString(),
      updatedAt: new Date().toISOString(),
      messageCount: 0,
      pinned: false
    }
  }

  /* ------------------------------ 核心发送 ------------------------------ */

  /**
   * 发送一条用户消息并触发全链路
   */
  async function sendMessage(text: string): Promise<void> {
    const trimmed = text.trim()
    if (trimmed.length === 0 || loading.value) return

    // 首次发送时创建会话
    if (!currentSession.value) {
      currentSession.value = createSession()
    }
    const sessionId = currentSession.value.id

    // 推送用户消息
    pushUserMessage(trimmed)

    // 更新会话标题（首条用户消息）
    if (currentSession.value.messageCount === 0) {
      currentSession.value.title = trimmed.slice(0, 20)
    }
    currentSession.value.messageCount += 1
    currentSession.value.updatedAt = new Date().toISOString()

    // 推送占位助手消息
    const assistantMsg = pushPendingAssistant()

    loading.value = true
    streaming.value = true
    error.value = null
    abortController = new AbortController()

    const req: ChatRequest = {
      sessionId,
      message: trimmed,
      datasourceId: datasourceId.value,
      autoExecute: autoExecute.value,
      autoRecommendChart: autoRecommendChart.value,
      autoSummarize: autoSummarize.value,
      locale: locale.value
    }

    try {
      // 优先尝试流式；若后端不支持则降级为非流式
      let resp: ChatResponse
      try {
        resp = await aiApi.chatStream(
          req,
          (chunk) => {
            // 增量更新占位消息
            assistantMsg.status = 'streaming'
            if (chunk.type === 'message' && chunk.message?.delta) {
              // 文本增量：合并到首个 text 内容
              const existing = assistantMsg.contents.find((c) => c.type === 'text')
              if (existing) {
                existing.text = (existing.text ?? '') + chunk.message!.delta
              } else {
                assistantMsg.contents.push({
                  type: 'text',
                  text: chunk.message!.delta
                })
              }
            }
          },
          abortController.signal
        )
      } catch (streamErr) {
        // 流式失败：降级非流式（除非是被中断）
        if (abortController.signal.aborted) throw streamErr
        resp = await aiApi.chat(req)
      }

      // 用最终结果替换占位内容
      assistantMsg.contents = buildAssistantContents(resp)
      assistantMsg.status = 'done'

      // 同步会话 ID（后端可能新建）
      if (resp.sessionId && currentSession.value.id !== resp.sessionId) {
        currentSession.value.id = resp.sessionId
      }
    } catch (e) {
      const err = e instanceof Error ? e : new Error(String(e))
      error.value = err
      assistantMsg.status = 'error'
      assistantMsg.contents = [createErrorContent(err.message)]
    } finally {
      loading.value = false
      streaming.value = false
      abortController = null
    }
  }

  /** 中断当前流式请求 */
  function abort(): void {
    if (abortController) {
      abortController.abort()
      abortController = null
      streaming.value = false
      loading.value = false
    }
  }

  /* ------------------------------ 会话管理 ------------------------------ */

  /** 新建会话 */
  function newSession(): void {
    if (loading.value) abort()
    currentSession.value = null
    messages.value = []
    error.value = null
  }

  /** 切换会话 */
  async function switchSession(id: string): Promise<void> {
    if (loading.value) abort()
    loading.value = true
    error.value = null
    try {
      const { session, messages: msgs } = await aiApi.getSession(id)
      currentSession.value = session
      messages.value = msgs
    } catch (e) {
      error.value = e instanceof Error ? e : new Error(String(e))
    } finally {
      loading.value = false
    }
  }

  /** 删除会话 */
  async function deleteSession(id: string): Promise<void> {
    await aiApi.deleteSession(id)
    sessions.value = sessions.value.filter((s) => s.id !== id)
    if (currentSession.value?.id === id) {
      newSession()
    }
  }

  /** 置顶 / 取消置顶 */
  async function pinSession(id: string, pinned: boolean): Promise<void> {
    await aiApi.pinSession(id, pinned)
    const s = sessions.value.find((x) => x.id === id)
    if (s) s.pinned = pinned
  }

  /* ------------------------------ 语言 / 设置 ------------------------------ */

  function toggleLocale(): void {
    locale.value = locale.value === 'zh' ? 'en' : 'zh'
  }

  function setLocale(loc: Locale): void {
    locale.value = loc
  }

  function setDatasource(id: string | undefined): void {
    datasourceId.value = id
  }

  function setDialect(d: SqlDialect): void {
    dialect.value = d
  }

  /* ------------------------------ 重新执行 / 图表切换 ------------------------------ */

  /** 重新执行当前 SQL */
  async function reexecute(): Promise<void> {
    const sql = lastSql.value
    if (!sql || loading.value) return
    loading.value = true
    error.value = null
    try {
      const resp = await aiApi.executeSql({
        sql: sql.sql,
        dialect: sql.dialect,
        datasourceId: datasourceId.value
      })
      // 把执行结果追加到当前助手消息
      const msg = lastAssistantMessage.value
      if (msg) {
        const card = msg.contents.find((c) => c.type === 'card')
        if (card) {
          card.table = resp.table
        } else {
          msg.contents.push({ type: 'table', table: resp.table })
        }
      }
    } catch (e) {
      error.value = e instanceof Error ? e : new Error(String(e))
    } finally {
      loading.value = false
    }
  }

  /**
   * 切换图表类型：基于当前执行结果 + 推荐项，重新构建 ECharts 配置
   * @returns 生成的 ChartConfig，失败返回 null
   */
  function switchChart(recommendation: ChartRecommendation): ChartConfig | null {
    const exec = lastExecution.value
    if (!exec) return null
    const cfg = buildChartConfig(recommendation, exec.table, locale.value)
    // 替换当前助手消息的 chart
    const msg = lastAssistantMessage.value
    if (msg) {
      const card = msg.contents.find((c) => c.type === 'card')
      if (card) {
        card.chart = cfg
      } else {
        const chartContent = msg.contents.find((c) => c.type === 'chart')
        if (chartContent) {
          chartContent.chart = cfg
        } else {
          msg.contents.push({ type: 'chart', chart: cfg })
        }
      }
    }
    return cfg
  }

  /* ------------------------------ Superset 仪表盘 ------------------------------ */

  /** 创建 Superset 仪表盘 */
  async function createDashboard(): Promise<{ url: string; embedUrl: string } | null> {
    const sql = lastSql.value
    const chart = lastChart.value
    if (!sql || !datasourceId.value) return null
    loading.value = true
    error.value = null
    try {
      const resp = await aiApi.createDashboard({
        title: {
          zh: currentSession.value?.title ?? 'AI 助手仪表盘',
          en: currentSession.value?.title ?? 'AI Assistant Dashboard'
        },
        datasourceId: datasourceId.value,
        sql: sql.sql,
        charts: chart ? [chart] : [],
        sessionId: currentSession.value?.id
      })
      return { url: resp.url, embedUrl: resp.embedUrl }
    } catch (e) {
      error.value = e instanceof Error ? e : new Error(String(e))
      return null
    } finally {
      loading.value = false
    }
  }

  /* ------------------------------ 反馈 ------------------------------ */

  async function feedback(
    messageId: string,
    fb: 'like' | 'dislike' | null
  ): Promise<void> {
    await aiApi.feedbackMessage(messageId, fb)
    const msg = messages.value.find((m) => m.id === messageId)
    if (msg) msg.feedback = fb
  }

  /* ------------------------------ 加载 ------------------------------ */

  async function loadExamplePrompts(): Promise<void> {
    try {
      examplePrompts.value = await aiApi.getExamplePrompts(locale.value)
    } catch {
      // 加载失败使用内置示例
      examplePrompts.value =
        locale.value === 'zh'
          ? [
              '查询最近 7 天订单金额趋势',
              '统计各省份用户数量',
              '对比本月与上月 GMV',
              '找出 TOP 10 高频商品'
            ]
          : [
              'Show order amount trend for last 7 days',
              'Count users by province',
              'Compare this month vs last month GMV',
              'Find TOP 10 frequent products'
            ]
    }
  }

  async function loadSessions(): Promise<void> {
    try {
      sessions.value = await aiApi.listSessions({ limit: 50 })
    } catch {
      sessions.value = []
    }
  }

  /* ------------------------------ 生命周期 ------------------------------ */
  onMounted(() => {
    if (loadSessionsOnMount) {
      void loadSessions()
    }
    void loadExamplePrompts()
  })

  onUnmounted(() => {
    abort()
  })

  return {
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
    hasMessages,
    sendMessage,
    abort,
    newSession,
    switchSession,
    deleteSession,
    pinSession,
    toggleLocale,
    setLocale,
    setDatasource,
    setDialect,
    reexecute,
    switchChart,
    createDashboard,
    feedback,
    loadExamplePrompts,
    loadSessions
  }
}

/* ------------------------------ ECharts 配置构建器 ------------------------------ */

/**
 * 基于推荐项 + 表格数据构建 ECharts 配置
 * @param rec 推荐项
 * @param table 表格数据
 * @param locale 语言
 */
export function buildChartConfig(
  rec: ChartRecommendation,
  table: TableData,
  locale: Locale
): ChartConfig {
  const labels = table.columns.reduce<Record<string, string>>((acc, col) => {
    acc[col.name] = locale === 'zh' ? col.label.zh : col.label.en
    return acc
  }, {})

  const xData = table.rows.map((r) => String(r[rec.dimensions[0]] ?? ''))
  const series = rec.metrics.map((metric) => ({
    name: labels[metric] ?? metric,
    type: rec.type === 'area' ? 'line' : rec.type,
    data: table.rows.map((r) => Number(r[metric] ?? 0)),
    smooth: rec.type === 'line' || rec.type === 'area',
    areaStyle: rec.type === 'area' ? {} : undefined
  }))

  let option: Record<string, unknown>

  switch (rec.type) {
    case 'pie':
      option = {
        tooltip: { trigger: 'item' },
        legend: { top: 'bottom' },
        series: [
          {
            type: 'pie',
            radius: ['40%', '70%'],
            data: table.rows.map((r) => ({
              name: String(r[rec.dimensions[0]] ?? ''),
              value: Number(r[rec.metrics[0]] ?? 0)
            }))
          }
        ]
      }
      break
    case 'scatter':
      option = {
        tooltip: { trigger: 'item' },
        xAxis: { type: 'value', name: labels[rec.dimensions[0]] ?? rec.dimensions[0] },
        yAxis: { type: 'value', name: labels[rec.metrics[0]] ?? rec.metrics[0] },
        series: [
          {
            type: 'scatter',
            data: table.rows.map((r) => [
              Number(r[rec.dimensions[0]] ?? 0),
              Number(r[rec.metrics[0]] ?? 0)
            ])
          }
        ]
      }
      break
    case 'map':
      // 地图：简化为柱状图占位（实际地图需注册地图 JSON）
      option = {
        tooltip: { trigger: 'item' },
        xAxis: { type: 'category', data: xData },
        yAxis: { type: 'value' },
        series
      }
      break
    case 'radar':
      option = {
        tooltip: { trigger: 'item' },
        radar: {
          indicator: xData.map((name) => ({ name, max: 100 }))
        },
        series: [
          {
            type: 'radar',
            data: [
              {
                value: table.rows.map((r) => Number(r[rec.metrics[0]] ?? 0)),
                name: labels[rec.metrics[0]] ?? rec.metrics[0]
              }
            ]
          }
        ]
      }
      break
    case 'bar':
    case 'line':
    case 'area':
    default:
      option = {
        tooltip: { trigger: 'axis' },
        legend: { data: rec.metrics.map((m) => labels[m] ?? m) },
        xAxis: { type: 'category', data: xData },
        yAxis: { type: 'value' },
        series
      }
      break
  }

  return {
    type: rec.type,
    option,
    title: {
      zh: `${rec.dimensions.join('、')} × ${rec.metrics.join('、')}`,
      en: `${rec.dimensions.join(', ')} × ${rec.metrics.join(', ')}`
    },
    recommendationId: rec.id
  }
}

/**
 * 图表类型双语标签便捷导出
 */
export { CHART_TYPE_LABELS } from '@/types/ai-assistant'