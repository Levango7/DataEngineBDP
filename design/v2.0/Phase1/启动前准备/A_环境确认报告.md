# Phase 1a 启动前准备 · A 环境（开发环境与工具链）确认报告

> 版本：v1.0
> 文档状态：已完成
> 检查日期：2026-08-06
> 检查人：Phase1a 启动前准备工程师（任务 144）
> 适用范围：数擎大数据平台 V2.0 Phase 1a（批次 1，2026-09-01 启动）8 个任务所需开发环境与工具链
> 上游输入：
> - `design/v2.0/Phase1/Phase1_详细执行计划.md`（§2.1~§2.6 任务技能要求与产出物）
> - `design/v2.0/V2.0_架构设计文档.md`（12 新增 + 17 增强模块技术栈）
> - `ske/README.md`（自研 SKE 发行版 K8s 1.30）
> - 各组件 `go.mod` / `pom.xml` / `requirements.txt` / `Chart.yaml`
> 下游输出：Phase 1a 启动就绪度评估、缺失工具安装/升级清单、风险登记输入

---

## 第1章 检查范围与方法

### 1.1 Phase 1a 批次 1 任务清单

Phase 1a 对应 Phase 1 详细执行计划 §3.2 批次 1，时间窗口 2026-09-01 ~ 2026-09-30，共 8 个任务、126 人天，分 2 波启动。

表：A-01 Phase 1a 批次 1 任务与工具依赖对照表

| 波次 | 任务 ID | 任务名称 | 工时 | 负责组 | 关键工具/技术栈依赖 |
| --- | --- | --- | --- | --- | --- |
| 波 1 | T008 | 多模态切片器（文本/表格/图像/语音） | 25d | AI 组 | Python 3.11+、PyTorch 2.0+、OCR/ASR、Embedding 模型 |
| 波 1 | T012 | Calcite 联邦优化器与下推规则 | 30d | 数据联邦组 | Java 17+、Apache Calcite、SQL 优化、Spring Boot 3.2 |
| 波 1 | T014 | Flink CDC 管道开发 | 15d | 实时数仓组 | Flink 1.18+、Flink CDC、Debezium、Kafka、Java 17+ |
| 波 1 | T000 | 等保三级合规整改准备 | 15d | 安全合规组 | 等保三级规范、密评规范、文档工具 |
| 波 1 | T018 | 金融模板 DDL/DAG/Dashboard 内容 | 15d | 行业模板组 | Java 17+、Helm、DDL/DAG/Dashboard 工具 |
| 波 2 | T001 | Service Mesh 控制面部署与 Sidecar 注入 | 8d | 云原生组 | Istio ≥1.20、Helm 3.14+、K8s 1.30、mTLS |
| 波 2 | T003 | ArgoCD 部署与 Chart 纯管 | 6d | 云原生组 | ArgoCD ≥2.7、GitOps、Helm、K8s 1.30 |
| 波 2 | T022 | CryptoSpiFactory 抽象与双栈实现 | 12d | 安全合规组 | Java 17+、SM2/SM3/SM4 国密、RSA/SHA/AES、SPI 抽象 |

### 1.2 检查方法

1. **本机工具链版本探测**：通过 PowerShell `Get-Command` / `*-version` 命令逐项检查已安装工具的版本号
2. **Python 依赖库探测**：通过 `python -c "import <pkg>; print(<pkg>.__version__)"` 检查关键 Python 库
3. **服务状态探测**：通过 `Get-Service` / `docker info` / `kubectl config get-contexts` 检查运行时服务可用性
4. **项目声明版本核对**：通过 `go.mod` / `pom.xml` / `requirements.txt` / `Chart.yaml` 核对项目自身声明的依赖版本
5. **Phase 1a 需求映射**：将 §1.1 任务工具依赖逐项对照本机实际状态，标注"已就绪 / 缺失 / 版本不符 / 服务未运行"

---

## 第2章 编程语言工具链检查结果

### 2.1 Go 语言工具链

表：A-02 Go 工具链检查结果

| 检查项 | Phase 1a 要求 | 本机实际 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| Go 版本 | ≥ 1.21（vector-engine/maop/catalog/dqctl 等组件） | go1.26.5 windows/amd64 | ✅ 已就绪 | 远超最低要求；满足项目 `go.mod` 声明（catalog 要求 1.25.0、vector-engine 要求 1.23） |
| gofmt | 随 Go 发行 | go1.26.5 自带 | ✅ 已就绪 | 代码格式化工具可用 |
| Go 模块代理 | GOPROXY 可达 | 默认 proxy.golang.org | ⚠️ 待验证 | 国内网络环境下建议设置 GOPROXY=https://goproxy.cn |

### 2.2 Java 语言工具链

表：A-03 Java 工具链检查结果

| 检查项 | Phase 1a 要求 | 本机实际 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| JDK 版本 | ≥ 17（encaps-layer/sql-gateway/rule-engine/Calcite 优化器等） | OpenJDK 17.0.20 (Amazon Corretto-17.0.20.8.1) | ✅ 已就绪 | 满足 Spring Boot 3.2.5 最低要求；JAVA_HOME 已正确指向 Corretto 17 |
| JDK 8 备用 | 部分遗留组件兼容 | jdk-1.8.0_461 | ✅ 已就绪 | 同时存在 JDK 8（Oracle），用于遗留组件兼容 |
| javac | 随 JDK 17 发行 | 17.0.20 | ✅ 已就绪 | 编译器可用 |

### 2.3 Python 语言工具链

表：A-04 Python 工具链检查结果

| 检查项 | Phase 1a 要求 | 本机实际 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| Python 版本 | ≥ 3.11（T008 多模态切片器、T010 NL2SQL、T009 混合检索等 AI 组件） | Python 3.14.3 | ✅ 已就绪 | 远超最低要求；项目 requirements.txt 声明 Python 3.10+ |
| pip | ≥ 21.0 | pip 26.1.2 | ✅ 已就绪 | 包管理器可用 |
| python3.11 备用 | 部分 AI 库兼容性 | python3.11.exe 已安装 | ✅ 已就绪 | 多版本共存（3.11/3.12/3.14） |
| venv | 标准库 | 随 Python 自带 | ✅ 已就绪 | 虚拟环境可用 |

### 2.4 Node.js 语言工具链

表：A-05 Node.js 工具链检查结果

| 检查项 | Phase 1a 要求 | 本机实际 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| Node.js 版本 | ≥ 18（前端 Vue3 工程、T007/T011 AI 助手前端） | v25.9.0 | ✅ 已就绪 | 远超最低要求；项目 frontend/package.json 未声明 engines 约束 |
| npm | ≥ 9.0 | 11.9.0 | ✅ 已就绪 | 包管理器可用 |
| pnpm | 可选（前端 monorepo） | 未安装 | ⚠️ 缺失（非阻塞） | 项目使用 npm，pnpm 非必需；如需 monorepo 可后续安装 |
| yarn | 可选 | 未安装 | ⚠️ 缺失（非阻塞） | 项目未使用 yarn，非必需 |

---

## 第3章 构建工具检查结果

表：A-06 构建工具检查结果

| 检查项 | Phase 1a 要求 | 本机实际 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| Maven | ≥ 3.8（Java 组件构建） | Apache Maven 3.9.12 | ✅ 已就绪 | 满足 Spring Boot 3.2.5 父 POM 要求；Maven home 已配置 |
| Gradle | 可选（项目未使用） | 未安装 | ⚠️ 缺失（非阻塞） | 项目 Java 组件全部使用 Maven（pom.xml），Gradle 非必需 |
| pip | ≥ 21.0 | pip 26.1.2 | ✅ 已就绪 | Python 组件构建可用 |
| poetry | 可选（Python 包管理） | 未安装 | ⚠️ 缺失（非阻塞） | 项目 Python 组件使用 requirements.txt + pip，poetry 非必需；如后续 MAOP/NL2SQL 改用 poetry 可安装 |
| Helm | ≥ 3.14（T001 Istio 部署、T003 ArgoCD Chart 纯管、T019 金融模板 Helm Chart） | 未安装 | ❌ 缺失（阻塞） | **Phase 1a 关键缺失**：T001/T003/T018/T019 均依赖 Helm 部署；需安装 Helm 3.14+ |
| Docker | ≥ 24.0（容器镜像构建、SKE bootstrap） | Docker 29.6.2 (CLI) | ⚠️ CLI 已装但 daemon 未运行 | Docker CLI 已安装；**Docker Desktop 服务处于 Stopped 状态**，需启动 Docker Desktop 后才能执行 `docker build` / `docker run` / SKE bootstrap |
| Docker Compose | ≥ 2.20（集成测试编排） | Docker Compose v5.3.1 | ✅ 已就绪（CLI） | Compose 插件已就绪；受 Docker daemon 未运行制约 |
| git | ≥ 2.30 | git 2.55.0.windows.2 | ✅ 已就绪 | GitOps 工作流基础可用 |

---

## 第4章 K8s 相关工具检查结果

### 4.1 K8s 客户端与集群

表：A-07 K8s 客户端与集群检查结果

| 检查项 | Phase 1a 要求 | 本机实际 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| kubectl 客户端 | ≥ 1.28（K8s 1.30 集群兼容） | v1.36.1 | ✅ 已就绪 | 客户端版本远超集群版本，向下兼容 |
| Kustomize | 随 kubectl 发行 | v5.8.1 | ✅ 已就绪 | kubectl 内置 kustomize 可用 |
| K8s 集群（自研 SKE 发行版） | K8s 1.30（SKE v0.1，kubeadm-config.wsl2.yaml 声明 v1.30.0） | 无可用集群上下文 | ❌ 缺失（阻塞） | **Phase 1a 关键缺失**：`kubectl config get-contexts` 返回空，未连接任何集群；需先启动 Docker Desktop + WSL2，再执行 `ske.sh up --target wsl2` 拉起 SKE 集群 |
| kubeconfig | ~/.kube/config 存在 | 未配置 | ❌ 缺失（阻塞） | 与集群缺失同因；SKE 拉起后自动生成 |

### 4.2 Service Mesh 与 GitOps

表：A-08 Service Mesh 与 GitOps 工具检查结果

| 检查项 | Phase 1a 要求 | 本机实际 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| Istio（istioctl） | ≥ 1.20（T001 Service Mesh 控制面部署） | 未安装 | ❌ 缺失（阻塞） | **Phase 1a 关键缺失**：T001 直接依赖；建议通过 Helm 安装 istio 1.20+ base + discovery，istioctl 可选（Helm 安装方式更声明式） |
| ArgoCD（argocd CLI） | ≥ 2.7（T003 ArgoCD 部署与 Chart 纯管） | 未安装 | ❌ 缺失（阻塞） | **Phase 1a 关键缺失**：T003 直接依赖；建议通过 Helm 安装 argo-cd Chart（版本 ≥ 2.7），argocd CLI 用于本地调试可选 |
| kind | SKE bootstrap 依赖（ske/node-image/build.sh 前置） | 未安装 | ❌ 缺失（阻塞） | **SKE 集群拉起关键缺失**：SKE README 与 build.sh 均依赖 kind 构建自定义节点镜像；需安装 kind v0.23+（支持 K8s 1.30） |

### 4.3 SKE 发行版就绪情况

表：A-09 SKE 发行版本地资源检查结果

| 检查项 | 期望状态 | 实际状态 | 说明 |
| --- | --- | --- | --- |
| `ske/ske.sh` bootstrap 脚本 | 存在且可执行 | ✅ 存在（13515 字节） | SKE 一键拉起脚本就绪 |
| `ske/manifests/kubeadm-config.wsl2.yaml` | K8s v1.30.0 | ✅ 声明 v1.30.0 | 与 Phase 1a 要求一致 |
| `ske/manifests/cilium-values.yaml` | Cilium eBPF 配置 | ✅ 存在 | SKE 网络底座就绪 |
| `ske/node-image/build.sh` | kind 节点镜像构建 | ✅ 存在 | 依赖 kind + docker daemon |
| WSL2 Ubuntu-24.04 | Running | ⚠️ Stopped | 需 `wsl -d Ubuntu-24.04` 启动后才能执行 ske.sh up |
| Docker Desktop 服务 | Running | ⚠️ Stopped | 需启动 Docker Desktop |

---

## 第5章 大数据组件检查结果

### 5.1 本地命令行工具

表：A-10 大数据组件 CLI 检查结果

| 检查项 | Phase 1a 要求 | 本机实际 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| Flink | ≥ 1.18（T014 Flink CDC 管道） | 未安装（CLI） | ⚠️ 缺失（非阻塞） | Phase 1a 通过 Helm Chart 部署 Flink 1.18.1 到 K8s，无需本地 CLI；Java 侧通过 Maven 依赖 flink-streaming-java 引入 |
| Iceberg | V2（T015 Iceberg V2 行级 upsert） | 未安装（本地 CLI） | ⚠️ 缺失（非阻塞） | Iceberg 为表格式，通过 Flink/Spark 集成；项目已有 iceberg-rest Chart (appVersion 0.7.0) |
| Doris | ≥ 2.1（T016 物化视图自动刷新） | 未安装（CLI） | ⚠️ 缺失（非阻塞） | 通过 Helm Chart 部署；**注意：项目 Chart 声明 appVersion 2.0.3，需升级至 2.1+** |
| Calcite | Apache Calcite（T012 联邦优化器） | 未独立安装 | ⚠️ 缺失（非阻塞） | Calcite 为 Java 库，通过 Maven 依赖 org.apache.calcite:calcite-core 引入，无需独立安装 |
| Kafka | ≥ 3.6（T005 MAOP 消息总线、T014 CDC 中转） | 未安装（CLI） | ⚠️ 缺失（非阻塞） | 通过 Helm Chart 部署 Kafka 3.6.1 到 K8s；本地开发可通过 Docker 快速拉起 |

### 5.2 项目 Helm Chart 声明版本核对

表：A-11 大数据组件 Helm Chart appVersion 对照表

| 组件 | Chart 路径 | Chart appVersion | Phase 1a 要求 | 状态 | 说明 |
| --- | --- | --- | --- | --- | --- |
| Flink | `design/deploy/charts/flink/Chart.yaml` | 1.18.1 | ≥ 1.18 | ✅ 满足 | T014 Flink CDC 可用 |
| Doris | `design/deploy/charts/doris/Chart.yaml` | 2.0.3 | ≥ 2.1 | ❌ 版本不符 | **T016 物化视图需要 Doris 2.1+，需升级 Chart appVersion 至 2.1.x** |
| Kafka | `design/deploy/charts/kafka/Chart.yaml` | 3.6.1 | ≥ 3.6 | ✅ 满足 | T005/T014 消息总线可用 |
| Trino | `design/deploy/charts/trino/Chart.yaml` | 428 | ≥ 428 | ✅ 满足 | 联邦查询引擎可用 |
| Spark | `design/deploy/charts/spark/Chart.yaml` | 3.5.1 | ≥ 3.5 | ✅ 满足 | 批计算引擎可用 |
| Iceberg REST | `design/deploy/charts/iceberg-rest/Chart.yaml` | 0.7.0 | Iceberg V2 | ✅ 满足 | REST Catalog 0.7.0 支持 Iceberg V2 表格式 |
| Keycloak | `design/deploy/charts/keycloak/Chart.yaml` | 24.0 | ≥ 24.0 | ✅ 满足 | T022/T023 国密 Realm 集成可用 |

---

## 第6章 AI/ML 组件检查结果

### 6.1 本地 Python 库

表：A-12 AI/ML Python 库检查结果

| 检查项 | Phase 1a 要求 | 本机实际 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| PyTorch | ≥ 2.0（T008 多模态切片器，文本/表格/图像/语音） | 2.12.1+cpu | ✅ 已就绪 | 满足要求；CPU 版本（如需 GPU 训练需安装 CUDA 版本，但推理可用） |
| LangChain | 最新稳定版（T010 NL2SQL 核心引擎） | 未安装 | ❌ 缺失（阻塞） | **Phase 1a 关键缺失**：T010 直接依赖 LangChain 进行 Schema 检索、SQL 生成、多轮对话；建议 `pip install langchain langchain-community langchain-openai` |
| LangGraph | 最新稳定版（T005 MAOP 编排引擎 ReAct 范式） | 未安装 | ❌ 缺失（阻塞） | **Phase 1a 关键缺失**：T005 直接依赖 LangGraph 实现 ReAct 编排；建议 `pip install langgraph` |
| PyMilvus | ≥ 2.4（T008/T009 向量检索） | 未安装 | ❌ 缺失（阻塞） | **Phase 1a 关键缺失**：T008 Embedding 写入、T009 混合检索依赖；建议 `pip install pymilvus>=2.4` |
| PyIceberg | 最新稳定版（T015 Iceberg V2 行级 upsert） | 未安装 | ⚠️ 缺失（建议安装） | T015 主要通过 Flink/Java 集成 Iceberg，PyIceberg 用于 Python 侧表管理；建议 `pip install pyiceberg` |
| PyFlink | ≥ 1.18（T014 Flink CDC 管道 Python 侧） | 未安装 | ⚠️ 缺失（建议安装） | T014 主要通过 Java/Scala 实现 CDC Source，PyFlink 用于 Python UDF；建议 `pip install apache-flink>=1.18` |
| kafka-python | ≥ 2.0（T005 MAOP 消息总线 Python 客户端） | 未安装 | ❌ 缺失（阻塞） | **Phase 1a 关键缺失**：T005 Agent 间 Kafka Topic 异步通信依赖；建议 `pip install kafka-python` |
| nebula3-python | ≥ 3.4（T009 图谱检索） | 未安装 | ❌ 缺失（阻塞） | **Phase 1a 关键缺失**：T009 三路混合检索中的图谱检索依赖；项目 knowledge-engine/requirements.txt 已声明 nebula3-python==3.4.0，需 `pip install -r` |
| BM25 库 | 任一可用（T009 混合检索） | 未检查 | ⚠️ 待确认 | 建议 `pip install rank-bm25` 或使用 LangChain 内置 BM25 retriever |
| Cross-Encoder / bge-reranker | T009 重排序 | 未检查 | ⚠️ 待确认 | 建议 `pip install sentence-transformers` 或通过 HuggingFace 加载 bge-reranker-v2 |

### 6.2 向量与图谱数据库（K8s 部署）

表：A-13 AI 数据库 Helm Chart appVersion 对照表

| 组件 | Chart 路径 | Chart appVersion | Phase 1a 要求 | 状态 | 说明 |
| --- | --- | --- | --- | --- | --- |
| Milvus | `design/deploy/charts/milvus/Chart.yaml` | 2.4.0 | ≥ 2.4 | ✅ 满足 | T008/T009 向量检索可用 |
| NebulaGraph | `design/deploy/charts/nebula-graph/Chart.yaml` | 3.6.0 | ≥ 3.5 | ✅ 满足 | T009 图谱检索可用 |

---

## 第7章 安全合规工具检查结果

### 7.1 国密算法库

表：A-14 国密算法库检查结果

| 检查项 | Phase 1a 要求 | 本机实际 | 状态 | 说明 |
| --- | --- | --- | --- | --- |
| gmssl（Python 国密 SM2/SM3/SM4） | T008/T022/T023 国密实现 | 未安装 | ❌ 缺失（阻塞） | **Phase 1a 关键缺失**：T022 CryptoSpiFactory 双栈实现需要国密算法；Python 侧建议 `pip install gmssl`；Java 侧通过 Bouncy Castle 或华为 GMBase 实现 |
| Bouncy Castle（Java 国密） | T022/T023 Java 侧 SM2/SM3/SM4 | 未在 pom.xml 显式声明 | ⚠️ 待确认 | 项目 encaps-layer/rule-engine pom.xml 未显式声明；T022 实现时需添加 `org.bouncycastle:bcprov-jdk18on` 依赖 |
| PyCryptodome（RSA/SHA/AES） | T022 双栈实现国际算法侧 | 已安装 | ✅ 已就绪 | 国际算法侧可用 |
| 华为 GMBase | T023 国密局认证密码模块 | 未安装 | ⚠️ 待确认 | T023 后半段才需要，Phase 1a 截止前可后置安装 |

### 7.2 CI/CD 与安全扫描

表：A-15 CI/CD 与安全扫描工具检查结果

| 检查项 | Phase 1a 要求 | 本机实际 | 状态 | 说明 |
| --- | --- | --- | --- | --- | --- |
| SonarQube Scanner | CI/CD 代码质量检查 | 未安装 | ⚠️ 缺失（非阻塞） | Phase 1a 通过 GitHub Actions CI 执行代码检查（项目已有 .github/workflows/ci.yml），sonar-scanner 可在 CI runner 侧安装；本地非必需 |
| ZAP（OWASP ZAP） | 安全扫描 | 未安装 | ⚠️ 缺失（非阻塞） | T020 等保三级控制项落地阶段才需要，Phase 1a 批次 1 可后置；建议通过 Docker 拉起 `owasp/zap2docker-stable` |
| GitHub Actions CI | 项目已配置 | ✅ 存在 `.github/workflows/ci.yml` + `release.yml` | ✅ 已就绪 | CI/CD 流水线配置就绪 |

---

## 第8章 服务运行时状态检查结果

表：A-16 服务运行时状态检查结果

| 服务 | 期望状态 | 实际状态 | 影响任务 | 处置建议 |
| --- | --- | --- | --- | --- |
| Docker Desktop daemon | Running | ⚠️ Stopped（com.docker.service 状态 Stopped） | T001/T003/T014/T018/T022（容器构建与部署） | 启动 Docker Desktop；启动后验证 `docker info` 与 `docker run hello-world` |
| WSL2 Ubuntu-24.04 | Running（SKE bootstrap 依赖） | ⚠️ Stopped | T001/T003（SKE 集群拉起前置） | `wsl -d Ubuntu-24.04` 启动；SKE README §WSL2-QUICKSTART 有详细步骤 |
| WSL2 docker-desktop | Running | ⚠️ Stopped | Docker Desktop 集成 | 启动 Docker Desktop 后自动同步 |
| K8s 集群（SKE v1.30） | Running | ❌ 未拉起 | T001/T003/T014/T018/T022（所有 K8s 部署任务） | 启动 Docker Desktop + WSL2 后执行 `bash ske/ske.sh up --target wsl2` |
| kubeconfig 上下文 | 当前上下文指向 SKE 集群 | ❌ 空 | 所有 kubectl 操作 | SKE 拉起后自动生成 ~/.kube/config |

---

## 第9章 Phase 1a 所需但缺失的工具清单

### 9.1 阻塞类缺失（必须在 2026-09-01 启动前安装/就绪）

表：A-17 阻塞类缺失工具清单

| 序号 | 工具 | 用途 | 涉及任务 | 安装/处置方式 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 1 | Helm 3.14+ | K8s 包管理 | T001/T003/T018/T019 | `choco install kubernetes-helm` 或下载二进制放入 PATH | P0 |
| 2 | kind v0.23+ | SKE 节点镜像构建 | T001/T003（SKE 拉起前置） | `choco install kind` 或 Go 安装 `go install sigs.k8s.io/kind@latest` | P0 |
| 3 | Istio ≥1.20 | Service Mesh 控制面 | T001/T002 | 通过 Helm 安装 `helm install istio-base istio/base -n istio-system`；istioctl CLI 可选 | P0 |
| 4 | ArgoCD ≥2.7 | GitOps Chart 纯管 | T003/T004 | 通过 Helm 安装 `helm install argocd argo/argo-cd -n argocd`；argocd CLI 可选 | P0 |
| 5 | LangChain | NL2SQL 编排 | T010 | `pip install langchain langchain-community langchain-openai` | P0 |
| 6 | LangGraph | MAOP ReAct 编排 | T005 | `pip install langgraph` | P0 |
| 7 | PyMilvus ≥2.4 | 向量检索 Python 客户端 | T008/T009 | `pip install pymilvus>=2.4` | P0 |
| 8 | kafka-python | MAOP 消息总线客户端 | T005 | `pip install kafka-python` | P0 |
| 9 | nebula3-python ≥3.4 | 图谱检索客户端 | T009 | `pip install nebula3-python==3.4.0`（项目已声明） | P0 |
| 10 | gmssl（Python 国密） | 国密 SM2/SM3/SM4 | T022/T023 | `pip install gmssl` | P0 |
| 11 | Docker Desktop daemon | 容器运行时 | 全部部署任务 | 启动 Docker Desktop 应用 | P0 |
| 12 | WSL2 Ubuntu-24.04 | SKE bootstrap 环境 | T001/T003 | `wsl -d Ubuntu-24.04` | P0 |
| 13 | SKE K8s 集群 | K8s 1.30 运行时 | 全部 K8s 部署任务 | `bash ske/ske.sh up --target wsl2` | P0 |

### 9.2 版本不符类

表：A-18 版本不符工具清单

| 序号 | 工具 | 当前版本 | 要求版本 | 涉及任务 | 处置建议 | 优先级 |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Doris Chart appVersion | 2.0.3 | ≥ 2.1 | T016 物化视图自动刷新 | 修改 `design/deploy/charts/doris/Chart.yaml` appVersion 至 2.1.x；同步升级 values.yaml 镜像 tag | P1（T016 在批次 3，可后置但需在 2026-11-10 前完成） |

### 9.3 建议安装类（非阻塞但推荐）

表：A-19 建议安装工具清单

| 序号 | 工具 | 用途 | 涉及任务 | 安装方式 | 优先级 |
| --- | --- | --- | --- | --- | --- |
| 1 | PyIceberg | Iceberg V2 Python 表管理 | T015 | `pip install pyiceberg` | P2 |
| 2 | PyFlink ≥1.18 | Flink Python UDF | T014 | `pip install apache-flink>=1.18` | P2 |
| 3 | sentence-transformers | bge-reranker-v2 重排序 | T009 | `pip install sentence-transformers` | P2 |
| 4 | rank-bm25 | BM25 检索 | T009 | `pip install rank-bm25` | P2 |
| 5 | poetry | Python 包管理（如后续 MAOP/NL2SQL 改用） | T005/T010 | `pip install poetry` | P3 |
| 6 | sonar-scanner | 本地代码质量检查 | CI/CD | 下载二进制放入 PATH | P3 |
| 7 | ZAP | 安全扫描 | T020 | `docker pull owasp/zap2docker-stable` | P3 |
| 8 | Bouncy Castle (Java) | Java 侧国密 SM2/SM3/SM4 | T022/T023 | pom.xml 添加 `org.bouncycastle:bcprov-jdk18on:1.78` | P1（T022 实现时必需） |

---

## 第10章 安装与升级建议

### 10.1 一键安装脚本（建议）

命令示例：Phase 1a 阻塞工具一键安装（PowerShell）

```powershell
# 1. 启动 Docker Desktop（手动启动应用，或）
Start-Process "C:\Program Files\Docker\Docker\Docker Desktop.exe"
# 等待 daemon 就绪（约 30-60 秒）

# 2. 启动 WSL2 Ubuntu-24.04
wsl -d Ubuntu-24.04

# 3. 安装 Helm 3.14+
choco install kubernetes-helm -y
# 或手动: 下载 https://get.helm.sh/helm-v3.14.0-windows-amd64.zip 解压放入 PATH

# 4. 安装 kind v0.23+
choco install kind -y
# 或 Go 安装: go install sigs.k8s.io/kind@latest

# 5. 安装 Python AI/ML 依赖（建议在虚拟环境中）
python -m venv .venv-phase1a
.\.venv-phase1a\Scripts\Activate.ps1
pip install langchain langchain-community langchain-openai langgraph
pip install pymilvus>=2.4 kafka-python nebula3-python==3.4.0 gmssl
pip install pyiceberg apache-flink>=1.18 sentence-transformers rank-bm25

# 6. 拉起 SKE K8s 集群（在 WSL2 中执行）
wsl -d Ubuntu-24.04 -- bash -c "cd /mnt/f/Agent/workbuddy/workspace/ShuqingBigDataPlatform && bash ske/ske.sh up --target wsl2"

# 7. 安装 Istio ≥1.20（通过 Helm，待 SKE 集群就绪后）
helm repo add istio https://istio-release.storage.googleapis.com/charts
helm repo update
kubectl create namespace istio-system
helm install istio-base istio/base -n istio-system
helm install istio-discovery istio/discovery -n istio-system

# 8. 安装 ArgoCD ≥2.7（通过 Helm）
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update
kubectl create namespace argocd
helm install argocd argo/argo-cd -n argocd --version 7.x.x  # 7.x 对应 ArgoCD 2.7+
```

### 10.2 Doris Chart 版本升级

命令示例：Doris Chart appVersion 升级至 2.1

```powershell
# 修改 design/deploy/charts/doris/Chart.yaml 中 appVersion: "2.0.3" -> appVersion: "2.1.0"
# 同步修改 design/deploy/values/doris-values.yaml 中 image.tag 至 2.1.0
# 验证 helm lint design/deploy/charts/doris/
```

### 10.3 网络环境配置建议

表：A-20 网络环境配置建议

| 配置项 | 建议值 | 原因 |
| --- | --- | --- |
| GOPROXY | https://goproxy.cn,direct | 国内访问 proxy.golang.org 不稳定 |
| Python pip 镜像 | https://pypi.tuna.tsinghua.edu.cn/simple | 国内访问 pypi.org 速度慢 |
| npm registry | https://registry.npmmirror.com | 国内访问 registry.npmjs.org 速度慢 |
| Helm repo 镜像 | 阿里云/华为云镜像 | 国内访问 Google Storage 不稳定 |
| Docker registry mirror | 配置 daemon.json registry-mirrors | 国内拉取 Docker Hub 镜像慢 |

---

## 第11章 环境就绪度评估

### 11.1 分维度就绪度

表：A-21 分维度就绪度评估

| 维度 | 总项数 | 已就绪 | 缺失/未运行 | 版本不符 | 就绪率 | 评估 |
| --- | --- | --- | --- | --- | --- | --- |
| 编程语言工具链（Go/Java/Python/Node） | 4 | 4 | 0 | 0 | 100% | ✅ 就绪 |
| 构建工具（Maven/pip/Helm/Docker） | 4 | 2 | 2（Helm 缺失、Docker daemon 未运行） | 0 | 50% | ❌ 未就绪 |
| K8s 客户端与集群 | 3 | 1（kubectl CLI） | 2（集群未拉起、kubeconfig 空） | 0 | 33% | ❌ 未就绪 |
| Service Mesh / GitOps | 3 | 0 | 3（Istio/ArgoCD/kind 均缺失） | 0 | 0% | ❌ 未就绪 |
| 大数据组件（Chart 声明） | 7 | 6 | 0 | 1（Doris 2.0.3→2.1+） | 86% | ⚠️ 部分就绪 |
| AI/ML Python 库 | 9 | 1（PyTorch） | 8 | 0 | 11% | ❌ 未就绪 |
| 安全合规工具 | 5 | 2（PyCryptodome、CI 配置） | 3 | 0 | 40% | ⚠️ 部分就绪 |
| 服务运行时 | 5 | 0 | 5（Docker/WSL2/K8s/kubeconfig 均未运行） | 0 | 0% | ❌ 未就绪 |

### 11.2 总体就绪度评估

表：A-22 总体就绪度评估

| 评估维度 | 结论 |
| --- | --- |
| **总体就绪度** | **部分就绪（约 40%）** |
| **就绪项** | 编程语言工具链（Go 1.26.5 / Java 17.0.20 / Python 3.14.3 / Node 25.9.0）全部就绪且远超要求；Maven 3.9.12、pip 26.1.2、git 2.55.0、kubectl CLI v1.36.1 就绪；PyTorch 2.12.1 就绪；项目 Helm Chart 大部分 appVersion 满足要求；GitHub Actions CI 配置就绪 |
| **阻塞项** | 13 项 P0 阻塞缺失（详见 §9.1）：Helm、kind、Istio、ArgoCD、LangChain、LangGraph、PyMilvus、kafka-python、nebula3-python、gmssl、Docker daemon、WSL2、SKE 集群 |
| **版本不符项** | 1 项 P1：Doris Chart appVersion 2.0.3 需升级至 2.1+ |
| **建议安装项** | 8 项 P2/P3：PyIceberg、PyFlink、sentence-transformers、rank-bm25、poetry、sonar-scanner、ZAP、Bouncy Castle |
| **能否按期启动** | **不能直接按期启动**：需先完成 §10.1 一键安装脚本（预计 2-4 小时含网络下载），并拉起 SKE 集群（预计 30-60 分钟） |
| **预计就绪时间** | 完成 §10.1 + §10.2 + §10.3 后约 0.5 人天可达到 Phase 1a 启动就绪标准 |

### 11.3 启动就绪标准（建议）

Phase 1a 启动前应满足以下全部条件：

1. ✅ 编程语言工具链全部就绪（已满足）
2. ✅ Maven / pip / git 就绪（已满足）
3. ⬜ Helm 3.14+ 已安装且 `helm version` 可执行
4. ⬜ Docker Desktop daemon Running，`docker run hello-world` 成功
5. ⬜ WSL2 Ubuntu-24.04 Running
6. ⬜ SKE K8s 1.30 集群已拉起，`kubectl get nodes` 返回 Ready 节点
7. ⬜ Istio ≥1.20 已部署到 SKE 集群 istio-system 命名空间
8. ⬜ ArgoCD ≥2.7 已部署到 SKE 集群 argocd 命名空间
9. ⬜ Python AI/ML 库全部安装：LangChain、LangGraph、PyMilvus、kafka-python、nebula3-python、gmssl
10. ⬜ Doris Chart appVersion 升级至 2.1+
11. ⬜ 网络环境配置完成（GOPROXY / pip 镜像 / npm registry / Docker registry mirror）

### 11.4 风险提示

表：A-23 风险提示

| 风险 | 影响 | 缓解措施 |
| --- | --- | --- |
| 国内网络下载 Helm/Istio/ArgoCD Chart 慢 | 安装耗时增加 | 配置 Helm repo 镜像（阿里云/华为云）；预下载 Chart tarball |
| PyTorch CPU 版本推理性能不足 | T008 多模态切片器推理延迟高 | Phase 1a 开发期可接受；生产部署时切换 CUDA 版本 |
| SKE 集群首次拉起可能遇到 WSL2 资源不足 | 集群拉起失败 | WSL2 内存配置 ≥ 8GB、CPU ≥ 4 核（参考 ske/WSL2-QUICKSTART.md） |
| LangChain/LangGraph 版本快速迭代 | API 兼容性风险 | 在 requirements.txt 中钉版本（如 `langchain==0.3.x`） |
| Doris 2.1 升级可能引入不兼容 | T016 物化视图实现需重新验证 | 升级前在测试环境验证；保留 2.0.3 镜像作为回滚备份 |
| Bouncy Castle 国密实现与华为 GMBase 互认 | T023 国密局认证可能不通过 | 提前与国密测评机构沟通；保留双栈切换能力（CryptoSpiFactory SPI 抽象） |

---

## 第12章 后续行动项

表：A-24 后续行动项跟踪表

| 序号 | 行动项 | 负责人 | 截止日期 | 状态 | 关联任务 |
| --- | --- | --- | --- | --- | --- |
| 1 | 执行 §10.1 一键安装脚本，完成 13 项 P0 阻塞工具安装 | DevOps 工程师 | 2026-08-25 | 待执行 | 全部 |
| 2 | 执行 §10.2 Doris Chart 版本升级 | 实时数仓组 | 2026-09-15 | 待执行 | T016 |
| 3 | 执行 §10.3 网络环境配置 | DevOps 工程师 | 2026-08-20 | 待执行 | 全部 |
| 4 | 拉起 SKE K8s 集群并验证 `kubectl get nodes` Ready | DevOps 工程师 | 2026-08-25 | 待执行 | T001/T003 |
| 5 | 部署 Istio ≥1.20 到 SKE 集群 | 云原生组 | 2026-08-28 | 待执行 | T001 |
| 6 | 部署 ArgoCD ≥2.7 到 SKE 集群 | 云原生组 | 2026-08-28 | 待执行 | T003 |
| 7 | 验证 Python AI/ML 库导入正常 | AI 组 | 2026-08-28 | 待执行 | T005/T008/T009/T010 |
| 8 | 复检：执行本报告 §1.2 检查方法全量复检，目标就绪率 ≥ 95% | Phase1a 启动前准备工程师 | 2026-08-31 | 待执行 | 全部 |

---

## 附录 A：本机已安装关键工具全量清单

表：A-25 本机已安装关键工具清单（Get-Command 探测结果）

| 工具 | 版本 | 路径 | 用途 |
| --- | --- | --- | --- |
| go.exe | 1.26.5 | F:\Program Files (x86)\CodeArts\go\bin\go.exe | Go 编译器 |
| java.exe (Corretto 17) | 17.0.20 | F:\Program Files (x86)\Amazon Corretto\jdk17.0.20_8\bin\java.exe | JDK 17 运行时 |
| java.exe (Oracle 8) | 1.8.0_461 | F:\Program Files (x86)\Java\jdk-1.8\bin\java.exe | JDK 8 备用 |
| python.exe | 3.14.3 | F:\Program Files (x86)\Python\Python 3.14.3 (64-bit)\python.exe | Python 解释器 |
| python3.11.exe | 3.11 | C:\Users\winge\.local\bin\python3.11.exe | Python 3.11 备用 |
| node.exe | 25.9.0 | F:\Program Files\nodejs\node.exe | Node.js 运行时 |
| npm.cmd | 11.9.0 | F:\Program Files\nodejs\npm.cmd | npm 包管理器 |
| mvn.cmd | 3.9.12 | F:\Program Files (x86)\apache-maven-3.9.12\bin\mvn.cmd | Maven 构建工具 |
| docker.exe | 29.6.2 | F:\Docker\resources\bin\docker.exe | Docker CLI |
| docker-compose.exe | v5.3.1 | F:\Docker\resources\bin\docker-compose.exe | Docker Compose |
| kubectl.exe | v1.36.1 | F:\Docker\resources\bin\kubectl.exe | K8s 客户端 |
| git.exe | 2.55.0 | (PATH) | Git 版本控制 |

## 附录 B：项目声明依赖版本汇总

表：A-26 项目声明依赖版本汇总

| 组件 | 声明文件 | 声明版本 | Phase 1a 要求 | 状态 |
| --- | --- | --- | --- | --- |
| Go (catalog) | platform/catalog/go.mod | 1.25.0 | ≥ 1.21 | ✅ |
| Go (vector-engine) | platform/vector-engine/go.mod | 1.23 | ≥ 1.21 | ✅ |
| Java (encaps-layer) | platform/encaps-layer/pom.xml | 17 (Spring Boot 3.2.5) | ≥ 17 | ✅ |
| Python (knowledge-engine) | platform/knowledge-engine/requirements.txt | 3.10+ | ≥ 3.11 | ⚠️ 项目声明 3.10+，本机 3.14.3 满足 |
| Python (ml-platform) | platform/ml-platform/requirements.txt | 3.10+ | ≥ 3.11 | ⚠️ 同上 |
| Node.js (frontend) | frontend/package.json | 未声明 engines | ≥ 18 | ✅ 本机 25.9.0 |
| Flink | design/deploy/charts/flink/Chart.yaml | 1.18.1 | ≥ 1.18 | ✅ |
| Doris | design/deploy/charts/doris/Chart.yaml | 2.0.3 | ≥ 2.1 | ❌ 需升级 |
| Kafka | design/deploy/charts/kafka/Chart.yaml | 3.6.1 | ≥ 3.6 | ✅ |
| Milvus | design/deploy/charts/milvus/Chart.yaml | 2.4.0 | ≥ 2.4 | ✅ |
| NebulaGraph | design/deploy/charts/nebula-graph/Chart.yaml | 3.6.0 | ≥ 3.5 | ✅ |
| Trino | design/deploy/charts/trino/Chart.yaml | 428 | ≥ 428 | ✅ |
| Spark | design/deploy/charts/spark/Chart.yaml | 3.5.1 | ≥ 3.5 | ✅ |
| Keycloak | design/deploy/charts/keycloak/Chart.yaml | 24.0 | ≥ 24.0 | ✅ |
| Iceberg REST | design/deploy/charts/iceberg-rest/Chart.yaml | 0.7.0 | Iceberg V2 | ✅ |

---

## 文档变更记录

表：A-27 文档变更记录

| 版本 | 日期 | 变更内容 | 作者 |
| --- | --- | --- | --- |
| v1.0 | 2026-08-06 | 初版：完成 Phase 1a 批次 1 全部 8 任务所需开发环境与工具链检查，输出就绪度评估与安装建议 | Phase1a 启动前准备工程师（任务 144） |