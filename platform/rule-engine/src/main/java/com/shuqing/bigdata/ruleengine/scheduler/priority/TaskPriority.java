package com.shuqing.bigdata.ruleengine.scheduler.priority;

/**
 * 任务优先级枚举。
 *
 * <p>调度引擎按优先级排队执行任务，{@link #ordinal()} 越小优先级越高。
 * 三档优先级对应业务场景：</p>
 * <ul>
 *   <li>{@link #HIGH}：实时告警、SLA 敏感任务，调度权重最高</li>
 *   <li>{@link #MEDIUM}：常规数据质量检查、定时脱敏，默认级别</li>
 *   <li>{@link #LOW}：批量回算、历史补偿等可延迟任务</li>
 * </ul>
 *
 * <p>同时提供调度权重 {@link #weight()}，用于优先级队列内部排序与
 * 弹性伸缩时的抢占式调度决策；权重越大越优先被取出执行。</p>
 */
public enum TaskPriority {

    /** 高优先级：实时告警 / SLA 敏感任务 */
    HIGH(100),

    /** 中优先级：常规 DQ / 定时脱敏（默认） */
    MEDIUM(50),

    /** 低优先级：批量回算 / 历史补偿 */
    LOW(10);

    /** 调度权重：越大越优先；与 ordinal 反向，便于排序时直接比较 */
    private final int weight;

    TaskPriority(int weight) {
        this.weight = weight;
    }

    /**
     * 返回调度权重。
     *
     * @return 权重值，{@link #HIGH} 为 100，{@link #MEDIUM} 为 50，{@link #LOW} 为 10
     */
    public int weight() {
        return weight;
    }

    /**
     * 安全解析优先级字符串，非法或 null 时回退到 {@link #MEDIUM}。
     *
     * <p>用于 REST API 入参容错，避免因客户端传入非法枚举导致 400。</p>
     *
     * @param value 优先级字符串（大小写不敏感）
     * @return 解析得到的枚举；非法时返回 {@link #MEDIUM}
     */
    public static TaskPriority fromStringOrDefault(String value) {
        if (value == null || value.isBlank()) {
            return MEDIUM;
        }
        try {
            return TaskPriority.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return MEDIUM;
        }
    }
}