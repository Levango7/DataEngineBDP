package handler

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestHealthHandler_ReturnsUP 测试健康检查返回 UP 状态。
func TestHealthHandler_ReturnsUP(t *testing.T) {
	gin.SetMode(gin.TestMode)
	h := NewHealthHandler("1.0.0")

	r := gin.New()
	r.GET("/api/v1/health", h.Health)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/health", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp HealthResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, "UP", resp.Status)
	assert.Equal(t, "catalog", resp.Component)
}

// TestHealthHandler_Version 测试健康检查返回正确版本号。
func TestHealthHandler_Version(t *testing.T) {
	gin.SetMode(gin.TestMode)
	h := NewHealthHandler("2.5.3")

	r := gin.New()
	r.GET("/api/v1/health", h.Health)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/health", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp HealthResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, "2.5.3", resp.Version)
}

// TestHealthHandler_EmptyVersion 测试空版本号。
func TestHealthHandler_EmptyVersion(t *testing.T) {
	gin.SetMode(gin.TestMode)
	h := NewHealthHandler("")

	r := gin.New()
	r.GET("/api/v1/health", h.Health)

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/api/v1/health", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)

	var resp HealthResponse
	err := json.Unmarshal(w.Body.Bytes(), &resp)
	require.NoError(t, err)
	assert.Equal(t, "", resp.Version)
	assert.Equal(t, "UP", resp.Status)
}

// TestNewHealthHandler 测试构造函数。
func TestNewHealthHandler(t *testing.T) {
	h := NewHealthHandler("test-ver")
	assert.NotNil(t, h)
	assert.Equal(t, "test-ver", h.version)
}
