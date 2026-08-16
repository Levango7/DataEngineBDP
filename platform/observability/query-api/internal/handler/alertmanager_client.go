package handler


import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// AlertmanagerClient 封装对 Alertmanager HTTP API 的调用。
//
// 端点：
//   - GET /api/v2/alerts     活跃告警列表
//   - POST /api/v2/alerts    创建告警（本 handler 暂不使用）
//   - POST /api/v2/silences  静默告警（HandleAlert 用）
//
// 线程安全：http.Client 内部维护连接池，可并发使用。
type AlertmanagerClient struct {
	baseURL string
	client  *http.Client
}

// NewAlertmanagerClient 创建 AlertmanagerClient。
//
// baseURL 是 Alertmanager 地址，如 http://alertmanager:9093。
// timeout 是 HTTP 请求超时。
func NewAlertmanagerClient(baseURL string, timeout time.Duration) *AlertmanagerClient {
	return &AlertmanagerClient{
		baseURL: strings.TrimRight(baseURL, "/"),
		client:  &http.Client{Timeout: timeout},
	}
}

// AlertmanagerAlert 是 Alertmanager /api/v2/alerts 的响应条目。
//
// 字段对齐前端 Alert 契约：id / content / level / triggeredAt / handled。
type AlertmanagerAlert struct {
	// 告警指纹（Alertmanager 自动生成）
	Fingerprint string `json:"fingerprint"`
	// 告警标签
	Labels map[string]string `json:"labels"`
	// 告警注解
	Annotations map[string]string `json:"annotations"`
	// 起始时间（RFC3339）
	StartsAt string `json:"startsAt"`
	// 结束时间（RFC3339，空表示仍在活跃）
	EndsAt string `json:"endsAt"`
	// 状态：firing / pending / resolved
	State string `json:"status"`
	// 接收器名
	Receiver string `json:"receiver"`
}

// ListAlerts 查询 Alertmanager 活跃告警列表。
//
// 默认只返回 firing 状态的告警（active=true）。
// 如果 Alertmanager 不可达，返回 error，由调用方降级。
func (a *AlertmanagerClient) ListAlerts(ctx context.Context) ([]AlertmanagerAlert, error) {
	endpoint := a.baseURL + "/api/v2/alerts?active=true&silenced=false&inhibited=false"

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, fmt.Errorf("build request: %w", err)
	}

	resp, err := a.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("call alertmanager: %w", err)
	}
	defer resp.Body.Close()

	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("alertmanager returned %d: %s", resp.StatusCode, string(body))
	}

	var alerts []AlertmanagerAlert
	if err := json.Unmarshal(body, &alerts); err != nil {
		return nil, fmt.Errorf("decode alerts: %w", err)
	}

	return alerts, nil
}

// SilenceRequest 是创建静默规则的请求体。
type SilenceRequest struct {
	// 匹配的标签
	Matchers []SilenceMatcher `json:"matchers"`
	// 起始时间
	StartsAt time.Time `json:"startsAt"`
	// 结束时间
	EndsAt time.Time `json:"endsAt"`
	// 创建者
	CreatedBy string `json:"createdBy"`
	// 注释
	Comment string `json:"comment"`
}

// SilenceMatcher 是静默规则的标签匹配器。
type SilenceMatcher struct {
	Name    string `json:"name"`
	Value   string `json:"value"`
	IsRegex bool   `json:"isRegex"`
	IsEqual bool   `json:"isEqual"`
}

// SilenceAlert 静默指定告警（按 alertname 标签匹配）。
//
// duration 是静默持续时间，如 1h、2h。
func (a *AlertmanagerClient) SilenceAlert(ctx context.Context, alertName, createdBy, comment string, duration time.Duration) (string, error) {
	now := time.Now().UTC()
	req := SilenceRequest{
		Matchers: []SilenceMatcher{
			{Name: "alertname", Value: alertName, IsEqual: true},
		},
		StartsAt:  now,
		EndsAt:    now.Add(duration),
		CreatedBy: createdBy,
		Comment:   comment,
	}

	body, err := json.Marshal(req)
	if err != nil {
		return "", fmt.Errorf("marshal silence request: %w", err)
	}

	endpoint := a.baseURL + "/api/v2/silences"
	httpReq, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(string(body)))
	if err != nil {
		return "", fmt.Errorf("build request: %w", err)
	}
	httpReq.Header.Set("Content-Type", "application/json")

	resp, err := a.client.Do(httpReq)
	if err != nil {
		return "", fmt.Errorf("call alertmanager: %w", err)
	}
	defer resp.Body.Close()

	respBody, err := io.ReadAll(resp.Body)
	if err != nil {
		return "", fmt.Errorf("read response: %w", err)
	}

	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("alertmanager returned %d: %s", resp.StatusCode, string(respBody))
	}

	// 响应：{"silenceId":"..."}
	var result struct {
		SilenceID string `json:"silenceId"`
	}
	if err := json.Unmarshal(respBody, &result); err != nil {
		return "", fmt.Errorf("decode response: %w", err)
	}

	return result.SilenceID, nil
}

// BaseURL 返回 Alertmanager 地址（用于测试与日志）。
func (a *AlertmanagerClient) BaseURL() string {
	return a.baseURL
}