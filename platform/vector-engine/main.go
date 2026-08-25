// Package main 是数据引擎大数据平台向量检索引擎（L4.5.1）的入口。
//
// 该服务提供向量集合管理、向量 CRUD、ANN 近似检索与混合检索（向量+标量）能力，
// 底层通过 VectorStore 接口抽象，支持 Mock（内存实现，用于测试）与 Milvus（生产实现）两种后端，
// 通过环境变量 STORE_TYPE 切换。
//
// 启动示例：
//
//	STORE_TYPE=mock ./vector-engine
//	STORE_TYPE=milvus MILVUS_HOST=127.0.0.1 MILVUS_PORT=19530 ./vector-engine
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

	"github.com/Levango7/DataEngineBDP/vector-engine/internal/api"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/config"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/middleware"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/service"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store"
	"github.com/Levango7/DataEngineBDP/vector-engine/internal/store/mock"
)

// 服务常量。
const (
	serviceName    = "vector-engine"
	defaultVersion = "0.1.0"
	defaultPort    = "8086"
)

func main() {
	version := os.Getenv("VECTOR_ENGINE_VERSION")
	if version == "" {
		version = defaultVersion
	}
	port := os.Getenv("VECTOR_ENGINE_PORT")
	if port == "" {
		port = defaultPort
	}

	// 初始化结构化 JSON 日志。
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	// 加载配置。
	cfg := config.Load()

	// 根据配置构造 VectorStore 实例。
	// 当前默认使用 Mock 实现；Milvus 实现通过 build tag 控制（参见 internal/store/milvus/）。
	var s store.VectorStore
	switch cfg.StoreType {
	case "mock":
		s = mock.NewMockVectorStore()
		log.Printf("[%s] store backend: mock (in-memory)", serviceName)
	case "milvus":
		// Milvus 实现需要 build tag milvus_enabled，未启用时回退到 mock 并告警。
		s = newMilvusStoreOrFallback(cfg, logger)
	default:
		s = mock.NewMockVectorStore()
		log.Printf("[%s] unknown store type %q, fallback to mock", serviceName, cfg.StoreType)
	}

	// 初始化服务层。
	vectorService := service.NewVectorService(s)

	// 初始化 handlers。
	healthH := api.NewHealthHandler(version, serviceName)
	vectorH := api.NewVectorHandler(vectorService)

	// 初始化 Gin 路由。
	r := newRouter(logger, healthH, vectorH)

	// 启动 HTTP 服务（支持优雅关闭）。
	addr := ":" + port
	srv := &http.Server{
		Addr:              addr,
		Handler:           r,
		ReadHeaderTimeout: 10 * time.Second, // 防 Slowloris 慢速攻击（gosec G112）
	}

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

// newRouter 构建 Gin 引擎：全局中间件链 + 路由注册。
//
// 路由组织（参照 catalog 模式）：公开端点（健康检查）先注册在鉴权之外，
// 业务路由组挂 AuthMiddleware 强制鉴权，避免 K8s 探针被 401 拦截进入重启循环。
func newRouter(logger *slog.Logger, healthH *api.HealthHandler, vectorH *api.VectorHandler) *gin.Engine {
	r := gin.New()

	// 全局中间件链：Recovery → Logging → CORS。
	r.Use(gin.Recovery())
	r.Use(middleware.LoggingMiddleware(logger))
	r.Use(middleware.CorsMiddleware())

	// 公开端点：健康检查无需认证，K8s 探针匿名可达。
	v1 := r.Group("/api/v1")
	v1.GET("/health", healthH.Health)

	// 业务路由组：强制 JWT 鉴权（secure-by-default）。
	// 网关前置鉴权部署须显式设置 VECTOR_AUTH_REQUIRED=false。
	authed := v1.Group("")
	authed.Use(middleware.AuthMiddleware())
	vectorH.RegisterRoutes(authed)

	return r
}

// newMilvusStoreOrFallback 在未启用 milvus_enabled build tag 时回退到 Mock 实现。
//
// 真实 Milvus 实现位于 internal/store/milvus/，需通过 `-tags milvus_enabled` 编译启用。
// 默认构建（无 build tag）下，newMilvusStore 返回 nil，此处回退到 Mock 并打印告警。
func newMilvusStoreOrFallback(cfg *config.Config, logger *slog.Logger) store.VectorStore {
	s := newMilvusStore(cfg)
	if s == nil {
		logger.Warn("milvus store not built (need build tag milvus_enabled), fallback to mock",
			"hint", "rebuild with: go build -tags milvus_enabled")
		return mock.NewMockVectorStore()
	}
	return s
}
