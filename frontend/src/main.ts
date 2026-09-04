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
  useAuthStore().logout()
  router.push('/account')
})
// A2 错误国际化：后端 messageKey → 当前语种文案；词条缺失回退后端原文
setI18nTranslator((key, fallback) => {
  const { t, te } = i18n.global
  return te(key) ? t(key) : fallback
})

app.mount('#app')
