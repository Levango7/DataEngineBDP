package middleware

import (
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
)

// TestCorsMiddleware_AllowedOrigin 测试允许的源。
func TestCorsMiddleware_AllowedOrigin(t *testing.T) {
	gin.SetMode(gin.TestMode)
	os.Setenv("CORS_ORIGINS", "http://localhost:5173,http://localhost:3000")
	defer os.Unsetenv("CORS_ORIGINS")

	r := gin.New()
	r.Use(CorsMiddleware())
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/test", nil)
	req.Header.Set("Origin", "http://localhost:5173")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, "http://localhost:5173", w.Header().Get("Access-Control-Allow-Origin"))
	assert.Equal(t, "true", w.Header().Get("Access-Control-Allow-Credentials"))
}

// TestCorsMiddleware_DisallowedOrigin 测试不允许的源。
func TestCorsMiddleware_DisallowedOrigin(t *testing.T) {
	gin.SetMode(gin.TestMode)
	os.Setenv("CORS_ORIGINS", "http://localhost:5173")
	defer os.Unsetenv("CORS_ORIGINS")

	r := gin.New()
	r.Use(CorsMiddleware())
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/test", nil)
	req.Header.Set("Origin", "http://evil.example.com")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Empty(t, w.Header().Get("Access-Control-Allow-Origin"))
}

// TestCorsMiddleware_Preflight 测试 OPTIONS 预检请求。
func TestCorsMiddleware_Preflight(t *testing.T) {
	gin.SetMode(gin.TestMode)
	os.Setenv("CORS_ORIGINS", "http://localhost:5173")
	defer os.Unsetenv("CORS_ORIGINS")

	r := gin.New()
	r.Use(CorsMiddleware())
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodOptions, "/test", nil)
	req.Header.Set("Origin", "http://localhost:5173")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNoContent, w.Code)
	assert.Equal(t, "http://localhost:5173", w.Header().Get("Access-Control-Allow-Origin"))
	assert.NotEmpty(t, w.Header().Get("Access-Control-Allow-Methods"))
	assert.NotEmpty(t, w.Header().Get("Access-Control-Allow-Headers"))
}

// TestCorsMiddleware_DefaultOrigin 测试默认源配置。
func TestCorsMiddleware_DefaultOrigin(t *testing.T) {
	gin.SetMode(gin.TestMode)
	os.Unsetenv("CORS_ORIGINS")

	r := gin.New()
	r.Use(CorsMiddleware())
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	w := httptest.NewRecorder()
	req, _ := http.NewRequest(http.MethodGet, "/test", nil)
	req.Header.Set("Origin", "http://localhost:5173")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, "http://localhost:5173", w.Header().Get("Access-Control-Allow-Origin"))
}
