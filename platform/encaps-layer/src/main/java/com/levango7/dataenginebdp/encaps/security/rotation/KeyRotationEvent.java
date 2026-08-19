package com.levango7.dataenginebdp.encaps.security.rotation;

import java.time.Instant;
import java.util.UUID;

/**
 * 密钥轮换事件记录（审计用）。
 *
 * <p>每次轮换生成一个事件实例，记录新旧密钥标识、轮换时间、过渡期结束时间与执行结果，
 * 便于安全审计与合规追溯（等保三级要求关键操作可审计）。</p>
 */
public class KeyRotationEvent {

    /** 事件唯一标识。 */
    private final String eventId;
    /** 新 active 密钥的 kid。 */
    private final String newKid;
    /** 轮换前的 active 密钥 kid；首次轮换时为 null。 */
    private final String previousKid;
    /** 轮换发生时间。 */
    private final Instant rotatedAt;
    /** 过渡期结束时间（旧密钥废弃时间）。 */
    private final Instant overlapEndAt;
    /** 轮换是否成功。 */
    private final boolean success;
    /** 失败时的错误信息；成功时为 null。 */
    private final String errorMessage;

    /**
     * 构造轮换事件。
     *
     * @param newKid       新密钥 kid
     * @param previousKid  旧密钥 kid（可为 null）
     * @param rotatedAt    轮换时间
     * @param overlapEndAt 过渡期结束时间
     * @param success      是否成功
     * @param errorMessage 错误信息（可为 null）
     */
    public KeyRotationEvent(String newKid, String previousKid, Instant rotatedAt,
                            Instant overlapEndAt, boolean success, String errorMessage) {
        this.eventId = UUID.randomUUID().toString();
        this.newKid = newKid;
        this.previousKid = previousKid;
        this.rotatedAt = rotatedAt;
        this.overlapEndAt = overlapEndAt;
        this.success = success;
        this.errorMessage = errorMessage;
    }

    /**
     * 事件标识。
     *
     * @return UUID
     */
    public String getEventId() {
        return eventId;
    }

    /**
     * 新密钥 kid。
     *
     * @return kid
     */
    public String getNewKid() {
        return newKid;
    }

    /**
     * 旧密钥 kid。
     *
     * @return kid；首次轮换时为 null
     */
    public String getPreviousKid() {
        return previousKid;
    }

    /**
     * 轮换时间。
     *
     * @return 时间
     */
    public Instant getRotatedAt() {
        return rotatedAt;
    }

    /**
     * 过渡期结束时间。
     *
     * @return 时间
     */
    public Instant getOverlapEndAt() {
        return overlapEndAt;
    }

    /**
     * 是否成功。
     *
     * @return true 表示成功
     */
    public boolean isSuccess() {
        return success;
    }

    /**
     * 错误信息。
     *
     * @return 错误信息；成功时为 null
     */
    public String getErrorMessage() {
        return errorMessage;
    }

    @Override
    public String toString() {
        return "KeyRotationEvent{eventId=" + eventId
                + ", newKid=" + newKid
                + ", previousKid=" + previousKid
                + ", rotatedAt=" + rotatedAt
                + ", overlapEndAt=" + overlapEndAt
                + ", success=" + success
                + (errorMessage != null ? ", error=" + errorMessage : "")
                + "}";
    }
}