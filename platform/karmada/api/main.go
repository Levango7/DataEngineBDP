package main

// Karmada PropagationPolicy 控制台 API 入口。
//
// 提供租户通过控制台管理 PropagationPolicy 的 REST API：
//   - POST   /api/v1/propagation-policies        创建传播策略
//   - GET    /api/v1/propagation-policies        列出传播策略
//   - GET    /api/v1/propagation-policies/{name} 获取单个策略
//   - PUT    /api/v1/propagation-policies/{name} 更新策略
//   - DELETE /api/v1/propagation-policies/{name} 删除策略
//
// 后端通过 Karmada kubeconfig 与控制面交互（本骨架先用 SQLite 持久化策略元数据，
// 生产环境通过 controller-runtime client 写入 Karmada 控制面）。

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
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"

	"github.com/Levango7/DataEngineBDP/karmada-api/internal/handler"
	"github.com/Levango7/DataEngineBDP/karmada-api/internal/middleware"
	"github.com/Levango7/DataEngineBDP/karmada-api/internal/model"
	"github.com/Levango7/DataEngineBDP/karmada-api/internal/store"
)

// 服务常量。
const (
	serviceName    = "karmada-api"
	defaultVersion = "0.1.0"
	defaultPort    = "8090"
	// defaultDBPath 开发环境默认 SQLite 数据库文件路径。
	defaultDBPath = "karmada-api.db"
)

func main() {
	version := os.Getenv("KARMADA_API_VERSION")
	if version == "" {
		version = defaultVersion
	}
	port := os.Getenv("KARMADA_API_PORT")
	if port == "" {
		port = defaultPort
	}

	// 初始化结构化 JSON 日志。
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	// 初始化 GORM 持久化存储（开发环境 SQLite，生产环境可切换 PostgreSQL）。
	dbPath := os.Getenv("KARMADA_API_DB")
	if dbPath == "" {
		dbPath = defaultDBPath
	}
	gormDB, err := gorm.Open(sqlite.Open(dbPath), &gorm.Config{})
	if err != nil {
		logger.Error("failed to open database", "path", dbPath, "error", err)
		os.Exit(1)
	}

	// 自动迁移：根据 model 结构创建/更新表结构。
	if err := gormDB.AutoMigrate(&model.PropagationPolicy{}); err != nil {
		log.Fatalf("failed to auto migrate: %v", err)
	}
	logger.Info("database initialized", "service", serviceName, "path", dbPath)

	// 初始化基于 GORM 的存储。
	s := store.NewGormStore(gormDB)

	// 初始化 handlers。
	healthH := handler.NewHealthHandler(version)
	ppH := handler.NewPropagationPolicyHandler(s)

	// 初始化 Gin 路由。
	r := gin.New()
	r.Use(gin.Recovery())
	r.Use(middleware.LoggingMiddleware())
	r.Use(middleware.CorsMiddleware())

	// API v1 group。
	v1 := r.Group("/api/v1")
	{
		// 健康检查端点不需要认证。
		v1.GET("/health", healthH.Health)

		// /api/v1/propagation-policies 路径要求有效 JWT。
		ppGroup := v1.Group("/propagation-policies")
		ppGroup.Use(middleware.AuthMiddleware())
		ppH.RegisterRoutes(ppGroup)
	}

	// 启动 HTTP 服务（支持优雅关闭）。
	addr := ":" + port
	srv := &http.Server{Addr: addr, Handler: r, ReadHeaderTimeout: 10 * time.Second}

	go func() {
		logger.Info("server listening", "service", serviceName, "version", version, "addr", addr)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("failed to start server: %v", err)
		}
	}()

	// 等待中断信号。
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Printf("[%s] shutting down...", serviceName)

	// 优雅关闭超时 5 秒，确保在飞行中的请求能完成。
	// 修复：原代码传入裸整数 5，time.Duration(5) 等于 5 纳秒，几乎立即超时。
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("[%s] server forced to shutdown: %v", serviceName, err)
	}
	log.Printf("[%s] server exited", serviceName)
}
