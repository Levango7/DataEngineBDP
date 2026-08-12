package karmada

// Karmada 控制面客户端。
//
// 通过 Karmada API（kubectl proxy 或 karmada-apiserver）查询集群列表、
// 集群状态、触发 failover。本实现使用 HTTP 调用 Karmada 控制面 API
// （生产环境通过 kubeconfig + controller-runtime client）。
//
// 主要接口：
//   - ListClusters：列出所有成员集群
//   - GetCluster：获取单个集群详情
//   - Failover：触发 Karmada failover（修改 PropagationPolicy 的 clusterAffinity）
//   - MigrateWorkload：迁移工作负载到目标集群

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"

	"github.com/Levango7/DataEngineBDP/failover-engine/internal/model"
)

// Client Karmada 控制面客户端。
type Client struct {
	baseURL    string
	httpClient *http.Client
	token      string
}

// NewClient 创建 Karmada 控制面客户端。
//
// baseURL 例：http://karmada-apiserver.karmada-system:8080
// token 用于 Bearer 认证（生产环境从 kubeconfig 提取）。
func NewClient(baseURL, token string) *Client {
	return &Client{
		baseURL: baseURL,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
		token: token,
	}
}

// ListClusters 列出所有成员集群。
//
// 调用 Karmada API：GET /apis/cluster.karmada.io/v1alpha1/clusters
func (c *Client) ListClusters(ctx context.Context) ([]model.ClusterInfo, error) {
	url := c.baseURL + "/apis/cluster.karmada.io/v1alpha1/clusters"

	resp, err := c.doRequest(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("list clusters: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("list clusters: status=%d body=%s", resp.StatusCode, string(body))
	}

	var list struct {
		Items []model.ClusterInfo `json:"items"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&list); err != nil {
		return nil, fmt.Errorf("decode clusters: %w", err)
	}
	return list.Items, nil
}

// GetCluster 获取单个集群详情。
func (c *Client) GetCluster(ctx context.Context, name string) (*model.ClusterInfo, error) {
	url := fmt.Sprintf("%s/apis/cluster.karmada.io/v1alpha1/clusters/%s", c.baseURL, name)

	resp, err := c.doRequest(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("get cluster %s: %w", name, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("get cluster %s: status=%d body=%s", name, resp.StatusCode, string(body))
	}

	var cluster model.ClusterInfo
	if err := json.NewDecoder(resp.Body).Decode(&cluster); err != nil {
		return nil, fmt.Errorf("decode cluster: %w", err)
	}
	return &cluster, nil
}

// failoverRequest failover 请求体。
type failoverRequest struct {
	SourceCluster string   `json:"sourceCluster"`
	TargetCluster string   `json:"targetCluster"`
	Workloads     []string `json:"workloads"`
	PolicyName    string   `json:"policyName"`
}

// Failover 触发 Karmada failover。
//
// 通过修改 PropagationPolicy 的 clusterAffinity，将工作负载从源集群
// 迁移到目标集群。返回迁移操作 ID。
func (c *Client) Failover(
	ctx context.Context,
	sourceCluster, targetCluster string,
	workloads []string,
	policyName string,
) (string, error) {
	url := c.baseURL + "/apis/policy.karmada.io/v1alpha1/failover"

	reqBody := failoverRequest{
		SourceCluster: sourceCluster,
		TargetCluster: targetCluster,
		Workloads:     workloads,
		PolicyName:    policyName,
	}
	bodyBytes, _ := json.Marshal(reqBody)

	resp, err := c.doRequest(ctx, http.MethodPost, url, bodyBytes)
	if err != nil {
		return "", fmt.Errorf("failover: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK && resp.StatusCode != http.StatusAccepted {
		body, _ := io.ReadAll(resp.Body)
		return "", fmt.Errorf("failover: status=%d body=%s", resp.StatusCode, string(body))
	}

	var result struct {
		OperationID string `json:"operationId"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return "", fmt.Errorf("decode failover result: %w", err)
	}
	return result.OperationID, nil
}

// MigrateWorkload 迁移工作负载到目标集群。
//
// 通过修改 PropagationPolicy 的 staticWeightList，将源集群权重置 0，
// 目标集群权重提升，实现平滑迁移。
func (c *Client) MigrateWorkload(
	ctx context.Context,
	policyName, namespace string,
	sourceCluster, targetCluster string,
	sourceWeight, targetWeight int,
) error {
	url := fmt.Sprintf("%s/apis/policy.karmada.io/v1alpha1/namespaces/%s/propagationpolicies/%s",
		c.baseURL, namespace, policyName)

	// 构造 PATCH 请求体（更新 weightPreference）。
	patch := map[string]interface{}{
		"spec": map[string]interface{}{
			"placement": map[string]interface{}{
				"replicaScheduling": map[string]interface{}{
					"weightPreference": map[string]interface{}{
						"staticWeightList": []map[string]interface{}{
							{
								"targetCluster": map[string][]string{"clusterNames": {sourceCluster}},
								"weight":        sourceWeight,
							},
							{
								"targetCluster": map[string][]string{"clusterNames": {targetCluster}},
								"weight":        targetWeight,
							},
						},
					},
				},
			},
		},
	}
	bodyBytes, _ := json.Marshal(patch)

	resp, err := c.doRequest(ctx, http.MethodPut, url, bodyBytes)
	if err != nil {
		return fmt.Errorf("migrate workload: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return fmt.Errorf("migrate workload: status=%d body=%s", resp.StatusCode, string(body))
	}
	return nil
}

// doRequest 执行 HTTP 请求（注入 Bearer token）。
func (c *Client) doRequest(ctx context.Context, method, url string, body []byte) (*http.Response, error) {
	var bodyReader io.Reader
	if body != nil {
		bodyReader = bytes.NewReader(body)
	}

	req, err := http.NewRequestWithContext(ctx, method, url, bodyReader)
	if err != nil {
		return nil, err
	}

	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	return c.httpClient.Do(req)
}
