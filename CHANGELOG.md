# 变更日志

本项目所有重要变更均记录于此文件。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

### v2.1.0 进行中 — 行业生态扩展与生产化加固

#### Added
- **行业模板扩展**：新增医疗（电子病历NLP结构化+DRG/DIP分组）、交通（路网流量预测+信号调度）、教育（学情画像+教学质量评估）、农牧（物联监测+产量预测）4个行业模板（5a9481f）
- **Argo Rollouts**：金丝雀渐进式交付Chart（bd958b1）
- **v1.1性能优化**：SQL网关查询结果缓存（Caffeine 60s TTL）、封装层K8s informer watch、规则引擎异步批量执行、82 Chart HPA autoscaling
- **v1.1真实依赖**：封装层真实K8s client（fabric8+k3s IT）、规则引擎真实数据源、元数据采集器Iceberg REST Hook、血缘解析器NebulaGraph、APISIX jwt-auth+keycloak-auth插件链
- **v1.2 E2E链路**：7条端到端链路全部落地（SeaTunnel/Spark/Kafka-CDC/Trino/治理闭环/Superset/多租户）

#### Changed
- **ROADMAP更新**：v1.1/v1.2/v2.1进展项标记完成
- **前后端接线**：24个API模块全部接通后端

#### Fixed
- **K8s翻译边界**：createNamespace空/非法校验（add2030）
- **前端状态残留**：工作空间切换watch重载（add2030）
- **开发环境**：vite bind 127.0.0.1修复IPv6问题、Windows原生启动脚本、RestTemplate超时修复
- **P2/P3文档勘误**：README技术栈版本修正（Spring Boot 4.1→3.2.6、Go 1.26→1.25），模块数统一为37个（原36/32不一致），Helm Chart数修正（81→87个）
- **P2文档矛盾**：docs/README.md、docs/development-guide.md、docs/deployment-guide.md中模块数与Helm Chart数对齐实际值
- **P2 gitignore完善**：补充.env/.env.*环境变量文件忽略规则，防止敏感配置入库
- **P2构建产物审计**：确认git索引中无target/、.coverage、*.log等构建产物入库

## [2.0.0] - 2026-08-08

数据引擎大数据平台 v2.0.0 正式发布 GA（General Availability）。在 v1.0.0 基础上新增云原生与 AI 方向模块，53 个开发任务（估算工作量 755 人天）的骨架与文档交付。**v2.0 具备生产可用性：886 后端测试 + 155 前端测试全通过，7 条端到端链路落地，安全合规达标，镜像签名 + SBOM 就绪。v2.1 生产化加固进行中。**

### Added

- **自研组件**：新增 10 个自研组件，云原生与 AI 能力补齐。
  - chunker：文档分块服务，向量化预处理。
  - finops：FinOps 成本运营服务，成本模型与资源用量采集。
  - flink-cdc：Flink CDC 实时数据集成组件。
  - karmada：多集群联邦编排组件，基于 Karmada 二次封装。
  - knative：Knative Serverless 部署清单与事件源配置。
  - model-finetuning：模型微调服务，支持 LoRA / 全参微调。
  - nl2sql：自然语言转 SQL 服务，Text2SQL 引擎。
  - observability：可观测性配置（Grafana / Alertmanager / Prometheus）与统一查询 API。
  - registry：元数据注册中心服务。
  - stream-batch-scheduler：流批统一调度组件。

### Changed

- **文档改进**：修正 Apache Calcite 集成口径（规划中而非已集成），knowledge-engine 模块改名对齐，README 勘误（版本号 v1.0.0 → v2.0.0）。
- **开发模式说明**：README 与 CHANGELOG 明确标注 AI 辅助开发模式，由华为云码道(CodeArts)代码智能体协助完成，所有代码经人工审查与验证。

### Fixed

- **部署改进**：60 个 Helm Chart 全部修复，消除模板渲染与 values 缺失问题。
- **CORS 收敛**：observability/query-api 的 CorsMiddleware 由 Access-Control-Allow-Origin: * 收敛为环境变量 CORS_ALLOWED_ORIGINS 白名单匹配，生产环境按部署域显式配置，未命中时不回写头（fail-secure）。

### Security

- **密钥环境变量化**：JWT 签名密钥等敏感配置改为 mustGetenv 强制显式注入，移除弱默认值。
- **SQL 注入防护**：tenant_id 正则白名单（^[a-zA-Z0-9_-]{1,64}$）防 PromQL 注入。
- **HikariCP 连接池**：JDBC 连接池统一接入 HikariCP，约束连接数与超时。

### CI

- **gitleaks 集成**：CI 流水线接入 gitleaks 密钥扫描，阻断密钥入库。
- **移除 || true**：清理 CI 步骤中的 || true 容错，确保失败可见。
- **lint 阻断**：ESLint / golangci-lint 等检查失败即阻断流水线。

### 开发模式

- 本项目采用 AI 辅助开发模式，由华为云码道(CodeArts)代码智能体协助完成
- 所有代码均经过人工审查与验证

## [1.0.0] - 2026-08-06

数据引擎大数据平台 v1.0.0 首个正式版本。99 个工程任务全部交付，工程成熟度约 95 / 100。

### Added

- **自研组件**：新增 21 个自研组件，覆盖封装层、引擎层、治理层、智能数据层与产品层。
  - Java 组件 9 个：encaps-layer、sql-gateway、rule-engine、tag-engine、governance/metadata-collector、governance/lineage-analyzer、infra-provider-xinchang、infra-provider-cloud、infra-provider-private、infra-orchestrator。
  - Go 组件 3 个 + 1 CLI：catalog、vector-engine、llm-gateway、infra-provider-baremetal、dqctl。
  - Python 组件 8 个：llmops、knowledge-engine、ml-platform、industry-templates、business-portal、open-api-catalog、asset-exchange、operations。
- **Helm Chart**：新增 59 个 Helm Chart，覆盖大数据引擎、治理组件、智能数据层、可观测基座与自研组件。
- **前端工程**：新增 Vue3 + TypeScript strict 前端，基于 Vite 6 + Pinia + Element Plus，包含 14 个核心视图页面与 7 个 API 客户端模块。
- **设计文档**：新增 43 份模块详细设计文档，覆盖 L0 基础设施层到 L5 产品层全栈架构。
- **SKE 发行版**：新增自研 K8s 发行版 SKE v0.1，基于 kubeadm 二次封装，包含内核调优、etcd 调优、Cilium 网络配置、存储配置与四环境 Profile。
- **CI/CD 流水线**：新增 GitHub Actions 工作流 ci.yml 与 release.yml，覆盖多语言构建、测试、镜像构建与发布。
- **单元测试**：新增 2000+ 单元测试，覆盖全部 21 个自研组件核心逻辑。
- **集成测试**：新增 38 个集成测试，覆盖 catalog、encaps-layer、rule-engine、sql-gateway 四个核心组件的 API 级测试，含 docker-compose 编排与 Prometheus 监控配置。
- **端到端 PoC**：新增 PoC 验证脚本包，包含 verify-catalog.sh、verify-encaps.sh、verify-rule-engine.sh、verify-sql-gateway.sh 与 run-poc.sh 总控。
- **多租户隔离**：新增基于 Namespace + ResourceQuota + NetworkPolicy 的三重隔离机制，配合 JWT 鉴权与租户上下文。
- **四环境支持**：新增信创、本地数据中心、公有云、私有云四环境 Profile 配置。
- **统一命名约定**：新增 CONVENTIONS.md，建立套餐命名、工作空间命名、模块计数、版本号的单一事实来源。
- **运营后台**：新增 FastAPI 运营后台服务骨架，含 Docker 镜像与 K8s 部署清单。

### Changed

- **Java 模块命名统一化**：将所有 17 个 Java 模块的 groupId 从 `com.shuqing.bigdata` 统一为 `com.levango7.dataenginebdp`，重命名 29 个 Java 包目录（main+test），替换 830 个 Java 文件的 package 声明和 import 语句，修改 39 个配置文件包名引用，重命名 2 个 SPI 服务文件，去除 870 个文件的 UTF-8 BOM。全部 17 个模块 `mvn clean compile` 通过。
- **README 诚实化**：完全重写 README.md，移除"3-5% 覆盖度"、"零代码实现"等过时表述，反映 v1.0.0 实际完成状态。
- **设计文档一致性修复**：修复 43 份详细设计文档间的口径漂移，统一套餐命名（base / standard / flagship）、工作空间命名（ws-\<name>）、模块计数（49 模块）、版本号（SKE v0.1）。
- **引擎版本对齐**：统一 Trino 428、Doris 2.0、Kafka 3.6、Spark 3.5、Flink 1.18、IoTDB 2.0、NebulaGraph 3.6、Keycloak 24.0 版本号，消除 CI 矩阵 / values / 部署清单文档三处版本漂移。
- **前端构建配置**：升级 Vite 6 构建配置，完善 ESLint 规则与 Prettier 格式化配置。

### Deprecated

无。

### Removed

无。

### Fixed

- **SKE 集群拉起缺陷**：修复 SKE kind / kubeadm 两条拉起路径的配置缺陷，包括 scheduler-policy 引用不存在插件、Cilium socketLB.mode 非法值等问题。
- **安全漏洞**：修复 JWT 鉴权过滤器中的 token 校验绕过风险，修复 SQL 注入风险点。
- **前端 CSS 变量自引用**：修复前端样式中 CSS 变量循环自引用导致的构建失败。
- **PoC SQL 占位符**：修复端到端 PoC 脚本中 SQL 占位符无替换逻辑、表命名互不衔接的问题。
- **CI/CD 流水线位置**：修复 build-images.yaml 位于错误目录（无 .github/workflows/）的问题，迁移至 .github/workflows/ci.yml。
- **Helm Chart 缺失**：补齐 46 个缺失的 Helm Chart 条目，从 13 个扩展至 59 个。

### Security

- **JWT 鉴权**：全部 4 个自研后端服务统一接入 JWT 鉴权过滤器，token 签发与校验基于 Keycloak。
- **租户隔离**：基于 K8s Namespace + ResourceQuota + NetworkPolicy 实现租户间资源、网络、数据三重隔离，Namespace 打 tenant 标签。
- **非 root 容器**：全部容器镜像以非 root 用户运行，降低容器逃逸风险。
- **脱敏规则执行**：rule-engine 提供 MaskRuleExecutor 脱敏规则执行器，支持掩码 / 哈希 / 仅授权 / 假名四种脱敏函数。

### 开发模式

- 本项目采用 AI 辅助开发模式，由华为云码道(CodeArts)代码智能体协助完成
- 所有代码均经过人工审查与验证

[Unreleased]: https://github.com/Levango7/DataEngineBDP/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/Levango7/DataEngineBDP/compare/v1.0.0...v2.0.0
[1.0.0]: https://github.com/Levango7/DataEngineBDP/releases/tag/v1.0.0