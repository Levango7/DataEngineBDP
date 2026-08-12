import axios from 'axios'

// HTTP 客户端封装
const http = axios.create({
  baseURL: '/api/v1',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json',
  },
})

// 请求拦截器
http.interceptors.request.use(
  (config) => {
    // 注入租户 ID（实际从 Keycloak token 提取）
    const tenantId = localStorage.getItem('tenantId') || 'tenant-default'
    config.headers['X-Tenant-Id'] = tenantId
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器
http.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response) {
      const { status, data } = error.response
      const message = data?.message || data?.error || '请求失败'
      console.error(`[API ${status}] ${message}`)
      return Promise.reject(new Error(message))
    }
    return Promise.reject(error)
  }
)

export default http