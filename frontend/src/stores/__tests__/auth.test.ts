/**
 * auth store 单元测试
 *
 * 测试认证状态管理：登录、退出、token 持久化
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'

// 使用 vi.hoisted 解决 vi.mock factory 的变量提升问题
const { mockPost } = vi.hoisted(() => ({
  mockPost: vi.fn(() =>
    Promise.resolve({
      token: 'mock-jwt-token',
      expiresIn: 3600,
      user: {
        id: 'u1',
        username: 'admin',
        nickname: '管理员',
        email: 'admin@example.com',
        tenantId: 't1',
        roles: ['admin'],
        status: 'active' as const
      }
    })
  )
}))

vi.mock('@/api/client', () => ({
  post: mockPost
}))

// Mock localStorage
const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: vi.fn((key: string) => store[key] ?? null),
    setItem: vi.fn((key: string, value: string) => {
      store[key] = value
    }),
    removeItem: vi.fn((key: string) => {
      delete store[key]
    }),
    clear: vi.fn(() => {
      store = {}
    })
  }
})()

Object.defineProperty(globalThis, 'localStorage', { value: localStorageMock })

describe('stores/auth.ts', () => {
  let useAuthStore: (typeof import('../auth'))['useAuthStore']

  beforeEach(async () => {
    localStorageMock.clear()
    vi.clearAllMocks()
    setActivePinia(createPinia())
    const mod = await import('../auth')
    useAuthStore = mod.useAuthStore
  })

  it('初始状态：未登录', () => {
    const store = useAuthStore()
    expect(store.token).toBeNull()
    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })

  it('login 应调用 POST /auth/login 并更新状态', async () => {
    const store = useAuthStore()
    await store.login('admin', 'password123')
    expect(mockPost).toHaveBeenCalledWith('/auth/login', {
      username: 'admin',
      password: 'password123',
      captcha: undefined
    })
    expect(store.token).toBe('mock-jwt-token')
    expect(store.isAuthenticated).toBe(true)
    expect(store.user?.username).toBe('admin')
  })

  it('login 应持久化 token 和 user 到 localStorage', async () => {
    const store = useAuthStore()
    await store.login('admin', 'password123')
    expect(localStorageMock.setItem).toHaveBeenCalledWith('sq_token', 'mock-jwt-token')
    expect(localStorageMock.setItem).toHaveBeenCalledWith('sq_user', expect.any(String))
  })

  it('logout 应清除 token 和 user', async () => {
    const store = useAuthStore()
    await store.login('admin', 'password123')
    expect(store.isAuthenticated).toBe(true)
    store.logout()
    expect(store.token).toBeNull()
    expect(store.user).toBeNull()
    expect(store.isAuthenticated).toBe(false)
  })

  it('logout 应移除 localStorage 中的 token 和 user', async () => {
    const store = useAuthStore()
    await store.login('admin', 'password123')
    store.logout()
    expect(localStorageMock.removeItem).toHaveBeenCalledWith('sq_token')
    expect(localStorageMock.removeItem).toHaveBeenCalledWith('sq_user')
  })

  it('setUser 应更新用户信息', async () => {
    const store = useAuthStore()
    await store.login('admin', 'password123')
    const updatedUser = {
      ...store.user!,
      nickname: '超级管理员'
    }
    store.setUser(updatedUser)
    expect(store.user?.nickname).toBe('超级管理员')
  })

  it('login 支持验证码参数', async () => {
    const store = useAuthStore()
    await store.login('admin', 'password123', 'abc123')
    expect(mockPost).toHaveBeenCalledWith('/auth/login', {
      username: 'admin',
      password: 'password123',
      captcha: 'abc123'
    })
  })
})
