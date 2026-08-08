// Package service 提供统一查询 API 的业务服务层。
//
// 包含：
//   - PrometheusClient: 封装对后端 Prometheus 的 HTTP 调用
//   - TenantFilter:     租户隔离 PromQL 注入与标签过滤
package service

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// PrometheusClient 封装对后端 Prometheus HTTP API 的调用。
//
// 线程安全：http.Client 内部维护连接池，可并发使用。
type PrometheusClient struct {
	baseURL string
	client  *http.Client
}

// NewPrometheusClient 创建 PrometheusClient。
//
// baseURL 是后端 Prometheus 地址，如 http://prometheus:9090。
// timeout 是 HTTP 请求超时。
func NewPrometheusClient(baseURL string, timeout time.Duration) *PrometheusClient {
	return &PrometheusClient{
		baseURL: strings.TrimRight(baseURL, "/"),
		client:  &http.Client{Timeout: timeout},
	}
}

// QueryResponse 是 Prometheus /api/v1/query 响应的精简结构。
//
// 保留原始 JSON 透传给前端 Grafana，仅解析 status 用于错误判断。
type QueryResponse struct {
	Status    string          `json:"status"`
	Data      json.RawMessage `json:"data"`
	ErrorType string          `json:"errorType,omitempty"`
	Error     string          `json:"error,omitempty"`
	Warnings  []string        `json:"warnings,omitempty"`
}

// Query 调用 Prometheus /api/v1/query（瞬时查询）。
//
// params 是已构造好的查询参数（含注入后的 PromQL）。
// 返回原始 JSON body 与解析后的 status，便于 handler 透传。
func (p *PrometheusClient) Query(ctx context.Context, params url.Values) (*QueryResponse, []byte, error) {
	return p.doGet(ctx, "/api/v1/query", params)
}

// QueryRange 调用 Prometheus /api/v1/query_range（范围查询）。
func (p *PrometheusClient) QueryRange(ctx context.Context, params url.Values) (*QueryResponse, []byte, error) {
	return p.doGet(ctx, "/api/v1/query_range", params)
}

// Labels 调用 Prometheus /api/v1/labels（标签名列表）。
func (p *PrometheusClient) Labels(ctx context.Context, params url.Values) (*QueryResponse, []byte, error) {
	return p.doGet(ctx, "/api/v1/labels", params)
}

// LabelValues 调用 Prometheus /api/v1/label/{name}/values（标签值列表）。
func (p *PrometheusClient) LabelValues(ctx context.Context, labelName string, params url.Values) (*QueryResponse, []byte, error) {
	return p.doGet(ctx, fmt.Sprintf("/api/v1/label/%s/values", url.PathEscape(labelName)), params)
}

// Series 调用 Prometheus /api/v1/series（序列查找）。
func (p *PrometheusClient) Series(ctx context.Context, params url.Values) (*QueryResponse, []byte, error) {
	return p.doGet(ctx, "/api/v1/series", params)
}

// doGet 是所有 GET 请求的内部实现，统一处理错误与超时。
func (p *PrometheusClient) doGet(ctx context.Context, path string, params url.Values) (*QueryResponse, []byte, error) {
	endpoint := p.baseURL + path
	if len(params) > 0 {
		endpoint = endpoint + "?" + params.Encode()
	}

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, nil, fmt.Errorf("build request: %w", err)
	}

	resp, err := p.client.Do(req)
	if err != nil {
		return nil, nil, fmt.Errorf("call prometheus %s: %w", path, err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, nil, fmt.Errorf("read response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, body, fmt.Errorf("prometheus returned %d: %s", resp.StatusCode, string(body))
	}

	var qr QueryResponse
	if err := json.Unmarshal(body, &qr); err != nil {
		return nil, body, fmt.Errorf("decode response: %w", err)
	}

	if qr.Status != "success" {
		return &qr, body, fmt.Errorf("prometheus status=%s error=%s", qr.Status, qr.Error)
	}

	return &qr, body, nil
}

// BaseURL 返回后端 Prometheus 地址（用于测试与日志）。
func (p *PrometheusClient) BaseURL() string {
	return p.baseURL
}
