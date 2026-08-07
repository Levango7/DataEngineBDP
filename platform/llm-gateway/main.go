// Package main 是 llm-gateway 服务入口。
//
// 大模型网关（LLM Gateway）：统一 API 入口，路由多模型、限流、计费、审计，
// 屏蔽底层部署差异。OpenAI 兼容协议，便于存量应用接入。
//
// Phase 2 增强：多模态网关统一 API 与路由
//   - OpenAI 兼容 API（/v1/chat/completions）
//   - 四维度路由（模型/租户/场景/成本）
//   - 多模态 Token 计量（文本/图像/语音/视频）
//   - SSE 流式响应 + 异步批处理
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
	"github.com/shuqing/bigdata/llm-gateway/internal/provider"
	"github.com/shuqing/bigdata/llm-gateway/internal/routing"
	"github.com/shuqing/bigdata/llm-gateway/internal/token"
)

// 服务常量。
const (
	serviceName    = "llm-gateway"
	defaultVersion = "0.2.0" // Phase 2 多模态增强
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

	// 6. 构造四维度路由引擎 + 多模态 Token 计量器。
	routingEngine := routing.NewEngine()
	tokenCounter := token.NewCounter()

	// 注入路由规则（兼容旧 RouteRule）。
	for _, r := range cfg.Routes {
		routingEngine.AddRule(routing.FromRouteRule(r.Model, r.Provider, r.TenantID, r.Priority))
	}
	// 默认路由：第一个 Provider 作为兜底。
	if len(providers) > 0 {
		routingEngine.SetDefault(providers[0].Name())
	}

	// 注入默认 Provider 成本信息（Mock 模式下为零成本）。
	for _, p := range providers {
		routingEngine.SetProviderCost(&routing.ProviderCost{
			Provider:        p.Name(),
			InputPricePerM:  0.0,
			OutputPricePerM: 0.0,
			AvgLatencyMs:    100,
		})
	}

	// 7. 构造多模态网关扩展。
	mmExt := gateway.NewMultimodalExt(gw, routingEngine, tokenCounter)

	// 8. Gin 路由。
	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(middleware.LoggingMiddleware(logger))
	r.Use(middleware.CorsMiddleware())

	// 注册现有 API（/api/v1/*）
	handler := api.New(gw, version)
	handler.RegisterRoutes(r, middleware.AuthMiddleware())

	// 注册多模态 OpenAI 兼容 API（/v1/*）
	mmHandler := api.NewMultimodalHandler(routingEngine, tokenCounter, mmExt.ChatCompletion)
	mmHandler.RegisterRoutes(r, middleware.AuthMiddleware())

	// 9. 启动 HTTP 服务（支持优雅关闭）。
	addr := ":" + port
	srv := &http.Server{Addr: addr, Handler: r}

	go func() {
		log.Printf("[%s] version=%s listening on %s (multimodal enabled)", serviceName, version, addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("failed to start server: %v", err)
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Printf("[%s] shutting down...", serviceName)

	// 优雅关闭批处理 worker pool
	mmHandler.Stop()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("[%s] server forced to shutdown: %v", serviceName, err)
	}
	log.Printf("[%s] server exited", serviceName)

	// 引用 provider 包避免未使用警告（provider 在 config 中使用）
	_ = provider.ErrModelNotFound
}
