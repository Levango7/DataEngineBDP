package karmadaclient

// Karmada REST API 客户端。
//
// P-01 多集群联邦 — 添加 Karmada REST API 客户端连接配置。
//
// 本模块封装与 Karmada 控制面的交互：
//   - 通过 Karmada API Server（REST）管理联邦集群
//   - 通过 kubeconfig 文件认证
//   - 支持集群注册/注销/查询/健康检查
//
// 环境变量配置：
//   KARMADA_API_SERVER    Karmada API Server 地址（如 https://karmada-apiserver.karmada-system:5443）
//   KARMADA_KUBECONFIG    kubeconfig 文件路径（如 /etc/karmada/kubeconfig）
//   KARMADA_API_TIMEOUT   API 请求超时秒（默认 30）
//
// TODO: 待异地机房真实验证
// TODO: 添加 mTLS 双向认证支持
// TODO: 添加 Karmada API 版本兼容性检查

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

// Config Karmada API 客户端配置。
type Config struct {
	// APIServer Karmada API Server 地址。
	APIServer string
	// Kubeconfig kubeconfig 文件路径。
	Kubeconfig string
	// Timeout API 请求超时。
	Timeout time.Duration
}

// Client Karmada REST API 客户端。
type Client struct {
	config Config
	http   *http.Client
}

// NewConfigFromEnv 从环境变量创建配置。
func NewConfigFromEnv() Config {
	apiServer := os.Getenv("KARMADA_API_SERVER")
	kubeconfig := os.Getenv("KARMADA_KUBECONFIG")
	timeoutSec := 30
	if v := os.Getenv("KARMADA_API_TIMEOUT"); v != "" {
		if n, err := time.ParseDuration(v + "s"); err == nil {
			return Config{APIServer: apiServer, Kubeconfig: kubeconfig, Timeout: n}
		}
	}
	return Config{
		APIServer:  apiServer,
		Kubeconfig: kubeconfig,
		Timeout:    time.Duration(timeoutSec) * time.Second,
	}
}

// NewClient 创建 Karmada API 客户端。
func NewClient(cfg Config) (*Client, error) {
	if cfg.APIServer == "" {
		return nil, fmt.Errorf("KARMADA_API_SERVER 未配置")
	}
	// TODO: 从 kubeconfig 加载 TLS 配置
	// TODO: 添加 mTLS 双向认证
	return &Client{
		config: cfg,
		http: &http.Client{
			Timeout: cfg.Timeout,
		},
	}, nil
}

// ClusterInfo 联邦集群信息。
type ClusterInfo struct {
	Name        string            `json:"name"`
	Provider    string            `json:"provider"` // 集群提供方（self-built / xinchuang / publiccloud / privatecloud）
	Region      string            `json:"region"`   // 地域
	Zone        string            `json:"zone"`     // 可用区
	Status      string            `json:"status"`   // online / offline / syncing
	APIEndpoint string            `json:"apiEndpoint"`
	Labels      map[string]string `json:"labels,omitempty"`
	CreatedAt   time.Time         `json:"createdAt"`
}

// RegisterCluster 注册联邦集群。
//
// TODO: 待异地机房真实验证
// TODO: 添加集群健康检查（注册前验证目标集群可达）
// TODO: 添加集群标签自动填充（arch/os/guomi）
func (c *Client) RegisterCluster(ctx context.Context, cluster ClusterInfo) error {
	url := fmt.Sprintf("%s/apis/cluster.karmada.io/v1alpha1/clusters", c.config.APIServer)
	body, err := json.Marshal(cluster)
	if err != nil {
		return fmt.Errorf("marshal cluster: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, "POST", url, bytesReader(body))
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	// TODO: 从 kubeconfig 加载认证信息
	// req.Header.Set("Authorization", "Bearer "+token)

	resp, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("register cluster: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusCreated && resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("register cluster failed: status=%d, body=%s", resp.StatusCode, string(body))
	}
	return nil
}

// UnregisterCluster 注销联邦集群。
//
// TODO: 待异地机房真实验证
// TODO: 注销前检查是否有工作负载在该集群运行
// TODO: 添加优雅注销（先驱逐工作负载再移除集群）
func (c *Client) UnregisterCluster(ctx context.Context, clusterName string) error {
	url := fmt.Sprintf("%s/apis/cluster.karmada.io/v1alpha1/clusters/%s", c.config.APIServer, clusterName)
	req, err := http.NewRequestWithContext(ctx, "DELETE", url, nil)
	if err != nil {
		return fmt.Errorf("create request: %w", err)
	}
	// TODO: 从 kubeconfig 加载认证信息

	resp, err := c.http.Do(req)
	if err != nil {
		return fmt.Errorf("unregister cluster: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusNoContent {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("unregister cluster failed: status=%d, body=%s", resp.StatusCode, string(body))
	}
	return nil
}

// ListClusters 列出所有联邦集群。
//
// TODO: 待异地机房真实验证
func (c *Client) ListClusters(ctx context.Context) ([]ClusterInfo, error) {
	url := fmt.Sprintf("%s/apis/cluster.karmada.io/v1alpha1/clusters", c.config.APIServer)
	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}
	// TODO: 从 kubeconfig 加载认证信息

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("list clusters: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("list clusters failed: status=%d, body=%s", resp.StatusCode, string(body))
	}

	var result struct {
		Items []ClusterInfo `json:"items"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	return result.Items, nil
}

// GetCluster 获取单个联邦集群信息。
func (c *Client) GetCluster(ctx context.Context, name string) (*ClusterInfo, error) {
	url := fmt.Sprintf("%s/apis/cluster.karmada.io/v1alpha1/clusters/%s", c.config.APIServer, name)
	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return nil, fmt.Errorf("create request: %w", err)
	}

	resp, err := c.http.Do(req)
	if err != nil {
		return nil, fmt.Errorf("get cluster: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("get cluster failed: status=%d", resp.StatusCode)
	}

	var cluster ClusterInfo
	if err := json.NewDecoder(resp.Body).Decode(&cluster); err != nil {
		return nil, fmt.Errorf("decode response: %w", err)
	}
	return &cluster, nil
}

// bytesReader 将 byte slice 转为 io.Reader（避免直接导入 bytes 包）。
func bytesReader(b []byte) io.Reader {
	return &bytesReaderImpl{data: b}
}

type bytesReaderImpl struct {
	data []byte
	pos  int
}

func (r *bytesReaderImpl) Read(p []byte) (int, error) {
	if r.pos >= len(r.data) {
		return 0, io.EOF
	}
	n := copy(p, r.data[r.pos:])
	r.pos += n
	return n, nil
}
