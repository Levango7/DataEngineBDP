package prometheus

// Prometheus 客户端：查询集群负载指标。
//
// 通过 PromQL 查询各集群的 CPU/内存/Pod 数等指标，作为健康检查的
// 补充信号。当 Karmada API 报告集群 Ready=True 但 Prometheus 显示
// CPU 负载 >90% 持续 N 秒，引擎也认为集群 degraded。

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"time"
)

// Client Prometheus 客户端。
type Client struct {
	baseURL    string
	httpClient *http.Client
}

// NewClient 创建 Prometheus 客户端。
func NewClient(baseURL string) *Client {
	return &Client{
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: 15 * time.Second,
		},
	}
}

// QueryResult PromQL 即时查询结果。
type QueryResult struct {
	Value     float64
	Timestamp time.Time
}

// Query 执行 PromQL 即时查询。
//
// 例：Query("100 - avg(rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100")
func (c *Client) Query(ctx context.Context, promql string) (*QueryResult, error) {
	u := c.baseURL + "/api/v1/query?query=" + url.QueryEscape(promql)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("prometheus query: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("prometheus query: status=%d body=%s", resp.StatusCode, string(body))
	}

	var promResp struct {
		Status string `json:"status"`
		Data   struct {
			ResultType string `json:"resultType"`
			Result     []struct {
				Value []interface{} `json:"value"` // [timestamp, "value"]
			} `json:"result"`
		} `json:"data"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&promResp); err != nil {
		return nil, fmt.Errorf("decode prometheus response: %w", err)
	}

	if promResp.Status != "success" {
		return nil, fmt.Errorf("prometheus query failed: status=%s", promResp.Status)
	}

	if len(promResp.Data.Result) == 0 {
		return &QueryResult{Value: 0, Timestamp: time.Now()}, nil
	}

	value := promResp.Data.Result[0].Value
	if len(value) < 2 {
		return &QueryResult{Value: 0, Timestamp: time.Now()}, nil
	}

	ts, _ := value[0].(float64)
	strVal, _ := value[1].(string)
	floatVal, _ := strconv.ParseFloat(strVal, 64)

	return &QueryResult{
		Value:     floatVal,
		Timestamp: time.Unix(int64(ts), 0),
	}, nil
}

// ClusterMetrics 集群指标集合。
type ClusterMetrics struct {
	ClusterName string
	CPULoad     float64
	MemoryLoad  float64
	PodCount    int
	NodeCount   int
}

// GetClusterMetrics 查询指定集群的负载指标。
//
// 通过多个 PromQL 查询聚合：
//   - CPU 负载：100 - avg(rate(node_cpu_seconds_total{mode="idle"}[5m])) * 100
//   - 内存负载：(1 - node_memory_MemAvailable / node_memory_MemTotal) * 100
//   - Pod 数：count(kube_pod_info)
//   - Node 数：count(kube_node_info)
func (c *Client) GetClusterMetrics(ctx context.Context, clusterName string) (*ClusterMetrics, error) {
	metrics := &ClusterMetrics{ClusterName: clusterName}

	// CPU 负载。
	cpuQuery := fmt.Sprintf(
		`100 - avg(rate(node_cpu_seconds_total{mode="idle",cluster="%s"}[5m])) * 100`,
		clusterName,
	)
	if result, err := c.Query(ctx, cpuQuery); err == nil {
		metrics.CPULoad = result.Value
	}

	// 内存负载。
	memQuery := fmt.Sprintf(
		`(1 - node_memory_MemAvailable_bytes{cluster="%s"} / node_memory_MemTotal_bytes{cluster="%s"}) * 100`,
		clusterName, clusterName,
	)
	if result, err := c.Query(ctx, memQuery); err == nil {
		metrics.MemoryLoad = result.Value
	}

	// Pod 数。
	podQuery := fmt.Sprintf(`count(kube_pod_info{cluster="%s"})`, clusterName)
	if result, err := c.Query(ctx, podQuery); err == nil {
		metrics.PodCount = int(result.Value)
	}

	// Node 数。
	nodeQuery := fmt.Sprintf(`count(kube_node_info{cluster="%s"})`, clusterName)
	if result, err := c.Query(ctx, nodeQuery); err == nil {
		metrics.NodeCount = int(result.Value)
	}

	return metrics, nil
}

// IsAvailable 探测 Prometheus 是否可用。
func (c *Client) IsAvailable(ctx context.Context) bool {
	u := c.baseURL + "/-/healthy"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return false
	}
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == http.StatusOK
}