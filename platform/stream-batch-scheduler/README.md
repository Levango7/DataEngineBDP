# T035 流批一体调度与统一入口

## 第1章 概述

### 1.1 模块定位

本模块是数擎大数据平台 V2.0 Phase 2 Batch 1b 的 T035 任务交付物，实现流批一体调度与统一入口。

### 1.2 核心能力

- **DolphinScheduler 流批统一编排**：扩展 DolphinScheduler DAG 节点类型，支持 Spark 批任务与 Flink 流任务在同一 DAG 编排
- **Iceberg snapshot 隔离**：Iceberg 表 snapshot 隔离机制，Spark 批读固定 snapshot，Flink 流读最新 snapshot，数据一致
- **BI 自动选择视图**：查询路由器根据查询模式（实时/离线）自动选择批快照视图或流最新视图
- **Doris 物化视图集成**：与 Phase 1 T016 Doris 物化视图集成，物化视图自动刷新支持流批一体

### 1.3 技术栈

表：技术栈对照表

| 组件 | 版本 | 用途 |
|------|------|------|
| DolphinScheduler | 3.2.0 | 流批统一 DAG 编排 |
| Spark | 3.5.0 | 批计算（读固定 snapshot） |
| Flink | 1.18.0 | 流计算（读最新 snapshot） |
| Iceberg | 1.5.0 | snapshot 隔离（V2 upsert） |
| Doris | 2.0.0 | 物化视图（OLAP 加速） |
| Spring Boot | 3.2.5 | REST API + 配置管理 |

## 第2章 目录结构

```
platform/stream-batch-scheduler/
├── pom.xml                                    # Maven 构建配置
├── README.md                                  # 本文档
├── docs/
│   └── iceberg-snapshot-isolation.md          # snapshot 隔离配置与验证文档
└── src/
    ├── main/
    │   ├── java/com/shuqing/bigdata/streambatch/
    │   │   ├── StreamBatchSchedulerApplication.java   # 启动类
    │   │   ├── model/                          # 数据模型
    │   │   │   ├── TaskType.java               # 任务类型枚举
    │   │   │   ├── DagNode.java                # DAG 节点
    │   │   │   ├── DagEdge.java                # DAG 边
    │   │   │   ├── StreamBatchDag.java         # 流批 DAG
    │   │   │   ├── TaskExecutionResult.java    # 任务执行结果
    │   │   │   ├── DagExecutionResult.java     # DAG 执行结果
    │   │   │   ├── ExecutionStatus.java        # 执行状态枚举
    │   │   │   └── SnapshotRef.java            # snapshot 引用
    │   │   ├── plugin/                         # DolphinScheduler 插件 SPI
    │   │   │   ├── TaskChannel.java            # 任务通道接口
    │   │   │   ├── TaskChannelFactory.java     # 任务通道工厂接口
    │   │   │   ├── SparkBatchTaskChannel.java  # Spark 批通道
    │   │   │   ├── FlinkStreamTaskChannel.java # Flink 流通道
    │   │   │   ├── SparkBatchTaskChannelFactory.java
    │   │   │   ├── FlinkStreamTaskChannelFactory.java
    │   │   │   └── TaskExecutionException.java
    │   │   ├── dag/                            # DAG 编排
    │   │   │   ├── StreamBatchDagOrchestrator.java  # 流批 DAG 编排器
    │   │   │   └── DagTopologicalSorter.java   # 拓扑排序与校验
    │   │   ├── spark/                          # Spark 批提交
    │   │   │   ├── SparkBatchSubmitter.java
    │   │   │   ├── SparkBatchConfig.java
    │   │   │   └── SparkSubmitResult.java
    │   │   ├── flink/                          # Flink 流提交
    │   │   │   ├── FlinkStreamSubmitter.java
    │   │   │   ├── FlinkStreamConfig.java
    │   │   │   └── FlinkSubmitResult.java
    │   │   ├── iceberg/                        # Iceberg snapshot 隔离
    │   │   │   ├── IcebergSnapshotManager.java
    │   │   │   ├── SnapshotIsolationConfig.java
    │   │   │   └── SnapshotIsolationResult.java
    │   │   ├── router/                         # BI 视图路由器
    │   │   │   ├── BiViewRouter.java
    │   │   │   ├── QueryMode.java
    │   │   │   ├── ViewRouterConfig.java
    │   │   │   ├── ViewSelectionResult.java
    │   │   │   └── DorisMaterializedViewIntegration.java
    │   │   ├── service/                        # 业务服务
    │   │   │   ├── StreamBatchOrchestrationService.java
    │   │   │   └── ViewRouterService.java
    │   │   └── controller/                     # REST API
    │   │       └── StreamBatchSchedulerController.java
    │   └── resources/
    │       ├── application.yml                 # 配置文件
    │       └── META-INF/services/             # DolphinScheduler SPI 注册
    │           └── com.levango7.dataenginebdp.streambatch.plugin.TaskChannelFactory
    └── test/
        └── java/com/shuqing/bigdata/streambatch/  # 单元测试
```

## 第3章 部署

### 3.1 编译

命令示例：编译 stream-batch-scheduler

```bash
cd platform/stream-batch-scheduler
mvn clean package -DskipTests
```

构建产物：

- `target/stream-batch-scheduler-0.1.0.jar` — 普通 jar（供其他模块依赖）
- `target/stream-batch-scheduler-0.1.0-exec.jar` — 可执行 fat jar（独立运行）

### 3.2 独立运行

命令示例：独立启动 stream-batch-scheduler

```bash
java -jar target/stream-batch-scheduler-0.1.0-exec.jar
```

服务端口：`18086`

### 3.3 DolphinScheduler 插件部署

将 `target/stream-batch-scheduler-0.1.0.jar` 复制到 DolphinScheduler Worker 的 `libs/` 目录，重启 Worker。插件通过 SPI 自动注册：

- `SparkBatchTaskChannelFactory` — 注册 `SPARK_BATCH` 任务类型
- `FlinkStreamTaskChannelFactory` — 注册 `FLINK_STREAM` 任务类型

### 3.4 Docker 部署

本模块在 `tests/integration/docker-compose.yml` 中编排，容器名 `it-stream-batch-scheduler`，端口 18086。

## 第4章 API 使用

### 4.1 提交流批 DAG

命令示例：提交流批 DAG

```bash
curl -X POST http://localhost:18086/api/v1/stream-batch/dags \
  -H "Content-Type: application/json" \
  -d '{
    "dagId": "dag-001",
    "name": "orders-pipeline",
    "nodes": [
      {
        "nodeId": "batch-node",
        "name": "Spark 批读 orders",
        "taskType": "SPARK_BATCH",
        "icebergTable": "orders_db.orders_table",
        "mainResource": "hdfs:///jobs/batch-orders.jar",
        "mainClass": "com.shuqing.jobs.BatchOrdersJob"
      },
      {
        "nodeId": "stream-node",
        "name": "Flink 流读 orders",
        "taskType": "FLINK_STREAM",
        "icebergTable": "orders_db.orders_table",
        "mainResource": "hdfs:///jobs/stream-orders.jar",
        "mainClass": "com.shuqing.jobs.StreamOrdersJob",
        "parallelism": 4
      }
    ],
    "edges": [
      {"source": "batch-node", "target": "stream-node"}
    ]
  }'
```

### 4.2 查询 DAG 执行结果

命令示例：查询 DAG 执行结果

```bash
curl http://localhost:18086/api/v1/stream-batch/dags/dag-001
```

响应包含 `snapshotIsolationValid`（隔离验证是否通过）与 `snapshotIsolationDetail`（验证详情）。

### 4.3 BI 视图路由

命令示例：BI 视图路由

```bash
curl -X POST "http://localhost:18086/api/v1/stream-batch/router/route?table=orders_db.orders_table&queryMode=AUTO&latencyRequirementMs=3000" \
  -H "Content-Type: text/plain" \
  -d "SELECT * FROM orders_db.orders_table WHERE dt = '\''2026-08-08'\''"
```

响应包含 `viewName`（选中的视图）、`viewType`（视图类型）、`rewrittenSql`（重写后的 SQL）。

## 第5章 验证

### 5.1 集成测试

命令示例：运行集成测试

```bash
cd tests/integration
pytest docker/test_stream_batch.py -v
```

测试覆盖（≥15 用例）：

- 批流一致场景（同一 Iceberg 表 Spark 批读与 Flink 流读数据一致）
- DAG 编排场景（同一 DAG 编排批流任务成功）
- 视图选择场景（BI 自动选择批快照或流最新视图正确）
- snapshot 隔离验证场景
- Doris 物化视图集成场景

### 5.2 验收标准

- [x] Iceberg 表同时被 Spark 批读与 Flink 流读数据一致（snapshot 隔离）
- [x] 批/流同一 DAG 编排，DolphinScheduler 调度成功
- [x] BI 自动选择批快照或流最新视图，查询结果正确

## 第6章 与 Phase 1 集成

### 6.1 依赖关系

表：Phase 1 依赖对照表

| Phase 1 任务 | 依赖内容 | 集成方式 |
|-------------|----------|----------|
| T015 Iceberg V2 upsert | Iceberg V2 表 upsert 产生新 snapshot | Flink 流作业写入 V2 表，Spark 批读固定 snapshot |
| T014 Flink CDC | CDC 实时同步到 Iceberg | Flink 流作业复用 CDC Source |
| T016 Doris 物化视图 | 物化视图自动刷新 | BI 视图路由器优先命中物化视图 |

### 6.2 数据流

图：流批一体数据流架构图

```
数据源 (MySQL/Oracle/Postgres)
    │
    ▼ Flink CDC (T014)
Iceberg V2 表 (T015 upsert)
    │
    ├──► Spark 批读固定 snapshot ──► 批快照视图 ──┐
    │        (snapshot 隔离)                      │
    │                                            ├──► BI 视图路由器 ──► 查询结果
    └──► Flink 流读最新 snapshot ──► 流最新视图 ──┘
             (streaming)              │
                                     │
Doris 物化视图 (T016 自动刷新) ◄─────┘
    │
    └──► 优先命中物化视图（避免重复聚合）
```