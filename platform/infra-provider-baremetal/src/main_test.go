// Package main - main_test.go 验证路由鉴权拓扑：登录匿名可达，业务路由强制鉴权。
package main

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/sirupsen/logrus"

	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/middleware"
	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/service"
)

// newTestEngine 按生产同源方式构建引擎（内存SQLite + setupRouter真实路由注册）。
func newTestEngine(t *testing.T) *gin.Engine {
	t.Helper()
	gin.SetMode(gin.TestMode)

	sum := sha256.Sum256([]byte("s3cret-pw"))
	cred, err := middleware.LoadCredentialConfigWith("admin", hex.EncodeToString(sum[:]), "")
	if err != nil {
		t.Fatalf("构造凭据失败: %v", err)
	}
	auth := middleware.NewJWTAuthenticator("test-secret", time.Hour, "test")

	db, err := service.InitDatabase("sqlite", ":memory:", 5, 2)
	if err != nil {
		t.Fatalf("初始化测试DB失败: %v", err)
	}
	t.Cleanup(func() {
		if sqlDB, err := db.DB(); err == nil {
			_ = sqlDB.Close()
		}
	})

	logger := logrus.NewEntry(logrus.New())
	redfish := service.NewRedfishClient(0, true, "admin", "admin")
	k8s := service.NewK8sBootstrapper(&localExecutor{logger: logger},
		"registry.k8s.io", "10.244.0.0/16", "10.96.0.0/12", 6443)

	svc := service.NewBareMetalService(db, redfish, k8s, logger)
	if err := svc.AutoMigrate(); err != nil {
		t.Fatalf("AutoMigrate失败: %v", err)
	}

	return setupRouter(auth, svc, cred, "test", logger)
}

// doJSON 发送JSON请求，bearer 非空时附带 Authorization 头。
func doJSON(t *testing.T, engine *gin.Engine, method, path, body, bearer string) *httptest.ResponseRecorder {
	t.Helper()
	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	req := httptest.NewRequest(method, path, reader)
	req.Header.Set("Content-Type", "application/json")
	if bearer != "" {
		req.Header.Set("Authorization", "Bearer "+bearer)
	}
	w := httptest.NewRecorder()
	engine.ServeHTTP(w, req)
	return w
}

// login 用正确凭据登录并返回签发的token。
func login(t *testing.T, engine *gin.Engine) string {
	t.Helper()
	w := doJSON(t, engine, http.MethodPost, "/api/v1/auth/login",
		`{"username":"admin","password":"s3cret-pw"}`, "")
	if w.Code != http.StatusOK {
		t.Fatalf("登录应200，实际 %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Data struct {
			Token string `json:"token"`
		} `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("解析登录响应失败: %v", err)
	}
	if resp.Data.Token == "" {
		t.Fatalf("应返回非空token: %s", w.Body.String())
	}
	return resp.Data.Token
}

func TestLoginReachableWithoutToken(t *testing.T) {
	engine := newTestEngine(t)

	w := doJSON(t, engine, http.MethodPost, "/api/v1/auth/login",
		`{"username":"admin","password":"s3cret-pw"}`, "")

	if w.Code != http.StatusOK {
		t.Fatalf("无Authorization头的登录应200，实际 %d: %s", w.Code, w.Body.String())
	}
	if !strings.Contains(w.Body.String(), `"token":"`) {
		t.Fatalf("应返回token，实际: %s", w.Body.String())
	}
}

func TestLoginWrongPassword401(t *testing.T) {
	engine := newTestEngine(t)

	w := doJSON(t, engine, http.MethodPost, "/api/v1/auth/login",
		`{"username":"admin","password":"wrong"}`, "")

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("错误密码应401，实际 %d", w.Code)
	}
	if !strings.Contains(w.Body.String(), "用户名或密码错误") {
		t.Fatalf("应返回统一错误消息防枚举，实际: %s", w.Body.String())
	}
}

func TestProtectedBusinessRouteWithoutToken401(t *testing.T) {
	engine := newTestEngine(t)

	w := doJSON(t, engine, http.MethodGet, "/api/v1/clusters/baremetal", "", "")

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("受保护业务端点无token应401，实际 %d", w.Code)
	}
}

func TestLoginIssuedTokenAccessesProtectedRoute(t *testing.T) {
	engine := newTestEngine(t)

	token := login(t, engine)

	w := doJSON(t, engine, http.MethodGet, "/api/v1/clusters/baremetal", "", token)
	if w.Code != http.StatusOK {
		t.Fatalf("登录签发的token应可访问受保护端点，实际 %d: %s", w.Code, w.Body.String())
	}
}

func TestRefreshWithoutToken401(t *testing.T) {
	engine := newTestEngine(t)

	w := doJSON(t, engine, http.MethodPost, "/api/v1/auth/refresh", "", "")

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("刷新端点无token应401（需有效JWT），实际 %d", w.Code)
	}
}
