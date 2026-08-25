import axios from 'axios'

const client = axios.create({
  baseURL: '/api/v1',
  timeout: 10000,
})

// 注入 JWT token（与平台其他组件统一）。
client.interceptors.request.use((config) => {
  const token = sessionStorage.getItem('sq_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

export default client