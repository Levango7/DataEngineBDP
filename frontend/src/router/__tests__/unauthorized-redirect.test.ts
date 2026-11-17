/**
 * 401 跳登录目标判定 + 处理编排的单元测试（纯函数）。
 *
 * 背景：台账 #69 —— `tests/e2e/auth.spec.ts:133`「登录后按 redirect 参数跳转回原页面」
 * 间歇性红。CI 现场（nightly-e2e run 43730063215 / job 132860339389，2026-11-15T15:42:01Z）
 * 失败断言原文：
 *   Error: expect(received).toContain(expected)
 *   Expected substring: "redirect=/projects"
 *   Received string:    "#/login?redirect=/"
 *
 * 根因：401 处理器在**首屏导航尚未落地**时读 `router.currentRoute.value.fullPath`，
 * 拿到的是 vue-router START_LOCATION 的 `'/'`，把目标页顶成根路径。
 * 本测试把该判定固化为纯函数，保证「不跳」的三种边界不被改回去。
 */
import { describe, it, expect, vi } from 'vitest'
import {
  resolveUnauthorizedRedirect,
  handleUnauthorized,
  LOGIN_PATH
} from '@/router/unauthorized-redirect'

/** 构造当前路由快照；matched 缺省给非空（= 已解析完成的路由） */
function snapshot(path: string, fullPath?: string, matched: readonly unknown[] = [{}]) {
  return { path, fullPath: fullPath ?? path, matched }
}

describe('resolveUnauthorizedRedirect（401 跳登录目标判定，纯函数）', () => {
  it('停在业务页 → 跳 /login，并把当前页作为 redirect', () => {
    expect(resolveUnauthorizedRedirect(snapshot('/projects'))).toEqual({
      path: LOGIN_PATH,
      query: { redirect: '/projects' }
    })
  })

  it('业务页带查询串 → redirect 保留完整 fullPath', () => {
    expect(resolveUnauthorizedRedirect(snapshot('/jobs', '/jobs?page=2'))).toEqual({
      path: LOGIN_PATH,
      query: { redirect: '/jobs?page=2' }
    })
  })

  // 竞态核心：首屏导航未落地时 currentRoute 是 START_LOCATION（matched 为空、fullPath 为 '/'）。
  // 若此时跳 /login?redirect=/ ，用户原本要去的 /projects 被顶掉 ⇒ 必须不跳，交给守卫跳。
  it('首次导航尚未落地（START_LOCATION，matched 为空）→ 不跳转', () => {
    expect(resolveUnauthorizedRedirect(snapshot('/', '/', []))).toBeNull()
  })

  it('已在登录页 → 不跳转，保留守卫已写入的 redirect 参数', () => {
    expect(resolveUnauthorizedRedirect(snapshot('/login', '/login?redirect=%2Fprojects'))).toBeNull()
  })
})

describe('handleUnauthorized（401 处理编排）', () => {
  it('业务页触发 401 → 清登录态并跳 /login?redirect=<当前页>', () => {
    const logout = vi.fn()
    const push = vi.fn()
    handleUnauthorized(snapshot('/projects'), logout, push)
    expect(logout).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledTimes(1)
    expect(push).toHaveBeenCalledWith({ path: LOGIN_PATH, query: { redirect: '/projects' } })
  })

  // 回归守卫：START_LOCATION 场景下**清登录态照做**，但**不得跳转**，
  // 否则 redirect 会被写成 '/'，auth.spec.ts:138 随机红。
  it('首次导航尚未落地 → 清登录态但不跳转（不把目标页顶成 /）', () => {
    const logout = vi.fn()
    const push = vi.fn()
    handleUnauthorized(snapshot('/', '/', []), logout, push)
    expect(logout).toHaveBeenCalledTimes(1)
    expect(push).not.toHaveBeenCalled()
  })

  it('已在登录页 → 清登录态但不跳转（不覆盖守卫写入的 redirect）', () => {
    const logout = vi.fn()
    const push = vi.fn()
    handleUnauthorized(snapshot('/login', '/login?redirect=%2Fprojects'), logout, push)
    expect(logout).toHaveBeenCalledTimes(1)
    expect(push).not.toHaveBeenCalled()
  })
})
