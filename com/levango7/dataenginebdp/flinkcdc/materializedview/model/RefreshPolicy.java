package com.shuqing.bigdata.flinkcdc.materializedview.model;

import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;

/**
 * 物化视图刷新策略，定义何时以及如何触发物化视图刷新。
 *
 * <p>支持三种刷新模式：</p>
 * <ul>
 *   <li>{@link Mode#SCHEDULED} — 定时刷新，按固定周期触发（如每 5 分钟）</li>
 *   <li>{@link Mode#EVENT_TRIGGERED} — 事件触发，监听源表 CDC 变更后触发</li>
 *   <li>{@link Mode#MANUAL} — 手动刷新，仅通过 REST API 显式触发</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * RefreshPolicy scheduled = RefreshPolicy.scheduled(Duration.ofMinutes(5));
 * RefreshPolicy eventBased = RefreshPolicy.eventTriggered(100, Duration.ofSeconds(30));
 * RefreshPolicy manual = RefreshPolicy.manual();
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public class RefreshPolicy implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 刷新模式枚举。
     */
    public enum Mode {
        /** 定时刷新：按固定周期触发。 */
        SCHEDULED("scheduled"),
        /** 事件触发：监听源表 CDC 变更后触发（可配置批量阈值与去抖窗口）。 */
        EVENT_TRIGGERED("event-triggered"),
        /** 手动刷新：仅通过 REST API 显式触发。 */
        MANUAL("manual");

        private final String code;

        Mode(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /**
         * 根据编码解析为枚举值（大小写不敏感，支持 kebab-case）。
         *
         * @param code 模式编码
         * @return 对应枚举值
         * @throws NullPointerException     若 code 为 null
         * @throws IllegalArgumentException 若编码不被识别
         */
        public static Mode fromCode(String code) {
            Objects.requireNonNull(code, "刷新模式编码不能为 null");
            String normalized = code.toLowerCase().replace("_", "-");
            for (Mode mode : values()) {
                if (mode.code.equals(normalized)) {
                    return mode;
                }
            }
            throw new IllegalArgumentException("未知的刷新模式: " + code);
        }
    }

    /** 刷新模式。 */
    private Mode mode = Mode.MANUAL;

    /** 定时刷新周期（SCHEDULED 模式下生效），默认 5 分钟。 */
    private Duration interval = Duration.ofMinutes(5);

    /**
     * 事件触发批量阈值：累计 N 条 CDC 变更后触发一次刷新（EVENT_TRIGGERED 模式下生效）。
     * 默认 100，设为 1 表示每条变更即触发。
     */
    private int batchThreshold = 100;

    /**
     * 事件触发去抖窗口：在窗口时间内仅触发一次刷新，避免高频变更导致刷新风暴。
     * 默认 30 秒。
     */
    private Duration debounceWindow = Duration.ofSeconds(30);

    /** 是否在刷新失败后自动重试。 */
    private boolean autoRetry = true;

    /** 最大重试次数。 */
    private int maxRetries = 3;

    /** 默认构造器，供反序列化使用。 */
    public RefreshPolicy() {
    }

    private RefreshPolicy(Mode mode, Duration interval, int batchThreshold,
                          Duration debounceWindow, boolean autoRetry, int maxRetries) {
        this.mode = mode;
        this.interval = interval;
        this.batchThreshold = batchThreshold;
        this.debounceWindow = debounceWindow;
        this.autoRetry = autoRetry;
        this.maxRetries = maxRetries;
    }

    /**
     * 创建定时刷新策略。
     *
     * @param interval 刷新周期
     * @return 定时刷新策略
     */
    public static RefreshPolicy scheduled(Duration interval) {
        Objects.requireNonNull(interval, "刷新周期不能为 null");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("刷新周期必须为正数");
        }
        return new RefreshPolicy(Mode.SCHEDULED, interval, 0, Duration.ZERO, true, 3);
    }

    /**
     * 创建事件触发刷新策略。
     *
     * @param batchThreshold  批量阈值（累计 N 条变更后触发）
     * @param debounceWindow  去抖窗口
     * @return 事件触发刷新策略
     */
    public static RefreshPolicy eventTriggered(int batchThreshold, Duration debounceWindow) {
        if (batchThreshold <= 0) {
            throw new IllegalArgumentException("批量阈值必须为正数");
        }
        Objects.requireNonNull(debounceWindow, "去抖窗口不能为 null");
        if (debounceWindow.isNegative()) {
            throw new IllegalArgumentException("去抖窗口不能为负数");
        }
        return new RefreshPolicy(Mode.EVENT_TRIGGERED, Duration.ZERO, batchThreshold,
                debounceWindow, true, 3);
    }

    /**
     * 创建手动刷新策略。
     *
     * @return 手动刷新策略
     */
    public static RefreshPolicy manual() {
        return new RefreshPolicy(Mode.MANUAL, Duration.ZERO, 0, Duration.ZERO, false, 0);
    }

    // ===== getter / setter =====

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public Duration getInterval() {
        return interval;
    }

    public void setInterval(Duration interval) {
        this.interval = interval;
    }

    public int getBatchThreshold() {
        return batchThreshold;
    }

    public void setBatchThreshold(int batchThreshold) {
        this.batchThreshold = batchThreshold;
    }

    public Duration getDebounceWindow() {
        return debounceWindow;
    }

    public void setDebounceWindow(Duration debounceWindow) {
        this.debounceWindow = debounceWindow;
    }

    public boolean isAutoRetry() {
        return autoRetry;
    }

    public void setAutoRetry(boolean autoRetry) {
        this.autoRetry = autoRetry;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    /**
     * 校验策略配置的合法性。
     *
     * @throws IllegalStateException 若配置不合法
     */
    public void validate() {
        switch (mode) {
            case SCHEDULED -> {
                if (interval == null || interval.isZero() || interval.isNegative()) {
                    throw new IllegalStateException("SCHEDULED 模式下 interval 必须为正数");
                }
            }
            case EVENT_TRIGGERED -> {
                if (batchThreshold <= 0) {
                    throw new IllegalStateException("EVENT_TRIGGERED 模式下 batchThreshold 必须为正数");
                }
                if (debounceWindow == null || debounceWindow.isNegative()) {
                    throw new IllegalStateException("EVENT_TRIGGERED 模式下 debounceWindow 不能为负数");
                }
            }
            case MANUAL -> {
                // 手动模式无额外约束
            }
        }
        if (autoRetry && maxRetries < 0) {
            throw new IllegalStateException("maxRetries 不能为负数");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof RefreshPolicy that)) {
            return false;
        }
        return batchThreshold == that.batchThreshold
                && autoRetry == that.autoRetry
                && maxRetries == that.maxRetries
                && mode == that.mode
                && Objects.equals(interval, that.interval)
                && Objects.equals(debounceWindow, that.debounceWindow);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mode, interval, batchThreshold, debounceWindow, autoRetry, maxRetries);
    }

    @Override
    public String toString() {
        return "RefreshPolicy{mode=" + mode
                + ", interval=" + interval
                + ", batchThreshold=" + batchThreshold
                + ", debounceWindow=" + debounceWindow
                + ", autoRetry=" + autoRetry
                + ", maxRetries=" + maxRetries + '}';
    }
}