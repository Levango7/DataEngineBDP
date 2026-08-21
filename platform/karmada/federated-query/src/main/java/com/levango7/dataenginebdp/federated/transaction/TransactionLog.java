package com.levango7.dataenginebdp.federated.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨集群事务日志模型。
 *
 * <p>记录一次 2PC 事务的完整生命周期信息，便于事务恢复服务
 * ({@link TransactionRecoveryService}) 在协调器重启后继续未完成事务。
 *
 * <p>状态机：
 * <pre>
 *   ACTIVE → PREPARING → PREPARED → COMMITTING → COMMITTED
 *                                      → ROLLING_BACK → ROLLED_BACK
 *                                      → FAILED
 * </pre>
 */
@Slf4j
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransactionLog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 事务状态枚举。 */
    public enum Status {
        ACTIVE,
        PREPARING,
        PREPARED,
        COMMITTING,
        COMMITTED,
        ROLLING_BACK,
        ROLLED_BACK,
        FAILED
    }

    /** 全局事务 ID（UUID）。 */
    private String txId;

    /** 参与集群端点列表（集群名 → endpoint URL）。 */
    @Builder.Default
    private Map<String, String> participants = new HashMap<>();

    /** 已 prepared 的集群列表。 */
    @Builder.Default
    private List<String> preparedClusters = new ArrayList<>();

    /** 已 committed 的集群列表。 */
    @Builder.Default
    private List<String> committedClusters = new ArrayList<>();

    /** 已 rolled back 的集群列表。 */
    @Builder.Default
    private List<String> rolledBackClusters = new ArrayList<>();

    /** 当前事务状态。 */
    @Builder.Default
    private Status status = Status.ACTIVE;

    /** 事务创建时间。 */
    private Instant createdAt;

    /** 最后更新时间。 */
    private Instant updatedAt;

    /** PREPARING 阶段开始时间。 */
    private Instant preparingAt;

    /** PREPARED 阶段完成时间。 */
    private Instant preparedAt;

    /** COMMITTING 阶段开始时间。 */
    private Instant committingAt;

    /** COMMITTED 阶段完成时间。 */
    private Instant committedAt;

    /** ROLLING_BACK 阶段开始时间。 */
    private Instant rollingBackAt;

    /** ROLLED_BACK 阶段完成时间。 */
    private Instant rolledBackAt;

    /** 失败原因（status=FAILED 时填充）。 */
    private String failureReason;

    /** 每个集群在 prepare 阶段的结果（集群名 → true/false）。 */
    @Builder.Default
    private Map<String, Boolean> prepareResults = new HashMap<>();

    /** 每个集群在 commit 阶段的结果（集群名 → true/false）。 */
    @Builder.Default
    private Map<String, Boolean> commitResults = new HashMap<>();

    /** 每个集群在 rollback 阶段的结果（集群名 → true/false）。 */
    @Builder.Default
    private Map<String, Boolean> rollbackResults = new HashMap<>();

    /** Iceberg 表 → 该事务在该表上创建的 snapshotId。 */
    @Builder.Default
    private Map<String, Long> snapshots = new HashMap<>();

    /** 重试次数（commit 失败重试）。 */
    @Builder.Default
    private int retryCount = 0;

    /**
     * 序列化为 JSON 字符串。
     *
     * @return JSON 字符串
     */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize transaction log txId={}", txId, e);
            throw new IllegalStateException("Serialize transaction log failed: " + txId, e);
        }
    }

    /**
     * 从 JSON 字符串反序列化。
     *
     * @param json JSON 字符串
     * @return TransactionLog 实例
     */
    public static TransactionLog fromJson(String json) {
        try {
            return MAPPER.readValue(json, TransactionLog.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to deserialize transaction log: {}", json, e);
            throw new IllegalStateException("Deserialize transaction log failed", e);
        }
    }

    /**
     * 判断事务是否处于终态（不会再变化）。
     *
     * @return true 表示终态
     */
    public boolean isTerminal() {
        return status == Status.COMMITTED
                || status == Status.ROLLED_BACK
                || status == Status.FAILED;
    }

    /**
     * 判断事务是否处于可恢复的中间态。
     *
     * @return true 表示需要恢复
     */
    public boolean isRecoverable() {
        return status == Status.PREPARING
                || status == Status.PREPARED
                || status == Status.COMMITTING;
    }
}