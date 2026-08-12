package com.levango7.dataenginebdp.streambatch.router;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 视图选择结果。
 *
 * <p>记录 BI 视图路由器为一次查询选择的视图信息：
 * <ul>
 *   <li>{@code viewName} — 实际查询的视图名（批快照视图或流最新视图）</li>
 *   <li>{@code queryMode} — 使用的查询模式</li>
 *   <li>{@code snapshotId} — 视图对应的 Iceberg snapshot-id</li>
 *   <li>{@code rewrittenSql} — 重写后的 SQL（指向所选视图）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ViewSelectionResult {

    /** 选中的视图名。 */
    private String viewName;

    /** 视图类型（BATCH_SNAPSHOT / STREAM_LATEST / MATERIALIZED_VIEW）。 */
    private String viewType;

    /** 查询模式。 */
    private QueryMode queryMode;

    /** 视图对应的 Iceberg snapshot-id。 */
    private Long snapshotId;

    /** 原始 SQL。 */
    private String originalSql;

    /** 重写后的 SQL（指向所选视图）。 */
    private String rewrittenSql;

    /** 选择原因（用于审计与调试）。 */
    private String selectionReason;

    /** 是否命中 Doris 物化视图。 */
    private boolean materializedViewHit;

    /** 物化视图名（命中时填充）。 */
    private String materializedViewName;
}