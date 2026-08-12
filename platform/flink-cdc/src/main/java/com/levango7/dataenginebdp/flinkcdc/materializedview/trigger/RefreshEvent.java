package com.levango7.dataenginebdp.flinkcdc.materializedview.trigger;

import com.levango7.dataenginebdp.flinkcdc.materializedview.model.MaterializedViewDef;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * 物化视图刷新事件，由各类触发器产生，传递给 {@code ViewRefresher} 执行刷新。
 *
 * <p>事件携带触发来源、触发时间戳、关联的物化视图定义以及可选的触发原因描述。</p>
 *
 * @author shuqing-bigdata
 */
public class RefreshEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 触发来源类型。
     */
    public enum Source {
        /** CDC 变更触发。 */
        CDC_CHANGE,
        /** 定时调度触发。 */
        SCHEDULED,
        /** 手动 API 触发。 */
        MANUAL
    }

    /** 事件唯一 ID。 */
    private final String eventId;

    /** 触发来源。 */
    private final Source source;

    /** 触发时间戳。 */
    private final Instant triggerTime;

    /** 关联的物化视图名称。 */
    private final String viewName;

    /** 关联的物化视图定义（可选，若未携带则按名称查找）。 */
    private final MaterializedViewDef viewDef;

    /** 触发原因描述。 */
    private final String reason;

    /**
     * 全参构造器。
     *
     * @param eventId     事件 ID
     * @param source      触发来源
     * @param triggerTime 触发时间戳
     * @param viewName    物化视图名称
     * @param viewDef     物化视图定义
     * @param reason      触发原因
     */
    public RefreshEvent(String eventId, Source source, Instant triggerTime,
                        String viewName, MaterializedViewDef viewDef, String reason) {
        this.eventId = eventId;
        this.source = source;
        this.triggerTime = triggerTime;
        this.viewName = viewName;
        this.viewDef = viewDef;
        this.reason = reason;
    }

    /**
     * 便捷工厂方法：创建 CDC 变更触发事件。
     *
     * @param viewName 物化视图名称
     * @param reason   触发原因（如 "100 条 CDC 变更"）
     * @return RefreshEvent
     */
    public static RefreshEvent cdc(String viewName, String reason) {
        return new RefreshEvent(generateId(), Source.CDC_CHANGE, Instant.now(),
                viewName, null, reason);
    }

    /**
     * 便捷工厂方法：创建定时触发事件。
     *
     * @param viewName 物化视图名称
     * @return RefreshEvent
     */
    public static RefreshEvent scheduled(String viewName) {
        return new RefreshEvent(generateId(), Source.SCHEDULED, Instant.now(),
                viewName, null, "定时调度触发");
    }

    /**
     * 便捷工厂方法：创建手动触发事件。
     *
     * @param viewName 物化视图名称
     * @param operator 操作人
     * @return RefreshEvent
     */
    public static RefreshEvent manual(String viewName, String operator) {
        return new RefreshEvent(generateId(), Source.MANUAL, Instant.now(),
                viewName, null, "手动触发 by " + operator);
    }

    /**
     * 生成事件 ID（时间戳 + 随机数）。
     *
     * @return 事件 ID
     */
    static String generateId() {
        return System.currentTimeMillis() + "-" + Long.toHexString(System.nanoTime());
    }

    public String getEventId() {
        return eventId;
    }

    public Source getSource() {
        return source;
    }

    public Instant getTriggerTime() {
        return triggerTime;
    }

    public String getViewName() {
        return viewName;
    }

    public MaterializedViewDef getViewDef() {
        return viewDef;
    }

    public String getReason() {
        return reason;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RefreshEvent that)) {
            return false;
        }
        return Objects.equals(eventId, that.eventId)
                && source == that.source
                && Objects.equals(triggerTime, that.triggerTime)
                && Objects.equals(viewName, that.viewName)
                && Objects.equals(viewDef, that.viewDef)
                && Objects.equals(reason, that.reason);
    }

    @Override
    public int hashCode() {
        return Objects.hash(eventId, source, triggerTime, viewName, viewDef, reason);
    }

    @Override
    public String toString() {
        return "RefreshEvent{eventId='" + eventId + "', source=" + source
                + ", triggerTime=" + triggerTime
                + ", viewName='" + viewName + '\''
                + ", reason='" + reason + "'}";
    }
}