// Package middleware - auth_test.go JWT鉴权中间件单元测试。
package middleware

import (
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
)

func TestJWTAuthenticator_GenerateAndParseToken(t *testing.T) {
	auth := NewJWTAuthenticator("test-secret", time.Hour, "test-issuer")

	token, err := auth.GenerateToken("admin", "admin")
	if err != nil {
		t.Fatalf("GenerateToken失败: %v", err)
	}
	if token == "" {
		t.Fatal("Token为空")
	}

	claims, err := auth.ParseToken(token)
	if err != nil {
		t.Fatalf("ParseToken失败: %v", err)
	}
	if claims.Username != "admin" {
		t.Errorf("期望Username=admin，得到 %s", claims.Username)
	}
	if claims.Role != "admin" {
		t.Errorf("期望Role=admin，得到 %s", claims.Role)
	}
	if claims.Issuer != "test-issuer" {
		t.Errorf("期望Issuer=test-issuer，得到 %s", claims.Issuer)
	}
}

func TestJWTAuthenticator_ParseToken_Invalid(t *testing.T) {
	auth := NewJWTAuthenticator("test-secret", time.Hour, "test-issuer")

	_, err := auth.ParseToken("invalid-token")
	if err == nil {
		t.Fatal("期望解析失败，但成功")
	}
}

func TestJWTAuthenticator_ParseToken_WrongSecret(t *testing.T) {
	auth1 := NewJWTAuthenticator("secret-1", time.Hour, "issuer")
	auth2 := NewJWTAuthenticator("secret-2", time.Hour, "issuer")

	token, _ := auth1.GenerateToken("user", "role")
	_, err := auth2.ParseToken(token)
	if err == nil {
		t.Fatal("期望签名校验失败，但成功")
	}
}

func TestAuthMiddleware_NoAuthHeader(t *testing.T) {
	gin.SetMode(gin.TestMode)
	auth := NewJWTAuthenticator("secret", time.Hour, "issuer")

	r := gin.New()
	r.Use(auth.AuthMiddleware())
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusUnauthorized {
		t.Errorf("期望401，得到 %d", w.Code)
	}
}

func TestAuthMiddleware_ValidToken(t *testing.T) {
	gin.SetMode(gin.TestMode)
	auth := NewJWTAuthenticator("secret", time.Hour, "issuer")

	token, _ := auth.GenerateToken("admin", "admin")

	r := gin.New()
	r.Use(auth.AuthMiddleware())
	r.GET("/test", func(c *gin.Context) {
		username, _ := c.Get("username")
		c.JSON(http.StatusOK, gin.H{"username": username})
	})

	req := httptest.NewRequest(http.MethodGet, "/test", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("期望200，得到 %d", w.Code)
	}
}

func TestAuthMiddleware_MalformedHeader(t *testing.T) {
	gin.SetMode(gin.TestMode)
	auth := NewJWTAuthenticator("secret", time.Hour, "issuer")

	r := gin.New()
	r.Use(auth.AuthMiddleware())
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	tests := []string{
		"InvalidToken",
		"Basic abc123",
		"Bearer",
	}
	for _, h := range tests {
		req := httptest.NewRequest(http.MethodGet, "/test", nil)
		req.Header.Set("Authorization", h)
		w := httptest.NewRecorder()
		r.ServeHTTP(w, req)
		if w.Code != http.StatusUnauthorized {
			t.Errorf("Authorization=%q: 期望401，得到 %d", h, w.Code)
		}
	}
}

func TestCORSMiddleware(t *testing.T) {
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(CORSMiddleware())
	r.GET("/test", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"ok": true})
	})

	req := httptest.NewRequest(http.MethodOptions, "/test", nil)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, req)

	if w.Code != http.StatusNoContent {
		t.Errorf("OPTIONS期望204，得到 %d", w.Code)
	}
	if w.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Error("缺少CORS头")
	}
}
