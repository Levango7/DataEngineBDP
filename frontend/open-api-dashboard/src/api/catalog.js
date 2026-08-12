import http from './http'

// API 目录相关接口

// 列出 API
export function listApis(params) {
  return http.get('/apis', { params })
}

// 获取 API 详情
export function getApi(id) {
  return http.get(`/apis/${id}`)
}

// 注册 API
export function createApi(data) {
  return http.post('/apis', data)
}

// 更新 API
export function updateApi(id, data) {
  return http.put(`/apis/${id}`, data)
}

// 删除 API
export function deleteApi(id) {
  return http.delete(`/apis/${id}`)
}

// 发布流程
export function submitReview(id) {
  return http.post(`/apis/${id}/submit-review`)
}

export function approveApi(id) {
  return http.post(`/apis/${id}/approve`)
}

export function publishApi(id) {
  return http.post(`/apis/${id}/publish`)
}

// 调用 API
export function callApi(id, data, apiKey) {
  return http.post(`/apis/${id}/call`, data, {
    headers: { 'X-API-Key': apiKey },
  })
}

// 获取计量
export function getMetrics(id, params) {
  return http.get(`/apis/${id}/metrics`, { params })
}

// 获取 APISIX 配置
export function getApisixConfig(id) {
  return http.get(`/apis/${id}/apisix-config`)
}

// 部署 APISIX 路由
export function deployApisixRoute(id) {
  return http.post(`/apis/${id}/apisix-deploy`)
}

// 一键生成
export function generateFromSql(data) {
  return http.post('/apis/generate/sql', data)
}

export function generateFromModel(data) {
  return http.post('/apis/generate/model', data)
}

export function generateFromFunction(data) {
  return http.post('/apis/generate/function', data)
}

export function getGenerateOptions() {
  return http.get('/apis/generate/options')
}

// 计费配置
export function configureBilling(id, data) {
  return http.put(`/apis/${id}/billing`, data)
}

export function getBilling(id) {
  return http.get(`/apis/${id}/billing`)
}