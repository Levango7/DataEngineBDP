/**
 * 通用 API 调用组合式函数
 *
 * 用法：
 * ```ts
 * const { data, loading, error, execute } = useApi(
 *   () => clusterApi.getClusterOverview()
 * )
 * onMounted(execute)
 * ```
 *
 * 特性：
 * - 自动维护 loading / error / data 三态
 * - execute 支持手动触发与参数覆盖
 * - 可配置 immediate 立即执行
 * - 可配置 onSuccess / onError 回调
 */
import { ref, type Ref } from 'vue'
import { ApiError } from '@/api/client'

/** useApi 配置项 */
export interface UseApiOptions<T> {
  /** 是否立即执行（默认 false） */
  immediate?: boolean
  /** 初始数据 */
  initialData?: T
  /** 成功回调 */
  onSuccess?: (data: T) => void
  /** 失败回调 */
  onError?: (err: ApiError | Error) => void
}

/** useApi 返回值 */
export interface UseApiReturn<T, Args extends unknown[]> {
  /** 响应数据 */
  data: Ref<T | null>
  /** 加载状态 */
  loading: Ref<boolean>
  /** 错误信息 */
  error: Ref<ApiError | Error | null>
  /** 是否已请求过至少一次 */
  hasLoaded: Ref<boolean>
  /** 触发请求 */
  execute: (...args: Args) => Promise<T | null>
  /** 重置为初始状态 */
  reset: () => void
}

/**
 * 通用 API 调用包装
 * @param factory 返回 Promise 的工厂函数，接收 execute 传入的参数
 * @param options 配置项
 */
export function useApi<T, Args extends unknown[] = []>(
  factory: (...args: Args) => Promise<T>,
  options: UseApiOptions<T> = {}
): UseApiReturn<T, Args> {
  const { immediate = false, initialData = null, onSuccess, onError } = options

  const data = ref<T | null>(initialData) as Ref<T | null>
  const loading = ref(false)
  const error = ref<ApiError | Error | null>(null)
  const hasLoaded = ref(false)

  /** 触发请求 */
  async function execute(...args: Args): Promise<T | null> {
    loading.value = true
    error.value = null
    try {
      const result = await factory(...args)
      data.value = result
      hasLoaded.value = true
      onSuccess?.(result)
      return result
    } catch (e) {
      const err = e instanceof Error ? e : new Error(String(e))
      error.value = err
      onError?.(err)
      return null
    } finally {
      loading.value = false
    }
  }

  /** 重置状态 */
  function reset(): void {
    data.value = initialData
    loading.value = false
    error.value = null
    hasLoaded.value = false
  }

  // 立即执行
  if (immediate) {
    void execute(...([] as unknown as Args))
  }

  return { data, loading, error, hasLoaded, execute, reset }
}
