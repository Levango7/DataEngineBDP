import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import { i18n } from './i18n'
import './styles/index.css'

// API 客户端接线：注入 token 获取器、错误提示器、401 跳登录、i18n 错误翻译
import {
  setTokenGetter,
  setErrorNotifier,
  setUnauthorizedHandler,
  setI18nTranslator
} from './api/client'
import { useAuthStore } from './stores/auth'
import { useAppStore } from './stores/app'
import { handleUnauthorized } from './router/unauthorized-redirect'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus)
app.use(i18n)

// 在 pinia 装载后再注入回调，避免循环依赖
setTokenGetter(() => useAuthStore().token)
setErrorNotifier((msg) => useAppStore().showToast(msg))
setUnauthorizedHandler(() => {
  // 401 后应留在触发页的上下文中：跳 /account 会被守卫二次转成
  // /login?redirect=/account，重新登录后落在 /account 而非原页面。
  // 判定与跳转规则见 router/unauthorized-redirect.ts：
  //  - 首屏导航尚未落地时 currentRoute 仍是 START_LOCATION（fullPath 为 '/'），
  //    此时**不跳**，否则会把目标页顶成根路径（台账 #69：auth.spec.ts:133 间歇红的根因）；
  //    路由守卫会用正确的 to.fullPath 自己去跳。
  //  - 已在登录页时**不跳**，登录页 URL 上的 redirect 参数已由守卫写入。
  //  - 其余情况跳 /login?redirect=<当前路由 fullPath>（守卫只接受站内绝对路径）。
  handleUnauthorized(
    router.currentRoute.value,
    () => useAuthStore().logout(),
    (target) => router.push(target)
  )
})
// A2 错误国际化：后端 messageKey → 当前语种文案；词条缺失回退后端原文
setI18nTranslator((key, fallback) => {
  const { t, te } = i18n.global
  return te(key) ? t(key) : fallback
})

app.mount('#app')
