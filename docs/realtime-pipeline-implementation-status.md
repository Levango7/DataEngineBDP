# real-time-pipeline 实现状态报告

**日期**：2026-09-02
**Sprint**：1.2
**结论**：本模块为**真实实现**（非骨架）。此前的「30 行骨架」判断仅基于 Application 主类，
未读取 service 层代码，已更正。

## 1. 服务类实现清单（全部真实实现）

| 类 | 文件 | 行数 | 依赖 | 状态 |
|----|------|------|------|------|
| MetadataCollector | catalog/MetadataCollector.java | 239 | IcebergRestCatalogClient, MeterRegistry | 真实 |
| IcebergRestCatalogClient | catalog/IcebergRestCatalogClient.java | 284 | RestClient | 真实 |
| RealTimeLineageAnalyzer | lineage/RealTimeLineageAnalyzer.java | 150 | FlinkCdcSqlLineageParser, NebulaLineageGraphClient | 真实 |
| FlinkCdcSqlLineageParser | lineage/FlinkCdcSqlLineageParser.java | 373 | 无（纯正则） | 真实 |
| NebulaLineageGraphClient | lineage/NebulaLineageGraphClient.java | 234 | RestClient | 真实（含降级缓存） |
| StreamingQualityRuleEngine | quality/StreamingQualityRuleEngine.java | 186 | QualityRuleEvaluator, QualityAlertEmitter | 真实 |
| QualityRuleEvaluator | quality/QualityRuleEvaluator.java | 251 | 无 | 真实 |
| GovernancePipelineOrchestrator | pipeline/GovernancePipelineOrchestrator.java | 265 | RealTimeLineageAnalyzer, StreamingQualityRuleEngine | 真实 |
| GovernanceController | controller/GovernanceController.java | 247 | 上述服务 | 真实 |
| HealthController | controller/HealthController.java | 30 | 无 | 真实 |

## 2. 单元测试覆盖（src/test，5 个）

- controller/HealthControllerTest.java
- lineage/FlinkCdcSqlLineageParserTest.java
- model/FieldLineageTest.java
- quality/QualityRuleConditionTest.java
- quality/QualityRuleEvaluatorTest.java

## 3. 外部依赖与降级策略

| 依赖 | 配置键 | 默认值 | 降级行为 |
|------|--------|--------|----------|
| Iceberg REST Catalog | governance.iceberg.rest-catalog-url | http://localhost:8181 | 失败返回 null/空 |
| NebulaGraph | governance.nebula.graphd-host/port | localhost:9669 | 写入失败降级内存缓存 |
| Flink | governance.flink.sql-gateway-url | http://localhost:8083 | 仅配置，CEP 作业为 Phase 2 |

## 4. 缺失功能（Phase 2+ 计划）

- Flink CEP 流式评估（当前仅同步 REST 模式）
- 血缘图一致性周期校验
- webhook 事件推送（当前仅轮询）
- 国密合规（Phase 4）

## 5. 结论

模块可部署（需外部依赖配置），不需 @Profile("stub") 标记。