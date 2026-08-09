# Iceberg snapshot 隔离配置与验证文档

## 第1章 概述

### 1.1 文档目的

本文档描述数据引擎大数据平台 T035 流批一体调度模块中 Iceberg snapshot 隔离机制的配置方法与验证流程。

### 1.2 snapshot 隔离原理

Iceberg 表的每次 commit 产生一个不可变 snapshot。snapshot 隔离机制利用此特性实现批流数据一致：

- **Spark 批读固定 snapshot**：批作业启动时锁定当前 snapshot-id=S0，整个批作业期间读 S0 数据视图，不受后续 Flink 流写入影响
- **Flink 流读最新 snapshot**：流作业通过 Flink Iceberg Source streaming 模式持续读最新 snapshot，实时消费增量数据
- **数据一致保证**：批读历史快照（S0）、流读实时增量（S1、S2...），基于同一 Iceberg 表但 snapshot 隔离

图：snapshot 隔离示意图

```
时间轴 ──────────────────────────────────────────────►

Iceberg 表 snapshot:
  S0 ──────► S1 ──────► S2 ──────► S3 ──────► S4
  │          │          │          │          │
  │          │          │          │          │
  ▼          ▼          ▼          ▼          ▼
  批作业     Flink      Flink      Flink      Flink
  锁定 S0    写入 S1    写入 S2    写入 S3    写入 S4
  │          │          │          │          │
  │          └──────────┴──────────┴──────────┘
  │                      │
  │                      ▼
  │                  流作业持续读
  │                  最新 snapshot
  ▼
  批作业始终读 S0
  （固定快照，不受流写入影响）
```

## 第2章 Spark 批读固定 snapshot 配置

### 2.1 Spark Session 配置

Spark 批作业通过以下配置连接 Iceberg Catalog 并读取固定 snapshot：

代码示例：Spark 批读固定 snapshot（Scala）

```scala
import org.apache.spark.sql.SparkSession

val spark = SparkSession.builder()
  .appName("batch-read-fixed-snapshot")
  // Iceberg Spark SQL 扩展
  .config("spark.sql.extensions",
    "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
  // Iceberg Catalog 配置
  .config("spark.sql.catalog.shuqing_catalog",
    "org.apache.iceberg.spark.SparkCatalog")
  .config("spark.sql.catalog.shuqing_catalog.type", "hive")
  .config("spark.sql.catalog.shuqing_catalog.uri",
    "thrift://localhost:9083")
  .config("spark.sql.catalog.shuqing_catalog.warehouse",
    "s3://shuqing-warehouse/iceberg")
  // S3 配置
  .config("spark.sql.catalog.shuqing_catalog.s3.endpoint",
    "http://localhost:9000")
  .config("spark.sql.catalog.shuqing_catalog.s3.access-key-id",
    "minio-access-key")
  .config("spark.sql.catalog.shuqing_catalog.s3.secret-access-key",
    "minio-secret-key")
  .getOrCreate()

// 方式1：通过 history(snapshot_id => ...) 读固定 snapshot
val batchSnapshotId = 1001L  // 由 IcebergSnapshotManager.lockBatchSnapshot() 获取
val batchData = spark.sql(
  s"""
    |SELECT * FROM shuqing_catalog.orders_db.orders_table
    |.history(snapshot_id => $batchSnapshotId)
    |""".stripMargin)

// 方式2：通过 snapshot-id scan 配置（Iceberg 1.5+）
spark.conf.set(
  "spark.sql.catalog.shuqing_catalog.orders_db.orders_table.snapshot-id",
  batchSnapshotId.toString)
val batchData2 = spark.sql(
  "SELECT * FROM shuqing_catalog.orders_db.orders_table")
```

### 2.2 批作业提交流程

1. **锁定 snapshot**：批作业启动前调用 `IcebergSnapshotManager.lockBatchSnapshot(table)` 获取当前 snapshot-id
2. **注入配置**：将固定 snapshot-id 注入 Spark Conf
3. **提交作业**：通过 `SparkBatchSubmitter.submitBatch()` 提交（内部使用 SparkLauncher）
4. **执行期间隔离**：Spark 批作业读固定 snapshot，Flink 流作业可同时写入新 snapshot

### 2.3 配置项

表：Spark 批读固定 snapshot 配置参数说明表

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `shuqing.stream-batch.iceberg.batch-snapshot-lock-mode` | `AT_JOB_START` | 批读 snapshot 锁定模式（`AT_JOB_START` / `EXPLICIT`） |
| `shuqing.stream-batch.spark.master` | `spark://localhost:7077` | Spark Master |
| `shuqing.stream-batch.spark.deploy-mode` | `cluster` | Deploy Mode |
| `shuqing.stream-batch.spark.executor-cores` | `3` | Executor 核数（与 L2.2 对齐） |
| `shuqing.stream-batch.spark.executor-instances` | `3` | Executor 实例数 |

## 第3章 Flink 流读最新 snapshot 配置

### 3.1 Flink Table API 配置

Flink 流作业通过 Iceberg Connector streaming 模式持续读最新 snapshot：

代码示例：Flink 流读最新 snapshot（Java）

```java
import org.apache.flink.table.api.TableEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;

TableEnvironment tEnv = TableEnvironment.create(
    EnvironmentSettings.inStreamingMode());

// 创建 Iceberg Catalog
tEnv.executeSql(
    "CREATE CATALOG shuqing_catalog WITH (" +
    "  'type' = 'iceberg'," +
    "  'catalog-type' = 'hive'," +
    "  'uri' = 'thrift://localhost:9083'," +
    "  'warehouse' = 's3://shuqing-warehouse/iceberg'," +
    "  's3.endpoint' = 'http://localhost:9000'," +
    "  's3.access-key-id' = 'minio-access-key'," +
    "  's3.secret-access-key' = 'minio-secret-key'" +
    ")");

// 流读最新 snapshot（streaming 模式，持续消费增量）
tEnv.executeSql(
    "SELECT * FROM shuqing_catalog.orders_db.orders_table" +
    " /*+ OPTIONS('streaming'='true', 'monitor-interval'='10s') */");
```

### 3.2 流作业提交流程

1. **获取流读起点**：调用 `IcebergSnapshotManager.getStreamStartSnapshot(table)` 获取起始 snapshot
2. **注入配置**：将 Iceberg Connector streaming 配置注入 Flink Conf
3. **提交作业**：通过 `FlinkStreamSubmitter.submitStream()` 提交（内部使用 Flink REST API）
4. **持续消费**：Flink 流作业持续读最新 snapshot，实时消费增量数据

### 3.3 配置项

表：Flink 流读最新 snapshot 配置参数说明表

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `shuqing.stream-batch.iceberg.stream-snapshot-mode` | `LATEST` | 流读 snapshot 模式（`LATEST` / `FROM_TIMESTAMP`） |
| `shuqing.stream-batch.flink.job-manager-rest` | `http://localhost:8081` | Flink JM REST 地址 |
| `shuqing.stream-batch.flink.parallelism` | `4` | Flink 并行度（与 L2.3 对齐） |
| `shuqing.stream-batch.flink.checkpoint-interval-ms` | `30000` | Checkpoint 间隔 |

## 第4章 snapshot 隔离验证

### 4.1 验证流程

DAG 执行完成后，`StreamBatchDagOrchestrator` 自动调用 `IcebergSnapshotManager.verifySnapshotIsolation()` 验证：

1. **收集 snapshot**：按 Iceberg 表分组收集批节点使用的 snapshot-id 与流节点使用的 snapshot-id
2. **比对 snapshot-id**：验证批 snapshot-id ≤ 流 snapshot-id（批读历史快照，流读最新）
3. **比对时间戳**：验证批 snapshot 时间戳 ≤ 流 snapshot 时间戳（在容忍度内）
4. **输出结论**：生成验证结果（通过/失败 + 详情）

### 4.2 验证规则

表：snapshot 隔离验证规则说明表

| 规则 | 说明 | 失败原因 |
|------|------|----------|
| 批 snapshot-id ≤ 流 snapshot-id | 批读的是历史快照 | 批读超前于流读，隔离失效 |
| 批 snapshot 时间戳 ≤ 流 snapshot 时间戳 | 批读时间不超前 | 流 snapshot 早于批 snapshot |
| 时间差 ≤ 容忍度 | 批流时间差在容忍范围 | 时间差超过 `isolation-tolerance-ms` |

### 4.3 验证 API

命令示例：验证 snapshot 隔离

```bash
# 提交流批 DAG（DAG 执行完成后自动验证）
curl -X POST http://localhost:18086/api/v1/stream-batch/dags \
  -H "Content-Type: application/json" \
  -d @dag-definition.json

# 查询 DAG 执行结果（含 snapshot 隔离验证结论）
curl http://localhost:18086/api/v1/stream-batch/dags/<dagId>
```

验证结果字段：

- `snapshotIsolationValid` — 隔离验证是否通过
- `snapshotIsolationDetail` — 验证详情描述

## 第5章 与 Phase 1 集成

### 5.1 与 T015 Iceberg V2 upsert 集成

本模块基于 Phase 1 T015 Iceberg V2 upsert 能力：

- T015 实现 Iceberg V2 表的 upsert（MERGE INTO）操作，产生新 snapshot
- 本模块的 Flink 流作业通过 Iceberg Sink 写入 V2 表，触发 upsert commit 产生新 snapshot
- Spark 批作业读固定 snapshot，不受 upsert commit 影响

### 5.2 与 T014 Flink CDC 集成

本模块基于 Phase 1 T014 Flink CDC 能力：

- T014 实现 Flink CDC 从 MySQL/Oracle/Postgres 到 Iceberg 的实时同步
- 本模块的 Flink 流作业复用 T014 的 CDC Source，CDC 数据写入 Iceberg 表
- BI 视图路由器对流最新视图的查询实时反映 CDC 同步的数据

### 5.3 与 T016 Doris 物化视图集成

本模块与 Phase 1 T016 Doris 物化视图集成：

- T016 实现 Doris 物化视图的自动刷新（CDC 触发 / 定时触发）
- 本模块的 BI 视图路由器优先命中 Doris 物化视图（避免重复聚合计算）
- 物化视图刷新与 Iceberg snapshot 同步，保证物化视图数据与 Iceberg 表一致

## 第6章 部署配置

### 6.1 Iceberg Catalog 配置

配置文件：application.yml

```yaml
shuqing:
  stream-batch:
    iceberg:
      catalog-type: hive
      catalog-name: shuqing_catalog
      catalog-uri: thrift://localhost:9083
      warehouse: s3://shuqing-warehouse/iceberg
      batch-snapshot-lock-mode: AT_JOB_START
      stream-snapshot-mode: LATEST
      isolation-validation-enabled: true
      isolation-tolerance-ms: 0
```

### 6.2 Docker Compose 集成

本模块在 `tests/integration/docker-compose.yml` 中编排以下容器：

- `it-dolphinscheduler` — DolphinScheduler 3.2.0（Master + Worker + API + Alert）
- `it-spark` — Spark 3.5.0（Master + Worker）
- `it-flink` — Flink 1.18.0（JobManager + TaskManager）
- `it-iceberg` — Iceberg Catalog（Hive Metastore）
- `it-minio` — MinIO（S3 兼容存储，Iceberg Warehouse）
- `it-doris` — Doris 2.0.0（FE + BE，物化视图）
- `it-stream-batch-scheduler` — 本模块（端口 18086）

## 第7章 验证清单

- [ ] Spark 批作业能读取 Iceberg 固定 snapshot
- [ ] Flink 流作业能持续读 Iceberg 最新 snapshot
- [ ] 同一 Iceberg 表同时被 Spark 批读与 Flink 流读数据一致（snapshot 隔离）
- [ ] DAG 执行完成后 snapshot 隔离验证自动通过
- [ ] BI 视图路由器能根据查询模式自动选择批快照或流最新视图
- [ ] Doris 物化视图命中时优先使用物化视图