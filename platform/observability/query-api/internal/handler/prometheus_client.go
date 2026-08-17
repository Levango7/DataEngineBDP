package handler

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

// PrometheusQueryClient 是运维台专用的 Prometheus 查询客户端。
//
// 与 service/prometheus.go 的 PrometheusClient 区别：
//   - PrometheusClient 是通用代理（透传原始 JSON 给前端 Grafana）
//   - PrometheusQueryClient 是 ops 专用（解析 scalar 结果用于概览聚合）
//
// 线程安全：http.Client 内部维护连接池，可并发使用。
type PrometheusQueryClient struct {
	baseURL string
	client  *http.Client
}

// NewPrometheusQueryClient 创建 PrometheusQueryClient。
//
// baseURL 是后端 Prometheus 地址，如 http://prometheus:9090。
// timeout 是 HTTP 请求超时。
func NewPrometheusQueryClient(baseURL string, timeout time.Duration) *PrometheusQueryClient {
	return &PrometheusQueryClient{
		baseURL: strings.TrimRight(baseURL, "/"),
		client:  &http.Client{Timeout: timeout},
	}
}

// promQueryResponse 是 Prometheus /api/v1/query 响应的精简结构。
type promQueryResponse struct {
	Status string          `json:"status"`
	Data   json.RawMessage `json:"data"`
	Error  string          `json:"error,omitempty"`
}

// promVectorResult 是 Prometheus vector 查询结果。
type promVectorResult struct {
	ResultType string `json:"resultType"`
	Result     []struct {
		Metric map[string]string `json:"metric"`
		Value  [2]interface{}    `json:"value"` // [timestamp, "value"]
	} `json:"result"`
}

// QueryScalar 调用 Prometheus /api/v1/query 瞬时查询，返回第一个 scalar 值。
//
// 若9如果查询返回空结果或失败，返回 0 和 error。
func (p *PrometheusQueryClient) QueryScalar(ctx context.Context, promql string) (float64, error) {
	endpoint := p.baseURL + "/api/v1/query?query=" + url.QueryEscape(promql)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return 0, fmt.Errorf("build request: %w", err)
	}

	resp, err := p.client.Do(req)
	if err != nil {
		return 0, fmt.Errorf("call prometheus: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return 0, fmt.Errorf("read response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return 0, fmt.Errorf("prometheus returned %d: %s", resp.StatusCode, string(body))
	}

	var qr promQueryResponse
	if err := json.Unmarshal(body, &qr); err != nil {
		return 0, fmt.Errorf("decode response: %w", err)
	}

	if qr.Status != "success" {
		return 0, fmt.Errorf("prometheus status=%s error=%s", qr.Status, qr.Error)
	}

	var vector promVectorResult
	if err := json.Unmarshal(qr.Data, &vector); err != nil {
		return 0, fmt.Errorf("decode vector: %w", err)
	}

	if len(vector.Result) == 0 {
		return 0, nil
	}

	// value[1] 是字符串形式的数值
	valStr, ok := vector.Result[0].Value[1].(string)
	if !ok {
		return 0, fmt.Errorf("unexpected value type: %T", vector.Result[0].Value[1])
	}

	var val float64
	if _, err := fmt.Sscanf(valStr, "%g", &val); err != nil {
		return 0, fmt.Errorf("parse value %q: %w", valStr, err)
	}
	return val, nil
}

// QueryOverview 查询运维概览所需的 Prometheus 指标。
//
// 返回：集群数、节点总数、健康节点数、平均延迟（秒）。
// 查询失败时返回 0 和 error，由调用方决定降级策略。
func (p *PrometheusQueryClient) QueryOverview(ctx context.Context) (clusters, nodesTotal, nodesReady int, avgLatencySec float64, err error) {
	// 集群数：count(up{job=~\"kubernetes.*\"}) by (cluster) 的 cardinality
	clusterCount, e := p.QueryScalar(ctx, "count(count(up{job=~\"kubernetes.*\"}) by (cluster))")
	if e != nil {
		return 0, 0, 0, 0, fmt.Errorf("query cluster count: %w", e)
	}
	clusters = int(clusterCount)

	// 节点总数
	nodeTotal, e := p.QueryScalar(ctx, "count(kube_node_info)")
	if e != nil {
		return clusters, 0, 0, 0, fmt.Errorf("query node total: %w", e)
	}
	nodesTotal = int(nodeTotal)

	// 健康节点数
	nodeReady, e := p.QueryScalar(ctx, "count(kube_node_status_condition{condition=\"Ready\",status=\"true\"})")
	if e != nil {
		return clusters, nodesTotal, 0, 0, fmt.Errorf("query node ready: %w", e)
	}
	nodesReady = int(nodeReady)

	// 平均延迟（秒）：HTTP 请求平均响应时间
	latency, e := p.QueryScalar(ctx,
		"sum(rate(http_server_requests_seconds_sum[5m])) / sum(rate(http_server_requests_seconds_count[5m]))")
	if e != nil {
		// 延迟查询失败不致命，返回 0
		avgLatencySec = 0
	} else {
		avgLatencySec = latency
	}

	return clusters, nodesTotal, nodesReady, avgLatencySec, nil
}

// BaseURL 返回后端 Prometheus 地址（用于测试与日志）。
func (p *PrometheusQueryClient) BaseURL() string {
	return p.baseURL
}
