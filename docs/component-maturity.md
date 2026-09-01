# 组件成熟度矩阵

> 数据引擎大数据平台 `platform/` 全部 **37 个自研组件**的真实成熟度盘点，用于校正 README、ROADMAP 与发布物料中的能力表述。
>
> - 成熟度分级：**真实可部署**（含库形态）/ **服务级**（SQLite/H2 或部分功能）/ **骨架**（Mock 默认或模板集）/ 规划中。
> - 除表中单独注明外，全部组件的**四环境部署与升级验证均未在真实环境执行**（见 [V2.0.0 勘误公告](../releases/v2.0.0/ERRATUM.md)）。
> - 技术栈与默认持久层依据各模块构建文件与配置核实（Spring Boot 3.2.6 / Go 1.26 / Python 3.11 + FastAPI 为主）。

## 一、真实可部署（21 个）

| 组件 | 技术栈 | 成熟度 | 默认持久层 | 关键缺口 |
| --- | --- | --- | --- | --- |
| sql-gateway | Java 17 · Spring Boot 3.2.6（WebFlux 引擎代理 + JPA + Caffeine 结果缓存） | 真实可部署 | H2 文件（`./data/sql-gateway-db`），`DB_URL` 切 PostgreSQL | — |
| encaps-layer | Java 17 · Spring Boot 3.2.6（JPA + OpenTelemetry + 国密 BC + springdoc） | 真实可部署 | H2 文件（`./data/encaps-layer-db`），prod profile 切 PostgreSQL | 保留 K8s 模拟开关；Keycloak Realm 导入 / OIDC 默认开启 / APISIX 联调待办（ROADMAP v1.1 记录） |
| encaps-tenant | Java 17 · Spring Boot 3.2.6（JPA + fabric8 kubernetes-client + BouncyCastle 国密） | 真实可部署（少量 Mock） | H2 文件（`./data/encaps-tenant-db`），`DB_URL` 切 PostgreSQL | 少量 Mock 分支待清理 |
| catalog | Go 1.26 · Gin + GORM | 真实可部署 | SQLite（glebarez 纯 Go 驱动），`CATALOG_DB=postgres://...` 切 PostgreSQL | Elasticsearch 倒排检索加速待接入（ROADMAP v1.1 记录） |
| rule-engine | Java 17 · Spring Boot 3.2.6（JPA + JdbcTemplate 真实数据源执行） | 真实可部署 | H2 文件双库（规则库 + 质量库 `./data/rule-engine-*-db`），prod profile 切 PostgreSQL | — |
| governance | Java 17 · Spring Boot 3.2.6 三模块：metadata-collector / lineage-analyzer / real-time-pipeline（手写 SQL 血缘解析 + NebulaGraphClient） | 真实可部署 | metadata-collector：H2 文件 → PostgreSQL；lineage-analyzer：H2 内存 + 内存邻接图；real-time-pipeline：H2 内存 | NebulaGraph 图存储默认关闭（内存图降级），生产需显式启用并实测 |
| tag-engine | Java 17 · Spring Boot 3.2.6（JPA + MySQL 协议驱动对接 Doris 宽表） | 真实可部署 | H2 文件（`./data/tag-engine-db`），prod profile 切 PostgreSQL | Doris 宽表链路需真实 Doris 环境 |
| stream-batch-scheduler | Java 17 · Spring Boot 3.2.6 | 真实可部署 | H2 文件（`~/.shuqing/scheduler-data`），`SCHEDULER_DB_URL` 可切 | Flink/Spark 真实提交已经容器化集群验证，生产规模调度待实际环境验证 |
| infra-orchestrator | Java 17 · Spring Boot 3.2.6（JPA，ArgoCD 集成） | 真实可部署 | H2 文件（`./data/orchestrator-db`），`DB_URL` 切 PostgreSQL | ArgoCD 生产联动待实际环境验证 |
| infra-provider-baremetal | Go 1.26 · Gin + GORM | 真实可部署 | SQLite 文件（`./data/baremetal.db`），`POSTGRES_DSN` 切 PostgreSQL | — |
| infra-provider-cloud | Java 17 · Spring Boot 3.2.6（阿里云 ECS SDK、华为 / 腾讯云 Provider、WebFlux） | 真实可部署 | H2 文件（`./data/infra-cloud-db`），prod profile 切 PostgreSQL | SKE 引导仍需真实 SSH 执行环节（ROADMAP 记录）；云 API 需真实账号联调 |
| infra-provider-private | Java 17 · Spring Boot 3.2.6（OpenStack Keystone V3 + vCenter REST，WebClient） | 真实可部署 | H2 文件（`./data/infra-provider-private-db`），`DB_URL` 切 PostgreSQL | OpenStack / vSphere 真实环境联调待执行 |
| infra-provider-xinchang | Java 17 · Spring Boot 3.2.6（IPMI Redfish 客户端 + K8s 引导服务） | 真实可部署 | H2 文件（`./data/xinchang-provider-db`），`DB_URL` 切 PostgreSQL | Redfish 带外管理需真实硬件联调 |
| observability | Go 1.26 query-api（Gin + Prometheus client/common）+ Grafana / Alertmanager 配置与告警规则 | 真实可部署 | 无自有持久化（代理查询 Prometheus，`PROMETHEUS_URL`） | — |
| dqctl | Go 1.26 CLI（cobra + viper，HTTP 调用 rule-engine API） | 真实可部署 | 无本地存储 | — |
| nl2sql | Python 3.11 · FastAPI + Pydantic（LLM 经 llm-gateway OpenAI 兼容协议） | 真实可部署 | 无持久化（会话内存 LRU + TTL） | 真实生成质量依赖配置真实 LLM Provider（llm-gateway 缺省兜底 Mock） |
| open-api-catalog | Python 3.11 · FastAPI（APISIX 配置联动 + AK/SK 计量） | 真实可部署 | SQLite（`data/openapi_catalog.db`），`OPENAPI_CATALOG_STORE_TYPE` 可切 mock | 管理面已补鉴权；网关策略下发需真实 APISIX 环境 |
| asset-exchange | Python 3.11 · FastAPI（发布 / 订阅 / 结算全流程仓储接口） | 真实可部署（SQLite 级） | SQLite（`data/asset_exchange.db`），`ASSET_EXCHANGE_STORE_TYPE` 可切 mock | 单文件 SQLite 仅适用轻量负载，生产级数据库切换待做 |
| flink-cdc | Java 17 库（Flink CDC connectors + Debezium embedded；管道模板 YAML + 启动脚本） | 真实可部署（库形态） | 不适用（作业提交至 Flink 运行时；Kafka / Iceberg 为外部依赖） | — |
| common-security | Java 17 库（Spring Security 自动装配 starter + JJWT 0.13） | 真实可部署（库） | 不适用 | — |
| storage-io | Java 17 库（AWS S3 SDK） | 真实可部署（库） | 不适用 | — |

## 二、服务级（6 个）

| 组件 | 技术栈 | 成熟度 | 默认持久层 | 关键缺口 |
| --- | --- | --- | --- | --- |
| business-portal | Python 3.11 · FastAPI | 服务级 | 默认 SQLite（`BP_STORE_TYPE=sqlite`，`data/business_portal.db`），MLflow 指标源可选注入 | 指标数据依赖真实 MLflow 实例，缺省回退 Mock 仓储 |
| encaps-data | Java 17 · Spring Boot 3.2.6（依赖 encaps-layer / common-security + kafka-clients） | 服务级（部分功能） | H2 文件（`./data/encaps-data-db`），`DB_URL` 切 PostgreSQL | 数据服务域功能覆盖不全；Kafka 事件链路待真实环境验证 |
| encaps-gateway | Java 17 · Spring Boot 3.2.6（封装层薄壳，复用 encaps-layer / common-security） | 服务级（薄壳） | H2 文件（`./data/encaps-gateway-db`），`DB_URL` 切 PostgreSQL | 能力依赖 encaps-layer，自身路由 / 聚合面较薄 |
| ai-assistant | Go 1.26 · Gin + GORM | 服务级 | 会话 SQLite（GORM） | 对话经 nl2sql / sql-gateway 下游代理；回复润色依赖 llm-gateway（失败回退规则文案） |
| chunker | Python 3.11 库（多模态切片 + embedding 适配器 + RAG 混合检索） | 独立工具库（2026-09-01 决策） | 无持久化 | 定位为可独立引用的分块工具库（text/table/image/audio/asr 五模态 + rag 混合检索，516 测试）；不强行接入主链路——待 RAG 链路真实需求出现时由 knowledge-engine/chunker 按需集成 |
| finops | Java 17 双服务 cost-model / dashboard（Spring Boot 3.2.6 + JPA）+ exporters 与 Prometheus 告警规则 YAML | 服务级（部分功能） | H2 文件（cost-model / dashboard 各自 `./data/*-db`），`DB_URL` 切 PostgreSQL | 成本归集依赖 Prometheus / Kubernetes 指标真实采集 |

## 三、骨架 / Mock 默认（10 个）

| 组件 | 技术栈 | 成熟度 | 默认持久层 | 关键缺口 |
| --- | --- | --- | --- | --- |
| vector-engine | Go 1.26 · Gin（Milvus SDK v2 已引入） | 骨架（内存 Mock 默认） | 内存 Mock store；真实 Milvus 需 `-tags milvus_enabled` 编译启用（未启用自动回退并告警） | Milvus 生产实现需专用构建产物，默认构建不含 |
| llm-gateway | Go 1.26 · Gin（openai / qianwen / wenxin / zhipu / mock 五种 Provider 适配器） | 骨架（Provider 已备，默认 Mock 兜底） | 无持久化（路由 / 计量为内存态） | 未配置任何真实 Provider 时兜底 Mock，开箱响应非真实模型输出 |
| llmops | Python 3.11 · FastAPI | 骨架（Mock 默认） | `LLMOPS_STORE_TYPE=mock` 默认 | 真实 MLflow 需显式配置注入 |
| ml-platform | Python 3.11 · FastAPI（sklearn / Spark / MLflow 多后端设计） | 骨架（本地后端默认） | `ML_BACKEND_TYPE=sklearn` 默认；MLflow 总开关默认 false | 训练 / 实验 / 特征存储的真实规模运行需外部 MLflow / Spark 环境 |
| knowledge-engine | Python 3.11 · FastAPI | 骨架（镜像内 Mock 默认） | 配置默认指向 nebula / llm，但交付 Dockerfile 内置 `KE_STORE_TYPE=mock`、`KE_EXTRACTOR_TYPE=mock` | NebulaGraph 图谱与 LLM 抽取需显式覆盖环境变量并实测 |
| model-finetuning | Python 3.11 · FastAPI（PEFT / LLaMA-Factory / DeepSpeed 适配器 + Volcano 调度配置模板） | 骨架（Mock 默认） | `FINETUNE_MOCK_MODE=true` 默认（任务态内存） | 真实微调需 GPU 节点池与训练框架环境 |
| registry | Python 3.11 · FastAPI | 骨架（部署假闭环） | 模型 / 部署元数据为内存 dict | DeploymentManager 默认 `mock_mode=True`：不实际启动容器即标记 running 并伪造 endpoint |
| industry-templates | Python 3.11 包（金融 / 能源 / 政务等模板元数据 DDL + DAG + Dashboard）+ 8 个行业 Helm Chart 包装 | 骨架（chart+SQL 包） | 不适用（模板资产） | 模板安装落地依赖目标引擎真实可用 |
| karmada-api | Go 1.26 · Gin + GORM | 骨架（策略 CRUD 未接控制面） | SQLite（GORM sqlite driver） | PropagationPolicy CRUD 仅落本地库，未对接 Karmada 控制面实际下发 |
| knative | 函数运行时模板集（Go/Gin、Java/Spring、Python 三语言 runtime + KafkaSource / CronJobSource / PingSource / KService examples） | 骨架（模板集） | 不适用 | 需要 Knative 就绪集群方可部署验证 |

## 附：评级说明

- **评级日期**：2026-08-26
- **评定依据**：本仓安全 / 质量审计结论（`docs/PROJECT-AUDIT-REPORT.md`、`docs/项目体检报告.md`、CHANGELOG 安全加固记录），并对各组件构建文件（pom.xml / go.mod / pyproject.toml）、默认配置（application.yml / settings.py / config.yaml）与核心实现逐项读码核实。
- 本矩阵仅描述当前仓库状态，随组件演进动态更新；勾选语义对照见 [ROADMAP.md](../ROADMAP.md)。
