/**
 * 路由鉴权守卫单元测试（纯函数 authGuard）。
 *
 * 验证鉴权闭环（评估报告 §5.7）：未登录访问受保护页 → 重定向 /login；
 * 已登录访问 /login → 跳回 /dashboard。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/stores/auth', () => ({
  useAuthStore: () => ({ isAuthenticated: false })
}))

const { authGuard } = await import('@/router/index')

function fakeRoute(path: string, fullPath?: string) {
  return { path, fullPath: fullPath ?? path } as never
}

describe('authGuard 鉴权守卫（纯函数）', () => {
  beforeEach(() => vi.clearAllMocks())

  it('未登录访问受保护页面 → 重定向到 /login 并携带 redirect', () => {
    const result = authGuard(fakeRoute('/jobs', '/jobs'), false) as Record<string, unknown>
    expect(result.path).toBe('/login')
    expect((result.query as { redirect: string }).redirect).toBe('/jobs')
  })

  it('未登录访问受保护子路径 → redirect 保留完整路径', () => {
    const result = authGuard(fakeRoute('/jobs/run-1', '/jobs/run-1'), false) as Record<
      string,
      unknown
    >
    expect(result.path).toBe('/login')
    expect((result.query as { redirect: string }).redirect).toBe('/jobs/run-1')
  })

  it('已登录访问受保护页面 → 放行', () => {
    expect(authGuard(fakeRoute('/jobs'), true)).toBe(true)
  })

  it('未登录访问 /login → 放行（白名单）', () => {
    expect(authGuard(fakeRoute('/login'), false)).toBe(true)
  })

  it('已登录访问 /login → 跳回 /dashboard', () => {
    const result = authGuard(fakeRoute('/login'), true) as Record<string, unknown>
    expect(result.path).toBe('/dashboard')
  })
})
