package main

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/api"
	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/config"
	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/middleware"
	"github.com/Levango7/DataEngineBDP/ai-assistant/internal/service"

	"github.com/gin-gonic/gin"
)

func main() {
	cfg := config.Load()

	// 会话仓储（SQLite 持久化）
	sessionStore, err := service.NewSessionStore(cfg.SessionDBPath)
	if err != nil {
		log.Fatalf("初始化会话存储失败: %v", err)
	}

	// 下游服务代理（llm-gateway / nl2sql / sql-gateway）
	proxy := service.NewDownstreamProxy(cfg)

	// 业务服务
	assistant := service.NewAssistantService(sessionStore, proxy, cfg)

	// HTTP 路由：公开端点（/health 匿名，供探针）先注册，
	// 其余业务路由统一挂 JWT 认证中间件（对齐 catalog 模式）。
	router := gin.Default()
	router.GET("/api/v1/health", api.Health)

	protected := router.Group("/api/v1/ai-assistant")
	protected.Use(middleware.AuthMiddleware())
	api.RegisterRoutes(protected, assistant, cfg)

	srv := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           router,
		ReadHeaderTimeout: 10 * time.Second, // 防 Slowloris 慢速攻击
	}

	go func() {
		log.Printf("ai-assistant 服务启动: :%s", cfg.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("服务监听失败: %v", err)
		}
	}()

	// 优雅关闭
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Println("正在关闭 ai-assistant ...")
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = srv.Shutdown(ctx)
}
