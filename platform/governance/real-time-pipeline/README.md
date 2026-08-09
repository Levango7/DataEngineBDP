# T036 实时治理管道

数据引擎大数据平台 V2.0 Phase 2 Batch 1b - T036 实时治理管道（元数据/血缘/质量）。

## 概述

基于 Phase 1 Iceberg V2（T015）与 Flink CDC（T014），开发实时治理管道，实现治理闭环 P95 ≤ 10s：

```
Iceberg REST Catalog commit
    → 元数据采集（≤ 5s）
    → Flink CDC 实时血缘解析 + NebulaGraph 更新（≤ 3s）
    → Flink CEP 流式质量规则评估（≤ 2s）
    → 违规即告警（≤ 5s）
    → 治理闭环 P95 ≤ 10s
```

## 架构

图：T036 实时治理管道架构图

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Iceberg REST Catalog                              │
│                  (commit 事件触发)                                    │
└──────────────┬──────────────────────────┬───────────────────────────┘
               │ Webhook 推送              │ 轮询兜底
               ▼                          ▼
┌──────────────────────────────────────────────────────────────────────┐
│              CatalogEventListener (Spring Boot 3.2)                  │
│              POST /api/v1/governance/catalog/events                  │
└──────────────────────┬───────────────────────────────────────────────┘
                       │ @Async
                       ▼
┌──────────────────────────────────────────────────────────────────────┐
│              MetadataCollector (≤ 5s)                                │
│              调用 Iceberg REST API 采集 schema/partition/snapshot    │
└──────────────────────┬───────────────────────────────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────────────────────────────┐
│              GovernancePipelineOrchestrator                          │
│              治理闭环编排                                             │
└──────────┬───────────────────────────────────────┬───────────────────┘
           │                                       │
           ▼                                       ▼
┌──────────────────────────────┐  ┌─────────────────────────────────────┐
│ RealTimeLineageAnalyzer      │  │ StreamingQualityRuleEngine          │
│ FlinkCdcSqlLineageParser     │  │ QualityRuleEvaluator                │
│ → NebulaLineageGraphClient   │  │ → QualityAlertEmitter               │
│ (NebulaGraph, ≤ 3s)          │  │ (Flink CEP, ≤ 2s + 告警 ≤ 5s)       │
└──────────────────────────────┘  └─────────────────────────────────────┘
```

## 组件

### 1. Iceberg REST Catalog 事件监听器（Java/Spring Boot 3.2）

| 类 | 职责 |
|---|---|
| `CatalogEventListener` | 接收 commit 事件（Webhook + 轮询），异步触发元数据采集 |
| `IcebergRestCatalogClient` | 封装 Iceberg REST Catalog V1/V2 API |
| `MetadataCollector` | 采集表元数据（schema/partition/snapshot），≤ 5s |

### 2. Flink CDC 实时血缘解析器（Java + NebulaGraph）

| 类 | 职责 |
|---|---|
| `FlinkCdcSqlLineageParser` | 解析 Flink CDC SQL，提取字段级血缘 |
| `NebulaLineageGraphClient` | 写入 NebulaGraph 血缘图（含降级缓存） |
| `RealTimeLineageAnalyzer` | 端到端血缘解析 + 图更新 |

### 3. 流式质量规则引擎（Flink CEP）

| 类 | 职责 |
|---|---|
| `QualityRule` | 规则定义（NOT_NULL/UNIQUE/RANGE/FORMAT/CUSTOM） |
| `QualityRuleEvaluator` | 同步规则评估 |
| `QualityRuleCepJob` | Flink CEP 作业定义（Pattern 编译） |
| `QualityAlertEmitter` | 告警发射（Webhook + 内存缓冲） |
| `StreamingQualityRuleEngine` | 端到端评估 + 告警 |

### 4. 治理闭环编排

| 类 | 职责 |
|---|---|
| `GovernancePipelineOrchestrator` | 串联元数据 → 血缘 → 质量 → 告警，P95 ≤ 10s |
| `GovernanceController` | REST API 管理接口 |

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/v1/governance/catalog/events` | 接收 Catalog commit 事件（Webhook） |
| POST | `/api/v1/governance/metadata/collect` | 手动触发元数据采集 |
| GET | `/api/v1/governance/metadata/{table}` | 查询缓存的表元数据 |
| POST | `/api/v1/governance/lineage/parse` | 解析 Flink CDC SQL 血缘 |
| GET | `/api/v1/governance/lineage/{targetTable}` | 查询血缘 |
| GET | `/api/v1/governance/lineage` | 查询所有血缘 |
| POST | `/api/v1/governance/quality/rules` | 注册质量规则 |
| DELETE | `/api/v1/governance/quality/rules/{ruleId}` | 注销质量规则 |
| GET | `/api/v1/governance/quality/rules` | 查询所有规则 |
| POST | `/api/v1/governance/quality/evaluate` | 评估单条记录 |
| GET | `/api/v1/governance/alerts` | 查询所有告警 |
| GET | `/api/v1/governance/alerts/{table}` | 查询指定表告警 |
| GET | `/api/v1/governance/pipeline/metrics` | 治理闭环指标（P95） |
| GET | `/api/v1/governance/pipeline/history` | 治理闭环执行历史 |
| GET | `/api/v1/health` | 健康检查 |

## 配置

表：实时治理管道配置参数说明表

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `governance.iceberg.rest-catalog-url` | `http://localhost:8181` | Iceberg REST Catalog 端点 |
| `governance.iceberg.poll-interval-ms` | `1000` | 事件轮询间隔（毫秒） |
| `governance.flink.sql-gateway-url` | `http://localhost:8083` | Flink SQL Gateway 端点 |
| `governance.nebula.graphd-host` | `localhost` | NebulaGraph graphd 主机 |
| `governance.nebula.graphd-port` | `9669` | NebulaGraph graphd 端口 |
| `governance.nebula.space` | `lineage` | NebulaGraph 图空间 |
| `governance.pipeline.metadata-collect-timeout-ms` | `5000` | 元数据采集超时 |
| `governance.pipeline.alert-latency-target-ms` | `5000` | 告警延迟目标 |
| `governance.pipeline.pipeline闭环-p95-target-ms` | `10000` | 治理闭环 P95 目标 |

## 部署

### Docker

命令示例：构建并运行实时治理管道容器

```bash
# 构建
docker build -t sq/real-time-governance-pipeline:0.1.0 .

# 运行
docker run -d --name it-governance-pipeline \
    -p 18090:18090 \
    -e ICEBERG_REST_CATALOG_URL=http://iceberg-rest:8181 \
    -e FLINK_SQL_GATEWAY_URL=http://flink-sql-gateway:8083 \
    -e NEBULA_GRAPHD_HOST=nebula-graphd \
    -e JWT_SECRET=your-production-secret \
    sq/real-time-governance-pipeline:0.1.0
```

### 依赖服务

- **Iceberg REST Catalog**：监听 commit 事件源
- **Flink SQL Gateway**：提交 CDC 血缘解析与 CEP 质量规则作业
- **NebulaGraph**：血缘图存储（graphd + metad + storaged）
- **PostgreSQL**（生产）：治理事件持久化（开发用 H2）

## 测试

集成测试位于 `tests/integration/docker/test_realtime_governance.py`，覆盖：

- 事件触发场景（Catalog commit → 元数据采集）
- 血缘解析场景（Flink CDC SQL 字段级血缘正确）
- 质量规则场景（NOT_NULL/UNIQUE/RANGE/FORMAT/CUSTOM）
- 告警场景（质量违规即告警，延迟 ≤ 5s）
- 性能压测（治理闭环 P95 ≤ 10s）

命令示例：运行集成测试

```bash
cd tests/integration
pytest docker/test_realtime_governance.py -v --html=report.html
```

## 验收标准

- [x] 治理闭环 P95 ≤ 10s（从 Catalog commit 到告警）
- [x] 元数据采集 ≤ 5s
- [x] 血缘实时更新，字段级血缘正确
- [x] 质量违规即告警，告警延迟 ≤ 5s
- [x] 实时与批量管道并存，互不干扰

## 技术栈

- Java 17 + Spring Boot 3.2.5
- Flink 1.18.1 + Flink CDC 3.0.0 + Flink CEP
- Iceberg 1.5.2（REST Catalog V1/V2）
- NebulaGraph 3.6.0（血缘图存储）
- Apache Calcite 1.36.0（SQL 解析）
- H2/PostgreSQL（治理事件持久化）
- Micrometer Prometheus（指标暴露）