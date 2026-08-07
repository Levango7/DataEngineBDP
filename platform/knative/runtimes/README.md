# Serverless 函数运行时与计量 · 数擎大数据平台 T025

> 任务 T025 · Python / Java / Go 三种函数运行时 + 冷启动优化 + RPS 自动伸缩 + invocation 计量
> 依赖：T024 Knative Serving/Eventing（Batch 1a 已完成）
> 版本：Knative Serving >= 1.12 · 目标冷启动 ≤ 3s · KPA target=10 RPS · scale-to-zero 60s

## 1. 概述

本目录包含 Serverless 函数运行时的全部配置，为数擎大数据平台提供多语言函数执行能力。

### 1.1 核心特性

| 特性 | 说明 | 目标 |
| --- | --- | --- |
| 三种运行时 | Python(FastAPI) / Java(Spring Boot Native) / Go(Gin) | 多语言支持 |
| 冷启动优化 | 镜像预热 + 运行时缓存 + init container | ≤ 3s |
| KPA 自动伸缩 | 基于 RPS 的 Pod 自动伸缩，target=10 RPS | 自动扩缩 |
| scale-to-zero | 无流量 60s 后 Pod 缩容到 0 | 节约资源 |
| invocation 计量 | Prometheus 指标 + Loki 日志，按 tenant 隔离 | 租户级计量 |

### 1.2 依赖关系

```
T024 Knative Serving/Eventing ──┐
                                 ├──> T025 Serverless 函数运行时
Phase 1 Loki + Prometheus ───────┘
```

## 2. 目录结构

```
platform/knative/runtimes/
├── README.md                              # 本文档
├── python/                                # Python 函数运行时
│   ├── Dockerfile                         # 多阶段构建，预编译 .pyc
│   ├── kservice.yaml                      # Knative Service YAML
│   ├── requirements.txt                   # 依赖清单
│   └── app/                               # 应用代码
│       ├── main.py                        # FastAPI 入口
│       ├── metrics.py                     # invocation 计量
│       └── functions/default/handler.py   # 示例函数
├── java/                                  # Java 函数运行时
│   ├── Dockerfile                         # GraalVM Native Image
│   ├── kservice.yaml                      # Knative Service YAML
│   ├── pom.xml                            # Maven 配置
│   └── src/main/
│       ├── java/.../function/             # Java 源码
│       │   ├── FunctionApplication.java   # Spring Boot 入口
│       │   ├── FunctionController.java    # 调用控制器
│       │   ├── InvocationMetrics.java     # invocation 计量
│       │   ├── InvocationRequest.java     # 请求 DTO
│       │   └── InvocationResponse.java    # 响应 DTO
│       └── resources/application.yml      # Spring Boot 配置
├── go/                                    # Go 函数运行时
│   ├── Dockerfile                         # 静态编译单二进制
│   ├── kservice.yaml                      # Knative Service YAML
│   ├── go.mod                             # Go 模块
│   ├── cmd/main.go                        # 入口
│   └── internal/
│       ├── handler/handler.go             # 调用处理器
│       └── metrics/metrics.go             # invocation 计量
└── common/                                # 共享配置
    ├── autoscaling/                       # KPA 自动伸缩
    │   ├── kpa-config.yaml                # ConfigMap: config-autoscaler
    │   └── kpa-template.yaml              # KPA 注解模板
    ├── coldstart/                         # 冷启动优化
    │   ├── image-prepull-daemonset.yaml   # 镜像预热 DaemonSet
    │   ├── runtime-cache-config.yaml      # 运行时缓存策略
    │   └── init-container-templates.yaml  # init container 模板
    └── metrics/                           # invocation 计量
        ├── promtail-pipeline.yaml         # Promtail → Loki 管道
        ├── service-monitor.yaml           # Prometheus ServiceMonitor
        ├── prometheus-rules.yaml          # Prometheus 报警规则
        └── tenant_metrics_exporter.py     # 租户计量导出器
```

## 3. 三种运行时对比

| 维度 | Python | Java | Go |
| --- | --- | --- | --- |
| 框架 | FastAPI 0.115 | Spring Boot 3.3 Native | Gin 1.10 |
| 基础镜像 | python:3.12-slim | distroless/java17 | distroless/static |
| 冷启动策略 | 预编译 .pyc + init container | GraalVM Native Image | 静态编译单二进制 |
| 预期冷启动 | ~2s | < 1s | < 0.5s |
| 镜像大小 | ~120MB | ~80MB | ~20MB |
| 指标端点 | /metrics | /actuator/prometheus | /metrics |
| 健康检查 | /health | /actuator/health | /health |
| 调用端点 | POST /invoke | POST /api/v1/invoke | POST /invoke |

## 4. 冷启动优化

### 4.1 优化策略

图：冷启动优化策略示意图

```
┌─────────────────────────────────────────────────────────────┐
│                    冷启动优化三层策略                         │
├─────────────────────────────────────────────────────────────┤
│  层 1：镜像预热（DaemonSet）                                  │
│    └─ 每节点预拉取运行时镜像，消除 image pull 耗时（2-5s）     │
├─────────────────────────────────────────────────────────────┤
│  层 2：运行时缓存                                             │
│    ├─ Python：预编译 .pyc 字节码                              │
│    ├─ Java：GraalVM Native Image AOT 编译                    │
│    └─ Go：静态编译单二进制                                    │
├─────────────────────────────────────────────────────────────┤
│  层 3：init container 预加载                                  │
│    └─ 预加载依赖到 page cache，加速首次加载                   │
└─────────────────────────────────────────────────────────────┘
```

### 4.2 冷启动目标

| 运行时 | 目标 | 实现方式 |
| --- | --- | --- |
| Python | ≤ 3s | slim 镜像 + 预编译 .pyc + 依赖预装 |
| Java | ≤ 1s | Native Image AOT 编译，无 JVM 预热 |
| Go | ≤ 0.5s | 静态二进制，无运行时依赖 |

### 4.3 部署冷启动优化

```bash
# 部署镜像预热 DaemonSet
kubectl apply -f common/coldstart/image-prepull-daemonset.yaml

# 部署运行时缓存配置
kubectl apply -f common/coldstart/runtime-cache-config.yaml
kubectl apply -f common/coldstart/init-container-templates.yaml

# 验证 DaemonSet
kubectl get daemonset runtime-image-prepuller -n knative-serving
```

## 5. KPA 自动伸缩

### 5.1 伸缩策略

| 参数 | 值 | 说明 |
| --- | --- | --- |
| target RPS | 10 | 每个 Pod 目标处理 10 RPS |
| min-scale | 0 | 允许 scale-to-zero |
| max-scale | 20 | 最大 20 Pod |
| scale-to-zero-grace-period | 60s | 无流量 60s 后缩容到 0 |
| max-scale-up-rate | 1000 | 快速扩容应对突发 |
| stable-window | 60s | 伸缩决策观察窗口 |

### 5.2 伸缩示例

| RPS | Pod 数 | 说明 |
| --- | --- | --- |
| 0 | 0 | 60s 后 scale-to-zero |
| 10 | 1 | 1 Pod 承载 10 RPS |
| 50 | 5 | 5 Pods |
| 100 | 10 | 10 Pods |
| 200 | 20 | max-scale 上限 |

### 5.3 部署 KPA 配置

```bash
# 部署 KPA ConfigMap
kubectl apply -f common/autoscaling/kpa-config.yaml

# 验证
kubectl get configmap config-autoscaler -n knative-serving -o yaml
```

## 6. invocation 计量

### 6.1 计量架构

图：invocation 计量架构图

```
┌──────────────┐     ┌──────────────┐     ┌──────────────┐
│ Python Runtime│     │ Java Runtime │     │  Go Runtime  │
│  /metrics    │     │/actuator/... │     │  /metrics    │
└──────┬───────┘     └──────┬───────┘     └──────┬───────┘
       │                    │                    │
       │  stdout JSON log   │  stdout JSON log   │  stdout JSON log
       ▼                    ▼                    ▼
┌──────────────────────────────────────────────────────────────┐
│                    Promtail（采集 + tenant 标签）              │
└──────────────────────────┬───────────────────────────────────┘
                           ▼
                    ┌────────────┐
                    │   Loki     │  ← 按 tenant 隔离的调用日志
                    └────────────┘

       ┌────────────────────────────────────────────┐
       │         Prometheus ServiceMonitor           │
       └────────────────────┬───────────────────────┘
                            ▼
                     ┌────────────┐
                     │ Prometheus │  ← serverless_invocation_count{tenant=...}
                     └────────────┘
```

### 6.2 Prometheus 指标

| 指标 | 类型 | 标签 | 说明 |
| --- | --- | --- | --- |
| serverless_invocation_count | Counter | tenant, runtime, function, status | 调用总次数 |
| serverless_invocation_duration_seconds | Histogram | tenant, runtime, function | 调用延迟分布 |

### 6.3 PromQL 查询示例（按 tenant 隔离）

```promql
# 某租户 1 小时调用次数
sum by (tenant) (increase(serverless_invocation_count{tenant="tenant-a"}[1h]))

# 某租户 P99 延迟
histogram_quantile(0.99,
  sum by (le, tenant) (rate(serverless_invocation_duration_seconds_bucket{tenant="tenant-a"}[5m]))
)

# 某租户错误率
sum by (tenant) (rate(serverless_invocation_count{tenant="tenant-a",status="error"}[5m]))
/
sum by (tenant) (rate(serverless_invocation_count{tenant="tenant-a"}[5m]))
```

### 6.4 Loki 日志查询示例（按 tenant 隔离）

```logql
# 某租户的调用日志
{tenant="tenant-a"} |= "invocation"

# 某租户的错误调用
{tenant="tenant-a",logStatus="error"} |= "invocation"
```

### 6.5 部署计量模块

```bash
# 部署 Promtail Pipeline（Promtail 主配置需引用）
kubectl apply -f common/metrics/promtail-pipeline.yaml

# 部署 ServiceMonitor
kubectl apply -f common/metrics/service-monitor.yaml

# 部署 Prometheus 报警规则
kubectl apply -f common/metrics/prometheus-rules.yaml
```

## 7. 部署

### 7.1 前置条件

1. T024 Knative Serving/Eventing 已部署
2. Phase 1 Loki + Prometheus 已部署
3. 三种运行时镜像已构建并推送

### 7.2 构建运行时镜像

```bash
# Python
docker build -t shuqing/python-function-runtime:0.1.0 platform/knative/runtimes/python/

# Java（需要 GraalVM，耗时约 3 分钟）
docker build -t shuqing/java-function-runtime:0.1.0 platform/knative/runtimes/java/

# Go
docker build -t shuqing/go-function-runtime:0.1.0 platform/knative/runtimes/go/
```

### 7.3 部署全部

```bash
# 1. 冷启动优化
kubectl apply -f common/coldstart/

# 2. KPA 配置
kubectl apply -f common/autoscaling/

# 3. 计量模块
kubectl apply -f common/metrics/

# 4. 三种运行时 KService
kubectl apply -f python/kservice.yaml
kubectl apply -f java/kservice.yaml
kubectl apply -f go/kservice.yaml
```

### 7.4 验证

```bash
# 检查 KService
kubectl get ksvc -n serverless-functions

# 调用 Python 函数
curl -H "X-Tenant-Id: tenant-a" \
  http://python-function-runtime.serverless-functions.shuqing.local/invoke \
  -d '{"key":"value"}'

# 检查 Prometheus 指标
curl http://python-function-runtime.serverless-functions.shuqing.local/metrics

# 检查 Loki 日志
logcli query '{tenant="tenant-a"} |= "invocation"' --limit=10
```

## 8. 集成测试

```bash
# 运行 Serverless 运行时集成测试
pytest tests/integration/docker/test_serverless_runtime.py -v

# 启用 scale-to-zero 测试（耗时约 70s）
pytest tests/integration/docker/test_serverless_runtime.py -v --run-scale-to-zero

# 仅运行冷启动测试
pytest tests/integration/docker/test_serverless_runtime.py -v -k "cold_start"
```

测试覆盖：

| 测试类 | 测试项 | 说明 |
| --- | --- | --- |
| TestColdStartPython | 3 | Python 冷启动 ≤ 3s |
| TestColdStartJava | 3 | Java 冷启动 ≤ 3s |
| TestColdStartGo | 3 | Go 冷启动 ≤ 3s |
| TestScaleToZero | 2 | 无流量 60s 缩容到 0 |
| TestRpsAutoscaling | 2 | RPS 自动伸缩 |
| TestInvocationMetering | 4 | invocation 计量 + tenant 隔离 |

## 9. 相关任务

| 任务 | 说明 | 状态 |
| --- | --- | --- |
| T024 | Knative Serving/Eventing 部署 | Phase 2 Batch 1a 已完成 |
| **T025** | **Serverless 函数运行时与计量** | **本任务** |

## 10. 参考文档

- [Knative 自动伸缩](https://knative.dev/docs/serving/autoscaling/)
- [GraalVM Native Image](https://docs.spring.io/spring-boot/reference/packaging/native-image/introducing-graalvm-native-images.html)
- [Prometheus Client Libraries](https://prometheus.io/docs/instrumenting/clientlibs/)
- [Loki LogQL](https://grafana.com/docs/loki/latest/query/)