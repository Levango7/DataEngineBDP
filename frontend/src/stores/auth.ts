/**
 * 认证状态 store
 *
 * 职责：
 * - 持久化 token / user
 * - 提供 login / logout 动作
 * - 通过 setTokenGetter 注入到 HTTP 客户端，使请求自动携带 Bearer token
 */
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { post } from '@/api/client'
import type { LoginParams, LoginResult, User } from '@/api/types'
import { i18n } from '@/i18n'

/** store 内使用的 i18n 翻译函数（store 不在组件上下文内，不能用 useI18n()） */
const t = i18n.global.t

/** sessionStorage 持久化键（token 用 sessionStorage 降低 XSS 风险） */
const TOKEN_KEY = 'sq_token'
/** localStorage 持久化键（仅存非敏感用户信息） */
const USER_KEY = 'sq_user'

/** 读取 session 存储的 token */
function loadToken(): string | null {
  return sessionStorage.getItem(TOKEN_KEY)
}

/** 读取本地存储的用户信息 */
function loadUser(): User | null {
  const raw = localStorage.getItem(USER_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as User
  } catch {
    return null
  }
}

export const useAuthStore = defineStore('auth', () => {
  // 状态
  const token = ref<string | null>(loadToken())
  const user = ref<User | null>(loadUser())

  // 是否已登录
  const isAuthenticated = computed(() => !!token.value)

  /**
   * 登录
   * @param username 用户名
   * @param password 密码
   * @param captcha 验证码（可选）
   */
  async function login(username: string, password: string, captcha?: string): Promise<LoginResult> {
    const params: LoginParams = { username, password, captcha }
    const result = await post<LoginResult>('/auth/login', params)

    token.value = result.token
    user.value = result.user
    // 安全：token 存入 sessionStorage（关闭标签页自动清除），降低 XSS 持久化风险。
    // 如需持久登录可改用 localStorage，但需配合 httpOnly cookie 方案。
    sessionStorage.setItem(TOKEN_KEY, result.token)
    localStorage.setItem(USER_KEY, JSON.stringify(result.user))

    return result
  }

  /** 退出登录 */
  function logout(): void {
    token.value = null
    user.value = null
    sessionStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  }

  /** 刷新用户信息（外部调用） */
  function setUser(u: User): void {
    user.value = u
    localStorage.setItem(USER_KEY, JSON.stringify(u))
  }

  /** 启动时：有 token 但无用户信息，尝试从后端拉取 */
  async function hydrateUser(): Promise<void> {
    if (token.value && !user.value) {
      // 外层已确保 !user.value，无需再判断
      try {
        const u = await post<User>('/auth/me')
        user.value = u
        localStorage.setItem(USER_KEY, JSON.stringify(u))
      } catch {
        // 后端无 /auth/me 或失败：开发环境下兜底造 mock 用户，生产环境保持仅有 token
        if (import.meta.env.DEV) {
          const mockUser: User = {
            id: 'local-admin',
            username: 'admin',
            nickname: t('app.devMockNickname'),
            email: 'admin@example.com',
            tenantId: 'platform-admin',
            roles: ['admin'],
            status: 'active'
          }
          user.value = mockUser
          localStorage.setItem(USER_KEY, JSON.stringify(mockUser))
        }
      }
    }
  }

  // 立即执行一次补水
  hydrateUser()

  return {
    token,
    user,
    isAuthenticated,
    login,
    logout,
    setUser,
    hydrateUser
  }
})
