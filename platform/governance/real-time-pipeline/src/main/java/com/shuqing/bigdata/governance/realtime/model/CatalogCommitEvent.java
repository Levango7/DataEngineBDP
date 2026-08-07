package com.shuqing.bigdata.governance.realtime.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Iceberg REST Catalog commit 事件。
 *
 * <p>对应 Iceberg REST Catalog V1/V2 API 中的 commit 操作，包括：
 * <ul>
 *   <li>{@code update-snapshot}：表快照更新（数据写入/compaction）</li>
 *   <li>{@code append-snapshot}：追加写入</li>
 *   <li>{@code overwrite-snapshot}：覆盖写入</li>
 *   <li>{@code replace-snapshot}：替换快照（schema evolution）</li>
 *   <li>{@code remove-snapshot}：快照过期清理</li>
 * </ul>
 *
 * <p>事件来源：
 * <ul>
 *   <li>Webhook 推送：Iceberg REST Catalog 配置 webhook，commit 后主动 POST 到本服务</li>
 *   <li>轮询拉取：定时轮询 {@code /v1/{prefix}/namespaces/{namespace}/tables/{table}/snapshots}，
 *       对比最新 snapshot-id 与已处理 snapshot-id，发现新 commit</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogCommitEvent implements Serializable {

    /** 事件类型：commit 操作类型 */
    private String eventType;

    /** 事件唯一 ID（UUID） */
    private String eventId;

    /** Iceberg REST Catalog 命名空间，例如 {@code default} 或 {@code analytics.db} */
    private String namespace;

    /** 表名 */
    private String tableName;

    /** 完整表标识符，{@code namespace.tableName} */
    private String tableIdentifier;

    /** 旧 snapshot-id（首次 commit 时为 null） */
    private Long oldSnapshotId;

    /** 新 snapshot-id */
    private Long newSnapshotId;

    /** commit 时间戳（毫秒，来自 Catalog） */
    private Instant commitTimestamp;

    /** commit 操作者（用户/服务账号） */
    private String committer;

    /** commit 摘要统计（added-data-files、deleted-data-files、added-records 等） */
    private Map<String, String> summary;

    /** schema 变更涉及的字段列表（schema evolution 时非空） */
    private List<String> changedFields;

    /** 事件接收时间戳（本服务接收时刻，用于计算采集延迟） */
    private Instant receivedTimestamp;

    /**
     * 判断是否为 schema 变更事件。
     *
     * @return {@code true} 表示 schema 发生变更（replace-snapshot 且 changedFields 非空）
     */
    public boolean isSchemaChange() {
        return "replace-snapshot".equals(eventType)
                && changedFields != null
                && !changedFields.isEmpty();
    }

    /**
     * 判断是否为数据变更事件。
     *
     * @return {@code true} 表示数据发生变更（append/overwrite/update-snapshot）
     */
    public boolean isDataChange() {
        return "append-snapshot".equals(eventType)
                || "overwrite-snapshot".equals(eventType)
                || "update-snapshot".equals(eventType);
    }
}