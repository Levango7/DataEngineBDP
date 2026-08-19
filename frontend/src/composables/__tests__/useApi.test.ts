/**
 * useApi 组合式函数单元测试
 *
 * 测试通用 API 调用包装：
 * - 初始状态（data / loading / error / hasLoaded）
 * - execute 成功/失败路径
 * - immediate 立即执行
 * - initialData 初始数据
 * - reset 重置状态
 * - onSuccess / onError 回调
 * - execute 参数透传
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'

// Mock @/api/client：useApi 仅在类型层面使用 ApiError，运行时不需要真实实现
vi.mock('@/api/client', () => ({
  ApiError: class ApiError extends Error {
    code: number
    httpStatus: number
    constructor(message: string, code: number, httpStatus: number) {
      super(message)
      this.name = 'ApiError'
      this.code = code
      this.httpStatus = httpStatus
    }
  }
}))

import { useApi } from '../useApi'

describe('composables/useApi.ts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('初始状态', () => {
    it('默认初始状态：data=null, loading=false, error=null, hasLoaded=false', () => {
      const { data, loading, error, hasLoaded } = useApi(() => Promise.resolve(1))
      expect(data.value).toBeNull()
      expect(loading.value).toBe(false)
      expect(error.value).toBeNull()
      expect(hasLoaded.value).toBe(false)
    })

    it('initialData 应作为 data 的初始值', () => {
      const { data } = useApi(() => Promise.resolve(1), { initialData: 42 })
      expect(data.value).toBe(42)
    })
  })

  describe('execute 成功路径', () => {
    it('应更新 data 并返回结果', async () => {
      const { data, execute } = useApi(() => Promise.resolve({ id: 1, name: 'test' }))
      const result = await execute()
      expect(result).toEqual({ id: 1, name: 'test' })
      expect(data.value).toEqual({ id: 1, name: 'test' })
    })

    it('应将 hasLoaded 置为 true', async () => {
      const { hasLoaded, execute } = useApi(() => Promise.resolve('ok'))
      expect(hasLoaded.value).toBe(false)
      await execute()
      expect(hasLoaded.value).toBe(true)
    })

    it('执行完成后 loading 应恢复为 false', async () => {
      const { loading, execute } = useApi(() => Promise.resolve('ok'))
      await execute()
      expect(loading.value).toBe(false)
    })

    it('应调用 onSuccess 回调', async () => {
      const onSuccess = vi.fn()
      const { execute } = useApi(() => Promise.resolve('payload'), { onSuccess })
      await execute()
      expect(onSuccess).toHaveBeenCalledWith('payload')
    })

    it('成功后 error 应为 null', async () => {
      const { error, execute } = useApi(() => Promise.resolve('ok'))
      await execute()
      expect(error.value).toBeNull()
    })
  })

  describe('execute 失败路径', () => {
    it('应捕获错误并设置 error', async () => {
      const { error, execute } = useApi(() => Promise.reject(new Error('boom')))
      const result = await execute()
      expect(result).toBeNull()
      expect(error.value).toBeInstanceOf(Error)
      expect(error.value?.message).toBe('boom')
    })

    it('失败时 hasLoaded 应保持 false', async () => {
      const { hasLoaded, execute } = useApi(() => Promise.reject(new Error('fail')))
      await execute()
      expect(hasLoaded.value).toBe(false)
    })

    it('失败后 loading 应恢复为 false', async () => {
      const { loading, execute } = useApi(() => Promise.reject(new Error('fail')))
      await execute()
      expect(loading.value).toBe(false)
    })

    it('应调用 onError 回调', async () => {
      const onError = vi.fn()
      const { execute } = useApi(() => Promise.reject(new Error('boom')), { onError })
      await execute()
      expect(onError).toHaveBeenCalledTimes(1)
      expect(onError.mock.calls[0][0]).toBeInstanceOf(Error)
      expect(onError.mock.calls[0][0].message).toBe('boom')
    })

    it('非 Error 对象应包装为 Error', async () => {
      const { error, execute } = useApi(() => Promise.reject('string error'))
      await execute()
      expect(error.value).toBeInstanceOf(Error)
      expect(error.value?.message).toBe('string error')
    })
  })

  describe('loading 时序', () => {
    it('执行期间 loading 应为 true', async () => {
      let resolveFn!: (v: number) => void
      const factory = () => new Promise<number>((resolve) => { resolveFn = resolve })
      const { loading, execute } = useApi(factory)

      const promise = execute()
      // factory 已被调用，Promise 尚未 resolve，loading 应为 true
      expect(loading.value).toBe(true)
      resolveFn(99)
      await promise
      expect(loading.value).toBe(false)
    })
  })

  describe('execute 参数透传', () => {
    it('应将参数传给 factory', async () => {
      const factory = vi.fn((a: number, b: string) => Promise.resolve(`${a}-${b}`))
      const { execute } = useApi(factory)
      const result = await execute(1, 'x')
      expect(factory).toHaveBeenCalledWith(1, 'x')
      expect(result).toBe('1-x')
    })
  })

  describe('immediate', () => {
    it('immediate=true 应在创建时立即执行', async () => {
      const factory = vi.fn(() => Promise.resolve('immediate-result'))
      const { data, hasLoaded } = useApi(factory, { immediate: true })
      // 等待微任务执行完毕
      await nextTick()
      await vi.waitFor(() => expect(hasLoaded.value).toBe(true))
      expect(factory).toHaveBeenCalledTimes(1)
      expect(data.value).toBe('immediate-result')
    })

    it('immediate=false（默认）不应立即执行', () => {
      const factory = vi.fn(() => Promise.resolve('lazy'))
      useApi(factory)
      expect(factory).not.toHaveBeenCalled()
    })
  })

  describe('reset', () => {
    it('应将状态重置为初始值', async () => {
      const { data, loading, error, hasLoaded, execute, reset } = useApi(
        () => Promise.resolve('loaded'),
        { initialData: 'init' }
      )
      await execute()
      expect(data.value).toBe('loaded')
      expect(hasLoaded.value).toBe(true)

      reset()
      expect(data.value).toBe('init')
      expect(loading.value).toBe(false)
      expect(error.value).toBeNull()
      expect(hasLoaded.value).toBe(false)
    })

    it('reset 后 error 也应清空', async () => {
      const { error, execute, reset } = useApi(() => Promise.reject(new Error('boom')))
      await execute()
      expect(error.value).not.toBeNull()
      reset()
      expect(error.value).toBeNull()
    })
  })

  describe('连续调用', () => {
    it('第二次 execute 成功应覆盖 data', async () => {
      const { data, execute } = useApi((v: number) => Promise.resolve(v * 2))
      await execute(1)
      expect(data.value).toBe(2)
      await execute(5)
      expect(data.value).toBe(10)
    })

    it('失败后再 execute 成功应清空 error', async () => {
      let fail = true
      const { error, execute } = useApi(() =>
        fail ? Promise.reject(new Error('fail')) : Promise.resolve('ok')
      )
      await execute()
      expect(error.value).not.toBeNull()
      fail = false
      await execute()
      expect(error.value).toBeNull()
    })
  })
})