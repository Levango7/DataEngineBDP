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
	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/model"
	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/service"
)

// newTestEngine 按生产同源方式构建引擎（内存SQLite + setupRouter真实路由注册）。
//
// 注意：SQLite ":memory:" 每连接一个独立库，连接池多连接会让 AutoMigrate 建的表
// 在其它连接上消失（no such table）。故此处与 service 测试同一约定：MaxOpen/MaxIdle
// 均取 1，强制单连接，保证写入后即可回读（PUT /network 落库断言依赖此前提）。
func newTestEngine(t *testing.T) *gin.Engine {
	t.Helper()
	gin.SetMode(gin.TestMode)

	sum := sha256.Sum256([]byte("s3cret-pw"))
	cred, err := middleware.LoadCredentialConfigWith("admin", hex.EncodeToString(sum[:]), "")
	if err != nil {
		t.Fatalf("构造凭据失败: %v", err)
	}
	auth := middleware.NewJWTAuthenticator("test-secret", time.Hour, "test")

	db, err := service.InitDatabase("sqlite", ":memory:", 1, 1)
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
		t.Fatalf("刷新端点无token应401，实际 %d", w.Code)
	}
}

// createBaremetalCluster 创建集群并返回集群 ID（异步供应失败不影响记录落库）。
func createBaremetalCluster(t *testing.T, engine *gin.Engine, bearer string) string {
	t.Helper()
	body := `{"name":"net-test","k8s":{"kubernetes_version":"v1.29.2",`
	body += `"pod_cidr":"10.244.0.0/16","service_cidr":"10.96.0.0/12","api_server_port":6443,`
	body += `"network_plugin":"flannel"},`
	body += `"nodes":[{"hostname":"cp1","role":"control-plane",`
	body += `"bmc":{"host":"10.0.0.1"},"management_ip":"10.0.0.11"}]}`
	w := doJSON(t, engine, http.MethodPost, "/api/v1/clusters/baremetal", body, bearer)
	if w.Code != http.StatusCreated {
		t.Fatalf("创建集群应201，实际 %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Data struct {
			ID string `json:"id"`
		} `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("解析创建响应失败: %v", err)
	}
	if resp.Data.ID == "" {
		t.Fatalf("创建响应应含集群ID: %s", w.Body.String())
	}
	return resp.Data.ID
}

// TestPutNetworkConfig200PersistsAndReturnsProfile 验证 PUT /network：
// 200，返回值即前端 NetworkConfig 契约（cni/podCidr/serviceCidr/mtu），且已落库。
func TestPutNetworkConfig200PersistsAndReturnsProfile(t *testing.T) {
	engine := newTestEngine(t)
	token := login(t, engine)
	clusterID := createBaremetalCluster(t, engine, token)

	putBody := `{"cni":"calico","podCidr":"10.245.0.0/16","serviceCidr":"10.97.0.0/12",`
	putBody += `"ipFamily":"IPv4","mtu":8951}`
	w := doJSON(t, engine, http.MethodPut,
		"/api/v1/clusters/baremetal/"+clusterID+"/network", putBody, token)

	if w.Code != http.StatusOK {
		t.Fatalf("PUT network应200，实际 %d: %s", w.Code, w.Body.String())
	}
	var resp struct {
		Data model.NetworkProfile `json:"data"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatalf("解析PUT响应失败: %v", err)
	}
	if resp.Data.Cni != "calico" || resp.Data.PodCIDR != "10.245.0.0/16" ||
		resp.Data.ServiceCIDR != "10.97.0.0/12" || resp.Data.MTU != 8951 {
		t.Fatalf("返回值应与请求profile一致: %+v", resp.Data)
	}

	// 落库核实：GET 集群详情回读持久化字段
	get := doJSON(t, engine, http.MethodGet, "/api/v1/clusters/baremetal/"+clusterID, "", token)
	if get.Code != http.StatusOK {
		t.Fatalf("查询集群应200，实际 %d", get.Code)
	}
	var detail struct {
		Data struct {
			Cluster struct {
				PodCIDR       string `json:"pod_cidr"`
				ServiceCIDR   string `json:"service_cidr"`
				NetworkPlugin string `json:"network_plugin"`
				MTU           int    `json:"mtu"`
			} `json:"cluster"`
		} `json:"data"`
	}
	if err := json.Unmarshal(get.Body.Bytes(), &detail); err != nil {
		t.Fatalf("解析集群详情失败: %v", err)
	}
	if detail.Data.Cluster.PodCIDR != "10.245.0.0/16" ||
		detail.Data.Cluster.NetworkPlugin != "calico" ||
		detail.Data.Cluster.MTU != 8951 {
		t.Fatalf("网络配置应已持久化: %+v", detail.Data.Cluster)
	}
}

// TestPutNetworkConfigNotFound404 集群不存在 ⇒ 404。
func TestPutNetworkConfigNotFound404(t *testing.T) {
	engine := newTestEngine(t)
	token := login(t, engine)

	w := doJSON(t, engine, http.MethodPut,
		"/api/v1/clusters/baremetal/no-such-cluster/network",
		`{"cni":"flannel","podCidr":"10.244.0.0/16","serviceCidr":"10.96.0.0/12","mtu":1450}`, token)

	if w.Code != http.StatusNotFound {
		t.Fatalf("集群不存在应404，实际 %d: %s", w.Code, w.Body.String())
	}
}

// TestPutNetworkConfigValidation400 校验失败 ⇒ 400 + 中文原因（空 CIDR / 非法 CNI）。
func TestPutNetworkConfigValidation400(t *testing.T) {
	engine := newTestEngine(t)
	token := login(t, engine)
	clusterID := createBaremetalCluster(t, engine, token)
	url := "/api/v1/clusters/baremetal/" + clusterID + "/network"

	cases := []struct {
		name    string
		body    string
		wantSub string
	}{
		{
			name:    "空PodCIDR",
			body:    `{"cni":"flannel","podCidr":"","serviceCidr":"10.96.0.0/12","mtu":1450}`,
			wantSub: "PodCIDR不能为空",
		},
		{
			name:    "非法CNI",
			body:    `{"cni":"kube-ovn","podCidr":"10.244.0.0/16","serviceCidr":"10.96.0.0/12","mtu":1450}`,
			wantSub: "不支持的CNI插件",
		},
		{
			name:    "非法CIDR格式",
			body:    `{"cni":"flannel","podCidr":"not-a-cidr","serviceCidr":"10.96.0.0/12","mtu":1450}`,
			wantSub: "PodCIDR格式不合法",
		},
		{
			name:    "MTU越界",
			body:    `{"cni":"flannel","podCidr":"10.244.0.0/16","serviceCidr":"10.96.0.0/12","mtu":100}`,
			wantSub: "MTU必须在1280-9000之间",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			w := doJSON(t, engine, http.MethodPut, url, tc.body, token)
			if w.Code != http.StatusBadRequest {
				t.Fatalf("%s应400，实际 %d: %s", tc.name, w.Code, w.Body.String())
			}
			if !strings.Contains(w.Body.String(), tc.wantSub) {
				t.Fatalf("响应应含%q，实际: %s", tc.wantSub, w.Body.String())
			}
		})
	}
}

// TestPutNetworkConfigWithoutToken401 受保护端点无 token ⇒ 401。
func TestPutNetworkConfigWithoutToken401(t *testing.T) {
	engine := newTestEngine(t)

	w := doJSON(t, engine, http.MethodPut,
		"/api/v1/clusters/baremetal/any/network",
		`{"cni":"flannel","podCidr":"10.244.0.0/16","serviceCidr":"10.96.0.0/12","mtu":1450}`, "")

	if w.Code != http.StatusUnauthorized {
		t.Fatalf("无token应401，实际 %d", w.Code)
	}
}
