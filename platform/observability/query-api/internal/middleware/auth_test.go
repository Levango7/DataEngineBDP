package middleware

import (
	"log/slog"
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
	// AuthMiddleware 通过 mustGetenv 读取 JWT_SIGNING_KEY，缺失会 fatal。
	if os.Getenv("JWT_SIGNING_KEY") == "" {
		_ = os.Setenv("JWT_SIGNING_KEY", "test-secret-at-least-32-bytes-long-xxxx")
	}
	if os.Getenv("JWT_ISSUER") == "" {
		_ = os.Setenv("JWT_ISSUER", "shuqing-bigdata")
	}
}

// newTestRouter 创建带指定中间件的测试路由。
func newTestRouter(mw ...gin.HandlerFunc) *gin.Engine {
	r := gin.New()
	r.Use(mw...)
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })
	return r
}

// signJWT 用测试密钥生成一个 JWT。
func signJWT(t *testing.T, claims jwt.MapClaims) string {
	t.Helper()
	claims["iss"] = os.Getenv("JWT_ISSUER")
	claims["exp"] = time.Now().Add(1 * time.Hour).Unix()
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)
	s, err := token.SignedString([]byte(os.Getenv("JWT_SIGNING_KEY")))
	if err != nil {
		t.Fatalf("sign jwt: %v", err)
	}
	return s
}

// TestAuthMiddleware_MissingHeader 缺少 Authorization 头应返回 401。
func TestAuthMiddleware_MissingHeader(t *testing.T) {
	r := newTestRouter(AuthMiddleware())

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for missing header, got %d", w.Code)
	}
}

// TestAuthMiddleware_NonBearer 非 Bearer 前缀应返回 401。
func TestAuthMiddleware_NonBearer(t *testing.T) {
	r := newTestRouter(AuthMiddleware())

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Authorization", "Basic abc123")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for non-Bearer, got %d", w.Code)
	}
}

// TestAuthMiddleware_InvalidToken 无效 token 应返回 401。
func TestAuthMiddleware_InvalidToken(t *testing.T) {
	r := newTestRouter(AuthMiddleware())

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Authorization", "Bearer not-a-jwt")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for invalid token, got %d", w.Code)
	}
}

// TestAuthMiddleware_ValidToken 有效 token 应放行并注入 claims。
func TestAuthMiddleware_ValidToken(t *testing.T) {
	r := gin.New()
	r.Use(AuthMiddleware())
	r.GET("/ping", func(c *gin.Context) {
		if c.GetString("tenantId") != "tenant-1" {
			t.Fatalf("expected tenantId=tenant-1, got %q", c.GetString("tenantId"))
		}
		if c.GetString("userId") != "user-1" {
			t.Fatalf("expected userId=user-1, got %q", c.GetString("userId"))
		}
		if c.GetString("role") != "tenant-user" {
			t.Fatalf("expected role=tenant-user, got %q", c.GetString("role"))
		}
		c.String(http.StatusOK, "pong")
	})

	tok := signJWT(t, jwt.MapClaims{
		"tenantId": "tenant-1",
		"sub":      "user-1",
		"role":     "tenant-user",
	})
	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 for valid token, got %d body=%s", w.Code, w.Body.String())
	}
}

// TestPlatformRoleMiddleware_MatchingRole 角色匹配应放行。
func TestPlatformRoleMiddleware_MatchingRole(t *testing.T) {
	r := gin.New()
	r.Use(func(c *gin.Context) {
		c.Set("role", "platform-ops")
		c.Next()
	}, PlatformRoleMiddleware("platform-ops"))
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 for matching role, got %d", w.Code)
	}
}

// TestPlatformRoleMiddleware_NonMatchingRole 角色不匹配应返回 403。
func TestPlatformRoleMiddleware_NonMatchingRole(t *testing.T) {
	r := gin.New()
	r.Use(func(c *gin.Context) {
		c.Set("role", "tenant-user")
		c.Next()
	}, PlatformRoleMiddleware("platform-ops"))
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for non-matching role, got %d", w.Code)
	}
}

// TestPlatformRoleMiddleware_MissingRole 缺少 role 应返回 403。
func TestPlatformRoleMiddleware_MissingRole(t *testing.T) {
	r := gin.New()
	r.Use(PlatformRoleMiddleware("platform-ops"))
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for missing role, got %d", w.Code)
	}
}

// TestPlatformRoleMiddleware_EmptyExpectedRole 空期望角色应默认 platform-ops。
func TestPlatformRoleMiddleware_EmptyExpectedRole(t *testing.T) {
	r := gin.New()
	r.Use(func(c *gin.Context) {
		c.Set("role", "platform-ops")
		c.Next()
	}, PlatformRoleMiddleware(""))
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 for default platform-ops role, got %d", w.Code)
	}
}

// TestTenantIsolationMiddleware_MissingTenantId 缺少 tenantId 应返回 403。
func TestTenantIsolationMiddleware_MissingTenantId(t *testing.T) {
	r := newTestRouter(TenantIsolationMiddleware())

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for missing tenantId, got %d", w.Code)
	}
}

// TestTenantIsolationMiddleware_PlatformIdentity platform 身份访问客户方端点应返回 403。
func TestTenantIsolationMiddleware_PlatformIdentity(t *testing.T) {
	r := gin.New()
	r.Use(func(c *gin.Context) {
		c.Set("tenantId", "platform")
		c.Next()
	}, TenantIsolationMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for platform identity, got %d", w.Code)
	}
}

// TestTenantIsolationMiddleware_InvalidFormat 非法 tenantId 格式应返回 400。
func TestTenantIsolationMiddleware_InvalidFormat(t *testing.T) {
	r := gin.New()
	r.Use(func(c *gin.Context) {
		// 包含 } 的 tenantId 应被正则拒绝。
		c.Set("tenantId", "x} or up{")
		c.Next()
	}, TenantIsolationMiddleware())
	r.GET("/ping", func(c *gin.Context) { c.String(http.StatusOK, "pong") })

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid tenantId format, got %d", w.Code)
	}
}

// TestTenantIsolationMiddleware_Valid 有效 tenantId 应注入 effectiveTenantId 并放行。
func TestTenantIsolationMiddleware_Valid(t *testing.T) {
	r := gin.New()
	r.Use(func(c *gin.Context) {
		c.Set("tenantId", "tenant-1")
		c.Next()
	}, TenantIsolationMiddleware())
	r.GET("/ping", func(c *gin.Context) {
		if got := c.GetString("effectiveTenantId"); got != "tenant-1" {
			t.Fatalf("expected effectiveTenantId=tenant-1, got %q", got)
		}
		c.String(http.StatusOK, "pong")
	})

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200 for valid tenantId, got %d", w.Code)
	}
}

// TestLoggingMiddleware_RecordsRequest 日志中间件不应阻塞请求。
func TestLoggingMiddleware_RecordsRequest(t *testing.T) {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	r := newTestRouter(LoggingMiddleware(logger))

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}
}

// TestCorsMiddleware_OptionsRequest OPTIONS 预检应返回 204。
func TestCorsMiddleware_OptionsRequest(t *testing.T) {
	r := newTestRouter(CorsMiddleware())

	req := httptest.NewRequest(http.MethodOptions, "/ping", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNoContent {
		t.Fatalf("expected 204 for OPTIONS, got %d", w.Code)
	}
}

// TestCorsMiddleware_AllowedOrigin 命中白名单的 Origin 应回写 ACAO 头。
func TestCorsMiddleware_AllowedOrigin(t *testing.T) {
	_ = os.Setenv("CORS_ALLOWED_ORIGINS", "https://console.example.com")
	r := newTestRouter(CorsMiddleware())

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Origin", "https://console.example.com")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "https://console.example.com" {
		t.Fatalf("expected ACAO=https://console.example.com, got %q", got)
	}
}

// TestCorsMiddleware_DisallowedOrigin 未命中白名单不应回写 ACAO 头。
func TestCorsMiddleware_DisallowedOrigin(t *testing.T) {
	_ = os.Setenv("CORS_ALLOWED_ORIGINS", "https://console.example.com")
	r := newTestRouter(CorsMiddleware())

	req := httptest.NewRequest(http.MethodGet, "/ping", nil)
	req.Header.Set("Origin", "https://evil.example.com")
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if got := w.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("expected empty ACAO for disallowed origin, got %q", got)
	}
}