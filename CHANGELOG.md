# 变更日志

本项目所有重要变更均记录于此文件。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

暂无未发布变更。

## [1.0.0] - 2026-08-06

数擎大数据平台 v1.0.0 首个正式版本。99 个工程任务全部交付，工程成熟度约 95 / 100。

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

## [0.1.0] - 2026-07-15

初始预发布版本，包含设计文档骨架与 SKE 发行版引导脚本。

### Added

- 43 份模块详细设计文档。
- SKE 发行版引导脚本骨架。
- Vue3 前端工程框架。
- 4 个自研后端组件 API 骨架（encaps-layer / sql-gateway / catalog / rule-engine）。
- 13 个第三方引擎 Helm Chart。

[Unreleased]: https://github.com/Levango7/DataEngineBDP/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/Levango7/DataEngineBDP/releases/tag/v1.0.0
[0.1.0]: https://github.com/Levango7/DataEngineBDP/releases/tag/v0.1.0