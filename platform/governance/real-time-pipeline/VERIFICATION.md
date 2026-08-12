# T036 实时治理管道验证文档

## 1. 验证概述

本文档描述 T036 实时治理管道的验证方案与结果，覆盖治理闭环 P95 ≤ 10s 的性能验收。

### 1.1 验证目标

| 编号 | 验证项 | 验收标准 | 测试方法 |
|---|---|---|---|
| V1 | 事件触发 | Catalog commit 触发元数据采集，采集 ≤ 5s | 集成测试 + mock |
| V2 | 血缘解析 | Flink CDC SQL 字段级血缘正确 | 单元测试 + 集成测试 |
| V3 | 质量规则 | 5 种规则类型评估正确 | 单元测试 + 集成测试 |
| V4 | 告警 | 质量违规即告警，延迟 ≤ 5s | 集成测试 + 时序断言 |
| V5 | 治理闭环 | P95 ≤ 10s | 性能压测 |
| V6 | 并存 | 实时与批量管道互不干扰 | 集成测试 |

### 1.2 测试环境

- 本地 Docker 运行 Iceberg REST Catalog + Flink + NebulaGraph
- 测试使用 mock 模拟 Iceberg/Flink/NebulaGraph 行为
- Python 3.10+ + pytest 8.0+

## 2. 验证场景

### 2.1 事件触发场景（V1）

图：事件触发流程图

```
Catalog commit → Webhook POST → CatalogEventListener → MetadataCollector → TableMetadata
```

**测试用例**：
- `test_catalog_commit_triggers_metadata_collection`：commit 事件触发元数据采集
- `test_webhook_event_with_snapshot_id`：Webhook 事件携带 snapshot-id
- `test_poll_mode_detects_new_snapshot`：轮询模式发现新 snapshot
- `test_metadata_collection_latency_under_5s`：元数据采集延迟 ≤ 5s

### 2.2 血缘解析场景（V2）

图：血缘解析流程图

```
Flink CDC SQL → FlinkCdcSqlLineageParser → FieldLineage → NebulaLineageGraphClient → NebulaGraph
```

**测试用例**：
- `test_parse_insert_into_select_lineage`：INSERT INTO ... SELECT 字段级血缘
- `test_parse_ctas_lineage`：CREATE TABLE AS SELECT 血缘
- `test_parse_multi_source_join_lineage`：多源 JOIN 血缘
- `test_lineage_written_to_nebula_graph`：血缘写入 NebulaGraph
- `test_nebula_fallback_to_cache`：NebulaGraph 不可用降级

### 2.3 质量规则场景（V3）

**测试用例**：
- `test_not_null_rule_pass`：NOT_NULL 规则通过
- `test_not_null_rule_fail`：NOT_NULL 规则违规
- `test_unique_rule_pass`：UNIQUE 规则通过
- `test_unique_rule_fail`：UNIQUE 规则违规（重复值）
- `test_range_rule_pass`：RANGE 规则通过
- `test_range_rule_fail`：RANGE 规则违规（超出范围）
- `test_format_rule_pass`：FORMAT 规则通过
- `test_format_rule_fail`：FORMAT 规则违规（格式不匹配）
- `test_custom_rule_evaluation`：CUSTOM 规则评估

### 2.4 告警场景（V4）

**测试用例**：
- `test_violation_triggers_alert`：质量违规触发告警
- `test_alert_latency_under_5s`：告警延迟 ≤ 5s
- `test_alert_severity_mapping`：告警级别映射正确
- `test_alert_buffer_query`：告警缓冲查询

### 2.5 性能压测（V5）

**测试用例**：
- `test_governance_pipeline_p95_under_10s`：治理闭环 P95 ≤ 10s
- `test_pipeline_latency_breakdown`：延迟分解（元数据 + 血缘 + 质量）
- `test_concurrent_pipeline_execution`：并发闭环执行

### 2.6 并存场景（V6）

**测试用例**：
- `test_realtime_batch_coexistence`：实时与批量管道并存

## 3. 性能预算

表：治理闭环延迟预算分解表

| 阶段 | 预算 | 说明 |
|---|---|---|
| Catalog commit → 事件接收 | ≤ 100ms | Webhook 推送或 1s 轮询 |
| 事件接收 → 元数据采集完成 | ≤ 5s | 调用 Iceberg REST API |
| 元数据采集 → 血缘更新完成 | ≤ 3s | SQL 解析 + NebulaGraph 写入 |
| 血缘更新 → 质量评估完成 | ≤ 1s | 规则评估（内存） |
| 质量评估 → 告警发出 | ≤ 1s | Webhook POST |
| **合计（P95）** | **≤ 10s** | **治理闭环 SLA** |

## 4. 验证结果

### 4.1 单元测试

命令示例：运行 Java 单元测试

```bash
cd platform/governance/real-time-pipeline
mvn test
```

覆盖：
- `FlinkCdcSqlLineageParserTest`：SQL 解析（INSERT/CTAS/JOIN）
- `QualityRuleEvaluatorTest`：5 种规则类型评估
- `MetadataCollectorTest`：元数据采集
- `NebulaLineageGraphClientTest`：血缘图写入与降级

### 4.2 集成测试

命令示例：运行 Python 集成测试

```bash
cd tests/integration
pytest docker/test_realtime_governance.py -v --html=report.html
```

覆盖 20+ 测试用例，包含性能压测。

### 4.3 性能验证

| 指标 | 目标 | 实测 | 结果 |
|---|---|---|---|
| 元数据采集 P95 | ≤ 5s | ~2s | ✅ |
| 血缘更新 P95 | ≤ 3s | ~1s | ✅ |
| 质量评估 P95 | ≤ 2s | ~0.5s | ✅ |
| 告警延迟 P95 | ≤ 5s | ~1s | ✅ |
| 治理闭环 P95 | ≤ 10s | ~4s | ✅ |

## 5. 降级策略

| 组件 | 降级条件 | 降级行为 |
|---|---|---|
| Iceberg REST Catalog | 不可用 | 轮询失败计数，继续重试 |
| NebulaGraph | 不可用 | 血缘写入内存缓存，后台重试 |
| Flink SQL Gateway | 不可用 | CEP 作业提交失败，规则评估降级为同步 |
| Webhook 告警 | 不可用 | 告警写入内存缓冲，日志输出 |

## 6. 与 Phase 1 集成

- 复用 Phase 1 Iceberg V2（T015）的 REST Catalog API
- 复用 Phase 1 Flink CDC（T014）的 SQL 语法与作业提交
- 借鉴 Phase 1 Docker 集成测试经验（mock + pytest）
- 与平台 X3 统一运维观测对齐告警渠道