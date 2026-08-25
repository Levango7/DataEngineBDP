package middleware

import (
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/stretchr/testify/assert"
)

func TestCorsMiddleware_DefaultOrigin(t *testing.T) {
	gin.SetMode(gin.TestMode)
	_ = os.Unsetenv("CORS_ORIGINS")
	defer func() { _ = os.Unsetenv("CORS_ORIGINS") }()

	r := gin.New()
	r.Use(CorsMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Origin", "http://localhost:5173")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
	assert.Equal(t, "http://localhost:5173", w.Header().Get("Access-Control-Allow-Origin"))
}

func TestCorsMiddleware_Preflight(t *testing.T) {
	gin.SetMode(gin.TestMode)
	_ = os.Unsetenv("CORS_ORIGINS")
	defer func() { _ = os.Unsetenv("CORS_ORIGINS") }()

	r := gin.New()
	r.Use(CorsMiddleware())

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodOptions, "/anything", nil)
	req.Header.Set("Origin", "http://localhost:5173")
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusNoContent, w.Code)
}

func TestLoggingMiddleware(t *testing.T) {
	gin.SetMode(gin.TestMode)
	logger := slog.New(slog.NewJSONHandler(os.Stderr, nil))

	r := gin.New()
	r.Use(LoggingMiddleware(logger))
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	w := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	r.ServeHTTP(w, req)

	assert.Equal(t, http.StatusOK, w.Code)
}
