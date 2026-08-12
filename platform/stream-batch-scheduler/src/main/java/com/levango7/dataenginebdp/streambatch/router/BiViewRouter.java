package com.levango7.dataenginebdp.streambatch.router;

import com.levango7.dataenginebdp.streambatch.iceberg.IcebergSnapshotManager;
import com.levango7.dataenginebdp.streambatch.model.SnapshotRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * BI 自动选择视图路由器（核心组件）。
 *
 * <p>根据查询模式（实时/离线/自动）自动选择批快照视图或流最新视图，
 * 与 Doris 物化视图集成（Phase 1 T016），实现 BI 查询的流批一体路由。
 *
 * <p>路由策略：
 * <ol>
 *   <li><b>查询模式 = OFFLINE</b> → 批快照视图（Spark 固定 snapshot）</li>
 *   <li><b>查询模式 = REALTIME</b> → 流最新视图（Flink 最新 snapshot）</li>
 *   <li><b>查询模式 = AUTO</b> → 根据延迟阈值自动判断</li>
 *   <li><b>物化视图命中</b> → 优先使用 Doris 物化视图（避免重复聚合）</li>
 * </ol>
 *
 * <p><b>与 SQL 网关集成</b>：本路由器作为 SQL 网关（platform/sql-gateway）的
 * 查询路由扩展，在 SQL 执行前重写 SQL 指向所选视图。SQL 网关通过
 * {@code BiViewRouter} 注解或配置启用本路由器。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BiViewRouter {

    private final ViewRouterConfig config;
    private final IcebergSnapshotManager snapshotManager;
    private final DorisMaterializedViewIntegration mvIntegration;

    /**
     * 路由查询到合适的视图。
     *
     * @param table      查询的 Iceberg 表全名（database.table）
     * @param queryMode  查询模式（OFFLINE / REALTIME / AUTO）
     * @param originalSql 原始 SQL
     * @param latencyRequirementMs 查询延迟要求（毫秒，AUTO 模式判断依据；null 忽略）
     * @return 视图选择结果（含重写后的 SQL）
     */
    public ViewSelectionResult route(
            String table,
            QueryMode queryMode,
            String originalSql,
            Long latencyRequirementMs) {

        log.info("BI 视图路由: table={}, queryMode={}, latencyReq={}",
                table, queryMode, latencyRequirementMs);

        // 1. 解析 AUTO 模式
        QueryMode effectiveMode = resolveAutoMode(queryMode, latencyRequirementMs);

        // 2. 优先检查 Doris 物化视图命中
        String mvName = mvIntegration.findMaterializedView(table);
        if (mvName != null) {
            return routeToMaterializedView(table, mvName, effectiveMode, originalSql);
        }

        // 3. 根据查询模式选择视图
        return switch (effectiveMode) {
            case OFFLINE -> routeToBatchSnapshot(table, originalSql);
            case REALTIME -> routeToStreamLatest(table, originalSql);
            case AUTO -> routeToBatchSnapshot(table, originalSql); // AUTO 已解析，不会到达
        };
    }

    /**
     * 路由到批快照视图。
     *
     * <p>批快照视图读 Iceberg 固定 snapshot（Spark 批作业锁定的 snapshot），
     * 适用于离线分析、报表等对数据新鲜度要求不高的场景。
     */
    private ViewSelectionResult routeToBatchSnapshot(String table, String originalSql) {
        SnapshotRef batchSnapshot = snapshotManager.getLockedBatchSnapshot(table);
        long snapshotId;
        if (batchSnapshot != null) {
            snapshotId = batchSnapshot.getSnapshotId();
        } else {
            // 未锁定则使用当前最新（兼容无批作业场景）
            snapshotId = snapshotManager.getLatestSnapshot(table).getSnapshotId();
        }

        String batchView = table + config.getBatchViewSuffix();
        String rewrittenSql = mvIntegration.buildBatchSnapshotQuery(table, snapshotId, originalSql);

        log.info("路由到批快照视图: table={}, batchView={}, snapshotId={}", table, batchView, snapshotId);

        return ViewSelectionResult.builder()
                .viewName(batchView)
                .viewType("BATCH_SNAPSHOT")
                .queryMode(QueryMode.OFFLINE)
                .snapshotId(snapshotId)
                .originalSql(originalSql)
                .rewrittenSql(rewrittenSql)
                .selectionReason(String.format(
                        "离线查询选择批快照视图 %s（Iceberg 固定 snapshot-id=%d）", batchView, snapshotId))
                .materializedViewHit(false)
                .build();
    }

    /**
     * 路由到流最新视图。
     *
     * <p>流最新视图读 Iceberg 最新 snapshot（Flink 流作业实时写入的最新数据），
     * 适用于实时大屏、实时报警等对数据新鲜度要求高的场景。
     */
    private ViewSelectionResult routeToStreamLatest(String table, String originalSql) {
        SnapshotRef streamSnapshot = snapshotManager.getLatestSnapshot(table);
        long snapshotId = streamSnapshot.getSnapshotId();

        String streamView = table + config.getStreamViewSuffix();
        String rewrittenSql = mvIntegration.buildStreamLatestQuery(table, originalSql);

        log.info("路由到流最新视图: table={}, streamView={}, snapshotId={}", table, streamView, snapshotId);

        return ViewSelectionResult.builder()
                .viewName(streamView)
                .viewType("STREAM_LATEST")
                .queryMode(QueryMode.REALTIME)
                .snapshotId(snapshotId)
                .originalSql(originalSql)
                .rewrittenSql(rewrittenSql)
                .selectionReason(String.format(
                        "实时查询选择流最新视图 %s（Iceberg 最新 snapshot-id=%d）", streamView, snapshotId))
                .materializedViewHit(false)
                .build();
    }

    /**
     * 路由到 Doris 物化视图（命中物化视图时优先）。
     *
     * <p>物化视图已预计算聚合结果，查询直接命中物化视图避免重复计算。
     * 与 Phase 1 T016 Doris 物化视图自动刷新集成。
     */
    private ViewSelectionResult routeToMaterializedView(
            String table, String mvName, QueryMode mode, String originalSql) {
        Long snapshotId = mvIntegration.getMaterializedViewSnapshotId(table);
        String rewrittenSql = mvIntegration.buildMaterializedViewQuery(mvName, originalSql);

        log.info("路由到 Doris 物化视图: table={}, mvName={}, snapshotId={}", table, mvName, snapshotId);

        return ViewSelectionResult.builder()
                .viewName(mvName)
                .viewType("MATERIALIZED_VIEW")
                .queryMode(mode)
                .snapshotId(snapshotId)
                .originalSql(originalSql)
                .rewrittenSql(rewrittenSql)
                .selectionReason(String.format(
                        "命中 Doris 物化视图 %s（预计算聚合，避免重复计算）", mvName))
                .materializedViewHit(true)
                .materializedViewName(mvName)
                .build();
    }

    /**
     * 解析 AUTO 查询模式。
     *
     * <p>根据延迟要求判断：
     * <ul>
     *   <li>延迟要求 &lt; {@link ViewRouterConfig#getRealtimeLatencyThresholdMs()} → REALTIME</li>
     *   <li>否则 → OFFLINE（使用默认模式）</li>
     * </ul>
     */
    private QueryMode resolveAutoMode(QueryMode queryMode, Long latencyRequirementMs) {
        if (queryMode != QueryMode.AUTO) {
            return queryMode;
        }
        if (latencyRequirementMs != null
                && latencyRequirementMs < config.getRealtimeLatencyThresholdMs()) {
            log.debug("AUTO 模式解析为 REALTIME: latencyReq={} < threshold={}",
                    latencyRequirementMs, config.getRealtimeLatencyThresholdMs());
            return QueryMode.REALTIME;
        }
        log.debug("AUTO 模式解析为默认模式: {}", config.getDefaultQueryMode());
        return config.getDefaultQueryMode();
    }
}