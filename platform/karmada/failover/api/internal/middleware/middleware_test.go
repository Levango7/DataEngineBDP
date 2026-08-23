package middleware

import (
	"net/http"
	"net/http/httptest"
	"os"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/golang-jwt/jwt/v5"
)

// init 设置 Gin 测试模式与必需环境变量。
func init() {
	gin.SetMode(gin.TestMode)
	if os.Getenv("JWT_SECRET") == "" {
		_ = os.Setenv("JWT_SECRET", "dev-secret-key-change-in-production-at-least-256-bits")
	}
	if os.Getenv("JWT_ISSUER") == "" {
		_ = os.Setenv("JWT_ISSUER", "shuqing-bigdata")
	}
	// 延迟初始化 JWT 配置（ensureJWTConfig 使用 sync.Once，
	// 首次调用 AuthMiddleware 时自动触发；此处主动调用确保
	// signToken 等测试辅助函数可直接使用 jwtSecret 变量）。
	ensureJWTConfig()
}

// signToken 用测试密钥生成 JWT。
func signToken(t *testing.T, tenantID string) string {
	t.Helper()
	claims := jwt.MapClaims{
		"iss":      jwtIssuer,
		"exp":      time.Now().Add(1 * time.Hour).Unix(),
		"tenantId": tenantID,
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	s, err := token.SignedString(jwtSecret)
	if err != nil {
		t.Fatalf("sign: %v", err)
	}
	return s
}

// TestAuthMiddleware_MissingHeader 缺少 Authorization 头应返回 401。
func TestAuthMiddleware_MissingHeader(t *testing.T) {
	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", w.Code)
	}
}

// TestAuthMiddleware_InvalidFormat 非 Bearer 格式应返回 401。
func TestAuthMiddleware_InvalidFormat(t *testing.T) {
	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Authorization", "Basic abc")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", w.Code)
	}
}

// TestAuthMiddleware_InvalidToken 无效 token 应返回 401。
func TestAuthMiddleware_InvalidToken(t *testing.T) {
	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Authorization", "Bearer invalid-token")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", w.Code)
	}
}

// TestAuthMiddleware_ValidToken 有效 token 应放行并注入 tenantId。
func TestAuthMiddleware_ValidToken(t *testing.T) {
	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/ping", func(c *gin.Context) {
		if got := c.GetString("tenantId"); got != "tenant-1" {
			t.Fatalf("expected tenantId=tenant-1, got %q", got)
		}
		c.String(http.StatusOK, "pong")
	})

	tok := signToken(t, "tenant-1")
	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestAuthMiddleware_MissingTenantId token 中缺少 tenantId 应返回 401。
func TestAuthMiddleware_MissingTenantId(t *testing.T) {
	claims := jwt.MapClaims{
		"iss": jwtIssuer,
		"exp": time.Now().Add(1 * time.Hour).Unix(),
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tok, _ := token.SignedString(jwtSecret)

	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", w.Code)
	}
}

// TestAuthMiddleware_InvalidIssuer 错误 issuer 应返回 401。
func TestAuthMiddleware_InvalidIssuer(t *testing.T) {
	claims := jwt.MapClaims{
		"iss":      "wrong",
		"exp":      time.Now().Add(1 * time.Hour).Unix(),
		"tenantId": "tenant-1",
	}
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	tok, _ := token.SignedString(jwtSecret)

	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401, got %d", w.Code)
	}
}

// TestLoggingMiddleware_PassThrough 日志中间件应放行请求。
func TestLoggingMiddleware_PassThrough(t *testing.T) {
	r := gin.New()
	r.Use(LoggingMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
}

// TestCorsMiddleware_OptionsRequest OPTIONS 预检应返回 204。
func TestCorsMiddleware_OptionsRequest(t *testing.T) {
	r := gin.New()
	r.Use(CorsMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodOptions, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if w.Code != http.StatusNoContent {
		t.Fatalf("expected 204, got %d", w.Code)
	}
}

// TestCorsMiddleware_SetsHeaders CORS 头应被设置。
//
// 收敛策略：CORS_ALLOWED_ORIGINS 白名单命中时回写 ACAO；未配置时不回写（fail-secure）。
func TestCorsMiddleware_SetsHeaders(t *testing.T) {
	// 设置白名单
	old := os.Getenv("CORS_ALLOWED_ORIGINS")
	_ = os.Setenv("CORS_ALLOWED_ORIGINS", "http://localhost:5173")
	defer func() { _ = os.Setenv("CORS_ALLOWED_ORIGINS", old) }()

	r := gin.New()
	r.Use(CorsMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Origin", "http://localhost:5173")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)
	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "http://localhost:5173" {
		t.Fatalf("expected ACAO=http://localhost:5173, got %q", got)
	}
}

// TestCorsMiddleware_UnmatchedOrigin 未命中白名单时不回写 ACAO（fail-secure）。
func TestCorsMiddleware_UnmatchedOrigin(t *testing.T) {
	old := os.Getenv("CORS_ALLOWED_ORIGINS")
	_ = os.Setenv("CORS_ALLOWED_ORIGINS", "http://localhost:5173")
	defer func() { _ = os.Setenv("CORS_ALLOWED_ORIGINS", old) }()

	r := gin.New()
	r.Use(CorsMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Origin", "http://evil.example.com")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("expected empty ACAO for unmatched origin, got %q", got)
	}
}
