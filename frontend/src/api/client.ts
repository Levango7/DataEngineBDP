/**
 * Axios HTTP 客户端封装
 *
 * 功能：
 * - baseURL 从环境变量 VITE_API_BASE 读取，默认 /api/v1
 * - 请求拦截器：自动携带 Bearer token（从 auth store 动态获取，避免循环依赖）
 * - 响应拦截器：401→跳转登录页、403→提示无权限、500→提示服务器错误
 * - 统一拆包 ApiResponse<T>，业务调用直接拿到 T
 * - 导出 get / post / put / del 四个泛型方法
 */
import axios, {
  type AxiosInstance,
  type AxiosRequestConfig,
  type InternalAxiosRequestConfig
} from 'axios'
import type { ApiResponse } from './types'

/** 后端业务错误码：非 0 视为业务失败 */
const BIZ_SUCCESS_CODE = 0

/**
 * 对后端错误消息进行脱敏处理，防止泄露敏感信息。
 *
 * <p>后端 error 字段可能包含 IP 地址、连接字符串、密码、堆栈跟踪等敏感信息，
 * 直接展示给前端用户存在安全风险。处理策略：
 * <ol>
 *   <li>敏感模式替换为占位符（连接串、密码、内网 IP、文件路径、堆栈行）</li>
 *   <li>长度截断（最多 200 字符），避免超长错误信息撑爆 UI</li>
 *   <li>生产环境脱敏后若为空，回退通用提示</li>
 * </ol></p>
 *
 * @param raw 原始错误消息
 * @returns 脱敏后的安全消息
 */
function sanitizeErrorMessage(raw: string): string {
  let msg = String(raw)

  // 敏感模式替换（按优先级排序，先匹配的先替换）
  const SENSITIVE_PATTERNS: Array<[RegExp, string]> = [
    // JDBC / 通用协议连接串：jdbc:mysql://host:port/db?user=xxx&password=xxx
    [/(jdbc:[a-z]+:\/\/[^\s"'<>]+)/gi, '[连接串已隐藏]'],
    // 含密码的连接串：mysql://user:password@host:port
    [/([a-z]+:\/\/[^\s"'<>]*:[^\s"'<>]*@[^\s"'<>]+)/gi, '[连接串已隐藏]'],
    // password=xxx、token=xxx 等键值对
    [/(password|passwd|pwd|secret|token|apiKey|api_key)\s*[=:]\s*[^\s"'<>,;)]+/gi, '$1=***'],
    // IP 地址 + 端口（如 192.168.1.1:3306）
    [/\b(\d{1,3}\.){3}\d{1,3}:\d{2,5}\b/g, '[地址已隐藏]'],
    // 内网 IP 地址（如 192.168.1.1、10.0.0.1、172.16.x.x）
    [/\b(?:10|127|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b/g, '[内网IP已隐藏]'],
    // 文件路径（如 /etc/passwd、C:\Users\admin）
    [/(?:[A-Za-z]:\\[^\s"'<>]+|\/(?:etc|root|home|var|opt|usr)\/[^\s"'<>]+)/g, '[路径已隐藏]'],
    // 堆栈跟踪行（如 "at com.xxx.yyy.zzz(FileName.java:123)"）
    [/\s*at\s+[^\n]+/g, '']
  ]

  for (const [pattern, replacement] of SENSITIVE_PATTERNS) {
    msg = msg.replace(pattern, replacement)
  }

  // 长度截断（200 字符）
  if (msg.length > 200) {
    msg = msg.slice(0, 200) + '...'
  }

  // 脱敏后若为空，回退通用提示
  if (!msg.trim()) {
    return '请求失败（错误详情已脱敏）'
  }

  return msg
}

/** 全局错误提示回调，由外部注入（避免硬耦合 store） */
let errorNotifier: ((msg: string) => void) | null = null

/** 401 跳登录页回调，由外部注入 */
let unauthorizedHandler: (() => void) | null = null

/** i18n 翻译函数，由外部注入（A2 错误国际化：messageKey → 当前语种文案） */
let i18nTranslator: ((key: string, fallback: string) => string) | null = null

/**
 * 注入 i18n 翻译函数（应用启动时调用一次，main.ts）。
 *
 * <p>翻译失败（词条缺失）时回退 fallback（后端中文兜底文案）。
 * 用注入而非直接 import i18n，保持 client.ts 与 ui/store 零耦合
 * （client.ts 被 store 依赖，直接 import 会成环）。</p>
 */
export function setI18nTranslator(translator: (key: string, fallback: string) => string): void {
  i18nTranslator = translator
}

/** 按 messageKey 翻译错误消息；无 key / 无词条回退原文。 */
function translateError(messageKey: string | undefined | null, message: string): string {
  if (messageKey && i18nTranslator) {
    return i18nTranslator(messageKey, message)
  }
  return message
}

/** 401 单飞窗口：窗口内后续 401 静默，避免并发 401 触发 N 次登出/提示 */
const UNAUTHORIZED_RESET_MS = 1000
let unauthorizedInFlight = false
let unauthorizedResetTimer: ReturnType<typeof setTimeout> | null = null

function handleUnauthorized(): void {
  unauthorizedInFlight = true
  unauthorizedHandler?.()
  if (unauthorizedResetTimer !== null) {
    clearTimeout(unauthorizedResetTimer)
  }
  unauthorizedResetTimer = setTimeout(() => {
    unauthorizedInFlight = false
    unauthorizedResetTimer = null
  }, UNAUTHORIZED_RESET_MS)
}

/**
 * 注入错误提示函数（在应用启动时调用一次）
 * @param notifier 错误提示函数，例如 toast
 */
export function setErrorNotifier(notifier: (msg: string) => void): void {
  errorNotifier = notifier
}

/**
 * 注入 401 未授权处理函数（通常跳转登录页）
 * @param handler 跳转登录页函数
 */
export function setUnauthorizedHandler(handler: () => void): void {
  unauthorizedHandler = handler
}

/**
 * 触发 401 未授权处理（与响应拦截器复用同一回调）。
 *
 * <p>供非 axios 通道（如 SSE/fetch 流式请求）在收到 401 时调用，
 * 保证全局 401 行为一致：清理登录态 + 跳转登录页。
 * 若未注入 handler 则回退到硬跳转 `/account`，避免静默丢失。</p>
 */
export function triggerUnauthorized(): void {
  if (unauthorizedHandler) {
    unauthorizedHandler()
  } else {
    // 兜底：未注入 handler 时直接跳转 /account，避免 401 被静默吞掉
    window.location.href = '/account'
  }
}

/** token 获取函数，由 auth store 注入，避免循环依赖 */
let tokenGetter: (() => string | null) | null = null

/**
 * 注入 token 获取函数
 * @param getter 返回当前 token，无 token 时返回 null
 */
export function setTokenGetter(getter: () => string | null): void {
  tokenGetter = getter
}

/**
 * 获取当前 token（供非 axios 通道如 SSE/fetch 复用，避免硬编码 sessionStorage 键名）。
 *
 * <p>tokenGetter 由 auth store 通过 setTokenGetter 注入；未注入时回退读取 sessionStorage，
 * 保证在注入前（如应用启动早期）也能拿到 token。</p>
 *
 * @returns 当前 token，无 token 时返回 null
 */
export function getToken(): string | null {
  if (tokenGetter) {
    return tokenGetter()
  }
  // 兜底：未注入时直接读 sessionStorage（与 auth store 的 TOKEN_KEY 保持一致）
  try {
    return sessionStorage.getItem('sq_token')
  } catch {
    return null
  }
}

/** 创建 Axios 实例 */
const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api/v1',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

/** 当前租户 ID（业务线域 X-Tenant-Id 用）：优先取登录 user.tenantId，兜底平台管理员 */
const USER_KEY = 'sq_user'
function currentTenantId(): string {
  try {
    const raw = localStorage.getItem(USER_KEY)
    if (raw) {
      const u = JSON.parse(raw)
      if (u?.tenantId) return String(u.tenantId)
    }
  } catch {
    /* 忽略解析失败，兜底平台管理员 */
  }
  return 'platform-admin'
}

/* ------------------------------ 请求拦截器 ------------------------------ */
http.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 自动携带 Bearer token
    const token = tokenGetter?.() ?? null
    if (token) {
      config.headers = config.headers ?? {}
      config.headers.Authorization = `Bearer ${token}`
    }
    // business-portal 在 AUTH_MODE=none 下从 X-Tenant-Id header 读租户（与
    // asset-exchange 等匿名放行服务行为不一致，Sprint 3.1 联调发现）。
    // 仅对 /business-lines 域注入，避免影响 encaps-layer 的多租户校验。
    if (config.url?.includes('/business-lines')) {
      config.headers = config.headers ?? {}
      config.headers['X-Tenant-Id'] = currentTenantId()
    }
    return config
  },
  (error) => Promise.reject(error)
)

/* ------------------------------ 响应拦截器 ------------------------------ */
http.interceptors.response.use(
  (response) => {
    // 拆包 ApiResponse<T>
    const body = response.data as ApiResponse<unknown>
    if (body && typeof body === 'object' && 'code' in body) {
      if (body.code !== BIZ_SUCCESS_CODE) {
        // A2：优先按后端 messageKey 翻译，缺失时回退 message 原文
        const raw = body.message || translateError('errors.http.bizFailed', '业务处理失败')
        const msg = translateError((body as { messageKey?: string }).messageKey, raw)
        errorNotifier?.(msg)
        return Promise.reject(new ApiError(msg, body.code, response.status))
      }
      // 拆包：把 data 提到 response.data，方便调用方直接拿到业务数据
      response.data = body.data
    }
    return response
  },
  (error) => {
    // 网络错误或 HTTP 状态码非 2xx
    const status: number = error?.response?.status ?? 0
    let msg = translateError('errors.http.default', '请求失败，请稍后重试')

    if (error?.response?.data?.error) {
      // 服务端显式错误码（如跨源查询 FAILED 的 error 字段）优先展示
      // 脱敏处理：防止后端 error 字段泄露 IP、连接串、密码等敏感信息
      msg = sanitizeErrorMessage(String(error.response.data.error))
    } else if (status === 401) {
      msg = translateError('errors.http.unauthorized', '登录已过期，请重新登录')
      if (unauthorizedInFlight) {
        return Promise.reject(new ApiError(msg, status, status))
      }
      handleUnauthorized()
    } else if (status === 403) {
      msg = translateError('errors.http.forbidden', '无权限访问该资源')
    } else if (status === 500) {
      msg = translateError('errors.http.serverError', '服务器内部错误，请联系管理员')
    } else if (status === 502) {
      msg = translateError('errors.http.badGateway', '上游服务暂时不可用，请稍后重试')
    } else if (status === 504) {
      msg = translateError('errors.http.timeout', '查询超时，请检查 SQL 或稍后重试')
    } else if (status === 413) {
      msg = translateError('errors.http.payloadTooLarge', '结果集过大，请缩小查询范围')
    } else if (status === 404) {
      msg = translateError('errors.http.notFound', '请求的资源不存在')
    } else if (status === 0) {
      msg = translateError('errors.http.network', '网络异常，请检查网络连接')
    } else if (error?.response?.data?.message) {
      msg = error.response.data.message
    }

    errorNotifier?.(msg)
    return Promise.reject(new ApiError(msg, status, status))
  }
)

/** 业务错误类 */
export class ApiError extends Error {
  /** 业务码或 HTTP 状态码 */
  code: number
  /** HTTP 状态码 */
  httpStatus: number

  constructor(message: string, code: number, httpStatus: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
  }
}

/* ------------------------------ 通用请求方法 ------------------------------ */

/**
 * GET 请求
 * @param url 请求地址
 * @param params 查询参数
 * @param config 额外 Axios 配置
 */
export async function get<T>(
  url: string,
  params?: Record<string, unknown>,
  config?: AxiosRequestConfig
): Promise<T> {
  const res = await http.get<T>(url, { ...config, params })
  return res.data
}

/**
 * POST 请求
 * @param url 请求地址
 * @param data 请求体
 * @param config 额外 Axios 配置
 */
export async function post<T>(
  url: string,
  data?: unknown,
  config?: AxiosRequestConfig
): Promise<T> {
  const res = await http.post<T>(url, data, config)
  return res.data
}

/**
 * PUT 请求
 * @param url 请求地址
 * @param data 请求体
 * @param config 额外 Axios 配置
 */
export async function put<T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> {
  const res = await http.put<T>(url, data, config)
  return res.data
}

/**
 * DELETE 请求
 * @param url 请求地址
 * @param params 查询参数
 * @param config 额外 Axios 配置
 */
export async function del<T>(
  url: string,
  params?: Record<string, unknown>,
  config?: AxiosRequestConfig
): Promise<T> {
  const res = await http.delete<T>(url, { ...config, params })
  return res.data
}

/** 导出原始实例，供特殊场景使用 */
export { http as axiosInstance }
