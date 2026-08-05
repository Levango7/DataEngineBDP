package client

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestNewClient 测试创建客户端。
func TestNewClient(t *testing.T) {
	c := NewClient("https://api.example.com", "tenant-001", "token-abc")
	assert.NotNil(t, c)
	assert.Equal(t, "https://api.example.com", c.baseURL)
	assert.Equal(t, "tenant-001", c.tenantID)
	assert.Equal(t, "token-abc", c.token)
}

// TestClient_Get 测试 GET 请求携带认证头。
func TestClient_Get(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodGet, r.Method)
		assert.Equal(t, "Bearer test-token", r.Header.Get("Authorization"))
		assert.Equal(t, "tenant-001", r.Header.Get("X-Tenant-ID"))
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ok"}`))
	}))
	defer server.Close()

	c := NewClient(server.URL, "tenant-001", "test-token")
	resp, err := c.Get("/api/v1/test")
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
}

// TestClient_Post 测试 POST 请求携带认证头和 JSON 请求体。
func TestClient_Post(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, http.MethodPost, r.Method)
		assert.Equal(t, "Bearer test-token", r.Header.Get("Authorization"))
		assert.Equal(t, "tenant-001", r.Header.Get("X-Tenant-ID"))
		assert.Equal(t, "application/json", r.Header.Get("Content-Type"))

		var body map[string]string
		json.NewDecoder(r.Body).Decode(&body)
		assert.Equal(t, "value", body["key"])

		w.WriteHeader(http.StatusCreated)
		w.Write([]byte(`{"created":true}`))
	}))
	defer server.Close()

	c := NewClient(server.URL, "tenant-001", "test-token")
	resp, err := c.Post("/api/v1/test", map[string]string{"key": "value"})
	require.NoError(t, err)
	assert.Equal(t, http.StatusCreated, resp.StatusCode)
}

// TestClient_Get_NoToken 测试无 token 时不设置 Authorization 头。
func TestClient_Get_NoToken(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Empty(t, r.Header.Get("Authorization"))
		assert.Equal(t, "tenant-001", r.Header.Get("X-Tenant-ID"))
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	c := NewClient(server.URL, "tenant-001", "")
	resp, err := c.Get("/api/v1/test")
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
}

// TestClient_Post_NoTenant 测试无 tenant 时不设置 X-Tenant-ID 头。
func TestClient_Post_NoTenant(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		assert.Equal(t, "Bearer test-token", r.Header.Get("Authorization"))
		assert.Empty(t, r.Header.Get("X-Tenant-ID"))
		w.WriteHeader(http.StatusOK)
	}))
	defer server.Close()

	c := NewClient(server.URL, "", "test-token")
	resp, err := c.Post("/api/v1/test", map[string]string{"key": "value"})
	require.NoError(t, err)
	assert.Equal(t, http.StatusOK, resp.StatusCode)
}

// TestClient_NewRequest 测试 newRequest 构造正确的 URL。
func TestClient_NewRequest(t *testing.T) {
	c := NewClient("https://api.example.com", "t1", "tok")
	req, err := c.newRequest(http.MethodGet, "/api/v1/test", nil)
	require.NoError(t, err)
	assert.Equal(t, "https://api.example.com/api/v1/test", req.URL.String())
	assert.Equal(t, "Bearer tok", req.Header.Get("Authorization"))
	assert.Equal(t, "t1", req.Header.Get("X-Tenant-ID"))
}
