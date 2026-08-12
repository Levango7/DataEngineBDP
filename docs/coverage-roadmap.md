# 覆盖率提升路线图

> 文档版本：v1.0 ｜ 更新日期：2026-08-13 ｜ 负责人：覆盖率门禁工程师
>
> 本文档定义 DataEngineBDP 项目从当前覆盖率水平逐步提升至 GA 标准（85%）的路线图，
> 以及配套的 CI 门禁策略调整计划。

## 1. 当前状态（2026-08-13 快照）

### 1.1 整体覆盖率

| 语言 | 当前整体覆盖率 | GA 标准 | 差距 | 达标模块数 | CI 当前阈值 |
|------|--------------|--------|------|-----------|------------|
| Java (Line) | 42.26% | 85% | -42.74% | 2/18 (11.1%) | 行≥80% / 分支≥70% |
| Java (Branch) | 26.05% | 85% | -58.95% | 0/18 (0%) | 同上 |
| Go | ~30% | 85% | -55% | 0/10 (0%) | ≥70% |
| Python | ~64% | 85% | -21% | 1/10 (10%) | ≥75% |

### 1.2 关键结论

- 当前覆盖率**远未达到 85% GA 标准**，不能直接提升 CI 阈值至 85%
- Java 分支覆盖率（26.05%）是最大短板，0 个模块达标
- Go 覆盖率（~30%）次之，0 个模块达标
- Python 相对最好（~64%），但仍有 9/10 模块未达 75% 阈值

### 1.3 CI 门禁现状（本次调整后）

| 维度 | 调整前 | 调整后 |
|------|--------|--------|
| Java 门禁覆盖范围 | 仅 encaps-layer（1 个模块） | 全量 18 个模块 |
| Go 门禁覆盖范围 | 5 个模块（catalog/vector-engine/llm-gateway/query-api/dqctl） | 全量 10 个模块 |
| Python 门禁覆盖范围 | 仅 asset-exchange（1 个模块） | 全量 10 个模块 |
| Java 阈值 | 行 80% / 分支 70%（warning 模式） | 行 80% / 分支 70%（**阻断**模式） |
| Go 阈值 | 70%（warning 模式） | 70%（**阻断**模式） |
| Python 阈值 | 75%（warning 模式） | 75%（**阻断**模式） |
| 趋势阻断 | 无 | 覆盖率下降 > 2% 阻断 CI |

## 2. 模块覆盖率明细与优先级

### 2.1 Java 模块（18 个）

| 模块 | 行覆盖率 | 分支覆盖率 | 优先级 | 备注 |
|------|---------|-----------|--------|------|
| encaps-layer | ~80% | ~70% | P2 | 已达标，维持 |
| sql-gateway | ~45% | ~25% | P1 | 核心网关，需重点补充 |
| lineage-analyzer | ~40% | ~20% | P1 | 治理核心 |
| metadata-collector | ~35% | ~15% | P1 | 治理核心 |
| real-time-pipeline | ~30% | ~15% | P0 | 实时链路，覆盖率极低 |
| storage-io | ~50% | ~30% | P1 | 存储抽象层 |
| stream-batch-scheduler | ~35% | ~20% | P0 | 调度核心 |
| flink-cdc | ~30% | ~10% | P0 | CDC 核心 |
| tag-engine | ~40% | ~25% | P1 | 标签引擎 |
| rule-engine | ~45% | ~25% | P1 | 规则引擎 |
| infra-orchestrator | ~35% | ~15% | P0 | 基础设施编排 |
| infra-provider-private | ~30% | ~10% | P0 | 私有云 provider |
| infra-provider-cloud | ~30% | ~10% | P0 | 云 provider |
| infra-provider-xinchang | ~30% | ~10% | P0 | 新昌 provider |
| federated-query | ~35% | ~15% | P1 | 联邦查询 |
| dashboard | ~40% | ~20% | P1 | FinOps 仪表盘 |
| cost-model | ~40% | ~20% | P1 | FinOps 成本模型 |
| function-runtime-java | 0% | 0% | P2 | 无测试，需从零补充 |

### 2.2 Go 模块（10 个）

| 模块 | 覆盖率 | 优先级 | 备注 |
|------|--------|--------|------|
| catalog | ~35% | P1 | 元数据目录 |
| dqctl | ~30% | P1 | 数据质量控制 |
| vector-engine | ~25% | P0 | 向量引擎核心 |
| llm-gateway | ~30% | P1 | LLM 网关 |
| query-api | ~28% | P1 | 查询 API |
| infra-provider-baremetal | ~20% | P0 | 裸金属 provider |
| karmada/api | ~35% | P1 | Karmada API |
| karmada/failover/api | ~30% | P1 | 故障转移 API |
| karmada/failover/engine | ~25% | P0 | 故障转移引擎 |
| knative/runtimes/go | ~20% | P0 | Knative Go 运行时 |

### 2.3 Python 模块（10 个）

| 模块 | 覆盖率 | 优先级 | 备注 |
|------|--------|--------|------|
| asset-exchange | ~75% | P2 | 已达标，维持 |
| nl2sql | ~60% | P1 | NL2SQL 转换 |
| chunker | ~65% | P1 | 分块器 |
| business-portal | ~55% | P1 | 业务门户 |
| ml-platform | ~50% | P0 | ML 平台 |
| knowledge-engine | ~60% | P1 | 知识引擎 |
| llmops | ~55% | P1 | LLM Ops |
| open-api-catalog | ~50% | P0 | API 目录 |
| industry-templates | ~70% | P2 | 行业模板 |
| evaluation | ~45% | P0 | 评估模块 |

## 3. 提升路线图

### Phase 1：P0 模块补充（1-2 周）

**目标**：将 7 个 P0 模块（覆盖率 0-30%）提升至 50% 以上

**范围**：
- Java：real-time-pipeline、stream-batch-scheduler、flink-cdc、infra-orchestrator、infra-provider-private、infra-provider-cloud、infra-provider-xinchang
- Go：vector-engine、infra-provider-baremetal、karmada/failover/engine、knative/runtimes/go
- Python：ml-platform、open-api-catalog、evaluation

**预期结果**：
- Java 整体覆盖率：42.26% → ~50%
- Go 整体覆盖率：~30% → ~40%
- Python 整体覆盖率：~64% → ~68%

**验收标准**：
- 每个 P0 模块行覆盖率 ≥ 50%
- CI 趋势阻断检查通过（无下降 > 2%）
- 更新基线：`bash scripts/coverage/update-baseline.sh all`

### Phase 2：P1 模块补充（1-2 月）

**目标**：将 P1 模块提升至 70% 以上，整体覆盖率达到 70%

**范围**：
- Java：sql-gateway、lineage-analyzer、metadata-collector、storage-io、tag-engine、rule-engine、federated-query、dashboard、cost-model
- Go：catalog、dqctl、llm-gateway、query-api、karmada/api、karmada/failover/api
- Python：nl2sql、chunker、business-portal、knowledge-engine、llmops

**预期结果**：
- Java 整体覆盖率：~50% → ~70%
- Go 整体覆盖率：~40% → ~65%
- Python 整体覆盖率：~68% → ~75%

**验收标准**：
- 每个 P1 模块行覆盖率 ≥ 70%
- CI 门禁阈值可从当前值提升（见 Phase 4）

### Phase 3：P2 模块补充与全量达标（3-6 月）

**目标**：所有模块覆盖率达到 85% GA 标准

**范围**：
- Java：encaps-layer（维持）、function-runtime-java（从 0% 补充）
- Python：asset-exchange（维持）、industry-templates（维持）
- 所有 P0/P1 模块从 70% 提升至 85%

**预期结果**：
- Java 整体覆盖率：~70% → 85%
- Go 整体覆盖率：~65% → 85%
- Python 整体覆盖率：~75% → 85%

**验收标准**：
- 所有模块行覆盖率 ≥ 85%
- 所有模块分支覆盖率 ≥ 85%
- CI 门禁阈值提升至 85%

### Phase 4：CI 阈值分阶段提升

> 每次提升前必须确保所有模块已达标，避免 CI 持续失败。

| 阶段 | 触发条件 | Java 阈值 | Go 阈值 | Python 阈值 | 操作 |
|------|---------|----------|---------|------------|------|
| 当前 | 基线已建立 | 行 80% / 分支 70% | 70% | 75% | 全量门禁 + 趋势阻断 |
| 4a | Phase 1 完成（P0 ≥ 50%） | 行 80% / 分支 70% | 70% | 75% | 保持，更新基线 |
| 4b | Phase 2 完成（P1 ≥ 70%） | 行 80% / 分支 75% | 75% | 75% | 提升分支阈值 + Go 阈值 |
| 4c | Phase 3 中期（整体 ≥ 80%） | 行 85% / 分支 80% | 80% | 80% | 提升至 80% |
| 4d | Phase 3 完成（整体 ≥ 85%） | 行 85% / 分支 85% | 85% | 85% | 达到 GA 标准 |

## 4. CI 门禁机制说明

### 4.1 阻断门禁（本次已实现）

- **Java**：对所有 18 个模块执行 JaCoCo 门禁，行 < 80% 或分支 < 70% 阻断 CI
- **Go**：对所有 10 个模块执行 `go test -cover`，覆盖率 < 70% 阻断 CI
- **Python**：对所有 10 个模块执行 `pytest --cov`，覆盖率 < 75% 阻断 CI

### 4.2 趋势阻断（本次已实现）

- **基线文件**：`docs/coverage-baseline/{java,go,python}.json`
- **检查逻辑**：当前覆盖率相比基线下降 > 2% 阻断 CI
- **基线更新**：覆盖率提升后执行 `bash scripts/coverage/update-baseline.sh all` 更新基线
- **注意**：切勿在覆盖率下降时更新基线，否则趋势阻断将失效

### 4.3 重要约束

- **不要直接提升 CI 阈值至 85%**：当前覆盖率远未达标，会导致 CI 持续失败
- **不要删除或降低现有门禁**：门禁是防止覆盖率倒退的关键机制
- **每次提升阈值前必须验证所有模块已达标**：否则 CI 会阻断所有 PR

## 5. 行动项

| 编号 | 行动 | 负责人 | 截止日期 | 状态 |
|------|------|--------|---------|------|
| A1 | 扩大 CI 覆盖率门禁至全量模块 | 覆盖率门禁工程师 | 2026-08-13 | ✅ 已完成 |
| A2 | 添加覆盖率趋势阻断机制 | 覆盖率门禁工程师 | 2026-08-13 | ✅ 已完成 |
| A3 | 补充 storage-io / function-runtime-java 的 JaCoCo 配置 | 覆盖率门禁工程师 | 2026-08-13 | ✅ 已完成 |
| A4 | 创建覆盖率提升路线图 | 覆盖率门禁工程师 | 2026-08-13 | ✅ 已完成 |
| A5 | Phase 1：补充 P0 模块测试 | 开发团队 | 2026-08-27 | ⏳ 待启动 |
| A6 | Phase 2：补充 P1 模块测试 | 开发团队 | 2026-10-13 | ⏳ 待启动 |
| A7 | Phase 3：全量补充 P2 模块测试 | 开发团队 | 2027-02-13 | ⏳ 待启动 |
| A8 | Phase 4：分阶段提升 CI 阈值至 85% | 覆盖率门禁工程师 | 2027-02-28 | ⏳ 待启动 |

## 6. 参考资料

- Task 4 覆盖率分析报告（覆盖率门禁工程师，2026-08-13）
- CI 工作流配置：`.github/workflows/ci.yml`
- 覆盖率基线：`docs/coverage-baseline/`
- 基线更新脚本：`scripts/coverage/update-baseline.sh`
- JaCoCo Maven 插件配置参考：`platform/encaps-layer/pom.xml`