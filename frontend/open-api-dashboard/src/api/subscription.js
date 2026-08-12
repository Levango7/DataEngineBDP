import http from './http'

// 订阅相关接口

// 申请订阅
export function subscribe(apiId, data) {
  return http.post(`/apis/${apiId}/subscribe`, data)
}

// 列出订阅
export function listSubscriptions(params) {
  return http.get('/subscriptions', { params })
}

// 获取订阅详情
export function getSubscription(id) {
  return http.get(`/subscriptions/${id}`)
}

// 审批订阅
export function approveSubscription(id, data) {
  return http.post(`/subscriptions/${id}/approve`, data)
}

// 暂停订阅
export function suspendSubscription(id) {
  return http.post(`/subscriptions/${id}/suspend`)
}

// 恢复订阅
export function resumeSubscription(id) {
  return http.post(`/subscriptions/${id}/resume`)
}

// 吊销订阅
export function revokeSubscription(id) {
  return http.post(`/subscriptions/${id}/revoke`)
}

// 重新颁发 AK/SK
export function issueKey(id, data) {
  return http.post(`/subscriptions/${id}/keys`, data)
}

// 查询 Key 信息
export function getKeyInfo(id) {
  return http.get(`/subscriptions/${id}/keys`)
}

// 配置限流
export function configureRateLimit(id, data) {
  return http.put(`/subscriptions/${id}/rate-limit`, data)
}

// 查询限流配置
export function getRateLimit(id) {
  return http.get(`/subscriptions/${id}/rate-limit`)
}