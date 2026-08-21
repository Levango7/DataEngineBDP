# DataEngineBDP 基础设施 Docker 编排

在 WSL + Docker 中模拟真实大数据组件环境，用于 ROADMAP 集成测试。

## 组件清单

| 组件 | 镜像 | 版本 | 宿主机端口 | 用途 |
|------|------|------|-----------|------|
| Trino | trinodb/trino | 460 | 8080 | 交互查询引擎 |
| Doris FE | apache/doris-fe | 2.1.7 | 8030/9010/9020/9030 | OLAP 引擎前端 |
| Doris BE | apache/doris-be | 2.1.7 | 8040/9050/8060/9060 | OLAP 引擎后端 |
| NebulaGraph metad | vesoft/nebula-metad | v3.6.0 | 9559/19559 | 图数据库 Meta |
| NebulaGraph graphd | vesoft/nebula-graphd | v3.6.0 | 9669/19669 | 图数据库 Graph |
| NebulaGraph storaged | vesoft/nebula-storaged | v3.6.0 | 9779/19779 | 图数据库 Storage |
| MLflow | ghcr.io/mlflow/mlflow | v2.20.2 | 5000 | 机器学习平台 |
| Flink JobManager | flink | 1.20.0 | 8081/6123 | 流计算 JM |
| Flink TaskManager ×2 | flink | 1.20.0 | - | 流计算 TM |
| Spark Master | bitnami/spark | 3.5.3 | 7077/18080 | 批计算 Master |
| Spark Worker ×2 | bitnami/spark | 3.5.3 | - | 批计算 Worker |
| PostgreSQL | postgres | 16-alpine | 5432 | 共享数据库 |

## 快速开始

```bash
# 启动所有服务并初始化
bash scripts/infra/start-infra.sh

# 仅启动容器，不执行初始化
bash scripts/infra/start-infra.sh --no-init

# 停止并清理（删除数据卷）
bash scripts/infra/stop-infra.sh

# 停止但保留数据卷
bash scripts/infra/stop-infra.sh --keep-volumes

# 手动执行初始化
bash scripts/infra/init-infra.sh
```

## 服务端点

启动完成后可通过以下端点访问：

- **Trino UI**: http://localhost:8080
- **Doris FE UI**: http://localhost:8030
- **Doris MySQL**: `mysql -h 127.0.0.1 -P 9030 -u root`
- **NebulaGraph**: `nebula-console -addr 127.0.0.1 -port 9669 -user root -password nebula`
- **MLflow UI**: http://localhost:5000
- **Flink WebUI**: http://localhost:8081
- **Spark WebUI**: http://localhost:18080
- **PostgreSQL**: `psql -h localhost -p 5432 -U deadmin -d dataengine`

## 目录结构

```
docker/infra/
├── trino/config/          # Trino 配置
│   ├── config.properties
│   ├── node.properties
│   ├── jvm.config
│   └── catalog/           # 连接器配置
│       ├── memory.properties
│       └── tpcds.properties
├── doris/
│   ├── fe/fe.conf         # Doris FE 配置
│   └── be/be.conf         # Doris BE 配置
├── nebula/config/         # NebulaGraph 配置
│   ├── metad.conf
│   ├── graphd.conf
│   └── storaged.conf
├── flink/conf/            # Flink 配置
│   └── flink-conf.yaml
├── spark/conf/            # Spark 配置
│   ├── spark-defaults.conf
│   └── workers
├── mlflow/                # MLflow 后端存储
└── postgres/initdb/       # PostgreSQL 初始化 SQL
    └── 01-init.sql

scripts/infra/
├── start-infra.sh         # 启动脚本
├── stop-infra.sh          # 停止脚本
└── init-infra.sh          # 初始化脚本
```

## 资源需求

- **CPU**: 建议至少 8 核
- **内存**: 建议至少 16 GB（每容器限制 2 GB，共约 24 GB 峰值）
- **磁盘**: 建议至少 20 GB 可用空间
- **Docker**: Docker Compose V2
- **WSL**: WSL2（Windows 环境）

## 注意事项

1. **Doris 容器**需要 `privileged: true` 模式运行
2. **端口 18080** 用于 Spark Master WebUI，避免与 Trino 8080 冲突
3. **NebulaGraph** 初始化需要 `nebula-console` 客户端（脚本会自动检测）
4. **Doris MySQL** 默认用户 `root` 无密码
5. **NebulaGraph** 默认用户 `root` 密码 `nebula`
6. 首次启动需要拉取大量镜像，可能需要 10-30 分钟