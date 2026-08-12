# Catalog — 数据引擎大数据平台自研元数据目录

> 轻量级 Catalog 服务，替代 Hive Metastore / DataHub，管理表 / 列 / 分区元数据。

## 项目用途

`catalog` 是数据引擎大数据平台（DataEngineBDP）的元数据目录服务，提供：

- 数据库（Database / Namespace）的 CRUD
- 表（Table）元数据的 CRUD，含列定义、分区键、自定义属性
- 服务健康检查端点

当前版本采用 **内存存储**，可作为骨架快速启动；后续可扩展 `internal/store.Store` 接口接入 PostgreSQL 等持久化实现。

## 技术栈

- 语言：Go 1.21
- Web 框架：[Gin](https://github.com/gin-gonic/gin) v1.9.1
- 模块路径：`github.com/shuqing/bigdata/catalog`

## 目录结构

```
platform/catalog/
├── go.mod
├── main.go                          # 入口，启动 :8082 HTTP 服务
├── Dockerfile                       # 多阶段构建
├── README.md
├── .gitignore
└── internal/
    ├── model/
    │   ├── database.go              # Database 元数据模型
    │   └── table.go                 # Table / Column 元数据模型
    ├── store/
    │   └── store.go                 # Store 接口 + MemoryStore 实现
    └── handler/
        ├── health.go                # 健康检查 handler
        └── catalog.go               # Catalog REST handler
```

## 构建与运行

### 本地构建

```bash
cd platform/catalog
go build -o bin/catalog ./...
```

### 本地运行

```bash
go run ./...
# 或
./bin/catalog
```

服务默认监听 `:8082`，可通过环境变量覆盖：

| 环境变量          | 默认值 | 说明              |
| ----------------- | ------ | ----------------- |
| `CATALOG_PORT`    | 8082   | HTTP 监听端口     |
| `CATALOG_VERSION` | 0.1.0  | 健康检查返回版本  |

### Docker 构建

```bash
docker build -t shuqing/catalog:0.1.0 .
docker run -p 8082:8082 shuqing/catalog:0.1.0
```

## API 端点

所有端点位于 `/api/v1` 前缀下。

### 健康检查

| 方法 | 路径               | 说明         |
| ---- | ------------------ | ------------ |
| GET  | `/api/v1/health`   | 返回服务状态 |

响应示例：

```json
{"status":"UP","component":"catalog","version":"0.1.0"}
```

### Database CRUD

| 方法   | 路径                              | 说明         |
| ------ | --------------------------------- | ------------ |
| GET    | `/api/v1/catalog/databases`       | 列出数据库   |
| POST   | `/api/v1/catalog/databases`       | 创建数据库   |
| GET    | `/api/v1/catalog/databases/{id}`  | 获取数据库   |
| DELETE | `/api/v1/catalog/databases/{id}`  | 删除数据库   |

### Table CRUD

| 方法   | 路径                                  | 说明                          |
| ------ | ------------------------------------- | ----------------------------- |
| GET    | `/api/v1/catalog/tables?database=xxx` | 列出表（可选按库名过滤）      |
| POST   | `/api/v1/catalog/tables`              | 创建表                        |
| GET    | `/api/v1/catalog/tables/{id}`         | 获取表                        |
| PUT    | `/api/v1/catalog/tables/{id}`         | 更新表                        |
| DELETE | `/api/v1/catalog/tables/{id}`         | 删除表                        |

### 创建表示例

```bash
curl -X POST http://localhost:8082/api/v1/catalog/tables \
  -H "Content-Type: application/json" \
  -d '{
    "databaseName":"default",
    "tableName":"users",
    "columns":[
      {"name":"id","type":"bigint","nullable":false},
      {"name":"name","type":"string","nullable":true}
    ],
    "partitionKeys":["dt"]
  }'
```

## 与平台其他组件的关系

| 组件              | 关系                                                              |
| ----------------- | ----------------------------------------------------------------- |
| `sql-gateway`     | SQL 网关解析表名后向 catalog 查询表元数据，获取列与分区信息       |
| `encaps-layer`    | 封装层在写入数据前向 catalog 注册表 schema，保证元数据可发现      |
| MinIO             | catalog 不直接存储数据，仅维护逻辑 schema；底层数据落在 MinIO     |

## 后续规划

- [ ] 接入 PostgreSQL 持久化（实现 `store.Store`）
- [ ] 增加表血缘与统计信息字段
- [ ] 引入 OpenAPI / Swagger 文档
- [ ] 接入平台统一鉴权与链路追踪