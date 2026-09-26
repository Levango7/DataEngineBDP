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

function fakeRoute(path: string, fullPath?: string, meta: Record<string, unknown> = {}) {
  return { path, fullPath: fullPath ?? path, meta } as never
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

/**
 * 角色守卫：把后端已声明的 @PreAuthorize 限制在进页面前拦掉。
 * 后端 RegistrationController 用的是 hasAnyRole('SUPER_ADMIN','TENANT_ADMIN')，
 * /approvals 路由据此声明 requiresRole —— 两边必须一致。
 */
describe('authGuard 角色守卫', () => {
  const adminMeta = { requiresRole: ['SUPER_ADMIN', 'TENANT_ADMIN'] }

  it('角色命中 → 放行', () => {
    expect(
      authGuard(fakeRoute('/approvals', '/approvals', adminMeta), true, null, ['SUPER_ADMIN'])
    ).toBe(true)
  })

  it('只读角色访问受限页 → 回 /dashboard 并带 denied 标记', () => {
    const result = authGuard(fakeRoute('/approvals', '/approvals', adminMeta), true, null, [
      'USER'
    ]) as Record<string, unknown>
    expect(result.path).toBe('/dashboard')
    expect((result.query as { denied: string }).denied).toBe('/approvals')
  })

  it('无任何角色（token 有效但缺角色声明）→ 同样拦截', () => {
    const result = authGuard(
      fakeRoute('/approvals', '/approvals', adminMeta),
      true,
      null,
      []
    ) as Record<string, unknown>
    expect(result.path).toBe('/dashboard')
  })

  it('未声明 requiresRole 的路由不受角色影响（避免前端比后端更严）', () => {
    expect(authGuard(fakeRoute('/jobs'), true, null, [])).toBe(true)
  })
})
