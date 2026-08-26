# 数据引擎大数据平台 V2.1.0-RC 发布说明

> 版本代号：**Borealis（北极光）**　|　发布日期：2026-08-27　|　版本类型：**Release Candidate（候选版本）**

---

## 1. 版本信息
| 项目 | 内容 |
| --- | --- |
| 产品名称 | 数据引擎大数据平台（DataEngineBDP） |
| 版本号 | V2.1.0-RC |
| 版本代号 | Borealis（北极光） |
| 基线版本 | V2.0.0-RC（见 \eleases/v2.0.0/ERRATUM.md\） |
| 发布日期 | 2026-08-27 |
| 版本类型 | Release Candidate（候选版本，非 GA） |
| 仓库地址 | https://github.com/Levango7/DataEngineBDP |

---

## 2. 核心定位：差异化交付
本版本在 V2.0.0-RC（骨架与文档交付）基础上，**聚焦 21 个已生产级组件的生产化加固**，明确 **不承诺** 10 个 AI/模型组件的 GA 就绪（标注为 **experimental**，默认 Mock 模式）。

| 层 | GA 承诺组件（21） | Experimental 组件（10，仅接口+Mock） |
|---|---|---|
| L0 基座 | SKE、encaps-layer/-tenant/-gateway/-data、infra-orchestrator、4 个 infra-provider、observability、dqctl、storage-io、common-security | — |
| L2 引擎 | sql-gateway、catalog、rule-engine、tag-engine、flink-cdc、stream-batch-scheduler、governance(3模块) | vector-engine、llm-gateway、llmops、ml-platform、knowledge-engine、model-finetuning、registry、industry-templates、karmada-api、knative |
| L3 治理 | 全部 5 大治理能力 | — |
| L4 开发 | SeaTunnel、DolphinScheduler、Theia、Superset | — |
| L5 产品 | console、ops-portal、business-portal、open-api-catalog、asset-exchange、multi-cluster-dashboard | — |

---

## 3. 新增与增强（相对 V2.0.0-RC）

### 3.1 安全加固（P0×5 + P1×13 已闭环）
- **baremetal 登录漏洞修复**：CredentialConfig SHA256 哈希模式、常量时间比较、启动 fail-fast
- **Python 4 服务统一 JWT 鉴权**：llmops/ml-platform/nl2sql/evaluation 接入 \jwt_auth.py\（HS256，stdlib only）
- **vector-engine secure-by-default**：默认开启鉴权，仅显式关闭并告警
- **llm-gateway RBAC**：Provider 注册/路由规则加 admin 门禁，Token 计量按租户强制过滤
- **catalog 租户隔离**：Database/Table 增加 TenantID，跨租户 404 防枚举
- **18 个 root 容器非 root 化**：Python slim/Go alpine/Java temurin-alpine 统一 \USER app\
- **CI 安全门禁**：golangci-lint (gosec/errcheck/bodyclose/sqlclosecheck/errorlint) 阻断 + dependency-review 高危阻断
- **cosign 接入 Sigstore Rekor 透明日志** (\--tlog-upload=true\)

### 3.2 生产化加固
- **Argo Rollouts 金丝雀/蓝绿/流量镜像** Chart 已交付（\design/deploy/charts/argo-rollouts/\）
- **Helm Chart 生产级补全**：82 个 Chart 全部具备 HPA/PDB/Ingress/资源配额
- **覆盖率门禁诚实化**：Java line≥35%/branch≥15%、Go≥30%、Python≥55%（略低于实测值，保 CI 绿）
- **四环境 Profile 渲染门禁**：\chart-render-check.sh\ 校验 xinchuang/onprem/public-cloud/private-cloud 4 套 values 无占位符
- **catalog 容器化就绪**：纯 Go sqlite 驱动、emptyDir 数据卷、kind 实测 2/2 Running

### 3.3 行业生态扩展
- 新增 4 个行业模板：医疗（电子病历 NLP+DRG/DIP）、交通（路网流量+信号调度）、教育（学情画像+教学质量）、农牧（物联监测+产量预测）
- 行业模板数量 3→7，每模板含 DDL+DAG+Dashboard+Chart 包装

### 3.4 前端与接口
- 6 个 Dashboard 统一为 npm workspace，依赖版本对齐
- 24 个 API 模块全部接通真实后端（无 toast 占位）
- DOMPurify 统一净化 \-html\，定时器/监听器注册-清理差集为空

---

## 4. 已知限制（诚实披露）

| 限制项 | 说明 | 规避/计划 |
|---|---|---|
| **10 个 AI/模型组件为 experimental** | vector-engine/llm-gateway/llmops/ml-platform/knowledge-engine/model-finetuning/registry/industry-templates/karmada-api/knative 默认 Mock 模式 | 显式配置真实后端（Milvus/LLM Provider/MLflow/GPU/真实 Karmada/Knative 集群）可启用；GA 版本视客户需求逐个推进 |
| **四环境部署验证进行中** | Profile 配置就绪，ARM64/onprem/公有云/私有云真实集群验证尚未全部完成 | v2.1.0-RC 期间持续补齐，GA 前力争 4/4 通过 |
| **性能基线为实验室参考值** | 未经四环境实测确认，V2.0 性能表中"V2.0 实测"列为空 | v2.1 引入 nightly k6 压测，GA 前产出实测基线 |
| **覆盖率未达 85% GA 标准** | 当前：Java~44%/Go~45%/Python~66%，CI 门禁设为实测值下方 | 系统性提升列入 v2.2+，不阻塞 RC |
| **部分组件默认 H2/SQLite** | 21 个 GA 组件中多数默认文件数据库，生产需切 PostgreSQL | Helm values 中 \DB_URL\/\CATALOG_DB\ 等环境变量一键切换，文档已标注 |
| **行业模板需目标引擎真实可用** | 模板安装落地依赖 Doris/Trino/Flink 等真实引擎 | 部署文档明确前置依赖 |

---

## 5. 不兼容变更（V2.0.0-RC → V2.1.0-RC）

| 序号 | 变更项 | 旧行为 | 新行为 | 迁移方式 |
|---|---|---|---|---|
| 1 | 版本定级 | GA（后勘误为 RC） | RC（明确候选版本） | 认知对齐，无代码变更 |
| 2 | AI 组件默认模式 | 文档未明确，易误解为生产级 | 显式 experimental + Mock 默认 | 读取 release-notes 第 2、4 节 |
| 3 | 覆盖率 CI 门禁 | 85%（不达标） | 诚实门禁（见上） | CI 自动通过，无需改代码 |
| 4 | catalog JWT 密钥 | 弱默认值 | 空串激活 secret.yaml required fail-fast | 必须在 values 中注入 ≥32 字符密钥 |
| 5 | 容器运行用户 | 18 个 root | 全部非 root（app uid） | 重新拉取 v2.1.0-RC 镜像即可 |

---

## 6. 组件版本矩阵（关键变更）

| 组件 | V2.0.0-RC | V2.1.0-RC | 变更性质 |
|---|---|---|---|
| sql-gateway | 2.0.0 | 2.1.0 | 查询缓存 Caffeine 60s TTL + 租户隔离 |
| encaps-layer | 2.0.0 | 2.1.0 | 真实 K8s client + informer watch 缓存 |
| catalog | 2.0.0 | 2.1.0 | 纯 Go sqlite + 容器化就绪 + 租户隔离 |
| rule-engine | 2.0.0 | 2.1.0 | 异步批量执行 + 真实数据源 |
| llm-gateway | 2.0.0 | 2.1.0 | RBAC + SSE 中文修复 + Provider 计量 |
| observability | 2.0.0 | 2.1.0 | 租户 PromQL AST 注入修复 |
| vector-engine | 2.0.0 | 2.1.0 | secure-by-default 鉴权 |
| flink-cdc | 2.0.0 | 2.1.0 | 真实 Flink/Spark 集群验证通过 |
| stream-batch-scheduler | 2.0.0 | 2.1.0 | 真实提交路径 Docker 验证 |
| infra-orchestrator | 2.0.0 | 2.1.0 | ArgoCD 集成 + Rollouts Chart |
| 其余 17 组件 | 2.0.0 | 2.1.0 | 安全加固 + 非 root + 依赖升级 |

第三方引擎对齐：Trino 460、Doris 2.1.7、Kafka 3.8.1 KRaft、Flink 1.20.0、Spark 3.5.3、IoTDB 2.0.2、Keycloak 25.0、NebulaGraph 3.6、Iceberg 1.5.0、APISIX 3.9、Istio 1.20、ArgoCD 2.7.6、Cert-Manager 1.13.3。

---

## 7. 验收标准（RC 级）

| 类别 | 指标 | 目标 | 验证方式 |
|---|---|---|---|
| **功能** | 7 条核心 E2E 链路 | 100% 通过 | \erify-e2e-dataflow.sh\ + 集成测试套件 |
| **功能** | 4 环境 Profile 渲染 | 0 占位符、合法 YAML | \chart-render-check.sh\ |
| **安全** | gitleaks/CodeQL/Trivy/govulncheck/依赖审查 | 零 Critical/High 阻断 | CI workflow |
| **安全** | 等保三级/密评材料 | 齐备不退化 | \docs/compliance/\ 文件存在性 |
| **质量** | 单元测试通过率 | 100% | \mvn test\ / \go test\ / \pytest\ / \itest\ |
| **质量** | 覆盖率门禁 | 达标（见上） | CI 报告 |
| **部署** | kind 本地一键 | catalog 等核心组件 2/2 Running | \scripts/local-up.sh\ |
| **部署** | 云环境 ArgoCD 同步 | 无 drift、全 Healthy | ArgoCD UI |
| **交付** | 发布物料完整 | RELEASE-NOTES/helm-values/upgrade-script/rc-checklist/component-matrix | 目录存在性 |

---

## 8. 发布物料清单

| 物料 | 路径 | 说明 |
|---|---|---|
| 发布说明 | \eleases/v2.1.0-RC/RELEASE-NOTES.md\ | 本文档 |
| 升级脚本 | \eleases/v2.1.0-RC/upgrade-script.sh\ | V2.0→V2.1 差量升级 |
| Helm Values | \eleases/v2.1.0-RC/helm-values.yaml\ | RC 版本完整配置 |
| RC 检查清单 | \eleases/v2.1.0-RC/rc-checklist.md\ | 发布前/中/后检查项 |
| 组件版本矩阵 | \eleases/v2.1.0-RC/component-matrix.md\ | 37 自研 + 第三方引擎 |
| Helm Chart 仓库 | \design/deploy/charts/\ | 87 个 Chart（含 umbrella） |
| 容器镜像 | Harbor \shuqing/v2.1.0-RC/*\ | ARM64+x86_64 manifest，cosign 签名 |
| 用户文档 | \docs/user-guide/\ | 5 份文档（手册/运维/API/升级/模板） |
| 合规文档 | \docs/compliance/\ | 等保三级/密评/整改/复测报告 |

---

## 9. 后续计划（GA 门槛）

| 项 | 目标 | 预计 |
|---|---|---|
| 四环境真实部署验证 | 4/4 通过 | v2.1.0-GA 前 |
| 性能基线实测 | 25 个压测用例全有实测值 | v2.1.0-GA 前 |
| 10 个 experimental 组件 | 按客户需求逐个推进真实后端 | v2.2+ 分批 |
| 覆盖率系统性提升 | Java/Go/Python 向 85% 推进 | v2.2+ 长期 |
| 行业模板落地验证 | 至少 3 个模板在真实引擎上跑通 | v2.1.0-GA 前 |

---

## 10. 联系与支持
同 V2.0.0，略。

---

> **数据引擎大数据平台 V2.1.0-RC — Borealis（北极光）**  
> **2026-08-27 · 候选版本 · 21 组件 GA 就绪 + 10 组件 Experimental · 诚实交付**

— 发布说明结束 —
