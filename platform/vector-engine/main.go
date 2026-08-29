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
	"fmt"
	"io"
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
	// STORE_TYPE=mock 或显式 MOCK_FALLBACK=true 才会落入内存 Mock；milvus 模式下
	// 未启用 milvus_enabled 构建标签将 fail-fast 拒绝启动（不再静默回退）。
	s := selectStore(cfg, logger)
	warnDemoMode(log.Writer(), isMockStore(s), serviceName)

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

// fatalExit 是 fail-fast 的可注入出口：生产入口为 log.Fatalf（含 os.Exit(1)），
// 测试替换为 panic 以便 require.Panics 捕获断言（os.Exit 无法被测试拦截）。
var fatalExit = func() {
	log.Fatalf("[%s] refusing to silently fall back to mock storage in milvus mode", serviceName)
}

// selectStore 根据配置构造 VectorStore。
//
// 生产语义（2026-08-29 P0-3 收紧）：
//   - STORE_TYPE=mock：显式选择内存 Mock（本地/测试），允许启动并告警
//   - STORE_TYPE=milvus（默认）：真实 Milvus；若当前二进制未带 milvus_enabled
//     构建标签，直接 fail-fast 拒绝启动——静默回退 mock 会让生产在无感知的情况下
//     跑在内存假存储上（配置写 milvus、实际非 milvus）。
//     需要临时降级时显式设置 MOCK_FALLBACK=true。
func selectStore(cfg *config.Config, logger *slog.Logger) store.VectorStore {
	switch cfg.StoreType {
	case "mock":
		log.Printf("[%s] store backend: mock (in-memory)", serviceName)
		return mock.NewMockVectorStore()
	case "milvus":
		return newMilvusStoreOrFail(cfg, logger)
	default:
		logger.Error("FATAL: unknown store type",
			"storeType", cfg.StoreType, "supported", "mock | milvus")
		fatalExit()
		return nil
	}
}

// newMilvusStoreOrFail 在未启用 milvus_enabled build tag 时拒绝启动（fail-fast）。
//
// 真实 Milvus 实现位于 internal/store/milvus/，需通过 `-tags milvus_enabled` 编译启用。
// 默认构建（无 build tag）下，newMilvusStore 返回 nil：
//   - MOCK_FALLBACK=true（显式应急开关）→ 回退 mock 并打印显著告警
//   - 否则 → fail-fast 拒绝以假存储冒充生产实例
func newMilvusStoreOrFail(cfg *config.Config, logger *slog.Logger) store.VectorStore {
	s := newMilvusStore(cfg)
	if s == nil {
		if os.Getenv("MOCK_FALLBACK") == "true" {
			logger.Warn("milvus store not built; MOCK_FALLBACK=true explicit fallback to mock (NOT for production)",
				"hint", "rebuild with: go build -tags milvus_enabled")
			return mock.NewMockVectorStore()
		}
		logger.Error("FATAL: STORE_TYPE=milvus but milvus store not built in this binary",
			"hint", "rebuild with: go build -tags milvus_enabled; or set STORE_TYPE=mock for local dev; or MOCK_FALLBACK=true to override")
		fatalExit()
	}
	return s
}

// warnDemoMode 在以内置内存 Mock 存储运行时输出演示模式告警（w 抽象便于测试捕获）。
func warnDemoMode(w io.Writer, active bool, service string) {
	if !active {
		return
	}
	fmt.Fprintf(w, "WARNING: [%s] 正以内置内存 Mock 存储运行（演示模式），数据不持久化；生产需启用 Milvus 构建\n", service)
}

// isMockStore 判定存储实例是否为内置内存 Mock（演示模式标记）。
func isMockStore(s store.VectorStore) bool {
	_, ok := s.(*mock.MockVectorStore)
	return ok
}
