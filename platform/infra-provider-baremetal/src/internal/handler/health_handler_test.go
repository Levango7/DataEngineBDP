package handler

import (
	"crypto/sha256"
	"encoding/hex"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/sirupsen/logrus"

	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/middleware"
)

func newLoginRouter(t *testing.T) *gin.Engine {
	t.Helper()
	gin.SetMode(gin.TestMode)
	sum := sha256.Sum256([]byte("s3cret-pw"))
	cred, err := middleware.LoadCredentialConfigWith(
		"admin", hex.EncodeToString(sum[:]), "")
	if err != nil {
		t.Fatalf("构造凭据失败: %v", err)
	}
	logger := logrus.NewEntry(logrus.New())
	h := NewHealthHandler(middleware.NewJWTAuthenticator("test-secret", time.Hour, "test"), cred, "test", logger)
	engine := gin.New()
	rg := engine.Group("/api/v1")
	h.RegisterRoutes(rg, engine)
	return engine
}

func doLogin(engine *gin.Engine, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/api/v1/auth/login", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	engine.ServeHTTP(w, req)
	return w
}

func TestLoginWrongPassword401(t *testing.T) {
	engine := newLoginRouter(t)
	w := doLogin(engine, `{"username":"admin","password":"wrong"}`)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("错误密码应 401，实际 %d", w.Code)
	}
	if !strings.Contains(w.Body.String(), "用户名或密码错误") {
		t.Fatalf("应返回统一错误消息防枚举，实际: %s", w.Body.String())
	}
}

func TestLoginUnknownUser401(t *testing.T) {
	engine := newLoginRouter(t)
	w := doLogin(engine, `{"username":"root","password":"s3cret-pw"}`)
	if w.Code != http.StatusUnauthorized {
		t.Fatalf("未知用户应 401，实际 %d", w.Code)
	}
}

func TestLoginCorrectPasswordIssuesAdminToken(t *testing.T) {
	engine := newLoginRouter(t)
	w := doLogin(engine, `{"username":"admin","password":"s3cret-pw"}`)
	if w.Code != http.StatusOK {
		t.Fatalf("正确凭据应 200，实际 %d: %s", w.Code, w.Body.String())
	}
	body := w.Body.String()
	if !strings.Contains(body, `"role":"admin"`) || !strings.Contains(body, `"token":"`) {
		t.Fatalf("应签发含 admin 角色的 token，实际: %s", body)
	}
}

func TestLoginNilCredConfig500(t *testing.T) {
	gin.SetMode(gin.TestMode)
	logger := logrus.NewEntry(logrus.New())
	h := NewHealthHandler(nil, nil, "test", logger)
	engine := gin.New()
	h.RegisterRoutes(engine.Group("/api/v1"), engine)
	w := doLogin(engine, `{"username":"admin","password":"x"}`)
	if w.Code != http.StatusInternalServerError {
		t.Fatalf("凭据未配置应 500，实际 %d", w.Code)
	}
}
