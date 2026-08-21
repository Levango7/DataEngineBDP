package com.levango7.dataenginebdp.federated.transaction;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 事务状态响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionResponse {

    /** 事务 ID。 */
    private String txId;

    /** 当前状态。 */
    private String status;

    /** 参与集群列表。 */
    private List<String> participants;

    /** 已 prepared 集群列表。 */
    private List<String> preparedClusters;

    /** 已 committed 集群列表。 */
    private List<String> committedClusters;

    /** 已 rolled back 集群列表。 */
    private List<String> rolledBackClusters;

    /** 创建时间。 */
    private Instant createdAt;

    /** 更新时间。 */
    private Instant updatedAt;

    /** 失败原因。 */
    private String failureReason;

    /** Iceberg 表 → snapshotId。 */
    private Map<String, Long> snapshots;

    /** 重试次数。 */
    private int retryCount;

    /**
     * 从 TransactionLog 构造响应。
     */
    public static TransactionResponse from(TransactionLog log) {
        return TransactionResponse.builder()
                .txId(log.getTxId())
                .status(log.getStatus().name())
                .participants(log.getParticipants().keySet().stream().sorted().toList())
                .preparedClusters(log.getPreparedClusters())
                .committedClusters(log.getCommittedClusters())
                .rolledBackClusters(log.getRolledBackClusters())
                .createdAt(log.getCreatedAt())
                .updatedAt(log.getUpdatedAt())
                .failureReason(log.getFailureReason())
                .snapshots(log.getSnapshots())
                .retryCount(log.getRetryCount())
                .build();
    }
}