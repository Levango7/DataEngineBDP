# iceberg-compaction

DataEngineBDP Iceberg 小文件合并 + CBO 统计信息自动采集（v2.1 性能调优）。

## 功能

### 1. Iceberg 小文件合并

- **调度**：CronJob 每 6 小时执行一次（可配置）
- **策略**：binpack（默认）/ sort / rewrite_data_files
- **参数**：
  - `smallFileThresholdMB`：小文件阈值（默认 10MB），小于此值的文件视为小文件
  - `targetFileSizeMB`：合并后目标文件大小（默认 128MB）
  - `maxFilesPerCompaction`：单次合并最大文件数（默认 5000）
  - `parallelism`：合并并行度（默认 4）
- **快照清理**：过期快照 + 孤儿文件自动清理
- **实现**：调用 Trino `system.rewrite_data_files` / `system.expire_snapshots` / `system.remove_orphan_files` 过程

### 2. CBO 统计信息自动采集

- **调度**：CronJob 每 3 小时采集一次（可配置）
- **模式**：
  - `full`：全量采集（精确但开销大）
  - `sample`：采样采集（默认，采样率 10%）
- **统计类型**：rowCount / distinctValues / nullCount / minMax / dataSize
- **列级统计**：支持列级 distinct values / null count 采集
- **实现**：调用 Trino `ANALYZE` 语句

## 部署

```bash
helm install iceberg-compaction design/deploy/charts/iceberg-compaction \
  -f design/deploy/values/iceberg-compaction-values.yaml \
  -n data-engine
```

## 验证

```bash
kubectl get cronjobs -n data-engine
kubectl get jobs -n data-engine
```

## 配置

详见 [values.yaml](values.yaml)。