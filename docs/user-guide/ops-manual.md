# 数擎大数据平台运维手册

> 版本：V2.0 | 适用对象：平台运维工程师、SRE | 更新日期：2026-08-08

## 第1章 部署架构

### 1.1 K8s 集群拓扑

数擎大数据平台 V2.0 基于 K8s 1.28+ 部署，推荐集群拓扑：

| 节点角色 | 数量 | 规格 | 说明 |
|----------|------|------|------|
| master | 3 | 8C/16G/200G SSD | etcd + kube-apiserver + scheduler + controller-manager |
| worker-infra | 3 | 16C/32G/500G SSD | 基础设施节点：Trino/Doris FE/Kafka/Zookeeper |
| worker-compute | N | 32C/64G/1T NVMe | 计算节点：Doris BE/Spark/Flink |
| worker-gateway | 2 | 4C/8G/100G | 网关节点：Ingress/Istio/封装层/SQL 网关 |
| worker-storage | 3 | 8C/16G/多盘 | 存储节点：MinIO/Ceph |

### 1.2 组件部署关系

```
                    ┌─────────────────────────────────────┐
                    │       Ingress / Istio Gateway       │
                    └──────────────┬──────────────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        │                          │                          │
   ┌────▼─────┐   ┌────────────▼────────────┐   ┌──────────▼──────────┐
   │ 封装层   │   │      SQL 网关           │   │   行业模板服务     │
   │ (Java)   │   │      (Java)             │   │   (Python/FastAPI)  │
   └────┬─────┘   └────────────┬────────────┘   └─────────────────────┘
        │                       │
        │              ┌────────┴────────┐
        │              │                 │
        │         ┌────▼─────┐     ┌────▼─────┐
        │         │  Trino   │     │  Doris   │
        │         │ (路由A)  │     │ (路由B)  │
        │         └──────────┘     └──────────┘
        │
   ┌────▼─────┐   ┌────────────┐   ┌────────────┐
   │ Catalog  │   │ 规则引擎   │   │  dqctl CLI │
   │ (Go)     │   │ (Java)     │   │  (Go)      │
   └──────────┘   └────────────┘   └────────────┘
```

### 1.3 命名空间划分

| Namespace | 用途 |
|-----------|------|
| shuqing-system | 平台系统组件（封装层、SQL 网关、Catalog、规则引擎） |
| shuqing-infra | 基础设施（Trino、Doris、Kafka、Zookeeper） |
| shuqing-monitoring | 监控告警（Prometheus、Grafana、Alertmanager、Loki） |
| shuqing-argocd | ArgoCD GitOps |
| shuqing-istio-system | Istio 控制面 |
| ns-<tenant> | 租户隔离命名空间 |

## 第2章 安装部署

### 2.1 前置条件

| 项 | 要求 |
|----|------|
| K8s | >= 1.28 |
| Helm | >= 3.14 |
| Container Runtime | containerd >= 1.7 |
| CNI | Cilium >= 1.14（推荐）或 Calico >= 3.26 |
| StorageClass | 至少一个可用 StorageClass（推荐 local-path 或 ceph-rbd） |
| Ingress Controller | Nginx Ingress 或 Istio IngressGateway |
| CPU 架构 | x86_64 或 ARM64（信创环境支持鲲鹏/飞腾） |

### 2.2 Helm 安装步骤

#### 2.2.1 添加 Helm 仓库

```bash
helm repo add shuqing https://charts.shuqing.com
helm repo add bitnami https://charts.bitnami.com
helm repo add apache https://apache.github.io/helm-charts
helm repo update
```

#### 2.2.2 准备 values.yaml

```yaml
# values.yaml
global:
  environment: production  # xinchuang / local / public-cloud / private-cloud
  imageRegistry: registry.shuqing.com
  imageTag: "2.0.0"
  storageClass: "local-path"

encapsLayer:
  replicas: 2
  resources:
    requests: {cpu: "1", memory: "2Gi"}
    limits: {cpu: "2", memory: "4Gi"}

sqlGateway:
  replicas: 2
  resources:
    requests: {cpu: "2", memory: "4Gi"}
    limits: {cpu: "4", memory: "8Gi"}

catalog:
  replicas: 2
  resources:
    requests: {cpu: "0.5", memory: "512Mi"}
    limits: {cpu: "1", memory: "1Gi"}

ruleEngine:
  replicas: 2

trino:
  coordinator:
    resources: {requests: {cpu: "4", memory: "8Gi"}}
  worker:
    replicas: 3
    resources: {requests: {cpu: "4", memory: "16Gi"}}

doris:
  fe:
    replicas: 3
  be:
    replicas: 5

security:
  crypto:
    provider: GM  # GM(国密) / STANDARD
  jwt:
    issuer: "https://keycloak.shuqing.com/realms/shuqing"
```

#### 2.2.3 安装平台

```bash
# 创建命名空间
kubectl create namespace shuqing-system

# 安装平台
helm install shuqing-bigdata shuqing/shuqing-bigdata \
  -n shuqing-system \
  -f values.yaml \
  --version 2.0.0

# 等待全部 Pod 就绪
kubectl wait --for=condition=Ready pod -n shuqing-system --timeout=600s
```

#### 2.2.4 验证安装

```bash
# 检查 Pod 状态
kubectl get pods -n shuqing-system
kubectl get pods -n shuqing-infra

# 检查健康端点
kubectl port-forward svc/encaps-layer -n shuqing-system 8080:80
curl http://localhost:8080/api/v1/health
```

### 2.3 ArgoCD GitOps 配置

平台推荐使用 ArgoCD 进行声明式部署运维：

```bash
# 安装 ArgoCD
helm install argocd argo/argo-cd -n shuqing-argocd --create-namespace

# 配置应用
cat > shuqing-bigdata-app.yaml <<EOF
apiVersion: argoproj.io/v1alpha1
kind: Application
metadata:
  name: shuqing-bigdata
  namespace: shuqing-argocd
spec:
  source:
    repoURL: https://git.shuqing.com/platform/shuqing-deploy.git
    path: overlays/production
    targetRevision: main
  destination:
    server: https://kubernetes.default.svc
    namespace: shuqing-system
  syncPolicy:
    automated: {prune: true, selfHeal: true}
EOF

kubectl apply -f shuqing-bigdata-app.yaml
```

### 2.3 Service Mesh(Istio) 注入

```bash
# 安装 Istio
istioctl install --set profile=production

# 启用命名空间 Sidecar 自动注入
kubectl label namespace shuqing-system istio-injection=enabled
kubectl label namespace shuqing-infra istio-injection=enabled

# 重启 Pod 使 Sidecar 生效
kubectl rollout restart deployment -n shuqing-system
```

## 第3章 日常运维

### 3.1 集群健康检查

```bash
# 节点状态
kubectl get nodes -o wide
kubectl describe node <node-name>

# Pod 状态
kubectl get pods -A -o wide | grep -v Running

# 组件健康检查
curl http://encaps-layer:8080/api/v1/health
curl http://sql-gateway:8081/api/v1/health
curl http://catalog:8082/api/v1/health
curl http://rule-engine:8083/api/v1/health

# K8s 组件健康
kubectl get componentstatuses
etcdctl --endpoints=https://etcd:2379 endpoint health

# 事件检查
kubectl get events -A --sort-by='.lastTimestamp' | tail -50
```

### 3.2 组件扩缩容

#### 3.2.1 手动扩缩容

```bash
# 扩容 Doris BE
kubectl scale statefulset doris-be -n shuqing-infra --replicas=8

# 扩容 Trino Worker
kubectl scale deployment trino-worker -n shuqing-infra --replicas=5
```

#### 3.2.2 HPA 自动扩缩容

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: sql-gateway-hpa
  namespace: shuqing-system
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: sql-gateway
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target: {type: Utilization, averageUtilization: 70}
    - type: Resource
      resource:
        name: memory
        target: {type: Utilization, averageUtilization: 80}
```

```bash
kubectl apply -f sql-gateway-hpa.yaml
kubectl get hpa -n shuqing-system
```

### 3.3 日志收集与查询

#### 3.3.1 实时日志

```bash
# 查看 Pod 日志
kubectl logs -f deployment/encaps-layer -n shuqing-system
kubectl logs -f deployment/sql-gateway -n shuqing-system -c sql-gateway

# 多容器日志
kubectl logs -f deployment/doris-fe -n shuqing-infra --all-containers=true
```

#### 3.3.2 Loki 集中日志查询

平台部署 Loki + Promtail 收集日志，在 Grafana 中查询：

```logql
# 查询封装层 ERROR 日志
{namespace="shuqing-system", app="encaps-layer"} |= "ERROR"

# 查询 SQL 网关跨源查询日志
{namespace="shuqing-system", app="sql-gateway"} |= "cross-source"
```

### 3.4 监控告警配置

#### 3.4.1 Prometheus 采集

平台各组件暴露 `/metrics` 端点（Prometheus 格式），ServiceMonitor 配置：

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: sql-gateway-monitor
  namespace: shuqing-monitoring
spec:
  selector:
    matchLabels: {app: sql-gateway}
  endpoints:
    - port: metrics
      path: /metrics
      interval: 15s
```

#### 3.4.2 Grafana 看板

内置看板：
- 平台总览：CPU/内存/磁盘/网络、Pod 状态、QPS
- SQL 网关：查询 QPS、延迟分位、路由命中、跨源查询统计
- Doris：BE 负载、查询延迟、Compaction
- Trino：查询队列、Worker 负载、内存使用

#### 3.4.3 告警规则

```yaml
groups:
  - name: shuqing-alerts
    rules:
      - alert: PodDown
        expr: kube_pod_status_phase{phase!="Running"} == 1
        for: 5m
        labels: {severity: critical}
        annotations:
          summary: "Pod {{ $labels.pod }} down"
      - alert: SqlGatewayHighLatency
        expr: histogram_quantile(0.95, sql_gateway_query_duration_seconds_bucket) > 10
        for: 5m
        labels: {severity: warning}
        annotations:
          summary: "SQL 网关 P95 延迟 > 10s"
      - alert: DorisBeDiskHigh
        expr: doris_be_disk_used_ratio > 0.85
        for: 10m
        labels: {severity: warning}
        annotations:
          summary: "Doris BE 磁盘使用率 > 85%"
```

### 3.5 备份与恢复

#### 3.5.1 etcd 快照

```bash
# 备份
ETCDCTL_API=3 etcdctl --endpoints=https://etcd:2379 \
  --cacert=/etc/etcd/ca.crt \
  --cert=/etc/etcd/peer.crt \
  --key=/etc/etcd/peer.key \
  snapshot save /backup/etcd-$(date +%Y%m%d).db

# 恢复
ETCDCTL_API=3 etcdctl snapshot restore /backup/etcd-20260808.db
```

#### 3.5.2 PVC 备份（Velero）

```bash
# 安装 Velero
velero install --provider aws --bucket shuqing-backup --secret-file credentials-velero

# 按命名空间备份
velero backup create shuqing-$(date +%Y%m%d) --include-namespaces shuqing-system,shuqing-infra

# 恢复
velero restore create --from-backup shuqing-20260808
```

#### 3.5.3 数据库备份

```bash
# Doris 元数据备份（FE MySQL 协议）
mysqldump -h doris-fe -P 9030 -u root -p information_schema > doris-meta-$(date +%Y%m%d).sql

# Catalog SQLite 备份（开发环境）
kubectl exec -n shuqing-system deployment/catalog -- cp /app/catalog.db /tmp/catalog.db.bak

# Keycloak 数据库备份（PostgreSQL）
pg_dump -h keycloak-postgresql -U keycloak keycloak > keycloak-$(date +%Y%m%d).sql
```

## 第4章 故障排查

### 4.1 Pod 启动失败

```bash
# 查看事件
kubectl describe pod <pod-name> -n <ns>

# 常见原因
# 1) 镜像拉取失败
kubectl get pod <pod-name> -o yaml | grep image:
# 解决：检查镜像仓库可达性、认证 Secret

# 2) 资源不足
kubectl describe node <node> | grep -A 10 "Allocated"
# 解决：扩容节点或降低 Pod 资源请求

# 3) StorageClass 不存在或 PV 不足
kubectl get storageclass
kubectl get pv
# 解决：创建 StorageClass 或扩容存储

# 4) 配置错误
kubectl logs <pod-name> --previous
```

### 4.2 服务不可达

```bash
# 检查 Service 端点
kubectl get endpoints <svc> -n <ns>

# 检查 Ingress
kubectl get ingress -A
kubectl describe ingress <ingress> -n <ns>

# 检查 Istio 目标规则
istioctl analyze -n <ns>

# 网络连通性测试
kubectl exec -it <pod> -- curl http://<svc>:<port>/api/v1/health
```

### 4.3 SQL 查询超时

```bash
# 1) 查看查询日志
kubectl logs deployment/sql-gateway -n shuqing-system | grep "timeout"

# 2) 检查 Trino 队列
kubectl exec -it deployment/trino-coordinator -n shuqing-infra -- \
  curl -s http://localhost:8080/v1/query | jq '.[] | select(.state=="QUEUED")'

# 3) 检查 Doris BE 负载
curl http://doris-be:8040/metrics | grep doris_be_query

# 4) 调整超时参数
helm upgrade shuqing-bigdata shuqing/shuqing-bigdata \
  --set sqlGateway.query.timeout=300 \
  --reuse-values -n shuqing-system
```

### 4.4 内存溢出（OOM）

```bash
# 查看 OOM 历史
kubectl get pods -A -o jsonpath="{..message}" | grep OOMKilled

# 调整内存限制
kubectl set resources deployment/sql-gateway -n shuqing-system \
  --limits=memory=16Gi --requests=memory=8Gi

# JVM 堆内存调优
helm upgrade shuqing-bigdata shuqing/shuqing-bigdata \
  --set 'sqlGateway.jvmOpts=-Xmx8g -Xms4g -XX:+UseG1GC -XX:MaxGCPauseMillis=200' \
  --reuse-values -n shuqing-system
```

## 第5章 性能调优

### 5.1 JVM 参数调优

```yaml
# values.yaml
sqlGateway:
  jvmOpts: "-Xms4g -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/opt/heapdump"
encapsLayer:
  jvmOpts: "-Xms1g -Xmx2g -XX:+UseG1GC"
ruleEngine:
  jvmOpts: "-Xms1g -Xmx2g -XX:+UseG1GC"
```

### 5.2 连接池调优

```yaml
# 数据源连接池
datasource:
  hikari:
    maximumPoolSize: 20
    minimumIdle: 5
    connectionTimeout: 30000
    idleTimeout: 600000
    maxLifetime: 1800000
```

### 5.3 缓存配置

```yaml
# Catalog 元数据缓存
catalog:
  cache:
    enabled: true
    ttl: 300s
    maxSize: 10000

# 虚拟表元数据缓存
sqlGateway:
  virtualTable:
    cache:
      schemaTtl: 600s
      statsInterval: 60s
```

### 5.4 SQL 优化建议

1. **分区裁剪**：查询必带分区字段过滤条件，避免全表扫描
2. **列裁剪**：避免 `SELECT *`，仅查询所需列
3. **Join 顺序**：小表驱动大表，使用 `/*+ broadcast(t) */` Hint
4. **物化视图**：对高频聚合查询创建 Doris 物化视图
5. **索引**：对高频过滤字段建立 Doris 倒排索引或 Bloom Filter
6. **路由**：将点查路由到 Doris、复杂分析路由到 Trino

## 第6章 安全运维

### 6.1 证书更新

```bash
# 查看证书过期时间
kubeadm certs check-expiration

# 更新 K8s 证书
kubeadm certs renew all
systemctl restart kube-apiserver kube-controller-manager kube-scheduler

# 更新 Ingress 证书
kubectl create secret tls shuqing-tls \
  --cert=fullchain.pem --key=privkey.pem \
  -n shuqing-system --dry-run=client -o yaml | kubectl apply -f -
```

### 6.2 密钥轮换

```bash
# 轮换数据库密码（通过 SealedSecrets 或 External Secrets）
kubectl patch secret db-credentials -n shuqing-system \
  -p '{"data":{"password":"'$(echo -n "newpassword" | base64)'"}'

# 轮换 JWT 签名密钥
kubectl patch secret jwt-signing-key -n shuqing-system \
  -p '{"data":{"key":"'$(echo -n "newkey" | base64)'"}'

# 重启相关组件使密钥生效
kubectl rollout restart deployment -n shuqing-system
```

### 6.3 审计日志查询

```bash
# K8s 审计日志
kubectl logs -f deployment/kube-apiserver -n kube-system | grep audit

# 平台审计日志（Loki 查询）
# {app="encaps-layer"} |= "audit" | json | line_format "{{.user}} {{.action}} {{.resource}}"
```

## 第7章 升级运维

升级流程详见《升级指南》（upgrade-guide.md）。关键步骤摘要：

```bash
# 1. 备份
velero backup create pre-upgrade-$(date +%Y%m%d) --include-namespaces shuqing-system,shuqing-infra

# 2. dry-run 检查
helm upgrade shuqing-bigdata shuqing/shuqing-bigdata \
  -n shuqing-system -f values.yaml --dry-run

# 3. 执行升级
helm upgrade shuqing-bigdata shuqing/shuqing-bigdata \
  -n shuqing-system -f values.yaml --version 2.0.0

# 4. 验证
kubectl wait --for=condition=Ready pod -n shuqing-system --timeout=600s
curl http://encaps-layer:8080/api/v1/health
```

## 第8章 附录

### 8.1 运维工具清单

| 工具 | 用途 | 安装 |
|------|------|------|
| kubectl | K8s 命令行 | 必装 |
| helm | 包管理 | 必装 |
| istioctl | Istio 管理 | 必装 |
| velero | 备份恢复 | 推荐 |
| jq | JSON 处理 | 推荐 |
| etcdctl | etcd 管理 | master 节点 |
| dqctl | 平台 CLI | 平台运维 |

### 8.2 关键端口

| 组件 | 端口 | 说明 |
|------|------|------|
| 封装层 | 8080 | REST API |
| SQL 网关 | 8081 | REST API |
| Catalog | 8082 | REST API |
| 规则引擎 | 8083 | REST API |
| Trino | 8080 | REST API |
| Doris FE | 9030/8030 | MySQL/HTTP |
| Doris BE | 8040 | HTTP |
| Keycloak | 8080 | OAuth2 |
| Grafana | 3000 | UI |
| Prometheus | 9090 | UI/API |

### 8.3 相关文档

- 《用户手册》（user-manual.md）
- 《API 参考文档》（api-reference.md）
- 《升级指南》（upgrade-guide.md）
- 《行业模板使用指南》（industry-template-guide.md）
- 《部署指南》（../deployment-guide.md）