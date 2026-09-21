/**
 * AI 助手 API 封装（T011）
 *
 * 后端约定：
 * - 对话端点：POST /api/v1/ai-assistant/chat
 * - SQL 生成：POST /api/v1/ai-assistant/nl2sql
 * - SQL 执行：POST /api/v1/ai-assistant/execute
 * - 图表推荐：POST /api/v1/ai-assistant/recommend-chart
 * - 数据解读：POST /api/v1/ai-assistant/summarize
 * - Superset 仪表盘：POST /api/v1/ai-assistant/dashboard
 * - 历史会话：GET  /api/v1/ai-assistant/sessions
 *
 * 说明：
 * - 所有方法返回 Promise<T>，错误由 client 拦截器统一提示
 * - 类型从 @/types/ai-assistant 集中导出，避免循环依赖
 * - 流式对话通过 chatStream 提供，基于 fetch + ReadableStream
 */
import { get, post, del, triggerUnauthorized, getToken } from './client'
import type {
  ChatRequest,
  ChatResponse,
  Nl2SqlRequest,
  Nl2SqlResponse,
  ExecuteSqlRequest,
  ExecuteSqlResponse,
  RecommendChartRequest,
  RecommendChartResponse,
  SummarizeRequest,
  SummarizeResponse,
  CreateDashboardRequest,
  CreateDashboardResponse,
  SupersetDatasource,
  ChatSession,
  SessionQuery,
  ChatMessage,
  Locale
} from '@/types/ai-assistant'

/** AI 助手资源根路径 */
const BASE = '/ai-assistant'

/**
 * 带 i18n error code 的错误类型。
 *
 * API 层不直接依赖 i18n（避免循环依赖），而是抛出带 `i18nKey` 的错误，
 * 由调用方（Vue 组件）通过 `t()` 翻译展示。
 */
export interface AiAssistantError extends Error {
  /** i18n 词条 key（调用方用 t(key, params) 翻译） */
  i18nKey?: string
  /** i18n 词条参数 */
  i18nParams?: Record<string, unknown>
  /** HTTP 状态码（可选） */
  httpStatus?: number
  /** 原始后端文案（透传用） */
  backendMessage?: string
}

/** 创建带 i18n key 的错误 */
function createI18nError(
  i18nKey: string,
  i18nParams?: Record<string, unknown>,
  fallbackMessage?: string,
  httpStatus?: number
): AiAssistantError {
  const err = new Error(fallbackMessage ?? i18nKey) as AiAssistantError
  err.i18nKey = i18nKey
  err.i18nParams = i18nParams
  err.httpStatus = httpStatus
  err.name = 'AiAssistantError'
  return err
}

/* ------------------------------ 对话 ------------------------------ */

/**
 * 发起一次对话（非流式）
 *
 * 后端将一次性完成 NL→SQL→执行→图表→解读 全链路（按开关控制）。
 * 适用于简单场景或后端不支持流式时。
 *
 * @param req 对话请求
 */
export function chat(req: ChatRequest): Promise<ChatResponse> {
  return post<ChatResponse>(`${BASE}/chat`, req)
}

/**
 * 发起一次流式对话
 *
 * 通过 fetch + ReadableStream 实现 SSE 风格的增量接收，
 * 每收到一个事件块调用 onChunk 回调。
 *
 * @param req 对话请求
 * @param onChunk 增量回调
 * @param signal 中断信号
 */
export async function chatStream(
  req: ChatRequest,
  onChunk: (chunk: ChatStreamChunk) => void,
  signal?: AbortSignal
): Promise<ChatResponse> {
  const url = `${buildBase()}${BASE}/chat/stream`
  // 与 client 拦截器一致：自动携带 Bearer token（通过 getToken 复用 auth store 注入的 tokenGetter）
  const token = getToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'text/event-stream'
  }
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const resp = await fetch(url, {
    method: 'POST',
    headers,
    body: JSON.stringify(req),
    signal
  })

  if (!resp.ok) {
    // 401 → 复用 client 响应拦截器的 unauthorizedHandler，保证全局 401 行为一致
    // （统一清理登录态 + 跳转 /account，避免 SSE 通道绕过主拦截器导致行为分叉）
    if (resp.status === 401) {
      triggerUnauthorized()
      throw createI18nError(
        'aiAssistant.errors.streamUnauthorized',
        undefined,
        '登录已过期，请重新登录',
        resp.status
      )
    }
    // 503/502/5xx：后端（nl2sql / ai-assistant）返回结构化友好提示
    // - nl2sql LLM 未配置 → FastAPI {"detail": "LLM 服务未配置：请设置 LLM_API_KEY ..."}
    // - ai-assistant 下游失败 → gin {"error": "nl2sql 返回 503"}
    // 优先透传后端 detail/error 文案，让"LLM 未配置"等场景展示可操作提示
    let backendMsg = ''
    try {
      const body = (await resp.json()) as { detail?: unknown; error?: unknown; message?: unknown }
      backendMsg =
        (typeof body.detail === 'string' && body.detail) ||
        (typeof body.error === 'string' && body.error) ||
        (typeof body.message === 'string' && body.message) ||
        ''
    } catch {
      // 响应体非 JSON 时保留默认提示
    }
    // 后端有结构化文案时直接透传（可操作提示）；否则抛 i18n key 让调用方翻译
    if (backendMsg) {
      const err = createI18nError(
        'aiAssistant.errors.streamRequestFailed',
        { status: resp.status },
        backendMsg,
        resp.status
      )
      err.backendMessage = backendMsg
      throw err
    }
    throw createI18nError(
      'aiAssistant.errors.streamRequestFailed',
      { status: resp.status },
      `AI 助手流式请求失败：HTTP ${resp.status}`,
      resp.status
    )
  }
  if (!resp.body) {
    throw createI18nError('aiAssistant.errors.streamNoBody', undefined, 'AI 助手流式请求无响应体')
  }

  const reader = resp.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let finalResult: ChatResponse | null = null

  // SSE 简易解析：以 \n\n 分隔事件，每事件 data: JSON
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    let sepIdx: number
    while ((sepIdx = buffer.indexOf('\n\n')) !== -1) {
      const rawEvent = buffer.slice(0, sepIdx)
      buffer = buffer.slice(sepIdx + 2)
      const chunk = parseSseEvent(rawEvent)
      if (chunk) {
        if (chunk.type === 'final' && chunk.final) {
          finalResult = chunk.final
        } else {
          onChunk(chunk)
        }
      }
    }
  }

  if (!finalResult) {
    throw createI18nError(
      'aiAssistant.errors.streamNoFinal',
      undefined,
      'AI 助手流式请求未收到 final 事件'
    )
  }
  return finalResult
}

/** 流式事件块 */
export interface ChatStreamChunk {
  /** 事件类型 */
  type:
    'sql' | 'execution' | 'chart-recommend' | 'chart' | 'summary' | 'message' | 'final' | 'error'
  /** SQL 生成事件 */
  sql?: Nl2SqlResponse
  /** 执行结果事件 */
  execution?: ExecuteSqlResponse
  /** 图表推荐事件 */
  chartRecommendation?: RecommendChartResponse
  /** 图表配置事件 */
  chart?: { config: NonNullable<ChatResponse['chart']> }
  /** 摘要事件 */
  summary?: SummarizeResponse
  /** 消息增量事件 */
  message?: { delta: string }
  /** 最终事件 */
  final?: ChatResponse
  /** 错误事件 */
  error?: string
}

/** 解析单个 SSE 事件 */
function parseSseEvent(raw: string): ChatStreamChunk | null {
  const lines = raw.split('\n')
  for (const line of lines) {
    if (line.startsWith('data:')) {
      const json = line.slice(5).trim()
      if (!json) continue
      try {
        return JSON.parse(json) as ChatStreamChunk
      } catch {
        // 忽略非 JSON 数据行
        continue
      }
    }
  }
  return null
}

/** 拼接完整 baseURL（与 client.ts 保持一致） */
function buildBase(): string {
  const base = (import.meta.env.VITE_API_BASE as string | undefined) || '/api/v1'
  // 去掉末尾斜杠
  return base.endsWith('/') ? base.slice(0, -1) : base
}

/* ------------------------------ NL → SQL ------------------------------ */

/**
 * 自然语言转 SQL
 * @param req 请求
 */
export function nl2Sql(req: Nl2SqlRequest): Promise<Nl2SqlResponse> {
  return post<Nl2SqlResponse>(`${BASE}/nl2sql`, req)
}

/* ------------------------------ SQL 执行 ------------------------------ */

/**
 * 执行 SQL 并返回结果表格
 * @param req 请求
 */
export function executeSql(req: ExecuteSqlRequest): Promise<ExecuteSqlResponse> {
  return post<ExecuteSqlResponse>(`${BASE}/execute`, req)
}

/* ------------------------------ 图表推荐 ------------------------------ */

/**
 * 基于表格数据推荐图表类型与字段映射
 * @param req 请求
 */
export function recommendChart(req: RecommendChartRequest): Promise<RecommendChartResponse> {
  return post<RecommendChartResponse>(`${BASE}/recommend-chart`, req)
}

/* ------------------------------ 数据解读 ------------------------------ */

/**
 * 生成数据解读摘要
 * @param req 请求
 */
export function summarize(req: SummarizeRequest): Promise<SummarizeResponse> {
  return post<SummarizeResponse>(`${BASE}/summarize`, req)
}

/* ------------------------------ Superset 仪表盘 ------------------------------ */

/**
 * 一键创建 Superset 仪表盘
 * @param req 请求
 */
export function createDashboard(req: CreateDashboardRequest): Promise<CreateDashboardResponse> {
  return post<CreateDashboardResponse>(`${BASE}/dashboard`, req)
}

/**
 * 列出可用 Superset 数据源
 */
export function listSupersetDatasources(): Promise<SupersetDatasource[]> {
  return get<SupersetDatasource[]>(`${BASE}/superset/datasources`)
}

/* ------------------------------ 历史会话 ------------------------------ */

/**
 * 列出历史会话
 * @param query 查询参数
 */
export function listSessions(query?: SessionQuery): Promise<ChatSession[]> {
  return get<ChatSession[]>(`${BASE}/sessions`, query as Record<string, unknown>)
}

/**
 * 获取会话详情（含全部消息）
 * @param id 会话 ID
 */
export function getSession(id: string): Promise<{ session: ChatSession; messages: ChatMessage[] }> {
  return get(`${BASE}/sessions/${id}`)
}

/**
 * 删除会话
 * @param id 会话 ID
 */
export function deleteSession(id: string): Promise<void> {
  return del<void>(`${BASE}/sessions/${id}`)
}

/**
 * 置顶 / 取消置顶会话
 * @param id 会话 ID
 * @param pinned 是否置顶
 */
export function pinSession(id: string, pinned: boolean): Promise<void> {
  return post<void>(`${BASE}/sessions/${id}/pin`, { pinned })
}

/**
 * 重命名会话
 * @param id 会话 ID
 * @param title 新标题
 */
export function renameSession(id: string, title: string): Promise<void> {
  return post<void>(`${BASE}/sessions/${id}/rename`, { title })
}

/* ------------------------------ 反馈 ------------------------------ */

/**
 * 对单条消息反馈（点赞 / 点踩）
 * @param messageId 消息 ID
 * @param feedback 反馈
 */
export function feedbackMessage(
  messageId: string,
  feedback: 'like' | 'dislike' | null
): Promise<void> {
  return post<void>(`${BASE}/messages/${messageId}/feedback`, { feedback })
}

/* ------------------------------ 示例提问 ------------------------------ */

/**
 * 获取示例提问（用于空状态引导）
 * @param locale 语言
 */
export function getExamplePrompts(locale: Locale = 'zh'): Promise<string[]> {
  return get<string[]>(`${BASE}/example-prompts`, { locale })
}
