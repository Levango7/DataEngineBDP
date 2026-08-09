# T034 跨集群查询路由与归并

数据引擎大数据平台 V2.0 Phase 2 Batch 2 - T034 跨集群查询路由与归并服务。

## 1 概述

基于 T026 Karmada 控制面（Batch 1a 已完成）与 Phase 1 T012 Calcite 联邦优化器，
提供跨集群查询路由、表元数据定位、mTLS 跨集群传输、降级策略与查询归并能力。

### 1.1 核心能力

| 能力 | 说明 |
|------|------|
| 跨集群查询路由 | 接收 SQL，通过表元数据定位表所在集群，路由查询到对应集群 |
| 全局 Catalog 表定位 | 复用 Phase 1 platform/catalog REST API，记录表与集群映射 |
| mTLS 跨集群传输 | 复用 Phase 1 Istio mTLS，WebClient SSL Context 双向认证 |
| 降级策略 | 网络中断检测（超时/连接失败），降级到单集群查询（仅查本地表），告警通知 |
| 查询归并 | 跨集群查询结果归并（基于 Phase 1 T013 跨源 Join 归并器思想） |

### 1.2 验收标准

- 跨集群查询覆盖 ≥ 2 集群，查询结果正确
- P95 ≤ 30s（跨集群查询延迟）
- 网络中断降级单集群查询并告警，降级过程无查询失败

## 2 架构

图：T034 跨集群查询架构图

```
┌─────────────────────────────────────────────────────────────────┐
│                   Federated Query Service (8094)                │
│                                                                 │
│  ┌──────────────┐   ┌──────────────┐   ┌──────────────────┐    │
│  │  REST API    │──>│   Router     │──>│  Degrade Strategy │    │
│  │  Controller  │   │ (Calcite AST)│   │  (Failure Detect) │    │
│  └──────────────┘   └──────┬───────┘   └────────┬─────────┘    │
│                             │                     │              │
│                     ┌───────▼───────┐   ┌────────▼─────────┐    │
│                     │  Table Locate  │   │  mTLS Transport   │    │
│                     │  (Catalog API) │   │  (WebClient+SSL)  │    │
│                     └───────┬───────┘   └────────┬─────────┘    │
│                             │                     │              │
│                     ┌───────▼───────┐   ┌────────▼─────────┐    │
│                     │ Global Catalog │   │  Result Merger    │    │
│                     │  (Phase 1 Go)  │   │  (CONCAT/UNION/   │    │
│                     │   (8080)       │   │   JOIN/AGG)       │    │
│                     └───────────────┘   └───────────────────┘    │
└─────────────────────────────────────────────────────────────────┘
         │                              │
         ▼                              ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│ xinchang-cluster│  │  local-cluster  │  │   cce-cluster   │
│   (8091) ARM    │  │   (8092) x86    │  │  (8093) cloud   │
│   信创集群      │  │   本地集群      │  │   华为云 CCE    │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

## 3 模块说明

### 3.1 跨集群查询路由器（routing）

| 类 | 职责 |
|----|------|
| `FederatedQueryRouter` | 接收 SQL，解析表名，定位集群，生成查询计划 |
| `QueryPlan` | 查询计划数据结构（集群 → SQL 映射） |

### 3.2 全局 Catalog 表定位（catalog）

| 类 | 职责 |
|----|------|
| `GlobalCatalogClient` | 调用 Phase 1 platform/catalog REST API，解析表与集群映射 |
| `TableLocationService` | 用 Calcite SqlParser 解析 SQL AST 提取表名，定位表所在集群 |

表与集群映射规则：
1. 表元数据 `properties.cluster` 字段优先
2. 否则按 database 名匹配集群（约定 database 名包含集群标识）
3. 否则默认落到本地集群

### 3.3 mTLS 跨集群传输（transport）

| 类 | 职责 |
|----|------|
| `ClusterTransport` | 传输接口（支持替换实现） |
| `MtlsClusterTransport` | mTLS WebClient 实现，装载双向证书 |
| `MtlsConfig` | Netty SslContext 构造，复用 Phase 1 Istio mTLS |

配置示例：

```yaml
federated:
  mtls:
    enabled: true
    trust-store-path: /etc/istio/tls/ca.p12
    trust-store-password: ${MTLS_TRUSTSTORE_PASSWORD}
    key-store-path: /etc/istio/tls/client.p12
    key-store-password: ${MTLS_KEYSTORE_PASSWORD}
    key-alias: client
    verify-hostname: true
```

### 3.4 降级策略与告警（degrade）

| 类 | 职责 |
|----|------|
| `NetworkFailureDetector` | 滑动窗口失败计数，达阈值标记降级，冷却期防抖动 |
| `DegradeStrategy` | 降级决策与执行，失败集群回退到本地集群 |
| `AlertNotifier` | 告警事件收集与推送（日志 + Webhook） |

降级流程：
1. 对每个目标集群，先用 `NetworkFailureDetector` 判断是否已降级
2. 若已降级，跳过该集群，仅查询本地集群的本地表
3. 若查询中发生网络失败，记录失败，触发告警，回退到本地集群
4. 降级过程不抛异常，保证查询不失败（返回 DEGRADED 状态 + 部分结果）

### 3.5 查询归并（merge）

| 类 | 职责 |
|----|------|
| `QueryResultMerger` | 多集群结果归并，支持 4 种策略 |
| `MergeStrategy` | 归并策略枚举 |

归并策略：

| 策略 | 说明 | 适用场景 |
|------|------|----------|
| `CONCAT` | 简单拼接，保留顺序 | 分片表查询 |
| `UNION` | 去重合并 | UNION 语义 |
| `JOIN` | 跨集群等值连接 | 跨集群 Join（小结果集） |
| `AGGREGATE` | 数值列求和 | 聚合下推后二次归并 |

## 4 REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/federated/query` | 异步跨集群查询 |
| POST | `/api/v1/federated/query/sync` | 同步跨集群查询 |
| GET | `/api/v1/federated/health` | 健康检查 |
| GET | `/api/v1/federated/clusters` | 列出已知集群 |
| GET | `/api/v1/federated/degradations` | 列出降级告警事件 |

请求示例：

命令示例：跨集群查询

```bash
curl -X POST http://localhost:8094/api/v1/federated/query/sync \
  -H "Content-Type: application/json" \
  -d '{
    "sql": "SELECT * FROM orders_east UNION SELECT * FROM orders_west",
    "database": "default",
    "sync": true,
    "allowDegrade": true
  }'
```

响应示例：

```json
{
  "queryId": "uuid-...",
  "status": "SUCCESS",
  "schema": {"id": "INT", "name": "STRING"},
  "rows": [{"id": 1, "name": "a"}, {"id": 2, "name": "b"}],
  "totalRows": 2,
  "clusters": ["xinchang-cluster", "local-cluster"],
  "degraded": false,
  "elapsedMs": 1234,
  "timestamp": "2026-08-08T04:00:00Z"
}
```

## 5 集群拓扑

与 `platform/karmada/docker/docker-compose.yml` 对齐的 3 集群拓扑：

| 集群 | 端口 | 类型 | 厂商 | 架构 | 环境 |
|------|------|------|------|------|------|
| xinchang-cluster | 8091 | xinchang | kylin | arm64 | production |
| local-cluster | 8092 | local | kubernetes | amd64 | staging |
| cce-cluster | 8093 | cloud | huawei-cce | amd64 | production |

## 6 构建与运行

### 6.1 本地构建

命令示例：Maven 构建

```bash
cd platform/karmada/federated-query
mvn clean package -DskipTests
```

### 6.2 本地运行

命令示例：启动服务

```bash
java -jar target/federated-query-0.1.0-exec.jar
```

### 6.3 Docker 构建

命令示例：Docker 镜像

```bash
docker build -t sq/federated-query:0.1.0 .
```

### 6.4 单元测试

命令示例：运行单元测试

```bash
mvn test
```

### 6.5 集成测试

命令示例：pytest 集成测试

```bash
cd tests/integration
pytest docker/test_federated_query.py -v
```

## 7 依赖关系

| 依赖 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 3.2.5 | Web 框架 |
| Apache Calcite | 1.36.0 | SQL 解析与优化（Phase 1 T012） |
| Spring WebFlux | 3.2.5 | mTLS WebClient |
| Spring Data JPA | 3.2.5 | 持久化 |
| H2 / PostgreSQL | - | 开发/生产数据库 |
| platform/catalog | Phase 1 | 全局 Catalog 表元数据 |
| platform/karmada | Batch 1a | Karmada 控制面与集群拓扑 |

## 8 目录结构

```
platform/karmada/federated-query/
├── pom.xml                          # Maven 配置
├── Dockerfile                       # Docker 镜像
├── README.md                        # 本文档
├── src/main/java/com/shuqing/bigdata/federated/
│   ├── FederatedQueryApplication.java
│   ├── config/                      # 配置（MtlsConfig/SecurityConfig/Properties）
│   ├── controller/                  # REST API
│   ├── model/                       # 数据模型
│   ├── catalog/                     # 全局 Catalog 表定位
│   ├── routing/                     # 跨集群查询路由器
│   ├── transport/                   # mTLS 跨集群传输
│   ├── degrade/                     # 降级策略与告警
│   ├── merge/                       # 查询归并
│   └── service/                     # 核心执行服务
├── src/main/resources/
│   └── application.yml
└── src/test/java/                   # 单元测试
```