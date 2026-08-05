import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import router from './router'
import './styles/main.css'

// API 客户端接线：注入 token 获取器、错误提示器、401 跳登录
import { setTokenGetter, setErrorNotifier, setUnauthorizedHandler } from './api/client'
import { useAuthStore } from './stores/auth'
import { useAppStore } from './stores/app'

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus)

// 在 pinia 装载后再注入回调，避免循环依赖
setTokenGetter(() => useAuthStore().token)
setErrorNotifier((msg) => useAppStore().showToast(msg))
setUnauthorizedHandler(() => {
  useAuthStore().logout()
  router.push('/account')
})

app.mount('#app')
