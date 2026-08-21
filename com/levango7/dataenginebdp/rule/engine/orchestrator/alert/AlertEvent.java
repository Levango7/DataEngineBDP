package com.shuqing.bigdata.rule.engine.orchestrator.alert;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 告警事件。
 *
 * <p>封装一次告警的全部上下文：触发源（图/节点）、告警级别、原因、时间戳与扩展字段。
 * 由调度器在任务失败或超时时构造，交由 {@link AlertManager} 分发到各通道。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertEvent {

    /** 告警级别常量 */
    public static final String LEVEL_INFO = "INFO";
    public static final String LEVEL_WARN = "WARN";
    public static final String LEVEL_ERROR = "ERROR";
    public static final String LEVEL_CRITICAL = "CRITICAL";

    /** 告警类型常量 */
    public static final String TYPE_TASK_FAILED = "TASK_FAILED";
    public static final String TYPE_TASK_TIMEOUT = "TASK_TIMEOUT";
    public static final String TYPE_DAG_FAILED = "DAG_FAILED";

    /** 告警事件唯一 ID（由调用方生成，如 UUID） */
    private String id;

    /** 告警类型 */
    private String type;

    /** 告警级别 */
    private String level;

    /** 关联 DAG ID */
    private String dagId;

    /** 关联节点 ID（可为空，如整图失败） */
    private String nodeId;

    /** 告警标题 */
    private String title;

    /** 告警详情 */
    private String message;

    /** 触发时间 */
    private LocalDateTime triggeredAt;

    /** 扩展字段，供通道渲染模板使用 */
    private Map<String, Object> extras;
}