# Phase 1a 开发中间件环境

> 本目录为 Phase 1a 不依赖 K8s 的本地开发任务提供中间件支撑。
> 配置文件：[`docker-compose-middleware.yml`](./docker-compose-middleware.yml)

## 1. 中间件环境启动命令

> 前置条件：本机已安装 Docker Desktop（≥ 4.20）或 Docker Engine（≥ 24.0）+ docker compose v2 插件。
> 资源建议：≥ 8 vCPU / 16 GB RAM / 50 GB 可用磁盘。

```bash
# 进入 dev 目录（或在项目根用 -f 指定文件）
cd design/deploy/dev

# 启动全部中间件（后台运行）
docker compose -f docker-compose-middleware.yml up -d

# 查看运行状态（含健康检查）
docker compose -f docker-compose-middleware.yml ps

# 查看某服务实时日志
docker compose -f docker-compose-middleware.yml logs -f kafka
docker compose -f docker-compose-middleware.yml logs -f milvus-standalone

# 仅启动某任务所需中间件（例如 T008 只需 Milvus + MinIO）
docker compose -f docker-compose-middleware.yml up -d milvus-standalone minio

# 停止全部中间件（保留数据卷，下次启动数据仍在）
docker compose -f docker-compose-middleware.yml down

# 停止并清除数据卷（⚠️ 丢失所有数据，仅在开发重置时使用）
docker compose -f docker-compose-middleware.yml down -v

# 拉取所有镜像（首次启动前预热，避免启动超时）
docker compose -f docker-compose-middleware.yml pull
```

## 2. 各服务连接地址和凭据

| 服务 | 容器名 | 对外地址 | 容器内地址 | 凭据 | 用途 |
| --- | --- | --- | --- | --- | --- |
| Kafka | sq-dev-kafka | `localhost:19092` | `kafka:9092` | 无（PLAINTEXT） | 消息队列 / CDC 中转 |
| Milvus | sq-dev-milvus | `localhost:19530` | `milvus-standalone:19530` | 无 | 向量库 |
| Milvus Health | sq-dev-milvus | `localhost:9091` | `milvus-standalone:9091` | 无 | 健康检查端点 |
| NebulaGraph | sq-dev-nebula-graph | `localhost:9669` | `nebula-graph:9669` | root / nebula（默认） | 图数据库 |
| MySQL | sq-dev-mysql-source | `localhost:13306` | `mysql-source:3306` | root / `test123`；sq_cdc / `sq_cdc_pwd` | CDC 源 |
| PostgreSQL | sq-dev-postgresql-source | `localhost:15432` | `postgresql-source:5432` | sq_federal / `test123` | CDC 源 / 联邦测试 |
| MinIO API | sq-dev-minio | `localhost:19000` | `minio:9000` | minioadmin / minioadmin | 对象存储 |
| MinIO Console | sq-dev-minio | `localhost:19001` | `minio:9001` | minioadmin / minioadmin | Web 控制台 |
| Elasticsearch | sq-dev-elasticsearch | `localhost:19200` | `elasticsearch:9200` | 无（xpack 关闭） | BM25 搜索 |
| Redis | sq-dev-redis | `localhost:16379` | `redis:6379` | 密码 `test123` | 缓存 |
| Flink Web | sq-dev-flink-jm | `localhost:18089` | `flink-jobmanager:8081` | 无 | Flink JobManager UI |

> **注意**：容器内地址仅在 `sq-dev-net` 网络内有效，用于服务间互访（如 Flink TaskManager → JobManager）。
> 应用从宿主机连接时使用 `localhost:1xxxx` 对外地址。

## 3. 各 Phase 1a 任务所需中间件映射

| 任务 | 任务名称 | 所需中间件 | 启动命令 |
| --- | --- | --- | --- |
| T008 | 多模态切片器 | Milvus + MinIO | `docker compose up -d milvus-standalone minio` |
| T009 | 混合检索与重排序 | Milvus + NebulaGraph + Elasticsearch | `docker compose up -d milvus-standalone nebula-graph elasticsearch` |
| T012 | Calcite 联邦优化器 | PostgreSQL（+ 可选 Trino，本环境用 PG 模拟） | `docker compose up -d postgresql-source` |
| T014 | Flink CDC 管道 | Flink + Kafka + MySQL + PostgreSQL | `docker compose up -d flink-jobmanager flink-taskmanager kafka mysql-source postgresql-source` |
| T018 | 金融模板 | 无外部依赖 | 无需启动中间件 |
| T022 | CryptoSpiFactory | 无外部依赖 | 无需启动中间件 |

> **建议**：开发期直接 `docker compose up -d` 启动全部，避免遗漏依赖。
> **资源紧张时**：按上表精确启动，可节省内存。

## 4. 常见问题排查

### 4.1 端口冲突

**现象**：启动报 `Bind for 0.0.0.0:19092 failed: port is already allocated`。

**排查**：

```bash
# 查看占用端口的进程（Windows）
netstat -ano | findstr :19092

# 查看占用端口的容器
docker ps --format "table {{.Names}}\t{{.Ports}}" | findstr 19092
```

**处理**：

- 若是本机原生服务（如本机装了 MySQL 占 3306），本配置已用 1xxxx 段避开，无需处理。
- 若是上次未清理的容器：`docker rm -f <container>` 后重启。
- 若需自定义端口：修改 `docker-compose-middleware.yml` 中 `ports` 段左侧端口。

### 4.2 健康检查不通过

**现象**：`docker compose ps` 显示某服务 `health: starting` 长时间不转 `healthy`。

**排查**：

```bash
# 查看健康检查日志
docker inspect --format '{{json .State.Health}}' sq-dev-milvus | jq

# 查看服务日志
docker compose logs --tail 100 sq-dev-milvus
```

**常见原因**：

| 服务 | 原因 | 处理 |
| --- | --- | --- |
| Milvus | 首次启动需加载模型，`start_period: 60s` 内未就绪 | 等待 90s 后再查；或调大 `start_period` |
| Elasticsearch | `vm.max_map_count` 太低 | `wsl -d docker-desktop sysctl -w vm.max_map_count=262144` |
| NebulaGraph | storaged 未 ADD SPACE | 首次需手动 `ADD SPACE`，见 §4.4 |
| Flink TM | JM 未就绪 | 检查 `flink-jobmanager` 健康状态 |

### 4.3 内存不足

**现象**：容器启动后立即 OOM 退出，或 Docker Desktop 提示资源不足。

**处理**：

1. 调高 Docker Desktop 内存限制至 ≥ 16 GB（Settings → Resources → Memory）。
2. 或减少启动服务：按 §3 表精确启动任务所需中间件。
3. 或调小单服务内存：例如 ES 的 `ES_JAVA_OPTS=-Xms512m -Xmx512m` 已是开发值，可进一步降到 256m。

### 4.4 NebulaGraph 首次初始化

NebulaGraph 启动后 storaged 需手动 ADD HOSTS 才能使用：

```bash
# 进入 graphd 容器
docker exec -it sq-dev-nebula-graph nebula-console -u root -p nebula --addr=127.0.0.1 --port=9669

# 在 console 内执行：
ADD HOSTS "nebula-storaged":9779;
SHOW HOSTS;  -- 确认所有 storaged 在线

# 创建测试图空间
CREATE SPACE sq_dev(partition_num=10, replica_factor=1, vid_type=FIXED_STRING(32));
USE sq_dev;
-- 后续按 T009 需要创建 Tag / Edge
```

### 4.5 MySQL CDC 配置验证

```bash
# 验证 binlog 已开启
docker exec -it sq-dev-mysql-source mysql -uroot -ptest123 -e \
  "SHOW VARIABLES LIKE 'log_bin'; SHOW VARIABLES LIKE 'binlog_format'; SHOW VARIABLES LIKE 'gtid_mode';"

# 预期输出：
# log_bin     ON
# binlog_format ROW
# gtid_mode   ON
```

### 4.6 PostgreSQL CDC 配置验证

```bash
# 验证 logical replication 已开启
docker exec -it sq-dev-postgresql-source psql -U sq_federal -d sq_federal_test -c \
  "SHOW wal_level; SHOW max_wal_senders; SHOW max_replication_slots;"

# 预期输出：
# wal_level              logical
# max_wal_senders        10
# max_replication_slots  10
```

### 4.7 重置开发环境

```bash
# 完全重置（停止 + 删容器 + 删卷 + 删网络）
docker compose -f docker-compose-middleware.yml down -v --remove-orphans

# 重新启动（会重新初始化所有数据）
docker compose -f docker-compose-middleware.yml up -d
```

## 5. 数据卷清单

| 卷名 | 对应服务 | 用途 |
| --- | --- | --- |
| kafka-data | kafka | Kafka 日志段 |
| milvus-etcd-data | milvus-etcd | Milvus 元数据 |
| milvus-minio-data | milvus-minio | Milvus 内嵌对象存储 |
| milvus-data | milvus-standalone | Milvus 运行数据 |
| nebula-graphd-data | nebula-graph | Graphd 数据 |
| nebula-metad-data | nebula-metad | Metad 数据 |
| nebula-storaged-data | nebula-storaged | Storaged 数据 |
| mysql-source-data | mysql-source | MySQL 数据 |
| postgresql-source-data | postgresql-source | PostgreSQL 数据 |
| minio-data | minio | MinIO 对象数据 |
| elasticsearch-data | elasticsearch | ES 索引数据 |
| redis-data | redis | Redis AOF 持久化 |
| flink-jm-data | flink-jobmanager | Flink checkpoint |
| flink-tm-data | flink-taskmanager | Flink TM 运行数据 |

## 6. 网络拓扑

```
sq-dev-net (bridge)
├── kafka                :9092
├── milvus-etcd          :2379
├── milvus-minio         :9000
├── milvus-standalone    :19530 / :9091
├── nebula-metad         :9559
├── nebula-storaged      :9779
├── nebula-graph         :9669
├── mysql-source         :3306
├── postgresql-source    :5432
├── minio                :9000 / :9001
├── elasticsearch        :9200
├── redis                :6379
├── flink-jobmanager     :8081
└── flink-taskmanager    (无对外端口，仅容器内)
```

## 7. 与生产环境的差异声明

| 维度 | 开发环境（本配置） | 生产环境 |
| --- | --- | --- |
| Kafka | 单节点 KRaft | 多节点 + SASL_SSL + Schema Registry |
| Milvus | standalone | 集群模式 + 独立 etcd + 独立 minio |
| NebulaGraph | 单副本 | 多副本 + 独立 metad 集群 |
| MySQL | 单实例 | 主从 + ProxySQL |
| PostgreSQL | 单实例 | 主从 + PgBouncer |
| MinIO | 单节点 | 多节点 erasure coding |
| Elasticsearch | single-node + 无安全 | 多节点 + xpack 安全 + TLS |
| Redis | 单实例 | 哨兵或集群 |
| Flink | 1 JM + 1 TM | HA JM + 多 TM + K8s 部署 |

> ⚠️ 本配置仅用于开发与集成测试，**严禁用于生产**。生产部署见 `design/deploy/charts/` Helm Chart。