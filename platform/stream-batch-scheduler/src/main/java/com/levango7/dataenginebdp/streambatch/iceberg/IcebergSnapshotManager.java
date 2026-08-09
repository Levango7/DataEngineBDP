package com.levango7.dataenginebdp.streambatch.iceberg;

import com.levango7.dataenginebdp.streambatch.model.SnapshotRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Iceberg snapshot 管理器。
 *
 * <p>核心职责：
 * <ol>
 *   <li><b>获取当前 snapshot</b> — 查询 Iceberg 表当前 snapshot-id（最新已提交 snapshot）</li>
 *   <li><b>锁定批读 snapshot</b> — 批作业启动时记录当前 snapshot-id，整个批作业期间读该固定值</li>
 *   <li><b>获取流读起点</b> — 流作业从指定 snapshot 或最新 snapshot 开始流读</li>
 *   <li><b>验证 snapshot 隔离</b> — 比对批节点使用的固定 snapshot 与流节点使用的最新 snapshot，
 *       确认数据一致（snapshot 隔离语义）</li>
 * </ol>
 *
 * <p><b>snapshot 隔离原理</b>：Iceberg 表的每个 commit 产生一个不可变 snapshot。
 * Spark 批作业锁定 snapshot-id=S0 后，即使 Flink 流作业持续写入产生 S1、S2...，
 * 批作业始终读 S0 的数据视图，不受影响。流作业读最新 snapshot 实时消费增量。
 * 两者基于同一 Iceberg 表但 snapshot 隔离，保证批流数据一致（批读历史快照、流读实时增量）。
 *
 * <p>本实现提供 in-memory snapshot 注册表（适用于单实例部署）；
 * 生产环境可通过 JDBC/HBase 持久化 snapshot 锁定信息。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IcebergSnapshotManager {

    private final SnapshotIsolationConfig config;

    /** 表 → 当前锁定的批读 snapshot（in-memory 注册表）。 */
    private final Map<String, SnapshotRef> lockedBatchSnapshots = new ConcurrentHashMap<>();

    /** 表 → 最新 snapshot（模拟 Iceberg 表当前 snapshot，实际从 Iceberg API 获取）。 */
    private final Map<String, SnapshotRef> latestSnapshots = new ConcurrentHashMap<>();

    /** snapshot 自增计数器（模拟 Iceberg snapshot-id 递增）。 */
    private long snapshotIdCounter = 1000L;

    /**
     * 获取表当前最新 snapshot。
     *
     * <p>实际实现通过 Iceberg {@code Table.currentSnapshot()} 获取；
     * 本实现从内存注册表读取，若不存在则初始化一个 snapshot。
     *
     * @param table Iceberg 表全名（database.table）
     * @return 最新 snapshot 引用
     */
    public SnapshotRef getLatestSnapshot(String table) {
        return latestSnapshots.computeIfAbsent(table, t -> {
            long snapId = nextSnapshotId();
            SnapshotRef ref = SnapshotRef.builder()
                    .table(t)
                    .snapshotId(snapId)
                    .timestampMs(Instant.now().toEpochMilli())
                    .latest(true)
                    .summary(new HashMap<>())
                    .build();
            log.info("初始化表 {} 最新 snapshot: id={}", t, snapId);
            return ref;
        });
    }

    /**
     * 锁定批读 snapshot（批作业启动时调用）。
     *
     * <p>记录表当前最新 snapshot 作为批作业的固定读取点，
     * 整个批作业期间通过 {@link #getLockedBatchSnapshot(String)} 获取该固定值。
     *
     * @param table Iceberg 表全名
     * @return 锁定的 snapshot 引用
     */
    public SnapshotRef lockBatchSnapshot(String table) {
        SnapshotRef latest = getLatestSnapshot(table);
        SnapshotRef locked = SnapshotRef.builder()
                .table(table)
                .snapshotId(latest.getSnapshotId())
                .timestampMs(latest.getTimestampMs())
                .latest(false)
                .summary(latest.getSummary() != null ? new HashMap<>(latest.getSummary()) : new HashMap<>())
                .build();
        lockedBatchSnapshots.put(table, locked);
        log.info("锁定表 {} 批读 snapshot: id={}（批作业期间固定读取此 snapshot）", table, locked.getSnapshotId());
        return locked;
    }

    /**
     * 锁定批读指定 snapshot（EXPLICIT 模式，由 DAG 节点显式指定）。
     *
     * @param table      Iceberg 表全名
     * @param snapshotId 显式指定的 snapshot-id
     * @return 锁定的 snapshot 引用
     */
    public SnapshotRef lockBatchSnapshot(String table, long snapshotId) {
        SnapshotRef locked = SnapshotRef.builder()
                .table(table)
                .snapshotId(snapshotId)
                .timestampMs(Instant.now().toEpochMilli())
                .latest(false)
                .summary(new HashMap<>())
                .build();
        lockedBatchSnapshots.put(table, locked);
        log.info("显式锁定表 {} 批读 snapshot: id={}", table, snapshotId);
        return locked;
    }

    /**
     * 获取表已锁定的批读 snapshot。
     *
     * @param table Iceberg 表全名
     * @return 锁定的 snapshot；未锁定返回 {@code null}
     */
    public SnapshotRef getLockedBatchSnapshot(String table) {
        return lockedBatchSnapshots.get(table);
    }

    /**
     * 获取流读起点 snapshot。
     *
     * <p>根据 {@link SnapshotIsolationConfig#getStreamSnapshotMode()} 决定：
     * <ul>
     *   <li>{@code LATEST} — 返回当前最新 snapshot</li>
     *   <li>{@code FROM_TIMESTAMP} — 返回指定时间戳之后的 snapshot（简化为最新）</li>
     * </ul>
     *
     * @param table Iceberg 表全名
     * @return 流读起点 snapshot
     */
    public SnapshotRef getStreamStartSnapshot(String table) {
        SnapshotRef start = getLatestSnapshot(table);
        log.info("表 {} 流读起点 snapshot: id={}, mode={}", table, start.getSnapshotId(),
                config.getStreamSnapshotMode());
        return start;
    }

    /**
     * 模拟 Flink 流写入产生新 snapshot（流作业 commit 后调用）。
     *
     * <p>实际场景由 Flink Iceberg Sink commit 自动产生新 snapshot；
     * 本实现递增 snapshot-id 并更新最新 snapshot 注册表。
     *
     * @param table Iceberg 表全名
     * @return 新产生的 snapshot
     */
    public SnapshotRef commitStreamSnapshot(String table) {
        long snapId = nextSnapshotId();
        SnapshotRef newSnap = SnapshotRef.builder()
                .table(table)
                .snapshotId(snapId)
                .timestampMs(Instant.now().toEpochMilli())
                .latest(true)
                .summary(new HashMap<>())
                .build();
        latestSnapshots.put(table, newSnap);
        log.info("表 {} 流写入产生新 snapshot: id={}（批读仍锁定旧 snapshot，隔离生效）",
                table, snapId);
        return newSnap;
    }

    /**
     * 验证 snapshot 隔离（批流一致验证）。
     *
     * <p>检查批节点使用的固定 snapshot 与流节点使用的最新 snapshot 是否满足隔离语义：
     * <ul>
     *   <li>批 snapshot-id ≤ 流 snapshot-id（批读的是历史快照，流读的是最新）</li>
     *   <li>批 snapshot 时间戳与流 snapshot 时间戳差在容忍度内（可选）</li>
     * </ul>
     *
     * @param table           Iceberg 表全名
     * @param batchSnapshotId 批节点使用的 snapshot-id
     * @param streamSnapshotId 流节点使用的 snapshot-id
     * @return 验证结果描述（含通过/失败原因）
     */
    public SnapshotIsolationResult verifySnapshotIsolation(String table, long batchSnapshotId, long streamSnapshotId) {
        SnapshotIsolationResult result = new SnapshotIsolationResult();
        result.setTable(table);
        result.setBatchSnapshotId(batchSnapshotId);
        result.setStreamSnapshotId(streamSnapshotId);

        if (batchSnapshotId > streamSnapshotId) {
            result.setValid(false);
            result.setDetail(String.format(
                    "snapshot 隔离验证失败：批 snapshot-id(%d) > 流 snapshot-id(%d)，"
                            + "批读不应超前于流读", batchSnapshotId, streamSnapshotId));
            log.warn(result.getDetail());
            return result;
        }

        SnapshotRef batchRef = lockedBatchSnapshots.get(table);
        SnapshotRef streamRef = latestSnapshots.get(table);
        if (batchRef != null && streamRef != null) {
            long timeDiff = streamRef.getTimestampMs() - batchRef.getTimestampMs();
            result.setTimestampDiffMs(timeDiff);
            if (timeDiff < 0) {
                result.setValid(false);
                result.setDetail(String.format(
                        "snapshot 隔离验证失败：流 snapshot 时间戳早于批 snapshot，timeDiff=%dms", timeDiff));
                log.warn(result.getDetail());
                return result;
            }
            if (config.getIsolationToleranceMs() > 0 && timeDiff > config.getIsolationToleranceMs()) {
                result.setValid(false);
                result.setDetail(String.format(
                        "snapshot 隔离验证失败：批流时间差 %dms 超过容忍度 %dms",
                        timeDiff, config.getIsolationToleranceMs()));
                log.warn(result.getDetail());
                return result;
            }
        }

        result.setValid(true);
        result.setDetail(String.format(
                "snapshot 隔离验证通过：批 snapshot-id=%d（固定快照），流 snapshot-id=%d（最新），"
                        + "批读历史快照、流读实时增量，数据一致", batchSnapshotId, streamSnapshotId));
        log.info(result.getDetail());
        return result;
    }

    /**
     * 构建 Spark 批读 Iceberg 的配置（固定 snapshot）。
     *
     * <p>Spark 读 Iceberg 固定 snapshot 的配置项：
     * <pre>
     * spark.sql.extensions = org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions
     * spark.sql.catalog.shuqing_catalog = org.apache.iceberg.spark.SparkCatalog
     * spark.sql.catalog.shuqing_catalog.type = hive
     * spark.sql.catalog.shuqing_catalog.uri = thrift://...
     * # 固定 snapshot：通过 snapshot-id 参数
     * SELECT * FROM shuqing_catalog.db.table.history(snapshot_id => &lt;batchSnapshotId&gt;)
     * </pre>
     *
     * @param table           Iceberg 表全名
     * @param batchSnapshotId 批读固定 snapshot-id
     * @return Spark 配置键值对
     */
    public Map<String, String> buildSparkBatchConfig(String table, long batchSnapshotId) {
        Map<String, String> sparkConf = new HashMap<>(config.buildCatalogProperties());
        sparkConf.put("spark.sql.extensions",
                "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions");
        sparkConf.put("spark.sql.catalog." + config.getCatalogName(),
                "org.apache.iceberg.spark.SparkCatalog");
        sparkConf.put("spark.sql.catalog." + config.getCatalogName() + ".type",
                config.getCatalogType());
        sparkConf.put("spark.sql.catalog." + config.getCatalogName() + ".uri",
                config.getCatalogUri());
        sparkConf.put("spark.sql.catalog." + config.getCatalogName() + ".warehouse",
                config.getWarehouse());
        // 固定 snapshot 标记（实际通过 SQL history(snapshot_id => ...) 引用）
        sparkConf.put("__iceberg_batch_snapshot_id__", String.valueOf(batchSnapshotId));
        sparkConf.put("__iceberg_batch_table__", table);
        log.debug("构建 Spark 批读配置: table={}, snapshotId={}", table, batchSnapshotId);
        return sparkConf;
    }

    /**
     * 构建 Flink 流读 Iceberg 的配置（最新 snapshot / streaming）。
     *
     * <p>Flink 流读 Iceberg 的配置项：
     * <pre>
     * connector = iceberg
     * type = hive
     * uri = thrift://...
     * warehouse = s3://...
     * stream-mode = true（持续读最新 snapshot）
     * </pre>
     *
     * @param table Iceberg 表全名
     * @return Flink 配置键值对
     */
    public Map<String, String> buildFlinkStreamConfig(String table) {
        Map<String, String> flinkConf = new HashMap<>(config.buildCatalogProperties());
        flinkConf.put("connector", "iceberg");
        flinkConf.put("stream-mode", "true");
        flinkConf.put("__iceberg_stream_table__", table);
        SnapshotRef startSnap = getStreamStartSnapshot(table);
        flinkConf.put("__iceberg_stream_start_snapshot_id__",
                String.valueOf(startSnap.getSnapshotId()));
        log.debug("构建 Flink 流读配置: table={}, startSnapshotId={}", table, startSnap.getSnapshotId());
        return flinkConf;
    }

    private synchronized long nextSnapshotId() {
        return ++snapshotIdCounter;
    }
}