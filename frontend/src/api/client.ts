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

/** 全局错误提示回调，由外部注入（避免硬耦合 store） */
let errorNotifier: ((msg: string) => void) | null = null

/** 401 跳登录页回调，由外部注入 */
let unauthorizedHandler: (() => void) | null = null

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

/** 创建 Axios 实例 */
const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api/v1',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

/* ------------------------------ 请求拦截器 ------------------------------ */
http.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 自动携带 Bearer token
    const token = tokenGetter?.() ?? null
    if (token) {
      config.headers = config.headers ?? {}
      config.headers.Authorization = `Bearer ${token}`
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
        const msg = body.message || '业务处理失败'
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
    let msg = '请求失败，请稍后重试'

    if (error?.response?.data?.error) {
      // 服务端显式错误码（如跨源查询 FAILED 的 error 字段）优先展示
      msg = String(error.response.data.error)
    } else if (status === 401) {
      msg = '登录已过期，请重新登录'
      if (unauthorizedInFlight) {
        return Promise.reject(new ApiError(msg, status, status))
      }
      handleUnauthorized()
    } else if (status === 403) {
      msg = '无权限访问该资源'
    } else if (status === 500) {
      msg = '服务器内部错误，请联系管理员'
    } else if (status === 502) {
      msg = '上游服务暂时不可用，请稍后重试'
    } else if (status === 504) {
      msg = '查询超时，请检查 SQL 或稍后重试'
    } else if (status === 413) {
      msg = '结果集过大，请缩小查询范围'
    } else if (status === 404) {
      msg = '请求的资源不存在'
    } else if (status === 0) {
      msg = '网络异常，请检查网络连接'
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
