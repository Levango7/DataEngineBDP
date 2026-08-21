package com.levango7.dataenginebdp.infra.orchestrator.capacity;

import java.time.Instant;

/**
 * 容量扩容预警（v2.1 容量规划）。
 *
 * <p>当预测未来资源利用率超过阈值时，生成扩容预警，提前通知运维扩容。</p>
 *
 * @param serviceName        服务名
 * @param alertTime          预警生成时间
 * @param triggerTime        预计触发时间
 * @param severity           严重级别（WARNING/CRITICAL）
 * @param metric             预警指标（CPU/MEMORY/QPS）
 * @param currentValue       当前值
 * @param predictedValue     预测值
 * @param threshold          阈值
 * @param currentReplicas    当前副本数
 * @param recommendedReplicas 推荐副本数
 * @param message            预警消息
 */
public record CapacityAlert(
        String serviceName,
        Instant alertTime,
        Instant triggerTime,
        Severity severity,
        String metric,
        double currentValue,
        double predictedValue,
        double threshold,
        int currentReplicas,
        int recommendedReplicas,
        String message
) {

    /**
     * 预警严重级别。
     */
    public enum Severity {
        /**
         * 警告：预测值接近阈值（90%）。
         */
        WARNING,
        /**
         * 严重：预测值超过阈值。
         */
        CRITICAL
    }

    /**
     * 生成 CPU 利用率预警。
     *
     * @param serviceName     服务名
     * @param current         当前 CPU 利用率
     * @param predicted       预测 CPU 利用率
     * @param threshold       阈值
     * @param currentReplicas 当前副本数
     * @param recommendedReplicas 推荐副本数
     * @param triggerTime     预计触发时间
     * @return 预警对象
     */
    public static CapacityAlert cpuAlert(String serviceName, double current, double predicted,
                                         double threshold, int currentReplicas,
                                         int recommendedReplicas, Instant triggerTime) {
        Severity severity = predicted >= threshold ? Severity.CRITICAL : Severity.WARNING;
        String message = String.format(
                "服务[%s] CPU 利用率预测 %.1f%% 超过阈值 %.1f%%（当前 %.1f%%），建议从 %d 副本扩容到 %d 副本",
                serviceName, predicted * 100, threshold * 100, current * 100,
                currentReplicas, recommendedReplicas);
        return new CapacityAlert(serviceName, Instant.now(), triggerTime, severity,
                "CPU", current, predicted, threshold, currentReplicas, recommendedReplicas, message);
    }

    /**
     * 生成内存利用率预警。
     *
     * @param serviceName     服务名
     * @param current         当前内存利用率
     * @param predicted       预测内存利用率
     * @param threshold       阈值
     * @param currentReplicas 当前副本数
     * @param recommendedReplicas 推荐副本数
     * @param triggerTime     预计触发时间
     * @return 预警对象
     */
    public static CapacityAlert memoryAlert(String serviceName, double current, double predicted,
                                            double threshold, int currentReplicas,
                                            int recommendedReplicas, Instant triggerTime) {
        Severity severity = predicted >= threshold ? Severity.CRITICAL : Severity.WARNING;
        String message = String.format(
                "服务[%s] 内存利用率预测 %.1f%% 超过阈值 %.1f%%（当前 %.1f%%），建议从 %d 副本扩容到 %d 副本",
                serviceName, predicted * 100, threshold * 100, current * 100,
                currentReplicas, recommendedReplicas);
        return new CapacityAlert(serviceName, Instant.now(), triggerTime, severity,
                "MEMORY", current, predicted, threshold, currentReplicas, recommendedReplicas, message);
    }
}