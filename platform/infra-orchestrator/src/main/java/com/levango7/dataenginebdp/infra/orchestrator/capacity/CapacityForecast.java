package com.levango7.dataenginebdp.infra.orchestrator.capacity;

import java.time.Instant;
import java.util.List;

/**
 * 容量预测结果（v2.1 容量规划）。
 *
 * <p>基于历史负载数据预测未来一段时间的资源需求，并给出扩容建议。</p>
 *
 * @param serviceName           服务名
 * @param forecastTime          预测目标时间
 * @param predictedCpuUsage     预测 CPU 利用率（0-1）
 * @param predictedMemoryUsage  预测内存利用率（0-1）
 * @param predictedQps          预测 QPS
 * @param currentReplicas       当前副本数
 * @param recommendedReplicas   推荐副本数
 * @param algorithm             预测算法
 * @param confidence            预测置信度（0-1）
 * @param forecastPoints        预测序列点
 */
public record CapacityForecast(
        String serviceName,
        Instant forecastTime,
        double predictedCpuUsage,
        double predictedMemoryUsage,
        double predictedQps,
        int currentReplicas,
        int recommendedReplicas,
        String algorithm,
        double confidence,
        List<ForecastPoint> forecastPoints
) {

    /**
     * 预测序列点。
     *
     * @param timestamp    时间戳
     * @param cpuUsage     预测 CPU 利用率
     * @param memoryUsage  预测内存利用率
     * @param qps          预测 QPS
     */
    public record ForecastPoint(
            Instant timestamp,
            double cpuUsage,
            double memoryUsage,
            double qps
    ) { }
}