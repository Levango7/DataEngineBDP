package com.levango7.dataenginebdp.infra.orchestrator.capacity;

import java.time.Instant;
import java.util.List;

/**
 * 历史负载数据点（v2.1 容量规划）。
 *
 * <p>从 Prometheus 采集的历史负载数据点，作为容量预测模型的输入。</p>
 *
 * @param timestamp  采样时间戳
 * @param cpuUsage   CPU 利用率（0-1）
 * @param memoryUsage 内存利用率（0-1）
 * @param replicas    当前副本数
 * @param qps         每秒查询量
 */
public record LoadSample(
        Instant timestamp,
        double cpuUsage,
        double memoryUsage,
        int replicas,
        double qps
) {

    /**
     * 校验数据点合法性。
     *
     * @return 是否合法
     */
    public boolean isValid() {
        return timestamp != null
                && cpuUsage >= 0 && cpuUsage <= 1.0
                && memoryUsage >= 0 && memoryUsage <= 1.0
                && replicas > 0
                && qps >= 0;
    }

    /**
     * 从样本列表计算平均 CPU 利用率。
     *
     * @param samples 样本列表
     * @return 平均 CPU 利用率
     */
    public static double averageCpu(List<LoadSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0.0;
        }
        return samples.stream().mapToDouble(LoadSample::cpuUsage).average().orElse(0.0);
    }

    /**
     * 从样本列表计算平均内存利用率。
     *
     * @param samples 样本列表
     * @return 平均内存利用率
     */
    public static double averageMemory(List<LoadSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0.0;
        }
        return samples.stream().mapToDouble(LoadSample::memoryUsage).average().orElse(0.0);
    }

    /**
     * 从样本列表计算平均 QPS。
     *
     * @param samples 样本列表
     * @return 平均 QPS
     */
    public static double averageQps(List<LoadSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return 0.0;
        }
        return samples.stream().mapToDouble(LoadSample::qps).average().orElse(0.0);
    }
}