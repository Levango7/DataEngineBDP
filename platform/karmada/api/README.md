# Karmada PropagationPolicy 控制台 API

## 概述

本服务提供租户通过控制台管理 Karmada `PropagationPolicy` 的 REST API，支持传播策略的 CRUD 操作。

## 技术栈

- **语言**：Go 1.25
- **Web 框架**：Gin
- **ORM**：GORM（开发环境 SQLite，生产环境可切换 PostgreSQL）
- **认证**：JWT Bearer Token（HMAC-SHA256，与平台其他组件统一）
- **多租户**：JWT 中 `tenantId` claim 隔离

## API 端点

| 方法   | 路径                                  | 说明           | 认证 |
|--------|---------------------------------------|----------------|------|
| GET    | `/api/v1/health`                      | 健康检查       | 否   |
| POST   | `/api/v1/propagation-policies`        | 创建传播策略   | 是   |
| GET    | `/api/v1/propagation-policies`        | 列出传播策略   | 是   |
| GET    | `/api/v1/propagation-policies/:name`  | 获取单个策略   | 是   |
| PUT    | `/api/v1/propagation-policies/:name`  | 更新策略       | 是   |
| DELETE | `/api/v1/propagation-policies/:name`  | 删除策略       | 是   |

## 请求示例

### 创建传播策略

```bash
curl -X POST http://localhost:8090/api/v1/propagation-policies \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "weighted-spread",
    "namespace": "default",
    "spec": {
      "resourceSelectors": [
        {"apiVersion": "apps/v1", "kind": "Deployment"}
      ],
      "placement": {
        "clusterAffinity": {
          "matchLabels": {"cluster.karmada.io/type": "xinchang"}
        },
        "replicaScheduling": {
          "replicaSchedulingType": "Divided",
          "replicaDivisionPreference": "Weighted",
          "weightPreference": {
            "staticWeightList": [
              {"targetCluster": {"clusterNames": ["xinchang-cluster"]}, "weight": 3},
              {"targetCluster": {"clusterNames": ["local-cluster"]}, "weight": 2},
              {"targetCluster": {"clusterNames": ["cce-cluster"]}, "weight": 1}
            ]
          }
        }
      }
    }
  }'
```

## 本地运行

```bash
cd platform/karmada/api
go mod tidy
go run main.go
```

## Docker 构建

```bash
docker build -t sq/karmada-api:0.1.0 .
docker run -d --name karmada-api -p 8090:8090 sq/karmada-api:0.1.0
```

## 环境变量

| 变量                | 默认值                                                | 说明                |
|---------------------|-------------------------------------------------------|---------------------|
| KARMADA_API_VERSION | 0.1.0                                                 | 服务版本            |
| KARMADA_API_PORT    | 8090                                                  | 监听端口            |
| KARMADA_API_DB      | karmada-api.db                                        | SQLite 数据库路径   |
| JWT_SECRET          | dev-secret-key-change-in-production-at-least-256-bits | JWT 签名密钥        |
| JWT_ISSUER          | shuqing-bigdata                                       | JWT issuer          |