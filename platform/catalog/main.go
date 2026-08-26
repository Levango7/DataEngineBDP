package main

import (
	"context"
	"log"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"gorm.io/driver/postgres"
	"github.com/glebarez/sqlite"
	"gorm.io/gorm"

	"github.com/Levango7/DataEngineBDP/catalog/internal/handler"
	"github.com/Levango7/DataEngineBDP/catalog/internal/middleware"
	"github.com/Levango7/DataEngineBDP/catalog/internal/model"
	"github.com/Levango7/DataEngineBDP/catalog/internal/store"
)

// 服务常量。
const (
	serviceName    = "catalog"
	defaultVersion = "0.1.0"
	defaultPort    = "8082"
	// defaultDBPath 开发环境默认 SQLite 数据库文件路径，重启不丢数据。
	defaultDBPath = "catalog.db"
)

func main() {
	version := os.Getenv("CATALOG_VERSION")
	if version == "" {
		version = defaultVersion
	}
	port := os.Getenv("CATALOG_PORT")
	if port == "" {
		port = defaultPort
	}

	// 初始化 OpenTelemetry 追踪。
	shutdownTracer, err := middleware.InitTracer(serviceName)
	if err != nil {
		log.Printf("[%s] warning: tracer init failed: %v", serviceName, err)
	}
	defer func() {
		if err := shutdownTracer(context.Background()); err != nil {
			log.Printf("[%s] warning: tracer shutdown failed: %v", serviceName, err)
		}
	}()

	// 初始化结构化 JSON 日志。
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	// 初始化 GORM 持久化存储（开发环境 SQLite 文件数据库）。
	// 生产环境可通过环境变量 CATALOG_DB 切换到 PostgreSQL（需引入 gorm.io/driver/postgres）。
	dbPath := os.Getenv("CATALOG_DB")
	if dbPath == "" {
		dbPath = defaultDBPath
	}
	var gormDB *gorm.DB
	if strings.HasPrefix(dbPath, "postgres://") || strings.HasPrefix(dbPath, "postgresql://") {
		// 生产：PostgreSQL（CATALOG_DB=postgres://user:pass@host:5432/db）
		gormDB, err = gorm.Open(postgres.Open(dbPath), &gorm.Config{})
	} else {
		// 开发：SQLite 文件
		gormDB, err = gorm.Open(sqlite.Open(dbPath), &gorm.Config{})
	}
	if err != nil {
		log.Fatalf("failed to open database %s: %v", dbPath, err)
	}

	// 自动迁移：根据 model 结构创建/更新表结构。
	if err := gormDB.AutoMigrate(&model.Database{}, &model.Table{}); err != nil {
		log.Fatalf("failed to auto migrate: %v", err)
	}
	log.Printf("[%s] database initialized at %s", serviceName, dbPath)

	// 初始化基于 GORM 的存储。
	s := store.NewGormStore(gormDB)

	// 初始化 handlers。
	healthH := handler.NewHealthHandler(version)
	catalogH := handler.NewCatalogHandler(s)

	// 初始化 Gin 路由。
	r := gin.New() // 使用 gin.New() 替代 gin.Default() 以自定义中间件链

	// 全局中间件链：Recovery → Tracing → Metrics → Logging → CORS。
	r.Use(gin.Recovery())
	r.Use(middleware.TracingMiddleware(serviceName))
	r.Use(middleware.MetricsMiddleware())
	r.Use(middleware.LoggingMiddleware(logger))
	r.Use(middleware.CorsMiddleware())

	// Prometheus /metrics 端点（无需认证）。
	r.GET("/metrics", middleware.MetricsHandler())

	// API v1 group。
	v1 := r.Group("/api/v1")
	{
		// 健康检查端点不需要认证，直接注册。
		v1.GET("/health", healthH.Health)

		// /api/v1/catalog/** 路径要求有效 JWT。
		catalogGroup := v1.Group("/catalog")
		catalogGroup.Use(middleware.AuthMiddleware())
		catalogH.RegisterRoutes(catalogGroup)
	}

	// 启动 HTTP 服务（支持优雅关闭）。
	addr := ":" + port
	srv := &http.Server{
		Addr:              addr,
		Handler:           r,
		ReadHeaderTimeout: 10 * time.Second, // 防 Slowloris 慢速攻击（gosec G112）
	}

	go func() {
		log.Printf("[%s] version=%s listening on %s", serviceName, version, addr)
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
