/**
 * 401 跳登录的**目标判定 + 处理编排**（纯函数，可单测）。
 *
 * ## 为什么需要这个模块
 *
 * 401 处理器原先无条件执行：
 *
 * ```ts
 * router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
 * ```
 *
 * 问题出在**应用启动瞬间**：`router.currentRoute.value` 还是 vue-router 的
 * START_LOCATION（`path`/`fullPath` 均为 `'/'`，`matched` 为空数组），因为
 * 首屏导航（到 `/projects` 之类）还没落地。
 *
 * 此时若某个启动期请求（如 `TopBar` 的 `fetchTenantInfo()`）先返回 401，
 * 处理器就会把 URL 写成 `#/login?redirect=/` —— 把用户原本要去的 `/projects`
 * 顶成根路径，登录成功后只能兜底回 dashboard。
 *
 * CI 现场实证（`nightly-e2e` run 43730063215 / job 132860339389，
 * 2026-11-15T15:42:01Z）：`tests/e2e/auth.spec.ts:138` 断言
 * `Expected substring: "redirect=/projects"` / `Received string: "#/login?redirect=/"`。
 *
 * ## 判定规则（三条都是「不跳转」，而不是「跳到别处」）
 *
 * 1. **首次导航尚未落地**（`current.matched` 为空 ⇒ START_LOCATION）：**不跳转**。
 *    路由守卫会用正确的 `to.fullPath` 自己去跳 `/login?redirect=<目标页>`。
 * 2. **已在登录页**（`current.path === '/login'`）：**不跳转**。
 *    登录页 URL 上的 `redirect` 参数已由守卫写入，覆盖它等于丢掉目标页。
 * 3. 其余情况（停在某个已解析的业务页）：沿用原行为，跳 `/login?redirect=<当前 fullPath>`。
 */

export const LOGIN_PATH = '/login'

/** 当前路由的最小快照（只取本模块需要的三个字段，便于单测构造） */
export interface CurrentRouteSnapshot {
  path: string
  fullPath: string
  matched: readonly unknown[]
}

/** 401 后要跳转的目标 */
export interface LoginRedirectTarget {
  path: string
  query: { redirect: string }
}

/**
 * 判定 401 之后该不该跳登录页、跳到哪里。
 *
 * @param current 触发 401 时的当前路由快照
 * @returns 跳转目标；**不跳**时返回 `null`
 */
export function resolveUnauthorizedRedirect(
  current: CurrentRouteSnapshot
): LoginRedirectTarget | null {
  // ① 首次导航尚未落地：currentRoute 还是 START_LOCATION（matched 为空）。
  //    此时任何 fullPath 都是 '/'，跳过去会把目标页顶掉 ⇒ 交给守卫带正确的 to.fullPath 跳。
  if (!current.matched || current.matched.length === 0) {
    return null
  }
  // ② 已在登录页：守卫写入的 redirect 参数不能被覆盖（否则目标页丢失）
  if (current.path === LOGIN_PATH) {
    return null
  }
  // ③ 正常场景：401 后回到触发页的上下文（守卫只接受站内绝对路径）
  return { path: LOGIN_PATH, query: { redirect: current.fullPath } }
}

/**
 * 401 处理编排：清理登录态 + 按判定结果跳转。
 *
 * @param current 触发 401 时的当前路由快照
 * @param logout 清理登录态（清 token / 用户信息）
 * @param push   跳转函数
 */
export function handleUnauthorized(
  current: CurrentRouteSnapshot,
  logout: () => void,
  push: (target: LoginRedirectTarget) => void
): void {
  logout()
  const target = resolveUnauthorizedRedirect(current)
  if (target) {
    push(target)
  }
}
