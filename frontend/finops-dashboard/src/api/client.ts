/**
 * FinOps 看板 API 客户端
 *
 * baseURL 从环境变量 VITE_API_BASE 读取，默认 /api/v1
 * 自动携带 Bearer token（从 localStorage 读取）
 */
import axios, { type AxiosInstance } from 'axios'

const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE || '/api/v1',
  timeout: 30000,
  headers: {
    'Content-Type': 'application/json'
  }
})

// 请求拦截器：自动携带 Bearer token
http.interceptors.request.use((config) => {
  const token = localStorage.getItem('finops_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 响应拦截器：统一错误处理
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const status = error?.response?.status ?? 0
    if (status === 401) {
      console.error('未授权，请检查 token')
    }
    return Promise.reject(error)
  }
)

export { http }