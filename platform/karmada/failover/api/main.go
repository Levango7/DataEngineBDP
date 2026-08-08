package main

// Failover API 入口：OverridePolicy + FailoverEvent + ClusterHealth + ReplicaWeightPlan + FailoverPolicy。
//
// 提供租户通过控制台管理多集群故障迁移的 REST API：
//   - OverridePolicy CRUD：/api/v1/override-policies
//   - FailoverEvent 查询/触发：/api/v1/failover-events
//   - ClusterHealth 查询：/api/v1/clusters/health
//   - ReplicaWeightPlan CRUD：/api/v1/replica-plans
//   - FailoverPolicy CRUD：/api/v1/failover-policies
//
// 后端通过 Karmada kubeconfig 与控制面交互（本骨架先用 SQLite 持久化元数据，
// 故障迁移引擎异步执行实际迁移动作）。

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/gin-gonic/gin"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"

	"github.com/shuqing/bigdata/failover-api/internal/handler"
	"github.com/shuqing/bigdata/failover-api/internal/middleware"
	"github.com/shuqing/bigdata/failover-api/internal/model"
	"github.com/shuqing/bigdata/failover-api/internal/store"
)

// 服务常量。
const (
	serviceName    = "failover-api"
	defaultVersion = "0.1.0"
	defaultPort    = "8094"
	// defaultDBPath 开发环境默认 SQLite 数据库文件路径。
	defaultDBPath = "failover-api.db"
)

func main() {
	version := os.Getenv("FAILOVER_API_VERSION")
	if version == "" {
		version = defaultVersion
	}
	port := os.Getenv("FAILOVER_API_PORT")
	if port == "" {
		port = defaultPort
	}

	// 初始化 GORM 持久化存储（开发环境 SQLite，生产环境可切换 PostgreSQL）。
	dbPath := os.Getenv("FAILOVER_API_DB")
	if dbPath == "" {
		dbPath = defaultDBPath
	}
	gormDB, err := gorm.Open(sqlite.Open(dbPath), &gorm.Config{})
	if err != nil {
		log.Fatalf("failed to open database %s: %v", dbPath, err)
	}

	// 自动迁移：根据 model 结构创建/更新表结构。
	if err := gormDB.AutoMigrate(
		&model.OverridePolicy{},
		&model.FailoverEvent{},
		&model.ClusterHealthRecord{},
		&model.ReplicaWeightPlan{},
		&model.FailoverPolicy{},
	); err != nil {
		log.Fatalf("failed to auto migrate: %v", err)
	}
	log.Printf("[%s] database initialized at %s", serviceName, dbPath)

	// 初始化基于 GORM 的存储。
	s := store.NewGormStore(gormDB)

	// 初始化 handlers。
	healthH := handler.NewHealthHandler(version)
	opH := handler.NewOverridePolicyHandler(s)
	failoverH := handler.NewFailoverHandler(s)

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

		// /api/v1/override-policies 路径要求有效 JWT。
		opGroup := v1.Group("/override-policies")
		opGroup.Use(middleware.AuthMiddleware())
		opH.RegisterRoutes(opGroup)

		// 故障迁移相关端点要求有效 JWT。
		failoverGroup := v1.Group("")
		failoverGroup.Use(middleware.AuthMiddleware())
		failoverH.RegisterRoutes(failoverGroup)
	}

	// 启动 HTTP 服务（支持优雅关闭）。
	addr := ":" + port
	srv := &http.Server{Addr: addr, Handler: r}

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

	ctx, cancel := context.WithTimeout(context.Background(), 5)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("[%s] server forced to shutdown: %v", serviceName, err)
	}
	log.Printf("[%s] server exited", serviceName)
}
