// Package client 封装数据引擎大数据平台 API 的 HTTP 客户端。
//
// 提供基础 Get/Post 方法，自动注入租户 ID 与认证 token。
package client

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

// Client 是数据引擎大数据平台 API 客户端。
type Client struct {
	baseURL    string
	tenantID   string
	token      string
	httpClient *http.Client
}

// NewClient 创建一个新的 API 客户端实例。
func NewClient(baseURL, tenantID, token string) *Client {
	return &Client{
		baseURL:  baseURL,
		tenantID: tenantID,
		token:    token,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

// newRequest 创建带有认证头与租户头的 HTTP 请求。
func (c *Client) newRequest(method, path string, body io.Reader) (*http.Request, error) {
	url := fmt.Sprintf("%s%s", c.baseURL, path)
	req, err := http.NewRequest(method, url, body)
	if err != nil {
		return nil, fmt.Errorf("创建请求失败: %w", err)
	}
	if c.token != "" {
		req.Header.Set("Authorization", "Bearer "+c.token)
	}
	if c.tenantID != "" {
		req.Header.Set("X-Tenant-ID", c.tenantID)
	}
	req.Header.Set("Content-Type", "application/json")
	return req, nil
}

// Get 发起 GET 请求到指定 path。
func (c *Client) Get(path string) (*http.Response, error) {
	req, err := c.newRequest(http.MethodGet, path, nil)
	if err != nil {
		return nil, err
	}
	return c.httpClient.Do(req)
}

// Post 发起 POST 请求到指定 path，body 会被 JSON 序列化。
func (c *Client) Post(path string, body interface{}) (*http.Response, error) {
	buf, err := json.Marshal(body)
	if err != nil {
		return nil, fmt.Errorf("序列化请求体失败: %w", err)
	}
	req, err := c.newRequest(http.MethodPost, path, bytes.NewReader(buf))
	if err != nil {
		return nil, err
	}
	return c.httpClient.Do(req)
}
