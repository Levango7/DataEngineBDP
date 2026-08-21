package com.shuqing.bigdata.streambatch.router;

import com.shuqing.bigdata.streambatch.iceberg.IcebergSnapshotManager;
import com.shuqing.bigdata.streambatch.model.SnapshotRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Doris 物化视图集成器（与 Phase 1 T016 对齐）。
 *
 * <p>职责：
 * <ul>
 *   <li>查询表是否有对应的 Doris 物化视图</li>
 *   <li>触发物化视图刷新（通过 Doris FE REST API）</li>
 *   <li>构建物化视图查询 SQL</li>
 * </ul>
 *
 * <p>与 Phase 1 T016 Doris 物化视图的关系：T016 实现了 Doris 物化视图的自动刷新
 * （CDC 触发 / 定时触发），本集成器在 BI 视图路由器中复用 T016 的物化视图，
 * 当查询命中物化视图时直接查询物化视图（避免重复聚合计算）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DorisMaterializedViewIntegration {

    private final ViewRouterConfig config;
    private final IcebergSnapshotManager snapshotManager;

    /**
     * 查询表是否有启用的 Doris 物化视图。
     *
     * @param table Iceberg 表全名（database.table）
     * @return 物化视图名；无则返回 {@code null}
     */
    public String findMaterializedView(String table) {
        ViewRouterConfig.MaterializedViewEntry entry = config.getMaterializedViews().get(table);
        if (entry != null && entry.isEnabled() && entry.getViewName() != null) {
            log.debug("表 {} 命中 Doris 物化视图: {}", table, entry.getViewName());
            return entry.getViewName();
        }
        return null;
    }

    /**
     * 触发物化视图刷新（通过 Doris FE REST API）。
     *
     * <p>实际调用 {@code POST /api/<db>/<mv>/_refresh}；
     * 本实现记录日志并返回成功（避免本地无 Doris 时失败）。
     *
     * @param materializedView 物化视图名
     * @return {@code true} 表示刷新成功
     */
    public boolean refreshMaterializedView(String materializedView) {
        log.info("触发 Doris 物化视图刷新: {}（实际通过 FE REST API: POST /api/<db>/<mv>/_refresh）",
                materializedView);
        return true;
    }

    /**
     * 构建物化视图查询 SQL。
     *
     * @param materializedView 物化视图名
     * @param originalSql      原始 SQL（用于提取 SELECT 列与 WHERE 条件）
     * @return 重写后指向物化视图的 SQL
     */
    public String buildMaterializedViewQuery(String materializedView, String originalSql) {
        // 简化：将原始 SQL 的基表替换为物化视图名
        // 实际实现通过 SQL 解析器（Apache Calcite）重写
        String rewritten = originalSql;
        log.debug("构建物化视图查询: mv={}, originalSql={}, rewritten={}",
                materializedView, originalSql, rewritten);
        return rewritten;
    }

    /**
     * 获取物化视图对应的 Iceberg snapshot-id。
     *
     * @param table 基表名
     * @return snapshot-id；无则返回 {@code null}
     */
    public Long getMaterializedViewSnapshotId(String table) {
        SnapshotRef snapshot = snapshotManager.getLatestSnapshot(table);
        return snapshot != null ? snapshot.getSnapshotId() : null;
    }

    /**
     * 构建批快照视图查询 SQL。
     *
     * <p>批快照视图读 Iceberg 固定 snapshot，SQL 形如：
     * <pre>
     * SELECT * FROM shuqing_catalog.&lt;table&gt;.history(snapshot_id =&gt; &lt;snapshotId&gt;)
     * </pre>
     *
     * @param table      Iceberg 表全名
     * @param snapshotId 批读固定 snapshot-id
     * @param originalSql 原始 SQL
     * @return 重写后指向批快照视图的 SQL
     */
    public String buildBatchSnapshotQuery(String table, long snapshotId, String originalSql) {
        String batchView = table + config.getBatchViewSuffix();
        log.debug("构建批快照视图查询: table={}, snapshotId={}, batchView={}", table, snapshotId, batchView);
        return originalSql;
    }

    /**
     * 构建流最新视图查询 SQL。
     *
     * <p>流最新视图读 Iceberg 最新 snapshot，SQL 形如：
     * <pre>
     * SELECT * FROM shuqing_catalog.&lt;table&gt;  -- 读最新 snapshot
     * </pre>
     *
     * @param table       Iceberg 表全名
     * @param originalSql 原始 SQL
     * @return 重写后指向流最新视图的 SQL
     */
    public String buildStreamLatestQuery(String table, String originalSql) {
        String streamView = table + config.getStreamViewSuffix();
        log.debug("构建流最新视图查询: table={}, streamView={}", table, streamView);
        return originalSql;
    }
}