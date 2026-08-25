/**
 * client.ts 单元测试
 *
 * 测试 HTTP 客户端封装：拦截器、错误处理、通用请求方法、ApiError
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

// Mock axios
const mockGet = vi.fn((url: string, _config?: any) =>
  Promise.resolve({ data: { code: 0, message: 'ok', data: { url } } })
)
const mockPost = vi.fn((url: string, data?: any) =>
  Promise.resolve({ data: { code: 0, message: 'ok', data: { url, body: data } } })
)
const mockPut = vi.fn((url: string, data?: any) =>
  Promise.resolve({ data: { code: 0, message: 'ok', data: { url, body: data } } })
)
const mockDelete = vi.fn((url: string, _config?: any) =>
  Promise.resolve({ data: { code: 0, message: 'ok', data: { url } } })
)

let requestInterceptorFn: Function | null = null
let responseInterceptorSuccessFn: Function | null = null
let responseInterceptorErrorFn: Function | null = null

vi.mock('axios', () => ({
  default: {
    create: vi.fn(() => ({
      get: mockGet,
      post: mockPost,
      put: mockPut,
      delete: mockDelete,
      interceptors: {
        request: {
          use(fn: Function, _errFn: Function) {
            requestInterceptorFn = fn
          }
        },
        response: {
          use(successFn: Function, errorFn: Function) {
            responseInterceptorSuccessFn = successFn
            responseInterceptorErrorFn = errorFn
          }
        }
      }
    }))
  }
}))

// 只导入一次模块，避免 resetModules 导致拦截器丢失
let clientModule: typeof import('@/api/client') | null = null

async function getClient() {
  if (!clientModule) {
    clientModule = await import('@/api/client')
  }
  return clientModule
}

describe('api/client.ts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('模块导出', () => {
    it('应导出 setErrorNotifier 函数', async () => {
      const { setErrorNotifier } = await getClient()
      expect(typeof setErrorNotifier).toBe('function')
    })

    it('应导出 setUnauthorizedHandler 函数', async () => {
      const { setUnauthorizedHandler } = await getClient()
      expect(typeof setUnauthorizedHandler).toBe('function')
    })

    it('应导出 setTokenGetter 函数', async () => {
      const { setTokenGetter } = await getClient()
      expect(typeof setTokenGetter).toBe('function')
    })

    it('应导出 get / post / put / del 函数', async () => {
      const mod = await getClient()
      expect(typeof mod.get).toBe('function')
      expect(typeof mod.post).toBe('function')
      expect(typeof mod.put).toBe('function')
      expect(typeof mod.del).toBe('function')
    })

    it('应导出 axiosInstance', async () => {
      const mod = await getClient()
      expect(mod.axiosInstance).toBeDefined()
    })
  })

  describe('ApiError', () => {
    it('应包含 code 和 httpStatus 属性', async () => {
      const { ApiError } = await getClient()
      const err = new ApiError('test error', 1001, 400)
      expect(err.message).toBe('test error')
      expect(err.code).toBe(1001)
      expect(err.httpStatus).toBe(400)
      expect(err.name).toBe('ApiError')
      expect(err instanceof Error).toBe(true)
    })
  })

  describe('注入回调', () => {
    it('setErrorNotifier 应接受回调函数', async () => {
      const { setErrorNotifier } = await getClient()
      const notifier = vi.fn()
      expect(() => setErrorNotifier(notifier)).not.toThrow()
    })

    it('setTokenGetter 应接受回调函数', async () => {
      const { setTokenGetter } = await getClient()
      const getter = vi.fn(() => 'test-token')
      expect(() => setTokenGetter(getter)).not.toThrow()
    })

    it('setUnauthorizedHandler 应接受回调函数', async () => {
      const { setUnauthorizedHandler } = await getClient()
      const handler = vi.fn()
      expect(() => setUnauthorizedHandler(handler)).not.toThrow()
    })
  })

  describe('请求拦截器', () => {
    it('应注册请求拦截器', async () => {
      await getClient()
      expect(requestInterceptorFn).not.toBeNull()
    })

    it('有 token 时应添加 Authorization 头', async () => {
      const { setTokenGetter } = await getClient()
      setTokenGetter(() => 'my-jwt-token')
      const config = { headers: {} } as any
      const result = requestInterceptorFn!(config)
      expect(result.headers.Authorization).toBe('Bearer my-jwt-token')
    })

    it('无 token 时不应添加 Authorization 头', async () => {
      const { setTokenGetter } = await getClient()
      setTokenGetter(() => null)
      const config = { headers: {} } as any
      const result = requestInterceptorFn!(config)
      expect(result.headers.Authorization).toBeUndefined()
    })
  })

  describe('响应拦截器', () => {
    it('应注册响应拦截器', async () => {
      await getClient()
      expect(responseInterceptorSuccessFn).not.toBeNull()
      expect(responseInterceptorErrorFn).not.toBeNull()
    })

    it('业务码为 0 时应拆包返回 data', async () => {
      await getClient()
      const response = {
        data: { code: 0, message: 'ok', data: { id: '1', name: 'test' } }
      }
      const result = responseInterceptorSuccessFn!(response)
      expect(result.data).toEqual({ id: '1', name: 'test' })
    })

    it('业务码非 0 时应 reject', async () => {
      const { setErrorNotifier } = await getClient()
      const notifier = vi.fn()
      setErrorNotifier(notifier)
      const response = {
        data: { code: 1001, message: '参数错误', data: null }
      }
      await expect(responseInterceptorSuccessFn!(response)).rejects.toThrow('参数错误')
      expect(notifier).toHaveBeenCalledWith('参数错误')
    })

    it('403 应提示无权限', async () => {
      const { setErrorNotifier } = await getClient()
      const notifier = vi.fn()
      setErrorNotifier(notifier)
      const error = { response: { status: 403, data: {} } }
      await expect(responseInterceptorErrorFn!(error)).rejects.toThrow()
      expect(notifier).toHaveBeenCalledWith('无权限访问该资源')
    })

    it('500 应提示服务器错误', async () => {
      const { setErrorNotifier } = await getClient()
      const notifier = vi.fn()
      setErrorNotifier(notifier)
      const error = { response: { status: 500, data: {} } }
      await expect(responseInterceptorErrorFn!(error)).rejects.toThrow()
      expect(notifier).toHaveBeenCalledWith('服务器内部错误，请联系管理员')
    })

    it('404 应提示资源不存在', async () => {
      const { setErrorNotifier } = await getClient()
      const notifier = vi.fn()
      setErrorNotifier(notifier)
      const error = { response: { status: 404, data: {} } }
      await expect(responseInterceptorErrorFn!(error)).rejects.toThrow()
      expect(notifier).toHaveBeenCalledWith('请求的资源不存在')
    })

    it('网络异常（status=0）应提示网络问题', async () => {
      const { setErrorNotifier } = await getClient()
      const notifier = vi.fn()
      setErrorNotifier(notifier)
      const error = { response: null }
      await expect(responseInterceptorErrorFn!(error)).rejects.toThrow()
      expect(notifier).toHaveBeenCalledWith('网络异常，请检查网络连接')
    })

    it('响应中有 message 应优先使用', async () => {
      const { setErrorNotifier } = await getClient()
      const notifier = vi.fn()
      setErrorNotifier(notifier)
      const error = { response: { status: 422, data: { message: '验证失败：名称重复' } } }
      await expect(responseInterceptorErrorFn!(error)).rejects.toThrow('验证失败：名称重复')
      expect(notifier).toHaveBeenCalledWith('验证失败：名称重复')
    })

    it('并发 3 个 401 应仅触发一次 unauthorizedHandler 和一次提示', async () => {
      vi.useFakeTimers()
      try {
        const { setErrorNotifier, setUnauthorizedHandler } = await getClient()
        const notifier = vi.fn()
        const handler = vi.fn()
        setErrorNotifier(notifier)
        setUnauthorizedHandler(handler)

        const error = { response: { status: 401, data: {} } }
        const results = [
          responseInterceptorErrorFn!(error),
          responseInterceptorErrorFn!(error),
          responseInterceptorErrorFn!(error)
        ]
        for (const r of results) {
          await expect(r).rejects.toThrow('登录已过期，请重新登录')
        }

        expect(handler).toHaveBeenCalledTimes(1)
        expect(notifier).toHaveBeenCalledTimes(1)
        expect(notifier).toHaveBeenCalledWith('登录已过期，请重新登录')

        vi.advanceTimersByTime(1000)
        expect(handler).toHaveBeenCalledTimes(1)
        expect(notifier).toHaveBeenCalledTimes(1)
      } finally {
        vi.useRealTimers()
      }
    })

    it('401 单飞窗口过后应可再次触发 unauthorizedHandler', async () => {
      vi.useFakeTimers()
      try {
        const { setErrorNotifier, setUnauthorizedHandler } = await getClient()
        const notifier = vi.fn()
        const handler = vi.fn()
        setErrorNotifier(notifier)
        setUnauthorizedHandler(handler)

        const error = { response: { status: 401, data: {} } }
        await expect(responseInterceptorErrorFn!(error)).rejects.toThrow()
        expect(handler).toHaveBeenCalledTimes(1)

        vi.advanceTimersByTime(1000)

        await expect(responseInterceptorErrorFn!(error)).rejects.toThrow()
        expect(handler).toHaveBeenCalledTimes(2)
        expect(notifier).toHaveBeenCalledTimes(2)

        vi.advanceTimersByTime(1000)
      } finally {
        vi.useRealTimers()
      }
    })

    it('401 应触发 unauthorizedHandler', async () => {
      const { setErrorNotifier, setUnauthorizedHandler } = await getClient()
      const notifier = vi.fn()
      const handler = vi.fn()
      setErrorNotifier(notifier)
      setUnauthorizedHandler(handler)
      const error = { response: { status: 401, data: {} } }
      await expect(responseInterceptorErrorFn!(error)).rejects.toThrow()
      expect(handler).toHaveBeenCalled()
    })
  })

  describe('通用请求方法', () => {
    it('get 应调用 http.get 并传参', async () => {
      const { get, setTokenGetter } = await getClient()
      setTokenGetter(() => null)
      await get('/test')
      expect(mockGet).toHaveBeenCalledWith('/test', expect.objectContaining({ params: undefined }))
    })

    it('get 应支持 params 参数', async () => {
      const { get, setTokenGetter } = await getClient()
      setTokenGetter(() => null)
      await get('/test', { page: 1 })
      expect(mockGet).toHaveBeenCalledWith(
        '/test',
        expect.objectContaining({ params: { page: 1 } })
      )
    })

    it('post 应调用 http.post 并传请求体', async () => {
      const { post, setTokenGetter } = await getClient()
      setTokenGetter(() => null)
      await post('/test', { name: 'hello' })
      expect(mockPost).toHaveBeenCalledWith('/test', { name: 'hello' }, undefined)
    })

    it('put 应调用 http.put 并传请求体', async () => {
      const { put, setTokenGetter } = await getClient()
      setTokenGetter(() => null)
      await put('/test/1', { name: 'updated' })
      expect(mockPut).toHaveBeenCalledWith('/test/1', { name: 'updated' }, undefined)
    })

    it('del 应调用 http.delete 并传参', async () => {
      const { del, setTokenGetter } = await getClient()
      setTokenGetter(() => null)
      await del('/test/1')
      expect(mockDelete).toHaveBeenCalledWith(
        '/test/1',
        expect.objectContaining({ params: undefined })
      )
    })
  })
})
