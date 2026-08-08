// Package main 是 Go 函数运行时入口 · 数擎大数据平台 T025.
//
// 封装为 Knative Service，使用 Gin 框架。
// 冷启动优化：Go 静态编译单二进制，启动 < 0.5s（满足 ≤ 3s 目标）。
//
// 环境变量：
//   - FUNCTION_NAME：默认函数名（默认 default）
//   - TENANT_ID：默认租户 ID（默认 default-tenant）
//   - LOG_LEVEL：日志级别（默认 info）
package main

import (
	"fmt"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/shuqing/bigdata/function-runtime-go/internal/handler"
	"github.com/shuqing/bigdata/function-runtime-go/internal/metrics"
)

func main() {
	startTime := time.Now()

	// 配置
	functionName := os.Getenv("FUNCTION_NAME")
	if functionName == "" {
		functionName = "default"
	}
	defaultTenant := os.Getenv("TENANT_ID")
	if defaultTenant == "" {
		defaultTenant = "default-tenant"
	}
	logLevel := os.Getenv("LOG_LEVEL")
	if logLevel == "" {
		logLevel = "info"
	}

	// Gin 模式
	if logLevel == "release" {
		gin.SetMode(gin.ReleaseMode)
	}

	// 初始化计量记录器
	recorder := metrics.NewInvocationRecorder()
	recorder.Warmup(defaultTenant, functionName)

	// 初始化处理器
	h := handler.NewHandler(recorder, functionName, defaultTenant)

	// Gin 路由
	r := gin.New()
	r.Use(gin.Recovery())

	// 健康检查
	r.GET("/health", h.Health)
	// 函数调用
	r.POST("/invoke", h.Invoke)
	// Prometheus 指标
	r.GET("/metrics", gin.WrapH(promhttp.Handler()))

	// 启动 HTTP 服务
	port := os.Getenv("SERVER_PORT")
	if port == "" {
		port = "8080"
	}

	elapsed := time.Since(startTime)
	log.Printf("Go function runtime started in %.3fs, listening on :%s, function=%s",
		elapsed.Seconds(), port, functionName)

	srv := &http.Server{
		Addr:         ":" + port,
		Handler:      r,
		ReadTimeout:  30 * time.Second,
		WriteTimeout: 30 * time.Second,
	}

	if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		fmt.Fprintf(os.Stderr, "server error: %v\n", err)
		os.Exit(1)
	}
}
