// Package main 是数擎大数据平台统一查询 API 服务。
//
// 功能：
//   - 封装 Prometheus 查询 API（/api/v1/query, /api/v1/query_range, /api/v1/labels, /api/v1/series）
//   - 按租户隔离：客户方请求强制注入 tenant_id 标签过滤，租户间指标互不可见
//   - 双视图：
//     /platform/** → 平台方视图（全平台指标，仅 platform-ops 角色可访问）
//     /tenant/**   → 客户方视图（仅本租户指标，由 JWT tenantId claim 决定）
//   - 健康检查 /api/v1/health
//
// 安全：
//   - 所有 /tenant/** 端点要求有效 JWT Bearer token，从中提取 tenantId。
//   - /platform/** 端点要求 JWT 中 role=platform-ops。
//   - PromQL 注入防护：对 tenant_id 做正则校验（^[a-zA-Z0-9_-]{1,64}$）。
//
// 配置（环境变量）：
//   - QUERY_API_PORT          监听端口，默认 8090
//   - PROMETHEUS_URL          后端 Prometheus 地址，默认 http://prometheus:9090
//   - JWT_SECRET              HMAC-SHA 签名密钥（至少 32 字节）
//   - JWT_ISSUER              JWT issuer，默认 shuqing-bigdata
//   - QUERY_API_PLATFORM_ROLE 平台方角色名，默认 platform-ops
package main

import (
	"context"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"

	"github.com/shuqing/bigdata/query-api/internal/handler"
	"github.com/shuqing/bigdata/query-api/internal/middleware"
	"github.com/shuqing/bigdata/query-api/internal/service"
)

// 服务常量。
const (
	serviceName    = "query-api"
	defaultVersion = "0.1.0"
	defaultPort    = "8090"
	// defaultPrometheusURL 集成测试环境默认 Prometheus 地址。
	defaultPrometheusURL = "http://prometheus:9090"
)

func main() {
	version := os.Getenv("QUERY_API_VERSION")
	if version == "" {
		version = defaultVersion
	}
	port := os.Getenv("QUERY_API_PORT")
	if port == "" {
		port = defaultPort
	}
	promURL := os.Getenv("PROMETHEUS_URL")
	if promURL == "" {
		promURL = defaultPrometheusURL
	}

	// 初始化结构化 JSON 日志。
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	// 初始化 Prometheus 代理服务。
	promClient := service.NewPrometheusClient(promURL, 30*time.Second)
	tenantFilter := service.NewTenantFilter()
	platformRole := os.Getenv("QUERY_API_PLATFORM_ROLE")
	if platformRole == "" {
		platformRole = "platform-ops"
	}

	// 初始化 handlers。
	healthH := handler.NewHealthHandler(version)
	queryH := handler.NewQueryHandler(promClient, tenantFilter)

	// 初始化 Gin 路由。
	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(middleware.LoggingMiddleware(logger))
	r.Use(middleware.CorsMiddleware())

	// 健康检查（无需认证）。
	r.GET("/api/v1/health", healthH.Health)

	// 平台方视图：/platform/** 要求 platform-ops 角色，不做 tenant 过滤。
	platformGroup := r.Group("/platform")
	platformGroup.Use(middleware.AuthMiddleware())
	platformGroup.Use(middleware.PlatformRoleMiddleware(platformRole))
	queryH.RegisterPlatformRoutes(platformGroup)

	// 客户方视图：/tenant/** 要求有效 JWT，强制注入 tenant_id 过滤。
	tenantGroup := r.Group("/tenant")
	tenantGroup.Use(middleware.AuthMiddleware())
	tenantGroup.Use(middleware.TenantIsolationMiddleware())
	queryH.RegisterTenantRoutes(tenantGroup)

	// 启动 HTTP 服务（支持优雅关闭）。
	addr := ":" + port
	srv := &http.Server{Addr: addr, Handler: r}

	go func() {
		log.Printf("[%s] version=%s listening on %s (prometheus=%s)", serviceName, version, addr, promURL)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("failed to start server: %v", err)
		}
	}()

	// 等待中断信号。
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Printf("[%s] shutting down...", serviceName)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("[%s] server forced to shutdown: %v", serviceName, err)
	}
	log.Printf("[%s] server exited", serviceName)
}
