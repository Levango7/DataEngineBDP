# T000a: V1.1/V1.2 Mock清零状态核查报告

> 核查时间: 2026-08-06
> 核查范围: platform/ 下全部组件
> 核查方法: 逐组件搜索生产代码中的 Mock/Stub/SIM/模拟/placeholder/in-memory 等关键词（排除 tests/ 目录与测试文件），检查真实实现代码路径（build tag / 配置切换 / 环境变量控制），区分生产 Mock（需清零）与测试 Mock（正常）
> 核查人: T000a 前置依赖核查员

## 0. 组件计数说明

任务描述中提及 23 个组件，实际 `platform/` 目录下直接子目录为 **21 个**。其中 `governance` 目录包含 2 个独立子组件（`lineage-analyzer` 与 `metadata-collector`），若将其拆分独立计算，则核查组件总数为 **22 个**。本报告按 22 个组件逐项核查。另：任务描述中写作 `infra-provider-xinchuang`，实际目录名为 `infra-provider-xinchang`，已按实际目录核查。

## 1. 核查总览

| 状态 | 组件数 | 组件列表 |
|------|--------|----------|
| ✅ Mock已清零 | 6 | sql-gateway、catalog、infra-orchestrator、infra-provider-cloud、infra-provider-private、governance/metadata-collector |
| ❌ Mock未清零 | 7 | rule-engine、asset-exchange、business-portal、open-api-catalog、industry-templates、infra-provider-xinchang、ml-platform |
| ❓ 部分清零 | 9 | encaps-layer、dqctl、infra-provider-baremetal、knowledge-engine、llm-gateway、llmops、tag-engine、vector-engine、governance/lineage-analyzer |

**关键结论**：22 个组件中，仅 6 个（27%）Mock 完全清零；7 个（32%）Mock 完全未清零（生产代码硬编码模拟结果）；9 个（41%）为部分清零（存在真实实现代码路径，但默认配置或默认构建仍走 Mock）。V1.1"真实外部依赖接入"任务实质上尚未启动，与 ROADMAP.md 中 V1.1 所有 checkbox 未勾选状态一致。

## 2. 逐组件核查结果

### 2.1 encaps-layer (Java/Spring Boot)

- **Mock清零状态**: ❓部分清零
- **生产Mock使用**:
  - `src/main/java/.../workspace/K8sClientConfig.java:39-44`：`@Value("${app.k8s.mock-enabled:false}")` 控制 K8s client，`mockEnabled=true` 时返回 `null`（由测试注入 mock client）
  - `src/main/resources/application.yml:73`：`mock-enabled: ${K8S_MOCK_ENABLED:true}` —— **配置文件默认值为 true，即默认运行于 mock 模式**
- **真实实现路径**: `K8sClientConfig.java:46` 使用 fabric8 `KubernetesClientBuilder().build()` 真实连接 K8s；数据库默认 H2 文件模式，可通过 `DB_URL` 环境变量切换 PostgreSQL
- **V1.1清零工作**: 需要将 `application.yml` 中 `K8S_MOCK_ENABLED` 默认值改为 `false`（或生产 profile 覆盖），确保生产环境默认连接真实 K8s 集群
- **风险等级**: 中（代码已就绪，仅默认配置需调整；但若生产未设环境变量将静默走 mock）

### 2.2 sql-gateway (Java/Spring Boot)

- **Mock清零状态**: ✅已清零
- **生产Mock使用**: 无实质性 Mock。`optimizer/SqlOptimizerService.java:454` 的 Join 重排序使用"根据表名长度模拟小表驱动"启发式，注释标注"实际应基于统计信息"——此为 MVP 阶段优化算法简化，非 Mock 数据返回
- **真实实现路径**: `service/BackendProxyService.java` 使用 WebFlux `WebClient` 真实代理到 Trino（`POST /v1/statement`）与 Doris（`POST /api/query`），内置熔断器；`crosssource/CrossSourceExecutor` + `SourceQueryTask` 实现跨源并行查询
- **V1.1清零工作**: 无需（已接入真实 Trino/Doris 后端）
- **风险等级**: 低

### 2.3 catalog (Go)

- **Mock清零状态**: ✅已清零
- **生产Mock使用**: 无
- **真实实现路径**: `internal/store/store.go` 的 `GormStore` 基于 GORM 实现真实持久化；`main.go:62` 使用 `gorm.Open(sqlite.Open(dbPath))` 初始化 SQLite 文件数据库（`catalog.db`，重启不丢数据），可通过 `CATALOG_DB` 环境变量切换 PostgreSQL
- **V1.1清零工作**: 无需（已使用真实关系型存储）
- **风险等级**: 低

### 2.4 rule-engine (Java/Spring Boot)

- **Mock清零状态**: ❌未清零
- **生产Mock使用**:
  - `src/main/java/.../engine/DqRuleExecutor.java:24-32`：数据质量规则执行器返回硬编码 `status="PASS", message="SIMULATED"`
  - `src/main/java/.../engine/AlertRuleExecutor.java:24-32`：告警规则执行器返回 `SIMULATED`
  - `src/main/java/.../engine/MaskRuleExecutor.java:24-32`：脱敏规则执行器返回 `SIMULATED`
  - `src/main/java/.../service/RuleExecutionService.java:17`：注释"MVP 阶段返回模拟结果"
- **真实实现路径**: 无（3 个执行器均无真实数据源执行逻辑，全部返回 PASS/SIMULATED）
- **V1.1清零工作**: 需要实现 3 类规则的真实执行：DQ 规则接入真实数据源执行 SQL 校验；ALERT 规则接入真实指标系统比对阈值；MASK 规则接入真实脱敏算法
- **风险等级**: 高（治理闭环核心能力缺失，V1.2 端到端 PoC 的"治理闭环链路"强依赖此组件）

### 2.5 dqctl (Go CLI)

- **Mock清零状态**: ❓部分清零
- **生产Mock使用**:
  - `cmd/status.go:25-35`：硬编码返回 4 个组件的 `healthy` 状态，未调用真实健康端点
  - `cmd/apply.go:53-55`：打印"apply 完成"但未调用封装层 API，返回"API 调用返回模拟结果"
  - `cmd/query.go:39-43`：打印 SQL 但未调用 SQL 网关，返回"查询结果为模拟数据"
- **真实实现路径**: `internal/client/api.go` 提供真实 HTTP 客户端（`Get`/`Post` 方法，自动注入 Bearer token 与 X-Tenant-ID 头），但 3 个命令均未接入此 client
- **V1.1清零工作**: 需要将 3 个命令接入 `internal/client/api.go`：status 调用各组件 `/api/v1/health`；apply 调用封装层 API；query 调用 SQL 网关执行查询
- **风险等级**: 中（CLI 工具非核心链路，但影响运维体验与 PoC 演示）

### 2.6 asset-exchange (Python)

- **Mock清零状态**: ❌未清零
- **生产Mock使用**:
  - `config/settings.py:41-42`：`storeType: Literal["mock"]`，仅支持 mock 类型
  - `services/registry.py:51-55`：`if settings.isMock: _build_mock()` else 分支也调用 `_build_mock()` —— **无论配置如何都走 Mock**
  - `repositories/mock/` 下 4 个内存字典仓储：`MockAssetRepository`、`MockSubscriptionRepository`、`MockDeliveryRepository`、`MockBillingRepository`
  - `services/delivery_service.py:77,143,151,165,187`：交付执行、API key 生成、数据量、文件 URL、只读账号全部 Mock
- **真实实现路径**: 无（无真实后端仓储实现）
- **V1.1清零工作**: 需要实现真实仓储（PostgreSQL/对象存储）+ 真实交付执行（API 网关下发/文件分发/账号创建）
- **风险等级**: 高（V2.0 数据资产流通核心组件，V1.1 需完成真实存储接入）

### 2.7 business-portal (Python)

- **Mock清零状态**: ❌未清零
- **生产Mock使用**:
  - `config/settings.py:40-41`：`storeType: Literal["mock", "sqlite"]`，支持 sqlite 但默认 mock
  - `services/registry.py:55-59`：`if settings.isMock: _build_mock()` else 分支注释"SQLite 暂未实现，回退到 Mock"并调用 `_build_mock()` —— **SQLite 未实现，无论配置都走 Mock**
  - `repositories/mock.py` 下 5 个内存仓储：`MockBusinessLineStore`、`MockDashboardStore`、`MockWorkbenchStore`、`MockCatalogStore`、`MockReportStore`
- **真实实现路径**: 无（sqlite 配置项存在但实现未完成）
- **V1.1清零工作**: 需要实现 SQLite/PostgreSQL 真实仓储，替换 5 个 Mock 仓储
- **风险等级**: 高（业务门户核心存储缺失）

### 2.8 open-api-catalog (Python)

- **Mock清零状态**: ❌未清零
- **生产Mock使用**:
  - `config/settings.py:67-68`：`storeType: Literal["mock"]`，仅支持 mock
  - `services/registry.py:48`：`store = MockCatalogStore()` 硬编码 Mock
  - `services/api_call.py:186-213`：`_forward_to_backend` 返回 `mock_result`，注释"转发到后端（Mock 实现）"
  - `services/apisix_config.py:244-255`：`deploy_route` "模拟下发到 APISIX Admin API"
  - `repositories/mock/store.py`：`MockCatalogStore` 内存存储
- **真实实现路径**: 无
- **V1.1清零工作**: 需要实现真实存储 + 真实后端转发 + 真实 APISIX Admin API 下发
- **风险等级**: 高（V2.0 开放 API 服务目录核心组件）

### 2.9 industry-templates (Python)

- **Mock清零状态**: ❌未清零
- **生产Mock使用**:
  - `config/settings.py:41-42`：`deployMode: Literal["mock", "helm"]`，默认 mock
  - `services/template_engine.py:302`："生成部署记录并模拟 helm install"
  - `services/template_engine.py:317`："模拟 instantiate（首次作业运行 + 仪表盘快照）"
  - 注：`templates/*.py` 中的 `placeholder="${ORDER_DB_JDBC}"` 是模板参数占位符（正常设计，非 Mock）
- **真实实现路径**: 有 helm 配置项但部署逻辑为模拟
- **V1.1清零工作**: 需要实现真实 helm install 执行 + 真实作业运行 + 仪表盘快照生成
- **风险等级**: 中（V2.0 行业模板扩展依赖此组件，但 V1.1/V1.2 非强依赖）

### 2.10 infra-orchestrator (Java/Spring Boot)

- **Mock清零状态**: ✅已清零
- **生产Mock使用**: 无（`SupplyOrchestrator.java:210` 等处的 `placeholder` 为变量名，非 Mock）
- **真实实现路径**: `service/SupplyOrchestrator.java` 使用 `WebClient` 真实调用下游 Provider REST API（信创/裸金属/公有云/私有云），`registry/ProviderRegistry` 实现 Provider 路由，支持集群状态轮询
- **V1.1清零工作**: 无需
- **风险等级**: 低

### 2.11 infra-provider-baremetal (Go)

- **Mock清零状态**: ❓部分清零
- **生产Mock使用**:
  - `main.go:183-208`：`localExecutor` 的 `Execute` 返回模拟 kubeadm join 输出，`CopyFile` 直接返回 nil；注释明确"仅用于开发测试，实际部署应使用 SSH 执行器"
  - `main.go:90`：默认使用 localExecutor
- **真实实现路径**: `internal/service/redfish_client.go` 真实 BMC Redfish 客户端；`internal/service/k8s_bootstrap.go` 提供 `CommandExecutor` 接口抽象与 kubeadm 命令模板生成，SSH 执行器可注入
- **V1.1清零工作**: 需要实现 SSH 执行器（基于 `golang.org/x/crypto/ssh`），替换默认 localExecutor
- **风险等级**: 中（裸金属部署能力未就绪，但非 V1.2 PoC 强依赖）

### 2.12 infra-provider-cloud (Java/Spring Boot)

- **Mock清零状态**: ✅已清零
- **生产Mock使用**: 无
- **真实实现路径**: 3 个真实云 SDK 实现：
  - `provider/huawei/HuaweiCloudProvider.java`：华为云 Java SDK（`com.huaweicloud.sdk.ecs.v2.EcsClient`）
  - `provider/ali/AliCloudProvider.java`：阿里云 Java SDK（`com.aliyun.ecs20140526.Client`）
  - `provider/tencent/TencentCloudProvider.java`：腾讯云 Java SDK（`com.tencentcloudapi.cvm.v20170312.CvmClient`）
- **V1.1清零工作**: 无需
- **风险等级**: 低

### 2.13 infra-provider-private (Java/Spring Boot)

- **Mock清零状态**: ✅已清零
- **生产Mock使用**: 无（`application.yml:9` 的 `max-in-memory-size: 16MB` 为 WebFlux 配置，非 Mock）
- **真实实现路径**: 2 个真实私有云 REST API 客户端：
  - `provider/openstack/OpenStackClient.java`：WebClient 调用 OpenStack Nova（v2.1）+ Keystone（v3）REST API
  - `provider/vsphere/VSphereClient.java`：WebClient 调用 VMware vCenter 7.0+ REST API
- **V1.1清零工作**: 无需
- **风险等级**: 低

### 2.14 infra-provider-xinchang (Java/Spring Boot)

- **Mock清零状态**: ❌未清零
- **生产Mock使用**:
  - `src/main/java/.../service/K8sBootstrapService.java:23`：注释"本实现为模拟版，仅记录操作日志并返回 VIP；生产环境应通过 SSH/Ansible 远程执行 kubeadm 命令"
  - `K8sBootstrapService.java:76-78`："模拟 kubeadm init"，返回硬编码 `controlPlaneVip = "192.168.200.10"`
  - `K8sBootstrapService.java:80-84`："模拟 kubeadm join worker"，仅记录日志
- **真实实现路径**: 无（无 SSH/Ansible 执行器实现）
- **V1.1清零工作**: 需要实现真实 SSH/Ansible 远程执行 kubeadm 命令，替换模拟版
- **风险等级**: 高（信创环境 K8s 集群初始化能力缺失，V1.2 四环境一致性验证强依赖）

### 2.15 knowledge-engine (Python)

- **Mock清零状态**: ❓部分清零
- **生产Mock使用**:
  - `config/settings.py:47-48`：`storeType: Literal["mock", "nebula"]`，默认 mock
  - `config/settings.py:52-53`：`extractorType: Literal["mock", "llm"]`，默认 mock
  - `repositories/mock/graph_store.py`：`MockGraphStore` 内存邻接表
  - `repositories/mock/entity_extractor.py`、`relation_extractor.py`：基于规则匹配的 Mock 抽取器
- **真实实现路径**: `repositories/nebula/graph_store.py` NebulaGraph 真实实现（SDK 调用封装在私有方法）；LLM 抽取器实现路径存在
- **V1.1清零工作**: 需要将默认配置切换为 nebula + llm，并验证 NebulaGraph SDK 调用与 LLM 抽取真实可用
- **风险等级**: 中（V2.0 RAG 增强依赖此组件，V1.1/V1.2 非强依赖）

### 2.16 llm-gateway (Go)

- **Mock清零状态**: ❓部分清零
- **生产Mock使用**:
  - `internal/config/config.go:107`：`LLM_GATEWAY_MOCK_MODE` 默认 `"true"`
  - `internal/config/config.go:119-129`：`mockMode=true` 时强制启用 Mock Provider（`mock-gpt-4`、`mock-embedding`）
  - `internal/config/config.go:120`：`LLM_GATEWAY_PROVIDERS` 默认 `"mock"`
  - `internal/provider/mock.go`：`MockProvider` 不依赖外部 API，`sleep` 模拟延迟
- **真实实现路径**: `internal/provider/` 下有真实 Provider 适配器（openai/wenxin/qianwen/zhipu），`config.go:208-209` 的 `buildProvider` 支持 `case "mock"` 与真实 Provider 构造
- **V1.1清零工作**: 需要将 `LLM_GATEWAY_MOCK_MODE` 默认值改为 `false`，配置真实 Provider 的 AK/SK
- **风险等级**: 中（AI 能力链路入口，V2.0 强依赖；V1.1/V1.2 可降级）

### 2.17 llmops (Python)

- **Mock清零状态**: ❓部分清零
- **生产Mock使用**:
  - `config/settings.py:40-41`：`storeType: Literal["mock", "mlflow"]`，默认 mock
  - `repositories/mock/` 下 4 个 Mock 实现：`MockModelStore`（内存字典）、`MockModelTrainer`（状态机模拟）、`MockModelDeployer`（状态机模拟）、`MockModelMonitor`（生成模拟指标）
- **真实实现路径**: `repositories/mlflow/client.py` 惰性导入 mlflow 包，提供 MLflow 真实实现路径
- **V1.1清零工作**: 需要将默认配置切换为 mlflow，安装 mlflow 依赖，验证 MLflow Projects 训练 / 模型部署 / 监控真实可用
- **风险等级**: 中（V2.0 模型评测/微调依赖此组件）

### 2.18 ml-platform (Python)

- **Mock清零状态**: ❌未清零
- **生产Mock使用**:
  - `config/settings.py:43-54`：三个配置均默认 mock（`backendType`/`featureStoreType`/`experimentStoreType`）
  - `services/registry.py:87-92`：Spark MLlib 后端"暂未实现，回退到 Mock"
  - `services/registry.py:101-103`：Redis 特征存储"暂未实现，回退到 Mock"
  - `services/registry.py:114-118`：MLflow 实验存储"暂未实现，回退到 Mock"
  - `repositories/mock/` 下 3 个 Mock 实现：`MockMLBackend`、`MockFeatureStore`、`MockExperimentStore`
- **真实实现路径**: 无（sklearn/spark/redis/mlflow 配置项存在但真实后端全部"暂未实现"）
- **V1.1清零工作**: 需要实现 sklearn 真实后端 + Redis 特征存储 + MLflow 实验存储（spark 可后置）
- **风险等级**: 高（ML 平台核心训练/特征/实验能力全部缺失）

### 2.19 tag-engine (Java/Spring Boot)

- **Mock清零状态**: ❓部分清零
- **生产Mock使用**:
  - `src/main/resources/application.yml:50`：`type: ${TAG_STORE_TYPE:mock}`，默认 mock
  - `src/main/java/.../store/mock/MockTagStore.java:51`：`@ConditionalOnProperty(... havingValue="mock", matchIfMissing=true)` —— 默认激活 Mock
  - `MockTagStore.java:46-50`：注释"模拟 Spark ETL 的产出"，内存 Map 模拟湖仓事实表与 Doris 标签宽表
- **真实实现路径**: `store/doris/DorisTagStore.java` 真实 Doris 实现，通过 `@ConditionalOnProperty` 隔离，设置 `TAG_STORE_TYPE=doris` 激活
- **V1.1清零工作**: 需要将默认配置切换为 doris，验证 DorisTagStore 真实 ALTER ADD/DROP COLUMN 与标签计算可用
- **风险等级**: 中（标签引擎核心能力，V2.0 行业模板依赖）

### 2.20 vector-engine (Go)

- **Mock清零状态**: ❓部分清零
- **生产Mock使用**:
  - `milvus_stub.go:1-2`：`//go:build !milvus_enabled` —— 默认构建（无 build tag）下 `newMilvusStore` 返回 `nil`，main.go 回退到 Mock
  - `internal/config/config.go:52`：`StoreType: "mock"` 默认 mock
  - `internal/store/mock/mock.go:24-43`：`MockVectorStore` 内存 map 存储
- **真实实现路径**: `milvus_store.go:1-2`：`//go:build milvus_enabled` —— 启用 `-tags milvus_enabled` 构建时返回真实 `milvus.NewMilvusVectorStore`；`internal/store/milvus/milvus.go` 真实 Milvus Go SDK 实现
- **V1.1清零工作**: 需要启用 `milvus_enabled` build tag 构建镜像，将 `STORE_TYPE` 默认值改为 `milvus`，验证 Milvus 真实向量检索可用
- **风险等级**: 中（V2.0 RAG 混合检索依赖此组件）

### 2.21 governance/lineage-analyzer (Java/Spring Boot)

- **Mock清零状态**: ❓部分清零
- **生产Mock使用**:
  - `src/main/java/.../service/LineageGraphWriter.java:125-128`：NebulaGraph 后端"当前版本降级为日志占位"——即使 `nebula.enabled=true` 也仅记录日志，不真实写入 NebulaGraph
  - `src/main/resources/application.yml:36`：注释"NebulaGraph 可选后端配置（默认不启用，使用内存图 + H2）"
- **真实实现路径**: `analyzer/ColumnLineageExtractor.java` 与 `TableLineageExtractor.java` 使用 `SqlParserService` 真实 SQL 解析；`LineageGraphWriter` 通过 JPA `edgeRepository.save` 真实持久化到 H2/PostgreSQL
- **V1.1清零工作**: 需要实现 NebulaGraph 真实写入（替换日志占位），接入 NebulaGraph Go/Java SDK
- **风险等级**: 中（血缘解析核心能力已就绪，图存储降级为关系型，NebulaGraph 为性能增强项）

### 2.22 governance/metadata-collector (Java/Spring Boot)

- **Mock清零状态**: ✅已清零
- **生产Mock使用**: 无（`collector/MetadataCollector.java:13` 的"7.3"单元测试可通过 Mock 本接口模拟采集行为"为接口文档说明，非生产 Mock）
- **真实实现路径**: 5 个真实元数据采集器：
  - `AbstractJdbcMetadataCollector.java`：JDBC 通用采集
  - `HiveMetadataCollector.java`：Hive Metastore 采集
  - `DorisMetadataCollector.java`：Doris Catalog 采集
  - `KafkaMetadataCollector.java`：Kafka 元数据采集
  - `FileSystemMetadataCollector.java`：文件系统元数据采集
- **V1.1清零工作**: 无需
- **风险等级**: 低

## 3. 未清零项汇总与风险登记

| 组件 | Mock类型 | 影响范围 | 清零路径 | 风险等级 | 预估工时 |
|------|----------|----------|----------|----------|----------|
| rule-engine | 规则执行器返回 SIMULATED | 治理闭环全链路 | 实现 DQ/ALERT/MASK 3 类规则真实执行 | 高 | 5-8 人日 |
| asset-exchange | 内存仓储 + Mock 交付 | 数据资产流通 | 实现 PostgreSQL 仓储 + 真实交付执行 | 高 | 6-10 人日 |
| business-portal | 内存仓储（sqlite 未实现） | 业务门户 | 实现 SQLite/.0/PostgreSQL 仓储 | 高 | 4-6 人日 |
| open-api-catalog | 内存仓储 + Mock 转发 + Mock APISIX 下发 | 开放 API 服务目录 | 实现真实存储 + 后端转发 + APISIX Admin API | 高 | 5-8 人日 |
| industry-templates | 模拟 helm install + 模拟 instantiate | 行业模板部署 | 实现真实 helm + 作业运行 + 仪表盘快照 | 中 | 3-5 人日 |
| infra-provider-xinchang | 模拟 kubeadm init/join | 信创环境 K8s 集群初始化 | 实现 SSH/Ansible 远程执行 | 高 | 4-6 人日 |
| ml-platform | 3 类后端全部"暂未实现回退 Mock" | ML 训练/特征/实验 | 实现 sklearn + Redis + MLflow 真实后端 | 高 | 8-12 人日 |
| encaps-layer | K8s mock 默认开启 | K8s 资源翻译 | 修改默认配置 K8S_MOCK_ENABLED=false | 中 | 0.5 人日 |
| dqctl | 命令未接入真实 client | CLI 运维 | 3 命令接入 internal/client | 中 | 2-3 人日 |
| infra-provider-baremetal | localExecutor 模拟 | 裸金属 K8s 引导 | 实现 SSH 执行器 | 中 | 3-4 人日 |
| knowledge-engine | 默认 mock 存储 + mock 抽取 | 知识图谱 | 切换默认配置为 nebula + llm | 中 | 2-3 人日 |
| llm-gateway | 默认强制 Mock Provider | LLM 调用 | 修改 LLM_GATEWAY_MOCK_MODE 默认 false | 中 | 1 人日 |
| llmops | 默认 mock 存储 | 模型管理 | 切换默认配置为 mlflow + 安装依赖 | 中 | 2-3 人日 |
| tag-engine | 默认 mock 标签存储 | 标签计算 | 切换默认配置为 doris | 中 | 1-2 人日 |
| vector-engine | 默认构建为 Mock | 向量检索 | 启用 milvus_enabled build tag | 中 | 1-2 人日 |
| governance/lineage-analyzer | NebulaGraph 日志占位 | 血缘图存储 | 实现 NebulaGraph 真实写入 | 中 | 2-3 人日 |

**合计预估工时**: 约 44-72 人日（高优先级 7 项约 32-50 人日，中优先级 9 项约 12-22 人日）

## 4. V1.1/V1.2实现进度清单

| 版本 | 计划项 | 状态 | 关联组件 |
|------|--------|------|----------|
| V1.1 | 封装层接入真实 K8s client | ❓代码就绪/默认配置未切换 | encaps-layer |
| V1.1 | SQL 网关接入真实 Trino/Doris | ✅已完成 | sql-gateway |
| V1.1 | 规则引擎接入真实数据源执行 | ❌未开始 | rule-engine |
| V1.1 | 资产目录接入真实 PostgreSQL/ES | ✅已完成（PostgreSQL/H2） | catalog |
| V1.1 | 元数据采集器接入真实引擎 Hook | ✅已完成 | governance/metadata-collector |
| V1.1 | 血缘解析器接入真实 SQL 解析与图存储 | ❓SQL 解析已就绪/NebulaGraph 占位 | governance/lineage-analyzer |
| V1.1 | Keycloak Realm + JWT 鉴权全链路 | ❓需验证 | encaps-layer/sql-gateway/rule-engine 等 |
| V1.1 | APISIX 插件链配置落地 | ❌未开始 | open-api-catalog |
| V1.2 | 数据集成链路真实跑通 | ❌阻塞 | 依赖 V1.1 全部清零 |
| V1.2 | 治理闭环链路真实跑通 | ❌阻塞 | rule-engine Mock 阻塞 |
| V1.2 | 多租户链路真实跑通 | ❓部分就绪 | encaps-layer K8s mock 需切换 |
| V1.2 | 四环境一致性验证 | ❌阻塞 | infra-provider-xinchang Mock 阻塞 |

## 5. 结论与建议

### 5.1 Mock 清零总体状态评估

- **已清零率 27%**（6/22），**未清零率 32%**（7/22），**部分清零率 41%**（9/22）
- V1.1"真实外部依赖接入（替换 Mock 实现）"任务**实质未启动**，与 ROADMAP.md 中 V1.1 所有 checkbox 未勾选状态一致
- 原始 5 个自研后端中，sql-gateway 与 catalog 已完成真实依赖接入；rule-engine 完全未清零；encaps-layer 与 dqctl 部分清零
- 新增 4 个 Python 组件全部未清零（asset-exchange/business-portal/open-api-catalog/industry-templates）
- L4.5 的 7 个组件中，ml-platform 完全未清零，其余 6 个部分清零（有真实实现路径但默认配置走 Mock）

### 5.2 对 V2.0 Phase 1 的阻塞风险评估

- **高阻塞风险**（必须在 V1.1 完成，否则 V2.0 Phase 1 无法启动）：
  - rule-engine：治理闭环核心能力缺失
  - infra-provider-xinchang：信创环境 K8s 初始化缺失，四环境验证阻塞
  - ml-platform：ML 平台核心能力全部缺失
  - asset-exchange / business-portal / open-api-catalog：V2.0 数据资产流通 / 业务门户 / 开放 API 核心组件
- **中阻塞风险**（V1.1 完成默认配置切换即可，代码已就绪）：
  - encaps-layer / llm-gateway / tag-engine / vector-engine / knowledge-engine / llmops：仅需修改默认配置或 build tag
  - dqctl / infra-provider-baremetal / governance/lineage-analyzer：需补充实现但非核心链路阻塞

### 5.3 建议的清零优先级排序

**第一优先级（V1.1 必须完成，高阻塞）**：
1. rule-engine —— 实现 3 类规则真实执行（治理闭环阻塞）
2. infra-provider-xinchang —— 实现 SSH/Ansible kubeadm（信创环境阻塞）
3. encaps-layer —— 修改 K8S_MOCK_ENABLED 默认值（0.5 人日，快速解锁多租户链路）
4. asset-exchange / business-portal / open-api-catalog —— 实现真实存储与后端（V2.0 核心组件）

**第二优先级（V1.1 完成默认配置切换，中阻塞）**：
5. llm-gateway —— 修改 LLM_GATEWAY_MOCK_MODE 默认 false
6. tag-engine —— 切换 TAG_STORE_TYPE 默认 doris
7. vector-engine —— 启用 milvus_enabled build tag
8. knowledge-engine / llmops —— 切换默认配置为 nebula/mlflow

**第三优先级（V1.1/V1.2 期间完成，低阻塞）**：
9. ml-platform —— 实现 sklearn + Redis + MLflow 真实后端（工时较大）
10. dqctl —— 3 命令接入真实 client
11. infra-provider-baremetal —— 实现 SSH 执行器
12. governance/lineage-analyzer —— 实现 NebulaGraph 真实写入
13. industry-templates —— 实现真实 helm install

**特别说明**：第一优先级中 encaps-layer 仅需 0.5 人日即可解锁多租户链路，建议作为 V1.1 的首个快速胜利（quick win）。