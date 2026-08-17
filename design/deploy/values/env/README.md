# 多环境 Values 配置

## 目录结构

```
design/deploy/values/env/
├── dev/          # 开发环境：资源最小 · DEBUG · H2/SQLite · 无PVC/HPA
├── staging/      # 预发环境：资源中等 · INFO · PostgreSQL · 无PVC/HPA
└── prod/         # 生产环境：资源充足 · WARN · PostgreSQL+PVC+HPA+PDB+TLS
```

## 覆盖组件（10 个）

| 组件 | 类型 | dev 副本 | staging 副本 | prod 副本 | dev 存储 | prod 存储 |
|------|------|---------|-------------|----------|---------|----------|
| encaps-layer | 业务服务 | 1 | 2 | 3 | 无 | 50Gi PVC |
| sql-gateway | 业务服务 | 1 | 2 | 3 | 无 | 50Gi PVC |
| catalog | 业务服务 | 1 | 2 | 3 | 无 | 50Gi PVC |
| rule-engine | 业务服务 | 1 | 2 | 2 | 无 | 50Gi PVC |
| spark | 数据组件 | 1 | 2 | 3 | 无 | 100Gi PVC |
| flink | 数据组件 | 1 | 2 | 3 | 无 | 100Gi PVC |
| trino | 数据组件 | 1 | 2 | 3 | 无 | 50Gi PVC |
| doris | 数据组件 | fe=1,be=1 | fe=2,be=2 | fe=3,be=3 | 无 | 200Gi PVC |
| kafka | 数据组件 | broker=1 | broker=3 | broker=3 | 无 | 200Gi PVC |
| superset | 数据组件 | web=1 | web=2 | web=3 | 无 | 50Gi PVC |

## 环境差异矩阵

| 维度 | dev | staging | prod |
|------|-----|---------|------|
| 日志级别 | DEBUG | INFO | WARN |
| 数据库 | H2 内存 / SQLite | PostgreSQL | PostgreSQL + 连接池调优 |
| 资源 requests | 100m~500m / 256Mi~1Gi | 500m~1 / 1~2Gi | 1~2 / 2~4Gi |
| 资源 limits | 500m~1 / 512Mi~2Gi | 1~2 / 2~4Gi | 2~4 / 4~8Gi |
| PVC | 关闭 | 关闭 | 开启（sq-fast-ssd） |
| HPA | 关闭 | 关闭 | 开启（CPU 75%） |
| PDB | 关闭 | 开启（minAvailable=1） | 开启（minAvailable=2） |
| Ingress TLS | 关闭 | 关闭 | 开启 |
| 镜像 tag | 0.1.0-dev | 0.1.0-staging | 0.1.0 |

## 使用方式

```bash
# 渲染某环境配置
helm template encaps-layer design/deploy/charts/encaps-layer \
  -f design/deploy/values/env/dev/encaps-layer-values.yaml

# 部署到某环境
helm upgrade --install encaps-layer design/deploy/charts/encaps-layer \
  -n dev-platform \
  -f design/deploy/values/env/dev/encaps-layer-values.yaml
```

## 设计原则

1. **dev 极简**：单副本、最小资源、内存数据库，便于本地/CI 快速拉起
2. **staging 仿真**：多副本、真实 PostgreSQL、中等资源，用于集成测试与预发验证
3. **prod 加固**：三副本+PVC+HPA+PDB+TLS，资源充足，日志收敛，连接池调优
4. **不修改 Chart 默认 values**：环境 values 仅做覆盖，Chart 骨架保持稳定