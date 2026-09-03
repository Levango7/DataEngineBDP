# batch-pipeline · 数据平台批处理流水线引擎

`platform/batch-pipeline` 是 DataEngineBDP 的**批处理数据流水线执行引擎**：接入（ingest）→ 质量校验（validate）→ 清洗（clean）→ 计算（compute）→ 输出（output）五阶段，内置批次台账、质量规则引擎（8 类规则 + DQ Score + 坏行隔离）、高水位增量、断点续跑、血缘（OpenLineage）与指标导出。

与平台的关系：

- **stream-batch-scheduler**（Java）负责 DAG 编排与作业管理，本模块是其批处理任务的执行内核：已通过 `BATCH_PIPELINE` TaskChannel 对接（REST 提交 + JWT + 轮询，详见 stream-batch-scheduler README §3.4）
- **rule-engine** 是质量规则的单一事实源，本模块的 8 类规则是批量执行方言：已由 rule-engine 的 `BatchPipelineRuleAdapter` 把模板规则翻译为本模块 `config.quality.rules` 片段（M4 归一）
- **存储栈复用平台设施**：`storage.backend="iceberg"` + REST catalog 对接平台 iceberg-rest，对象存储对接 MinIO
- **血缘**：OpenLineage v1 事件（NDJSON 落盘 + 可选 HTTP 上报）；`openlineage.endpoint` 指向 lineage-analyzer `POST /api/v1/lineage/events` 即完成统一血缘归集（M4 归一）
- **指标**：每批次 metrics.json 扁平 key（`stage_validate_duration_ms` 等）直连 Prometheus；内置 `/health` 健康端点

M4 计算链路分工（归一结论）：批处理=本模块；实时=real-time-pipeline；联邦查询=federated-query；规则管理与模板=rule-engine。

## 能力矩阵（两个正交维度，任意组合）

| 维度 | 取值 | 说明 |
|---|---|---|
| `engine.backend` | `python` / `polars` / `spark` | 计算引擎；python 路径零第三方依赖 |
| `storage.backend` | `local_csv` / `parquet`（本地或 S3/MinIO）/ `iceberg` | 存储介质；iceberg 带 ACID、time travel、snapshot diff 增量 |

增量模式：`incremental.enabled=true` + `mode=high_watermark`（自建水位，两阶段提交、失败不推进）或 `mode=iceberg_snapshot_diff`（直接读湖表新增文件，IO 与增量行数成正比）。

## 快速开始

```bash
# 零依赖路径（python + local_csv）
python main.py --config config/pipeline_small.json

# 全量测试（平台 CI 与本地一致；spark 集群用例需本地 Docker，排除）
python -m pytest tests/ -q -k "not cluster"
```

产物：`run/<batch_id>/`（每批次五阶段目录 + manifest.json + metrics.json + 质量报告），最新批次指针 `run/latest.json`。

## 运行时目录（不入库）

`run/`、`state/`（水位与聚合）、`data/raw/`（演示生成器输出）均为运行时产物，已在 .gitignore 排除。`src→batch_pipeline` 包内的 `generator.py` 仅服务于演示数据与测试自持。

## 配置

主配置 `config/pipeline.json`，小规模示例 `config/pipeline_small.json`，监控阈值 `config/monitoring.json`。全部字段经 pydantic schema 校验（`config_schema.py`，pydantic 缺失时自动降级跳过）。

## 详细文档

- `docs/runbook.md` — 运行与扩展手册（含 Spark 集群、Iceberg REST catalog、故障恢复）
- `docs/evolution.md` — 演进记录与设计取舍

## 来源说明

本模块自独立原型项目（v1.5.0，Apache-2.0）迁入，迁移时改名 `src` → `batch_pipeline` 并按平台约定调整；历史交付验证记录见 `docs/evolution.md`。
