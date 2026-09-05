# MERGE-LOG：AutoBatch → DataEngineBDP

> 归并事实与事后审计日志。AutoBatch（独立原型仓库 `Levango7/AutoBatch`）于 **2026-09-04** 迁入本仓库 `platform/batch-pipeline`，源仓库已加归档横幅并打 tag `v-final`（归档日 HEAD：`765e50e`）。

## 归并映射

| 源（AutoBatch 仓库） | 目标（BDP） | 处置 |
| --- | --- | --- |
| `src/pipeline.py` + `src/stages/*`（五阶段骨架） | `platform/batch-pipeline/batch_pipeline/` | 原样吸收（包名改 `batch_pipeline`） |
| `src/quality.py`（8 类 DQ 规则引擎） | 同上，经 rule-engine 的 `BatchPipelineRuleAdapter` 与平台规则域对齐（规则管理事实源仍归 rule-engine，batch-pipeline 做行级求值） | 吸收+桥接 |
| `src/state.py` / `src/iceberg.py`（水位 / snapshot diff） | 同上 | 吸收 |
| `src/openlineage.py` + `src/lineage.py` | OpenLineage 事件端到端至 `lineage-analyzer`；AutoBatch 自建血缘图随归并退役 | 保留发射、拆除自画 |
| `src/metrics.py` / `monitoring.py` | 逐批次扁平 key 直接接 Prometheus 抓取体系 | 吸收 |
| `src/generator.py`、`dashboard/`、`docker/spark-cluster/`、`benchmarks/` | — | 删除（示例生成器由业模板/控制台承接，自建集群被平台基础设施取代） |
| — | `batch_pipeline/api/`（FastAPI 提交/查询壳）+ `tenant.py` | **新增**：JWT（`AUTH_MODE`）、`run/<tenant>/<batch>/` 路径分区，对接平台多租户 |

## 归并动机

AutoBatch 是"单机时代"的五阶段批处理流水线原型（python / polars / spark × local_csv / parquet / iceberg 正交后端矩阵，RevPhase 1-5）。其终态（Spark + Iceberg + MinIO 分布式湖平台）与 BDP 交付栈同构。归并消除了平台内第二套"轻量/零依赖"批处理实现，保全其一径让平台补齐小客户 / 无 K8s / POC 场景的批处理拔插能原真的单机执行核。原文详述见 `Levango7/AutoBatch` 仓库 README 归档横幅。

## 验证（归并时点）

- 迁入提交 `03c9129f` 自报：pytest 472 passed / 22 skipped（`-k "not cluster"`），Python 3.14；
- 迁移后等价性抽样（表结构逐字节、DQ Score 落区间、增量水位两阶段提交）达到与 AutoBatch 441 用例相同的等价性覆盖面；
- CI 独立腿 `batch-pipeline-test`（库依赖多、需 MinIO service，不走通用 python-test 矩阵）。
