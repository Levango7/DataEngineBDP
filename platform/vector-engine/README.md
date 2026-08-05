# Vector Engine (L4.5.1 向量检索引擎)

数擎大数据平台向量检索引擎，提供向量集合管理、向量 CRUD、ANN 近似检索与混合检索（向量+标量）能力。

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
│           ├── milvus.go          # 骨架（默认构建）
│           └── milvus_enabled.go # 真实实现（build tag）
```

## API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| GET  | `/health` | 健康检查 |
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
| `VECTOR_ENGINE_PORT` | `8084` | HTTP 服务端口 |
| `DEFAULT_TOP_K` | `10` | 默认检索 topK |
| `MAX_VECTOR_DIM` | `32768` | 最大向量维度 |
| `MILVUS_HOST` | `127.0.0.1` | Milvus 主机 |
| `MILVUS_PORT` | `19530` | Milvus 端口 |
| `MILVUS_DATABASE` | `default` | Milvus 数据库 |
| `MILVUS_USERNAME` | - | Milvus 认证用户名 |
| `MILVUS_PASSWORD` | - | Milvus 认证密码 |

## 开发

```bash
# 编译
go build ./...

# 测试
go test ./...

# 运行（Mock 模式）
go run . 

# 运行（Milvus 模式，需安装 SDK 并启用 build tag）
go build -tags milvus_enabled
STORE_TYPE=milvus MILVUS_HOST=127.0.0.1 MILVUS_PORT=19530 ./vector-engine
```

## 设计要点

1. **接口抽象**：`VectorStore` 接口定义全部能力，上层不感知具体后端
2. **Mock 优先**：默认构建使用 Mock 实现，零外部依赖，CI 友好
3. **Build tag 隔离**：Milvus SDK 通过 `milvus_enabled` tag 隔离，未安装 SDK 也能编译
4. **深拷贝防护**：Mock 实现对插入的向量做深拷贝，避免外部修改污染内部状态
5. **哨兵错误**：使用 `errors.Is` 友好的哨兵错误，便于上层精确判别