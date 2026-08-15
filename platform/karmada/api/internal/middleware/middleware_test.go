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
	// 重新加载配置（init 已在包加载时执行，但测试可能后于设置环境变量）。
	jwtSecret = []byte(os.Getenv("JWT_SECRET"))
	jwtIssuer = os.Getenv("JWT_ISSUER")
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
		t.Fatalf("expected 401 for missing tenantId, got %d", w.Code)
	}
}

// TestAuthMiddleware_InvalidIssuer 错误 issuer 应返回 401。
func TestAuthMiddleware_InvalidIssuer(t *testing.T) {
	claims := jwt.MapClaims{
		"iss":      "wrong-issuer",
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
		t.Fatalf("expected 401 for invalid issuer, got %d", w.Code)
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
func TestCorsMiddleware_SetsHeaders(t *testing.T) {
	r := gin.New()
	r.Use(CorsMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "*" {
		t.Fatalf("expected ACAO=*, got %q", got)
	}
	if got := w.Header().Get("Access-Control-Allow-Methods"); got == "" {
		t.Fatal("expected non-empty ACAM")
	}
}
