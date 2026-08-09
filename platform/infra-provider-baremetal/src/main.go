// Package main 是裸金属供应Provider的入口。
//
// infra-provider-baremetal 通过Redfish/IPMI带外管理物理机，
// 结合PXE装机与kubeadm初始化，为本地数据中心提供K8s集群供应能力。
//
// 用法:
//
//	infra-provider-baremetal --config ./config/config.yaml
//
// REST API:
//
//	POST   /api/v1/clusters/baremetal           创建集群
//	DELETE /api/v1/clusters/baremetal/{id}       销毁集群
//	GET    /api/v1/clusters/baremetal/{id}       查询状态
//	GET    /api/v1/clusters/baremetal            列出集群
//	GET    /api/v1/clusters/baremetal/{id}/nodes 查询节点
//	POST   /api/v1/clusters/baremetal/{id}/scale 扩缩容
//	POST   /api/v1/auth/login                    签发Token
//	GET    /healthz                              存活探针
//	GET    /readyz                               就绪探针
package main

import (
	"context"
	"flag"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/sirupsen/logrus"

	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/config"
	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/handler"
	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/middleware"
	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/model"
	"github.com/Levango7/DataEngineBDP/infra-provider-baremetal/src/internal/service"
)

// Version 通过ldflags注入的版本号
var Version = "0.1.0"

func main() {
	var (
		configPath  = flag.String("config", "./src/config/config.yaml", "配置文件路径")
		port        = flag.Int("port", 0, "覆盖配置中的监听端口")
		showVersion = flag.Bool("version", false, "显示版本号")
	)
	flag.Parse()

	if *showVersion {
		fmt.Printf("infra-provider-baremetal %s\n", Version)
		return
	}

	// 加载配置
	cfg, err := config.Load(*configPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "加载配置失败: %v\n", err)
		os.Exit(1)
	}
	if *port > 0 {
		cfg.Server.Port = *port
	}
	config.SetGlobal(cfg)

	// 初始化日志
	logger := initLogger(cfg)
	logger.Infof("启动 infra-provider-baremetal %s", Version)

	// 初始化数据库
	db, err := service.InitDatabase(cfg.Database.Driver, cfg.Database.SqlitePath, cfg.Database.MaxOpenConns, cfg.Database.MaxIdleConns)
	if err != nil {
		logger.Fatalf("初始化数据库失败: %v", err)
	}
	logger.Info("数据库初始化成功")

	// 初始化服务层
	redfishClient := service.NewRedfishClient(
		cfg.Redfish.TimeoutDuration(),
		cfg.Redfish.InsecureSkipVerify,
		cfg.Redfish.DefaultUsername,
		cfg.Redfish.DefaultPassword,
	)

	// K8s引导器(默认使用本地执行器，生产环境可注入SSH执行器)
	k8sBootstrapper := service.NewK8sBootstrapper(
		&localExecutor{logger: logger},
		cfg.K8s.ImageRepository,
		cfg.K8s.PodCIDR,
		cfg.K8s.ServiceCIDR,
		cfg.K8s.APIServerPort,
	)

	svc := service.NewBareMetalService(db, redfishClient, k8sBootstrapper, logger.WithField("component", "service"))
	if err := svc.AutoMigrate(); err != nil {
		logger.Fatalf("数据库迁移失败: %v", err)
	}

	// 初始化鉴权
	auth := middleware.NewJWTAuthenticator(cfg.Auth.Secret, cfg.Auth.TokenTTLDuration(), cfg.Auth.Issuer)

	// 初始化Gin
	gin.SetMode(cfg.Server.Mode)
	engine := gin.New()
	engine.Use(gin.Logger(), gin.Recovery(), middleware.CORSMiddleware())

	// 注册路由
	v1 := engine.Group("/api/v1")
	v1.Use(auth.AuthMiddleware())

	clusterHandler := handler.NewClusterHandler(svc, logger.WithField("handler", "cluster"))
	clusterHandler.RegisterRoutes(v1)

	healthHandler := handler.NewHealthHandler(auth, Version, logger.WithField("handler", "health"))
	healthHandler.RegisterRoutes(v1, engine)

	// 启动HTTP服务
	srv := &http.Server{
		Addr:         fmt.Sprintf(":%d", cfg.Server.Port),
		Handler:      engine,
		ReadTimeout:  time.Duration(cfg.Server.ReadTimeout) * time.Second,
		WriteTimeout: time.Duration(cfg.Server.WriteTimeout) * time.Second,
	}

	go func() {
		logger.Infof("HTTP服务监听 :%d", cfg.Server.Port)
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatalf("HTTP服务启动失败: %v", err)
		}
	}()

	// 优雅关闭
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logger.Info("收到关闭信号，正在优雅关闭...")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		logger.Errorf("HTTP服务关闭失败: %v", err)
	}

	if sqlDB, err := db.DB(); err == nil {
		_ = sqlDB.Close()
	}
	logger.Info("服务已关闭")
}

// initLogger 初始化logrus日志
func initLogger(cfg *config.Config) *logrus.Entry {
	logger := logrus.New()

	level, err := logrus.ParseLevel(cfg.Log.Level)
	if err != nil {
		level = logrus.InfoLevel
	}
	logger.SetLevel(level)

	if cfg.Log.Format == "json" {
		logger.SetFormatter(&logrus.JSONFormatter{
			TimestampFormat: time.RFC3339Nano,
		})
	} else {
		logger.SetFormatter(&logrus.TextFormatter{
			FullTimestamp:   true,
			TimestampFormat: time.RFC3339Nano,
		})
	}
	logger.SetReportCaller(cfg.Log.ReportCaller)

	return logrus.NewEntry(logger)
}

// localExecutor 本地命令执行器(主要用于测试/开发)
//
// 生产环境应替换为SSH执行器，通过SSH登录到裸金属节点执行kubeadm命令。
type localExecutor struct {
	logger *logrus.Entry
}

// Execute 在本地执行命令
func (e *localExecutor) Execute(ctx context.Context, host, command string) (stdout, stderr string, exitCode int, err error) {
	e.logger.WithFields(logrus.Fields{
		"host":    host,
		"command": command,
	}).Warn("localExecutor仅用于开发测试，实际部署应使用SSH执行器")
	// 开发模式下返回成功，模拟kubeadm init输出
	return "kubeadm join 127.0.0.1:6443 --token dev-token --discovery-token-ca-cert-hash sha256:dev-hash\n", "", 0, nil
}

// CopyFile 拷贝文件(本地直接使用源路径)
func (e *localExecutor) CopyFile(ctx context.Context, host, localPath, remotePath string) error {
	if remotePath == "" {
		remotePath = filepath.Base(localPath)
	}
	e.logger.WithFields(logrus.Fields{
		"host":        host,
		"local_path":  localPath,
		"remote_path": remotePath,
	}).Warn("localExecutor.CopyFile 仅用于开发测试")
	return nil
}

// 确保 model 包被使用(避免未使用导入)
var _ = model.APIResponse{}
