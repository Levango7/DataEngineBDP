# 数据引擎大数据平台 · DataEngineBDP

> 多平台、多租户、湖仓集一体的大数据平台。一套主代码，四环境交付（信创 / 本地数据中心 / 公有云 / 私有云），客户无感知 K8s。
>
> 拼音：数擎 = shù qíng → **Shuqing**（SKE = DataEngine Kubernetes Engine），非 Shuqian。

- 仓库地址：https://github.com/Levango7/DataEngineBDP
- 当前版本：**v0.1.0（开发中框架，尚未发布正式版本）**
- 工程成熟度：代码骨架完成度约 50%，服务级实现约 30%（详见 [项目体检报告](docs/项目体检报告.md)）
- 开源协议：Apache License 2.0

> ⚠️ **状态说明**：本项目为 AI 辅助开发的**高保真原型 + 设计资产 + 开发中框架**。
> 架构骨架与核心组件（封装层 / SQL 网关 / 元数据采集 / 对象存储 / LLM 网关）具备真实实现，
> 但默认配置包含 Mock 降级路径、部分外部依赖（K8s/Trino/Doris/Milvus 等）尚未端到端联调，
> 当前不适合生产使用。

## 项目简介

数据引擎大数据平台（DataEngineBDP）是一个面向企业级数据治理与分析场景的多平台多租户大数据平台。平台以 SKE 交付底座（基于 kubeadm/kind 封装的 K8s）为底座，通过封装层将客户概念翻译为 K8s 资源，向上提供湖仓集一体的数据引擎层、数据治理层、数据开发与分析层，最终以多租户 SaaS 产品层对外交付。平台支持信创、本地数据中心、公有云、私有云四种环境零改动交付，并通过 Namespace + Quota + NetworkPolicy 实现租户隔离。

## 核心特性

- **多平台交付**：一套主代码，四环境（信创 / 本地数据中心 / 公有云 / 私有云）通过 Profile 差异化配置实现零改动交付。
- **多租户隔离**：基于 K8s Namespace + ResourceQuota + NetworkPolicy 的三重隔离机制，配合 JWT 鉴权与租户上下文，实现租户间资源、网络、数据完全隔离。
- **湖仓集一体**：统一存储（Iceberg）+ 批计算（Spark）+ 流计算（Flink）+ 交互查询（Trino）+ OLAP（Doris）协同落地"湖 → 仓 → 集"三级数据流转。
- **智能数据层**：向量库（Milvus）+ 知识图谱服务 + LLMOps + 大模型网关，构成旗舰版差异化能力。
- **SaaS 产品层**：行业应用模板 + 业务线门户 + 开放 API 服务目录 + 数据资产流通，形成平台商业化闭环。
- **SKE 交付底座**：基于 kubeadm 封装的 K8s 交付底座，dev 模式基于 kind 节点镜像（烘焙 kubelet/Cilium 调优配置），prod 目标为自有 VM 镜像 / 裸金属 kubeadm 全量调优。当前为脚本封装 + 调优配置层（无自研 K8s 核心代码），详见 [ske/README.md](ske/README.md)。
- **统一 SQL 网关**：一个入口查全部引擎，基于 Apache Calcite 优化器 + 手写 SQL 解析 + 跨源归并引擎实现跨源联邦查询。
- **治理闭环**：元数据采集 → 质量校验 → 血缘解析 → 资产入目录，形成完整数据治理链路。

## 技术栈

| 类别 | 技术选型 |
| --- | --- |
| 后端语言 | Java 17 / Go 1.26 / Python 3.11 |
| 后端框架 | Spring Boot 3.2 / Gin / FastAPI / Pydantic |
| 前端 | Vue 3 / TypeScript strict / Vite 6 / Pinia / Element Plus |
| 大数据引擎 | Spark 3.5 / Flink 1.18 / Trino 428 / Doris 2.0 / Kafka 3.6 / IoTDB 2.0 |
| 湖仓存储 | Iceberg / MinIO / Ceph / JuiceFS |
| 治理与智能 | NebulaGraph 3.6 / Milvus / Redis（Elasticsearch 规划中） |
| 认证与网关 | Keycloak 24.0 / Apache APISIX |
| 调度与集成 | DolphinScheduler / SeaTunnel / Airflow |
| 开发与可视化 | Eclipse Theia 二开 / Apache Superset / ECharts |
| 容器与编排 | Kubernetes / Helm / Docker / SKE |
| 构建工具 | Maven 3.9 / Go modules / pip / npm |

## 项目结构

```
DataEngineBDP/
├── .github/workflows/          # CI/CD 流水线（ci.yml + release.yml）
├── design/                     # 设计文档
│   ├── 详细设计/               # 43 份模块详细设计文档
│   ├── deploy/                 # 部署设计态
│   │   ├── charts/             # 81 个 Helm Chart（80 骨架 + 1 完整）
│   │   ├── values/             # 各引擎 values 参数文件
│   │   ├── services/           # 运营后台 FastAPI 服务
│   │   ├── profiles/           # 四环境 Profile 配置
│   │   └── ci/                 # 镜像构建流水线
│   ├── 多平台多租户大数据平台_产品原型设计_v0.4.md
│   └── 数据引擎大数据平台_控制台原型_v0.3.html
├── platform/                   # 自研组件（31 个）
│   ├── encaps-layer/           # 封装层（Java）
│   ├── sql-gateway/            # 统一 SQL 网关（Java）
│   ├── rule-engine/            # 规则引擎（Java）
│   ├── tag-engine/             # 标签引擎（Java）
│   ├── governance/             # 治理中台（Java）
│   │   ├── metadata-collector/ # 元数据采集器
│   │   └── lineage-analyzer/   # 血缘解析器
│   ├── infra-provider-xinchang/  # 信创供应（Java）
│   ├── infra-provider-cloud/     # 公有云供应（Java）
│   ├── infra-provider-private/   # 私有云供应（Java）
│   ├── infra-provider-baremetal/ # 裸金属供应（Go）
│   ├── infra-orchestrator/       # 供给编排（Java）
│   ├── catalog/                  # 资产目录（Go）
│   ├── dqctl/                    # 数据质量 CLI（Go）
│   ├── vector-engine/            # 向量引擎（Go）
│   ├── llm-gateway/              # 大模型网关（Go）
│   ├── llmops/                   # LLMOps（Python）
│   ├── knowledge-engine/         # 知识图谱服务（Python）
│   ├── ml-platform/              # 机器学习平台（Python）
│   ├── industry-templates/       # 行业模板（Python）
│   ├── business-portal/          # 业务线门户（Python）
│   ├── open-api-catalog/         # 开放 API 目录（Python）
│   ├── asset-exchange/           # 资产流通（Python）
│   ├── bootstrap.sh              # 平台引导脚本
│   └── minio-incluster.yaml      # 集群内 MinIO 配置
├── frontend/                   # Vue3 + TypeScript 前端
│   └── src/
│       ├── views/              # 视图页面
│       ├── api/                # API 客户端模块
│       ├── components/         # 通用组件库
│       ├── stores/             # Pinia 状态管理
│       ├── router/             # 路由配置
│       └── composables/        # 组合式函数
├── ske/                        # SKE 交付底座（kubeadm/kind 封装）
│   ├── ske.sh                  # SKE 主控脚本
│   ├── manifests/              # K8s 调优清单
│   ├── profiles/               # 四环境 Profile
│   ├── tuning/                 # 内核与系统调优
│   └── wsl2/                   # WSL2 部署支持
├── tests/integration/          # 集成测试（43 个）
├── scripts/poc/                # 端到端 PoC 验证脚本
├── docs/                       # 项目文档
├── CONVENTIONS.md              # 统一命名与约定
├── CHANGELOG.md                # 变更日志
├── CONTRIBUTING.md             # 贡献指南
├── ROADMAP.md                  # 路线图
└── LICENSE                     # Apache 2.0 协议
```

## 快速开始

### 前置条件

| 工具 | 最低版本 | 用途 |
| --- | --- | --- |
| JDK | 17 | Java 组件构建 |
| Maven | 3.9 | Java 组件构建 |
| Go | 1.26 | Go 组件构建 |
| Python | 3.11 | Python 组件构建 |
| Node.js | 20 | 前端构建 |
| Docker | 24.0 | 容器镜像构建 |
| kubectl | 1.28 | 集群操作 |
| Helm | 3.14 | Chart 部署 |

### 克隆与构建

```bash
# 克隆仓库
git clone https://github.com/Levango7/DataEngineBDP.git
cd DataEngineBDP

# 构建全部 Java 组件
mvn -f platform/encaps-layer/pom.xml clean package
mvn -f platform/sql-gateway/pom.xml clean package
mvn -f platform/rule-engine/pom.xml clean package

# 构建全部 Go 组件
go -C platform/catalog build ./...
go -C platform/dqctl build ./...
go -C platform/vector-engine build ./...

# 构建全部 Python 组件
pip -C platform/llmops install -e .
pip -C platform/knowledge-engine install -e .

# 构建前端
cd frontend && npm install && npm run build && cd ..
```

### 部署（WSL2 本地环境）

```bash
# 拉起 SKE 集群
sudo bash ske/wsl2/setup-host.sh
sudo bash ske/ske.sh tune-host
sudo bash ske/ske.sh up --target wsl2 --profile local

# 引导平台运行时
bash platform/bootstrap.sh --profile local

# 部署 Helm Chart
helm install spark design/deploy/charts/spark -f design/deploy/values/spark-values.yaml
helm install trino design/deploy/charts/trino -f design/deploy/values/trino-values.yaml

# 运行端到端 PoC
bash scripts/poc/run-poc.sh
```

详细部署步骤参见 [部署指南](docs/deployment-guide.md)。

## 组件清单

平台共包含 32 个自研组件目录，覆盖封装层、引擎层、治理层、智能数据层与产品层。
（注：其中约 1/3 具备真实实现，其余为骨架/半实现，详见 [项目体检报告](docs/项目体检报告.md)）

### Java 组件（12 个）

| 组件 | 目录 | 描述 | 测试数 |
| --- | --- | --- | --- |
| encaps-layer | platform/encaps-layer | 封装层，将客户概念翻译为 K8s 资源，租户 / 工作空间 / 项目 / 任务 CRUD | 120+ |
| sql-gateway | platform/sql-gateway | 统一 SQL 网关，基于手写 SQL 解析 + 跨源归并引擎实现跨源查询 | 150+ |
| rule-engine | platform/rule-engine | 规则引擎，数据质量 / 告警 / 脱敏规则执行 | 130+ |
| tag-engine | platform/tag-engine | 标签引擎，标签管理与人群圈选 | 90+ |
| metadata-collector | platform/governance/metadata-collector | 元数据采集器，引擎 Hook 与定时抽取 | 80+ |
| lineage-analyzer | platform/governance/lineage-analyzer | 血缘解析器，字段级血缘自动采集 | 80+ |
| infra-provider-xinchang | platform/infra-provider-xinchang | 信创环境供应 Driver | 70+ |
| infra-provider-cloud | platform/infra-provider-cloud | 公有云环境供应 Driver | 70+ |
| infra-provider-private | platform/infra-provider-private | 私有云环境供应 Driver | 70+ |
| infra-orchestrator | platform/infra-orchestrator | 跨环境供给编排器 | 90+ |
| finops | platform/finops | FinOps 成本运营服务，成本模型与资源用量采集 | 70+ |
| flink-cdc | platform/flink-cdc | Flink CDC 实时数据集成组件 | 70+ |
| stream-batch-scheduler | platform/stream-batch-scheduler | 流批统一调度组件 | 70+ |

### Go 组件（5 个 + 1 CLI）

| 组件 | 目录 | 描述 | 测试数 |
| --- | --- | --- | --- |
| catalog | platform/catalog | 资产目录服务，多维检索与资产登记 | 100+ |
| vector-engine | platform/vector-engine | 向量引擎服务，Milvus 集合管理与检索 | 80+ |
| llm-gateway | platform/llm-gateway | 大模型网关，多模型路由与推理 | 80+ |
| infra-provider-baremetal | platform/infra-provider-baremetal | 裸金属环境供应 Driver | 70+ |
| karmada | platform/karmada | 多集群联邦编排组件，基于 Karmada 二次封装 | 60+ |
| dqctl (CLI) | platform/dqctl | 数据质量命令行工具 | 60+ |

### Python 组件（12 个）

| 组件 | 目录 | 描述 | 测试数 |
| --- | --- | --- | --- |
| llmops | platform/llmops | LLMOps，微调 / 部署 / 评测闭环 | 90+ |
| knowledge-engine | platform/knowledge-engine | 知识图谱服务，知识建模与图谱检索 | 90+ |
| ml-platform | platform/ml-platform | 机器学习平台，MLflow 训练与 serving | 100+ |
| industry-templates | platform/industry-templates | 行业应用模板，DDL + DAG + Dashboard | 70+ |
| business-portal | platform/business-portal | 对内业务线门户 | 70+ |
| open-api-catalog | platform/open-api-catalog | 开放 API 服务目录 | 70+ |
| asset-exchange | platform/asset-exchange | 数据资产流通 | 70+ |
| chunker | platform/chunker | 文档分块服务，向量化预处理 | 60+ |
| model-finetuning | platform/model-finetuning | 模型微调服务，支持 LoRA / 全参微调 | 60+ |
| nl2sql | platform/nl2sql | 自然语言转 SQL 服务，Text2SQL 引擎 | 60+ |
| registry | platform/registry | 元数据注册中心服务 | 60+ |
| operations | design/deploy/services/operations | 运营后台 FastAPI 服务 | 0 |

### 配置与部署组件（2 个）

| 组件 | 目录 | 描述 |
| --- | --- | --- |
| knative | platform/knative | Knative Serverless 部署清单与事件源配置 |
| observability | platform/observability | 可观测性配置（Grafana / Alertmanager / Prometheus） |

### 前端

| 模块 | 目录 | 描述 |
| --- | --- | --- |
| frontend | frontend/ | Vue3 + TypeScript strict 前端，14 个核心视图页面，Element Plus 组件库，Pinia 状态管理 |

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [架构概览](docs/architecture.md) | 五层架构 + X 横切层，组件交互关系，多租户隔离机制 |
| [部署指南](docs/deployment-guide.md) | SKE 集群拉起，Helm Chart 部署，四环境 Profile 配置 |
| [开发指南](docs/development-guide.md) | 环境要求，构建命令，测试命令，代码规范，调试技巧 |
| [用户手册](docs/user-guide/user-manual.md) | 平台概述、快速入门、控制台操作、数据开发、治理、分析 |
| [API 参考文档](docs/user-guide/api-reference.md) | 封装层、SQL 网关、Catalog、规则引擎、数据虚拟化、行业模板 API 完整定义 |
| [运维手册](docs/user-guide/ops-manual.md) | 部署架构、监控告警、日志、容量、备份恢复、安全运维 |
| [升级指南](docs/user-guide/upgrade-guide.md) | V1.0 → V2.0 升级流程、回滚、验证 |
| [行业模板使用指南](docs/user-guide/industry-template-guide.md) | 金融、能源、政务三个行业模板使用说明 |
| [文档索引](docs/README.md) | 设计文档与项目文档完整索引 |
| [变更日志](CHANGELOG.md) | 版本变更记录 |
| [贡献指南](CONTRIBUTING.md) | 开发规范，提交规范，PR 流程 |
| [路线图](ROADMAP.md) | v2.0 演进规划 |
| [命名约定](CONVENTIONS.md) | 统一命名与版本号规范 |
| [SKE 交付底座](ske/README.md) | kubeadm/kind 封装的 K8s 底座说明 |
| [SKE WSL2 快速上手](ske/WSL2-QUICKSTART.md) | WSL2 真实 kubeadm 部署手册 |
| [部署骨架](design/deploy/README.md) | Helm Chart 部署设计态、四环境 Profile、多 Arch 镜像 |

## 仓库统计

| 指标 | 数值 |
| --- | --- |
| 自研组件目录 | 32 个 |
| Helm Chart | 81 个（其中 80 个为骨架级，1 个为完整实现） |
| 详细设计文档 | 43 份 |
| 单元测试 | 约 5000+（Java 3400+ / Go 530+ / Python 1500+） |
| 集成测试 | 43 个文件（800+ 方法） |
| 前端视图页面 | 50 个 .vue（其中约 20% 为占位/假交互） |
| 支持环境 | 4 种（信创 / 本地数据中心 / 公有云 / 私有云，均为配置模板） |
| 工程成熟度 | 代码骨架约 50% / 服务级实现约 30% |

## 开发模式说明

本项目采用 AI 辅助开发模式，由华为云码道(CodeArts)代码智能体协助完成代码编写、测试生成与文档撰写。所有代码均经过人工审查与验证，确保功能正确性与安全性。

## 贡献

欢迎参与贡献。请先阅读 [贡献指南](CONTRIBUTING.md) 了解开发环境搭建、代码规范与提交规范。

## 开源协议

本项目基于 [Apache License 2.0](LICENSE) 开源。
