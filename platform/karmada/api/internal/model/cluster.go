package model

// 联邦集群注册/注销请求模型。
//
// P-01 多集群联邦 — 集群注册/注销 API 数据模型。
//
// TODO: 待异地机房真实验证

import "time"

// ClusterRegisterRequest 注册联邦集群请求。
type ClusterRegisterRequest struct {
	Name        string            `json:"name" binding:"required"`        // 集群名称
	Provider    string            `json:"provider" binding:"required"`    // 提供方: self-built / xinchuang / publiccloud / privatecloud
	Region      string            `json:"region"`                         // 地域
	Zone        string            `json:"zone"`                           // 可用区
	APIEndpoint string            `json:"apiEndpoint" binding:"required"` // 集群 API Server 地址
	Kubeconfig  string            `json:"kubeconfig,omitempty"`           // 集群 kubeconfig（可选，也可通过 Secret 引用）
	Labels      map[string]string `json:"labels,omitempty"`               // 集群标签
}

// ClusterUnregisterRequest 注销联邦集群请求。
type ClusterUnregisterRequest struct {
	Name string `json:"name" binding:"required"` // 集群名称
	// Force 是否强制注销（忽略工作负载检查）
	Force bool `json:"force,omitempty"`
}

// ClusterResponse 联邦集群响应。
type ClusterResponse struct {
	Name        string            `json:"name"`
	Provider    string            `json:"provider"`
	Region      string            `json:"region"`
	Zone        string            `json:"zone"`
	Status      string            `json:"status"`
	APIEndpoint string            `json:"apiEndpoint"`
	Labels      map[string]string `json:"labels,omitempty"`
	CreatedAt   time.Time         `json:"createdAt"`
}
