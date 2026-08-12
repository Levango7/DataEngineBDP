# Vector Engine (L4.5.1 向量检索引擎)

数据引擎大数据平台向量检索引擎，提供向量集合管理、向量 CRUD、ANN 近似检索与混合检索（向量+标量）能力。

## 架构

采用 **接口抽象 + 依赖注入** 策略：

```
┌─────────────┐     ┌──────────────┐     ┌────────────────────┐
│   API 层    │ ──▶ │  Service 层  │ ──▶ │  VectorStore 接口  │
│ (Gin HTTP)  │     │ (业务逻辑)   │     │      (抽象)        │
└─────────────┘     └──────────────┘     └────────────────────┘
                                                  │
                                    ┌─────────────┼─────────────┐
                                    ▼                           ▼
                          ┌─────────────────┐         ┌─────────────────┐
                          │  MockVectorStore │         │ MilvusVectorStore│
                          │  (内存实现/测试)  │         │  (生产实现/SDK)  │
                          └─────────────────┘         └─────────────────┘
```

- **Mock 实现**：基于内存 map，暴力检索，用于单元测试与本地开发，零外部依赖
- **Milvus 实现**：基于 Milvus Go SDK，通过 build tag `milvus_enabled` 控制编译

## 目录结构

```
vector-engine/
├── go.mod
├── main.go                    # Gin 服务器入口
├── milvus_stub.go             # 默认构建：newMilvusStore 返回 nil
├── milvus_store.go            # milvus_enabled 构建：真实 Milvus 实例
├── Dockerfile
├── internal/
│   ├── api/                   # HTTP handlers
│   │   ├── handler.go
│   │   └── handler_test.go
│   ├── config/                # 配置加载
│   │   ├── config.go
│   │   └── config_test.go
│   ├── middleware/            # Gin 中间件
│   │   ├── cors.go
│   │   ├── logging.go
│   │   └── middleware_test.go
│   ├── service/               # 业务逻辑层
│   │   ├── service.go
│   │   └── service_test.go
│   └── store/                 # 存储抽象与实现
│       ├── store.go           # VectorStore 接口 + 数据模型
│       ├── mock/              # Mock 实现
│       │   ├── mock.go
│       │   └── mock_test.go
│       └── milvus/            # Milvus 实现
│           ├── milvus_common.go    # 类型定义与构造器（所有构建）
│           ├── milvus.go           # 骨架（默认构建，返回 ErrNotImplemented）
│           └── milvus_enabled.go  # 真实实现（build tag milvus_enabled，链接 SDK）
```

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/api/v1/health` | 健康检查 |
| POST | `/api/v1/collections` | 创建集合 |
| DELETE | `/api/v1/collections/:name` | 删除集合 |
| POST | `/api/v1/collections/:name/vectors` | 插入向量 |
| POST | `/api/v1/collections/:name/search` | 向量检索 |
| POST | `/api/v1/collections/:name/hybrid-search` | 混合检索（向量+标量） |
| DELETE | `/api/v1/collections/:name/vectors` | 删除向量 |
| GET | `/api/v1/collections/:name/stats` | 集合统计 |

## 配置

通过环境变量配置：

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `STORE_TYPE` | `mock` | 存储后端：`mock` 或 `milvus` |
| `VECTOR_ENGINE_PORT` | `8086` | HTTP 服务端口 |
| `DEFAULT_TOP_K` | `10` | 默认检索 topK |
| `MAX_VECTOR_DIM` | `32768` | 最大向量维度 |
| `MILVUS_HOST` | `127.0.0.1` | Milvus 主机 |
| `MILVUS_PORT` | `19530` | Milvus 端口 |
| `MILVUS_DATABASE` | `default` | Milvus 数据库 |
| `MILVUS_USERNAME` | - | Milvus 认证用户名 |
| `MILVUS_PASSWORD` | - | Milvus 认证密码 |

## Milvus 启用

默认构建使用 Mock 实现（零外部依赖）。生产环境通过 build tag `milvus_enabled` 链接真实 Milvus Go SDK。

### 构建方式

```bash
# 默认构建（Mock，无需 Milvus 服务）
go build ./...
go test ./...

# Milvus 构建（链接真实 SDK）
go build -tags milvus_enabled ./...
go vet -tags milvus_enabled ./...
```

### 运行方式

```bash
# Mock 模式（默认）
STORE_TYPE=mock ./vector-engine

# Milvus 模式（需先用 -tags milvus_enabled 构建，并启动 Milvus 服务）
STORE_TYPE=milvus \
  MILVUS_HOST=127.0.0.1 \
  MILVUS_PORT=19530 \
  MILVUS_DATABASE=default \
  MILVUS_USERNAME=root \
  MILVUS_PASSWORD=milvus \
  ./vector-engine
```

若 `STORE_TYPE=milvus` 但未用 `milvus_enabled` 构建，服务会回退到 Mock 并打印告警。

### Milvus 集合 Schema 约定

每个向量集合在 Milvus 中映射为三字段 Schema：

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VarChar(65535) 主键 | 向量 ID（字符串） |
| `vector` | FloatVector(dim) | 向量数据 |
| `metadata` | JSON | 元数据 map |

索引在 `vector` 字段上创建，类型与参数由 `CreateCollectionRequest.IndexType` 决定：

| IndexType | Milvus 索引 | 默认参数 |
|-----------|-------------|----------|
| `FLAT` | FLAT | - |
| `IVF_FLAT` | IVF_FLAT | nlist=128, nprobe=10 |
| `HNSW` | HNSW | M=16, efConstruction=200, ef=64 |
| `IVF_PQ` | IVF_PQ | nlist=128, m=auto(dim), nbits=8, nprobe=10 |

度量类型（L2/IP/COSINE）直接映射到 Milvus 的 `entity.MetricType`，常量值一致。

### 标量过滤表达式

`Search.Filter` 与 `HybridSearch.Filter` 使用 [Milvus 表达式语法](https://milvus.io/docs/boolean.md)，例如：

- `color == "red"`
- `age > 18 && category == "news"`
- `tags like "%AI%"`

## 开发

```bash
# 编译（默认 Mock）
go build ./...

# 编译（Milvus 真实实现）
go build -tags milvus_enabled ./...

# 测试（使用 Mock，无需外部服务）
go test ./...

# 运行（Mock 模式）
go run .

# 运行（Milvus 模式）
STORE_TYPE=milvus MILVUS_HOST=127.0.0.1 MILVUS_PORT=19530 ./vector-engine
```

## 设计要点

1. **接口抽象**：`VectorStore` 接口定义全部能力，上层不感知具体后端
2. **Mock 优先**：默认构建使用 Mock 实现，零外部依赖，CI 友好
3. **Build tag 隔离**：Milvus SDK 通过 `milvus_enabled` tag 隔离，未启用时不链接 SDK，默认构建无需安装 SDK 即可编译
4. **真实 SDK 集成**：启用 `milvus_enabled` 后，`internal/store/milvus/milvus_enabled.go` 委托 Milvus Go SDK v2.4.2 实现集合 CRUD、向量插入、ANN 检索与混合检索
5. **深拷贝防护**：Mock 实现对插入的向量做深拷贝，避免外部修改污染内部状态
6. **哨兵错误**：使用 `errors.Is` 友好的哨兵错误（`ErrCollectionNotFound`/`ErrCollectionAlreadyExists`/`ErrInvalidDimension` 等），Mock 与 Milvus 实现保持一致
7. **元信息反查**：Milvus 实现通过 `DescribeCollection` + `DescribeIndex` 反查维度/度量类型/索引类型，无需本地缓存，支持跨重启一致性