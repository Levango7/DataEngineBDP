// Package main 是 llm-gateway 服务入口。
//
// 大模型网关（LLM Gateway）：统一 API 入口，路由多模型、限流、计费、审计，
// 屏蔽底层部署差异。OpenAI 兼容协议，便于存量应用接入。
//
// 启动：
//
//	LLM_GATEWAY_MOCK_MODE=true go run .
//
// 默认端口 8084。开发模式（JWT_DEV_MODE=true）跳过 JWT 校验。
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

	"github.com/shuqing/bigdata/llm-gateway/internal/api"
	"github.com/shuqing/bigdata/llm-gateway/internal/config"
	"github.com/shuqing/bigdata/llm-gateway/internal/gateway"
	"github.com/shuqing/bigdata/llm-gateway/internal/middleware"
)

// 服务常量。
const (
	serviceName    = "llm-gateway"
	defaultVersion = "0.1.0"
	defaultPort    = "8084"
)

func main() {
	// 1. 加载配置（环境变量驱动，默认 Mock 模式）。
	cfg := config.LoadFromEnv()

	version := cfg.Server.Version
	if version == "" {
		version = defaultVersion
	}
	port := cfg.Server.Port
	if port == "" {
		port = defaultPort
	}

	// 2. 结构化 JSON 日志。
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	// 3. 构造 Provider 实例。
	providers, err := config.BuildProviders(cfg.Providers)
	if err != nil {
		log.Fatalf("[%s] failed to build providers: %v", serviceName, err)
	}

	// 4. 构造网关。
	auditor := gateway.NewAuditor(cfg.Audit.SensitiveWords, cfg.Audit.LogPath)
	gw := gateway.New(auditor)

	// 5. 注册 Provider + 路由规则。
	for i, p := range providers {
		weight := 1
		if i < len(cfg.Providers) {
			weight = cfg.Providers[i].Weight
		}
		gw.RegisterProvider(p, weight)
	}
	// 注入路由规则。
	for _, r := range cfg.Routes {
		gw.AddRoute(gateway.RouteRule{
			Model:    r.Model,
			Provider: r.Provider,
			TenantID: r.TenantID,
			Priority: r.Priority,
		})
	}
	// 默认路由：第一个 Provider 作为兜底。
	if len(providers) > 0 {
		gw.AddRoute(gateway.RouteRule{
			Model:    "*",
			Provider: providers[0].Name(),
			Priority: -1,
		})
	}

	// 6. Gin 路由。
	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(middleware.LoggingMiddleware(logger))
	r.Use(middleware.CorsMiddleware())

	handler := api.New(gw, version)
	handler.RegisterRoutes(r, middleware.AuthMiddleware())

	// 7. 启动 HTTP 服务（支持优雅关闭）。
	addr := ":" + port
	srv := &http.Server{Addr: addr, Handler: r}

	go func() {
		log.Printf("[%s] version=%s listening on %s", serviceName, version, addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("failed to start server: %v", err)
		}
	}()

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
