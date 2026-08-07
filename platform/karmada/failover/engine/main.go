package main

// Failover Engine 入口：故障迁移策略引擎。
//
// 引擎周期性检查主集群健康状态，当主集群故障时在 60s 内触发
// Karmada failover 将工作负载迁移到备用集群。
//
// 启动流程：
//   1. 初始化 Karmada 控制面客户端
//   2. 初始化 Prometheus 客户端（负载指标查询）
//   3. 初始化健康检查器
//   4. 初始化副本权重分配器
//   5. 初始化故障迁移管理器
//   6. 加载故障迁移策略（从 FailoverPolicy 表）
//   7. 启动检查循环
//
// 配置通过环境变量：
//   - KARMADA_API_URL：Karmada 控制面 URL
//   - KARMADA_API_TOKEN：Karmada 控制面 Bearer token
//   - PROMETHEUS_URL：Prometheus URL
//   - FAILOVER_ENGINE_PORT：引擎 HTTP 端口（健康检查/指标）
//   - FAILOVER_DETECTION_WINDOW：故障检测窗口秒数（默认 30）
//   - FAILOVER_MIGRATION_TIMEOUT：迁移超时秒数（默认 60）

import (
	"context"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/shuqing/bigdata/failover-engine/internal/failover"
	"github.com/shuqing/bigdata/failover-engine/internal/health"
	"github.com/shuqing/bigdata/failover-engine/internal/karmada"
	"github.com/shuqing/bigdata/failover-engine/internal/model"
	"github.com/shuqing/bigdata/failover-engine/internal/prometheus"
	"github.com/shuqing/bigdata/failover-engine/internal/weight"
)

// 服务常量。
const (
	serviceName    = "failover-engine"
	defaultVersion = "0.1.0"
	defaultPort    = "8095"
)

func main() {
	version := os.Getenv("FAILOVER_ENGINE_VERSION")
	if version == "" {
		version = defaultVersion
	}
	port := os.Getenv("FAILOVER_ENGINE_PORT")
	if port == "" {
		port = defaultPort
	}

	// 1. 初始化 Karmada 控制面客户端。
	karmadaURL := os.Getenv("KARMADA_API_URL")
	if karmadaURL == "" {
		karmadaURL = "http://localhost:8080"
	}
	karmadaToken := os.Getenv("KARMADA_API_TOKEN")
	karmadaClient := karmada.NewClient(karmadaURL, karmadaToken)
	log.Printf("[%s] karmada client initialized: %s", serviceName, karmadaURL)

	// 2. 初始化 Prometheus 客户端。
	promURL := os.Getenv("PROMETHEUS_URL")
	var promClient *prometheus.Client
	if promURL != "" {
		promClient = prometheus.NewClient(promURL)
		log.Printf("[%s] prometheus client initialized: %s", serviceName, promURL)
	} else {
		log.Printf("[%s] prometheus URL not set, using karmada API only", serviceName)
	}

	// 3. 初始化健康检查器。
	checker := health.NewChecker(karmadaClient, promClient)

	// 4. 初始化副本权重分配器。
	allocator := weight.NewAllocator()

	// 5. 初始化故障迁移管理器。
	manager := failover.NewManager(checker, karmadaClient, allocator)

	// 6. 加载默认故障迁移策略（生产环境从 DB 加载）。
	loadDefaultPolicies(manager)

	// 7. 启动引擎主循环。
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	go func() {
		if err := manager.Run(ctx); err != nil {
			log.Printf("[%s] manager run error: %v", serviceName, err)
		}
	}()

	// 8. 启动事件消费协程（持久化迁移事件）。
	go consumeEvents(manager)

	// 9. 启动 HTTP 健康检查端点。
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(`{"status":"ok","service":"failover-engine","version":"` + version + `"}`))
	})
	mux.HandleFunc("/metrics", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		summary, _ := manager.MarshalHealthSummary()
		w.WriteHeader(http.StatusOK)
		w.Write([]byte(summary))
	})

	srv := &http.Server{Addr: ":" + port, Handler: mux}

	go func() {
		log.Printf("[%s] version=%s listening on %s", serviceName, version, port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("failed to start server: %v", err)
		}
	}()

	// 等待中断信号。
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	log.Printf("[%s] shutting down...", serviceName)

	shutdownCtx, shutdownCancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer shutdownCancel()
	if err := srv.Shutdown(shutdownCtx); err != nil {
		log.Printf("[%s] server forced to shutdown: %v", serviceName, err)
	}
	cancel()
	log.Printf("[%s] server exited", serviceName)
}

// loadDefaultPolicies 加载默认故障迁移策略。
//
// 从环境变量读取默认策略配置，便于 Docker 测试。
func loadDefaultPolicies(manager *failover.Manager) {
	primaryCluster := os.Getenv("FAILOVER_PRIMARY_CLUSTER")
	if primaryCluster == "" {
		primaryCluster = "xinchang-cluster"
	}

	backupClusters := []string{"local-cluster", "cce-cluster"}
	if backups := os.Getenv("FAILOVER_BACKUP_CLUSTERS"); backups != "" {
		// 简单解析逗号分隔列表。
		backupClusters = parseClusterList(backups)
	}

	detectionWindow := 30
	if v := os.Getenv("FAILOVER_DETECTION_WINDOW"); v != "" {
		if d, err := parseInt(v); err == nil {
			detectionWindow = d
		}
	}

	migrationTimeout := 60
	if v := os.Getenv("FAILOVER_MIGRATION_TIMEOUT"); v != "" {
		if d, err := parseInt(v); err == nil {
			migrationTimeout = d
		}
	}

	healthCheckInterval := 10
	if v := os.Getenv("FAILOVER_HEALTH_CHECK_INTERVAL"); v != "" {
		if d, err := parseInt(v); err == nil {
			healthCheckInterval = d
		}
	}

	policy := &model.FailoverPolicyConfig{
		Name:                      "default-failover",
		Namespace:                 "default",
		PrimaryCluster:            primaryCluster,
		BackupClusters:            backupClusters,
		DetectionWindowSeconds:    detectionWindow,
		MigrationTimeoutSeconds:   migrationTimeout,
		HealthCheckIntervalSeconds: healthCheckInterval,
		Enabled:                   true,
	}
	manager.AddPolicy(policy)
}

// consumeEvents 消费迁移事件（持久化到 DB 或日志）。
func consumeEvents(manager *failover.Manager) {
	for event := range manager.EventChan() {
		log.Printf("[%s] failover event: id=%s %s→%s status=%s duration=%dms",
			serviceName,
			event.EventID,
			event.SourceCluster,
			event.TargetCluster,
			event.Status,
			event.DurationMs,
		)
		// 生产环境：持久化到 FailoverEvent 表
	}
}

// parseClusterList 解析逗号分隔的集群列表。
func parseClusterList(s string) []string {
	var result []string
	current := ""
	for _, c := range s {
		if c == ',' {
			if current != "" {
				result = append(result, current)
				current = ""
			}
		} else {
			current += string(c)
		}
	}
	if current != "" {
		result = append(result, current)
	}
	return result
}

// parseInt 解析整数。
func parseInt(s string) (int, error) {
	result := 0
	negative := false
	for i, c := range s {
		if i == 0 && c == '-' {
			negative = true
			continue
		}
		if c < '0' || c > '9' {
			return 0, os.ErrInvalid
		}
		result = result*10 + int(c-'0')
	}
	if negative {
		result = -result
	}
	return result, nil
}