# Knative Serving/Eventing 部署配置

> 任务 T024 · Knative Serving + Eventing 部署 · 数据引擎大数据平台
> 版本：Knative Serving >= 1.12 · Eventing >= 1.12
> 网络层：复用 Phase 1 Istio Ingress Gateway（T001）
> GitOps：ArgoCD 集成（T003）

## 1. 概述

本目录包含 Knative Serving + Eventing 的部署配置，为 T025 Serverless 函数运行时提供基础设施。

### 1.1 核心特性

| 特性 | 说明 | 配置位置 |
| --- | --- | --- |
| Knative Serving >= 1.12 | Serverless 容器运行时 | `knative-serving-values.yaml` |
| Knative Eventing >= 1.12 | 事件驱动架构 | `knative-eventing-values.yaml` |
| KPA 自动伸缩 | 基于并发与 RPS 的 Pod 自动伸缩 | `config-autoscaler` |
| scale-to-zero | 无流量 60s 后 Pod 缩容到 0 | `config-autoscaler` |
| Istio Ingress 复用 | 复用 Phase 1 Istio 网关 | `config-network` |
| KafkaSource | Kafka 消息事件源 | `knative-eventing-values.yaml` |
| CronJobSource | 定时触发事件源 | `knative-eventing-values.yaml` |
| ArgoCD GitOps | Helm Chart 纳 GitOps 同步 | `argocd-application.yaml` |

### 1.2 依赖关系

```
Phase 1 Istio (T001) ──┐
                        ├──> Knative Serving ──┐
Phase 1 ArgoCD (T003) ──┘                      │
                                               ├──> Knative Eventing ──> T025 Serverless
Phase 1 Kafka ──────────────────────────────────┘
```

## 2. 目录结构

```
platform/knative/
├── README.md                       # 本文档
├── knative-serving-values.yaml     # Knative Serving Helm values
├── knative-eventing-values.yaml    # Knative Eventing Helm values
├── argocd-application.yaml         # ArgoCD Application 清单（3 个 Application）
└── examples/                       # 示例资源
    ├── kustomization.yaml          # Kustomize 配置
    ├── kservice-hello.yaml         # KService 示例（验证 Serving）
    ├── kafka-topic.yaml            # Kafka Topic（测试用）
    ├── kafkasource-example.yaml    # KafkaSource 示例（验证 Eventing）
    ├── cronjobsource-example.yaml  # CronJobSource 示例（定时触发）
    └── pingsource-example.yaml     # PingSource 示例（轻量定时）
```

## 3. 部署

### 3.1 前置条件

1. **Phase 1 Istio 已部署**（T001）
   ```bash
   kubectl get svc istio-ingressgateway -n istio-system
   ```

2. **Phase 1 ArgoCD 已部署**（T003）
   ```bash
   kubectl get pods -n argocd
   ```

3. **Phase 1 Kafka 集群已就绪**
   ```bash
   kubectl get svc kafka-bootstrap -n kafka
   ```

### 3.2 通过 ArgoCD 部署（推荐）

```bash
# 提交 ArgoCD Application
kubectl apply -f platform/knative/argocd-application.yaml

# 查看 Application 状态
kubectl get application -n argocd

# 手动触发同步（如未配置自动同步）
argocd app sync knative-serving
argocd app sync knative-eventing
argocd app sync knative-examples
```

### 3.3 通过 Helm 直接部署（调试用）

```bash
# 添加 Knative Helm 仓库
helm repo add knative-serving https://knative.github.io/docs/install/serving
helm repo add knative-eventing https://knative.github.io/docs/install/eventing
helm repo update

# 部署 Knative Serving
helm install knative-serving knative-serving/knative-serving \
  -n knative-serving --create-namespace \
  -f platform/knative/knative-serving-values.yaml

# 部署 Knative Eventing
helm install knative-eventing knative-eventing/knative-eventing \
  -n knative-eventing --create-namespace \
  -f platform/knative/knative-eventing-values.yaml

# 部署示例
kubectl apply -k platform/knative/examples/
```

### 3.4 sync wave 顺序

ArgoCD Application 通过 `sync-wave` 注解控制部署顺序：

| sync wave | Application | 说明 |
| --- | --- | --- |
| -1 | knative-serving | Serving CRD + 控制面 |
| 0 | knative-eventing | Eventing CRD + 控制面 |
| 1 | knative-examples | KafkaSource/CronJobSource 示例 |

## 4. 配置说明

### 4.1 Knative Serving 关键配置

| 配置项 | 值 | 说明 |
| --- | --- | --- |
| `enable-scale-to-zero` | `true` | 启用缩容到零 |
| `scale-to-zero-grace-period` | `60s` | 无流量 60s 后缩容 |
| `target-concurrency` | `10` | KPA 目标并发 |
| `target-rps` | `100` | KPA 目标 RPS |
| `ingress.class` | `istio.ingress.networking.knative.dev` | 复用 Istio |
| `istioIngressGatewayName` | `istio-ingressgateway` | Istio 网关名 |

### 4.2 Knative Eventing 关键配置

| 配置项 | 值 | 说明 |
| --- | --- | --- |
| `kafkaSource.enabled` | `true` | 启用 KafkaSource |
| `cronJobSource.enabled` | `true` | 启用 CronJobSource |
| `pingSource.enabled` | `true` | 启用 PingSource |
| `kafkaChannel.enabled` | `true` | 启用 Kafka Channel |
| `defaultBrokerClass` | `Kafka` | 默认 Broker 后端 |

### 4.3 镜像加速

所有镜像使用 `docker.m.daocloud.io` 国内镜像加速：

```yaml
global:
  imageRegistry: docker.m.daocloud.io
```

## 5. 验证

### 5.1 部署验证脚本

```bash
# 完整验证
bash scripts/verify-knative.sh

# 快速验证（跳过示例）
bash scripts/verify-knative.sh --quick

# 部署 + 验证
bash scripts/verify-knative.sh --deploy

# 验证 scale-to-zero
bash scripts/verify-knative.sh --scale-to-zero
```

### 5.2 手动验证

```bash
# 1. 检查 namespace
kubectl get namespace knative-serving knative-eventing

# 2. 检查 CRD
kubectl get crd | grep knative

# 3. 检查控制面 Pod
kubectl get pods -n knative-serving
kubectl get pods -n knative-eventing

# 4. 检查 scale-to-zero 配置
kubectl get configmap config-autoscaler -n knative-serving -o yaml

# 5. 检查 KService
kubectl get ksvc -A

# 6. 检查事件源
kubectl get kafkasource,cronjobsource,pingsource -A

# 7. 检查 ArgoCD Application
kubectl get application -n argocd
```

### 5.3 pytest 集成测试

```bash
# 完整测试（跳过 scale-to-zero）
pytest tests/integration/docker/test_knative.py -v

# 启用 scale-to-zero 测试（耗时约 70s）
pytest tests/integration/docker/test_knative.py -v --run-scale-to-zero

# 仅运行快速测试
pytest tests/integration/docker/test_knative.py -v -m "not slow"

# 运行特定测试类
pytest tests/integration/docker/test_knative.py::TestKServiceCreation -v
```

测试覆盖：

| 测试类 | 测试项 | 说明 |
| --- | --- | --- |
| `TestKnativeServingDeployment` | 6 | Serving 部署验证 |
| `TestKnativeEventingDeployment` | 4 | Eventing 部署验证 |
| `TestKServiceCreation` | 5 | KService 创建与 URL 分配 |
| `TestKafkaSource` | 3 | KafkaSource 消息触发 |
| `TestCronJobSource` | 4 | CronJobSource 定时触发 |
| `TestScaleToZero` | 2 | scale-to-zero 验证 |
| `TestArgoCDApplication` | 2 | ArgoCD 同步状态 |

## 6. 示例资源

### 6.1 KService 示例

```yaml
apiVersion: serving.knative.dev/v1
kind: Service
metadata:
  name: hello-kservice
  annotations:
    # KPA 自动伸缩
    serving.knative.dev/autoscaler-class: "kpa.autoscaling.knative.dev"
    autoscaling.knative.dev/target: "10"
    autoscaling.knative.dev/min-scale: "0"  # 允许 scale-to-zero
    autoscaling.knative.dev/max-scale: "10"
```

验证：

```bash
kubectl get ksvc hello-kservice -n knative-examples
# 获取 URL
kubectl get ksvc hello-kservice -n knative-examples -o jsonpath='{.status.url}'
# 访问
curl http://hello-kservice.knative-examples.shuqing.local
```

### 6.2 KafkaSource 示例

```bash
# 部署
kubectl apply -f platform/knative/examples/kafkasource-example.yaml

# 发送 Kafka 消息触发
kubectl exec -it kafka-client -- kafka-console-producer.sh \
  --bootstrap-server kafka-bootstrap.kafka:9092 \
  --topic knative-test-topic
```

### 6.3 CronJobSource 示例

```bash
# 部署
kubectl apply -f platform/knative/examples/cronjobsource-example.yaml

# 查看（每 2 分钟触发一次）
kubectl get cronjobsource cronjob-source-example -n knative-examples
```

## 7. 故障排查

### 7.1 KService 未分配 URL

```bash
# 检查 KService 状态
kubectl describe ksvc hello-kservice -n knative-examples

# 检查 config-network
kubectl get configmap config-network -n knative-serving -o yaml

# 检查 Istio Ingress Gateway
kubectl get svc istio-ingressgateway -n istio-system
```

### 7.2 scale-to-zero 不生效

```bash
# 检查 config-autoscaler
kubectl get configmap config-autoscaler -n knative-serving -o yaml

# 确认 enable-scale-to-zero=true
# 确认 scale-to-zero-grace-period=60s

# 检查 KPA
kubectl get podautoscaler -n knative-examples
```

### 7.3 KafkaSource 未就绪

```bash
# 检查 KafkaSource 状态
kubectl describe kafkasource kafka-source-example -n knative-examples

# 检查 Kafka 集群
kubectl get kafka -n kafka

# 检查 Topic
kubectl get kafkatopic knative-test-topic -n kafka
```

### 7.4 ArgoCD 同步失败

```bash
# 查看 Application 状态
argocd app get knative-serving

# 查看同步日志
kubectl logs -n argocd -l app.kubernetes.io/name=argocd-application-controller

# 手动同步
argocd app sync knative-serving
```

## 8. 清理

```bash
# 删除示例
kubectl delete -k platform/knative/examples/

# 删除 ArgoCD Application
kubectl delete -f platform/knative/argocd-application.yaml

# 删除 Knative（通过 Helm）
helm uninstall knative-eventing -n knative-eventing
helm uninstall knative-serving -n knative-serving

# 删除 namespace
kubectl delete namespace knative-examples knative-eventing knative-serving
```

## 9. 相关任务

| 任务 | 说明 | 状态 |
| --- | --- | --- |
| T001 | Istio Service Mesh | Phase 1 已完成 |
| T003 | ArgoCD GitOps | Phase 1 已完成 |
| **T024** | **Knative Serving/Eventing 部署** | **本任务** |
| T025 | Serverless 函数运行时 | 依赖本任务 |

## 10. 参考文档

- [Knative Serving 官方文档](https://knative.dev/docs/serving/)
- [Knative Eventing 官方文档](https://knative.dev/docs/eventing/)
- [Knative Helm 安装](https://knative.dev/docs/install/yaml-install/serving/installation-with-helm/)
- [Knative 自动伸缩](https://knative.dev/docs/serving/autoscaling/)
- [KafkaSource](https://knative.dev/docs/eventing/sources/kafka-source/)
- [CronJobSource](https://knative.dev/docs/eventing/sources/cronjob-source/)