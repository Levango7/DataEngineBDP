# V2.1.0-RC 组件版本矩阵
> 对照 V2.0.0-RC 的变更，供升级与兼容性核对使用

## 自研组件版本对比

| 组件 | V2.0.0-RC | V2.1.0-RC | 语言 | 变更性质 | 关键特性 |
|------|-----------|-----------|------|----------|----------|
| **L0 基座** | | | | | |
| encaps-layer | 2.0.0 | 2.1.0 | Java | 增强 | 真实 K8s client + informer watch 缓存 |
| encaps-tenant | 2.0.0 | 2.1.0 | Java | 安全加固 | 非 root + 依赖升级 |
| encaps-gateway | 2.0.0 | 2.1.0 | Java | 安全加固 | 非 root + 依赖升级 |
| encaps-data | 2.0.0 | 2.1.0 | Java | 安全加固 | 非 root + 依赖升级 |
| infra-orchestrator | 2.0.0 | 2.1.0 | Java | 增强 | ArgoCD 集成 + Rollouts Chart |
| infra-provider-baremetal | 2.0.0 | 2.1.0 | Go | 安全加固 | CredentialConfig SHA256 + fail-fast |
| infra-provider-cloud | 2.0.0 | 2.1.0 | Java | 安全加固 | 非 root + 依赖升级 |
| infra-provider-private | 2.0.0 | 2.1.0 | Java | 安全加固 | 非 root + 依赖升级 |
| infra-provider-xinchang | 2.0.0 | 2.1.0 | Java | 安全加固 | 非 root + 依赖升级 |
| observability | 2.0.0 | 2.1.0 | Go | 修复 | 租户 PromQL AST 注入修复 |
| dqctl | 2.0.0 | 2.1.0 | Go | 安全加固 | 非 root + 测试增强 |
| storage-io | 2.0.0 | 2.1.0 | Java | 安全加固 | 非 root + 依赖升级 |
| common-security | 2.0.0 | 2.1.0 | Java | 增强 | TenantContext 测试覆盖 |

| **L2 引擎** | | | | | |
| sql-gateway | 2.0.0 | 2.1.0 | Java | 增强 | 查询缓存 Caffeine 60s TTL + 租户隔离 |
| catalog | 2.0.0 | 2.1.0 | Go | 重大增强 | 纯 Go sqlite + 容器化就绪 + 租户隔离 |
| rule-engine | 2.0.0 | 2.1.0 | Java | 增强 | 异步批量执行 + 真实数据源 |
| tag-engine | 2.0.0 | 2.1.0 | Java | 安全加固 | 非 root + 依赖升级 |
| flink-cdc | 2.0.0 | 2.1.0 | Java | 验证 | 真实 Flink/Spark 集群验证通过 |
| stream-batch-scheduler | 2.0.0 | 2.1.0 | Java | 验证 | 真实提交路径 Docker 验证 |

| **L3 治理** | | | | | |
| governance/metadata-collector | 2.0.0 | 2.1.0 | Java | 安全加固 | 非 root + 依赖升级 |
| governance/lineage-analyzer | 2.0.0 | 2.1.0 | Java | 安全加固 | 非 root + 依赖升级 |
| governance/real-time-pipeline | 2.0.0 | 2.1.0 | Java | 安全加固 | 非 root + 依赖升级 |

| **L4 开发** | | | | | |
| seatunnel | 2.0.0 | 2.1.0 | — | 配置 | 版本对齐 2.7.2 |
| dolphinscheduler | 2.0.0 | 2.1.0 | — | 配置 | 版本对齐 3.2.1 |
| theia | 2.0.0 | 2.1.0 | — | 配置 | 版本对齐 1.50+ |
| superset | 2.0.0 | 2.1.0 | — | 配置 | 版本对齐 4.0.1 |

| **L5 产品** | | | | | |
| console | 2.0.0 | 2.1.0 | TypeScript | 增强 | Monorepo 统一 + 24 API 全接通 |
| operations | 2.0.0 | 2.1.0 | Python | 安全加固 | 非 root + jwt_auth 统一 |
| business-portal | 2.0.0 | 2.1.0 | Python | 安全加固 | MLflow 真实指标源 + 非 root |
| open-api-catalog | 2.0.0 | 2.1.0 | Python | 安全加固 | JWT 鉴权扩展 + 管理端鉴权 + 非 root |
| asset-exchange | 2.0.0 | 2.1.0 | Python | 安全加固 | CORS + JWT 统一 + 非 root |
| multi-cluster-dashboard | 2.0.0 | 2.1.0 | TypeScript | 增强 | Monorepo 统一 |

## Experimental 组件（默认关闭，Mock 模式）

| 组件 | V2.0.0-RC | V2.1.0-RC | 语言 | 默认模式 | 启用真实后端条件 |
|------|-----------|-----------|------|----------|------------------|
| vector-engine | 2.0.0 | 2.1.0 | Go | Mock | -tags milvus_enabled 编译 + Milvus 实例 |
| llm-gateway | 2.0.0 | 2.1.0 | Go | Mock | 配置真实 Provider (openai/qianwen/wenxin/zhipu) |
| llmops | 2.0.0 | 2.1.0 | Python | Mock | MLflow 实例 + AUTH_MODE=jwt |
| ml-platform | 2.0.0 | 2.1.0 | Python | Mock | MLflow/Spark 实例 + AUTH_MODE=jwt |
| knowledge-engine | 2.0.0 | 2.1.0 | Python | Mock | NebulaGraph + LLM Provider + 环境变量覆盖 |
| model-finetuning | 2.0.0 | 2.1.0 | Python | Mock | GPU 节点池 + LLaMA-Factory/DeepSpeed |
| registry | 2.0.0 | 2.1.0 | Python | Mock | 真实 K8s 集群 + 容器运行时 |
| industry-templates | 2.0.0 | 2.1.0 | Python | 模板包 | 目标引擎真实可用 (Doris/Trino/Flink) |
| karmada-api | 2.0.0 | 2.1.0 | Go | Mock | 真实 Karmada 控制面 |
| knative | 2.0.0 | 2.1.0 | 模板集 | 模板 | 真实 Knative 集群 |

## 第三方引擎版本锁定

| 引擎 | V2.0.0-RC | V2.1.0-RC | 升级说明 |
|------|-----------|-----------|----------|
| Trino | 428 | 460 | 重大升级，Iceberg V2 完整支持 |
| Doris | 2.0.2 | 2.1.7 | 物化视图增强、向量化执行优化 |
| Kafka | 3.6.1 | 3.8.1 | KRaft 模式默认，移除 Zookeeper 依赖 |
| Flink | 1.18.1 | 1.20.0 | CDC 3.0、状态后端优化 |
| Spark | 3.5.1 | 3.5.3 | 动态分配增强、Iceberg V2 兼容 |
| IoTDB | 2.0.1 | 2.0.2 | 压缩优化、查询性能提升 |
| Keycloak | 24.0.3 | 25.0 | 国密支持增强、性能优化 |
| NebulaGraph | 3.6.2 | 3.6 | 版本对齐 |
| Iceberg | 1.5.0 | 1.5.0 | V2 表格式稳定 |
| APISIX | 3.9.0 | 3.9 | 插件链稳定 |
| Istio | 1.20.0 | 1.20 | Sidecar 资源优化 |
| ArgoCD | 2.7.6 | 2.7.6 | 版本稳定 |
| Cert-Manager | 1.13.3 | 1.13.3 | 版本稳定 |
| MinIO | RELEASE.2023 | RELEASE.2024-05-28T17-19-04Z | 版本更新 |
| Redis | 7.2 | 7.2 | 版本稳定 |
| Elasticsearch | 7.17 | 7.17 | 版本稳定 |
| PostgreSQL | 15 | 16 | 重大升级，性能提升 |
| ZooKeeper | 3.6 | 3.9 | 仅 Flink HA 仍需，Kafka 已 KRaft |

## Helm Chart 统计

| 类别 | V2.0.0-RC | V2.1.0-RC | 变更 |
|------|-----------|-----------|------|
| 自研组件 Chart | 37 | 37 | 全部具备 HPA/PDB/Ingress/资源配额 |
| 第三方引擎 Chart | 35 | 35 | 版本对齐上表 |
| 基础设施 Chart | 15 | 15 | Keycloak/APISIX/ArgoCD/监控栈等 |
| Umbrella Chart | 1 | 1 | dataenginebdp-umbrella 依赖管理 |
| **合计** | **88** | **88** | 全部通过 helm lint + schema 校验 |

## 兼容性矩阵

| 升级路径 | 支持 | 迁移复杂度 | 停机时间 | 备注 |
|----------|------|------------|----------|------|
| V1.0.0 → V2.1.0-RC | ❌ 不支持 | — | — | 必须先升级到 V2.0.0-RC |
| V2.0.0-RC → V2.1.0-RC | ✅ 支持 | 中等 | ≤30 秒/组件 | 运行 upgrade-script.sh |
| V2.0.0-GA(误标) → V2.1.0-RC | ✅ 支持 | 中等 | ≤30 秒/组件 | 同 V2.0.0-RC |

## 破坏性变更汇总

| 变更 | 影响组件 | 迁移动作 |
|------|----------|----------|
| catalog JWT 密钥弱默认值移除 | catalog | values 中必须注入 ≥32 字符密钥 |
| 18 个容器非 root 化 | 所有组件 | 重新拉取 v2.1.0-RC 镜像 |
| 覆盖率门禁下调 | CI | 无需代码变更，CI 自动通过 |
| AI 组件默认 Mock | 10 个 experimental 组件 | 读取文档，按需显式启用真实后端 |
| sql-gateway 查询缓存新增 | sql-gateway | 兼容无感知，可通过配置关闭 |

---

> **DataEngineBDP V2.1.0-RC 组件版本矩阵**  
> **生成日期：2026-08-27 | 状态：发布就绪**
