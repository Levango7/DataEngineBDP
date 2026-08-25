# MAOP 数据引擎 BDP — 架构文档

## 1. 系统概览

MAOP 数据引擎 BDP（Big Data Platform）是一个基于 Spring Boot 3.2.5 的微服务架构大数据平台引擎，由 14 个模块组成（11 个 Spring Boot 微服务 + 3 个库模块）。

## 2. 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| 语言 | Java | 17 |
| 框架 | Spring Boot | 3.2.5 |
| 构建 | Gradle | 8.5 |
| 数据库 | PostgreSQL | 16 |
| 缓存 | Redis | 7 |
| SQL 引擎 | Apache Calcite | - |
| 认证 | JJWT | - |
| 容器化 | Docker + Docker Compose | - |
| CI/CD | GitHub Actions | - |

## 3. 模块架构

```
                    ┌─────────────────────────────────────────┐
                    │           API Gateway / Load Balancer     │
                    └─────────────────┬───────────────────────┘
                                      │
        ┌─────────────┬──────────────┼──────────────┬─────────────┐
        │             │              │              │             │
  ┌─────▼─────┐ ┌─────▼─────┐ ┌─────▼─────┐ ┌─────▼─────┐ ┌─────▼─────┐
  │ sqlgateway │ │ federated │ │ governance│ │  finops   │ │ tagengine │
  │  (8091)    │ │  (8083)   │ │  (8087)   │ │  (8084)   │ │  (8093)   │
  └─────┬─────┘ └─────┬─────┘ └─────┬─────┘ └─────┬─────┘ └─────┬─────┘
        │             │              │              │             │
  ┌─────▼─────┐ ┌─────▼─────┐ ┌─────▼─────┐ ┌─────▼─────┐
  │  encaps   │ │bigdata-   │ │ruleengine │ │  infra    │
  │  (8082)   │ │encaps     │ │  (8090)   │ │  (8088)   │
  └───────────┘ │  (8081)   │ └─────┬─────┘ └───────────┘
                └───────────┘       │
                              ┌─────▼─────┐
                              │   rule    │
                              │ (库模块)  │
                              └───────────┘

  ┌─────────────────────────────────────────────────────────┐
  │                    共享库模块                            │
  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  │
  │  │ common-health│  │  flinkcdc    │  │    rule      │  │
  │  │ (健康检查)   │  │ (CDC同步)    │  │ (规则定义)   │  │
  │  └──────────────┘  └──────────────┘  └──────────────┘  │
  └─────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────┐
  │                    基础设施层                            │
  │  ┌──────────────┐  ┌──────────────┐                     │
  │  │  PostgreSQL  │  │    Redis     │                     │
  │  │   (5432)     │  │   (6379)     │                     │
  │  └──────────────┘  └──────────────┘                     │
  └─────────────────────────────────────────────────────────┘
```

## 4. 模块职责

| 模块 | 职责 | 类型 | 端口 |
|------|------|------|------|
| bigdata-encaps | 大数据封装层，提供数据加密/解密、配额管理、工作空间 | Spring Boot | 8081 |
| encaps | 封装层，含国密(SM2/SM4)加密 | Spring Boot | 8082 |
| federated | 联邦查询，基于 Calcite 实现跨源 SQL 查询 | Spring Boot | 8083 |
| finops | 成本运营(FinOps)，成本采集/建模/仪表盘/优化建议 | Spring Boot | 8084 |
| function | 函数服务，提供 UDF/UDAF 注册和执行 | Spring Boot | 8086 |
| governance | 数据治理，血缘分析/数据质量/安全策略 | Spring Boot | 8087 |
| infra | 基础设施编排，集群管理/资源调度/供应商注册 | Spring Boot | 8088 |
| ruleengine | 规则引擎，规则执行/告警/动作触发 | Spring Boot | 8090 |
| sqlgateway | SQL 网关，SQL 解析/优化/改写/路由/虚拟表 | Spring Boot | 8091 |
| streambatch | 流批一体，Flink+Spark+Iceberg 集成 | Spring Boot | 8092 |
| tagengine | 标签引擎，标签管理/标签存储/标签计算 | Spring Boot | 8093 |
| common-health | 共享健康检查库(AbstractHealthController + Indicator) | 库模块 | - |
| flinkcdc | Flink CDC 数据同步(Flink+Kafka+Debezium) | 库模块 | - |
| rule | 规则定义库，供 ruleengine 引用 | 库模块 | - |

## 5. 依赖关系

```
sqlgateway ← governance (使用 sqlgateway parser)
common-health ← 所有微服务 (共享健康检查)
rule ← ruleengine (规则定义)
```

## 6. 数据流

1. **查询流**: Client → sqlgateway → (Calcite 解析/优化) → federated/encaps → 数据源
2. **治理流**: sqlgateway → governance (血缘采集) → Nebula Graph (图存储)
3. **成本流**: 各微服务 → finops (成本采集) → PostgreSQL (存储) → Dashboard (展示)
4. **CDC 流**: 数据源 → flinkcdc (Debezium) → Kafka → streambatch (Flink/Spark)

## 7. 安全架构

- **认证**: JWT (JJWT) — 各微服务独立验证，公共 JwtAuthFilter 在 common-health
- **授权**: SecurityConfig + TenantContext (多租户隔离)
- **加密**: encaps 模块提供国密 SM2/SM4 加密
- **凭据**: 环境变量注入，不硬编码（docker-compose.yml 使用 ${VAR:-default}）

## 8. 部署架构

### Docker Compose（开发/测试）
- 13 个服务 + PostgreSQL + Redis
- 命名卷持久化
- healthcheck 健康检查
- depends_on 依赖编排

### Kubernetes（生产）
- 每个微服务 2 副本 Deployment + ClusterIP Service
- imagePullSecrets 从 GHCR 拉取
- 凭据从 K8s Secret 引用
- livenessProbe + readinessProbe (Actuator)
- 资源限制 (requests + limits)

## 9. CI/CD 流水线

```
Push/PR → Build (gradle build) → Test (gradle test)
                                      ↓
                                   Image (Docker build+push to GHCR)
                                      ↓
                                   Deploy (K8s apply + rollout)
```

## 10. 已知限制

- **离线构建**: 部分依赖(WebFlux/OAuth2/Nebula/Flink)被注释，对应源文件被排除
- **包名不匹配**: 源码包名 `com.shuqing.bigdata.*` vs 目录 `com/levango7/dataenginebdp/`
- **无 Gradle Wrapper**: 需要全局安装 Gradle 8.5
- **无前端 UI**: 纯后端 API 服务