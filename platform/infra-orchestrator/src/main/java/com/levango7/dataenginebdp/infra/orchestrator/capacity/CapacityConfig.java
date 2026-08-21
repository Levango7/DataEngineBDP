package com.levango7.dataenginebdp.infra.orchestrator.capacity;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 容量规划配置（v2.1 生产化加固）。
 *
 * <p>基于历史负载数据建立容量预测模型，提前预警扩容需求。
 * 配置前缀 {@code app.capacity}，对应 application.yml 中 {@code app.capacity} 段。</p>
 *
 * <p>核心参数：</p>
 * <ul>
 *   <li>{@link #prometheusAddress} - Prometheus 查询地址，用于采集历史负载数据</li>
 *   <li>{@link #historyWindow} - 历史数据采样窗口（默认 7 天）</li>
 *   <li>{@link #forecastHorizon} - 预测时长（默认 3 天）</li>
 *   <li>{@link #cpuThreshold} - CPU 利用率预警阈值（默认 0.8）</li>
 *   <li>{@link #memoryThreshold} - 内存利用率预警阈值（默认 0.85）</li>
 *   <li>{@link #scaleAheadHours} - 提前扩容预警时长（默认 24 小时）</li>
 * </ul>
 */
@Configuration
@ConfigurationProperties(prefix = "app.capacity")
public class CapacityConfig {

    /**
     * 是否启用容量规划。
     */
    private boolean enabled = true;

    /**
     * Prometheus 查询地址。
     */
    private String prometheusAddress = "http://prometheus-server.observability.svc.cluster.local:9090";

    /**
     * 历史数据采样窗口（天数，默认 7 天）。
     */
    private int historyWindowDays = 7;

    /**
     * 预测时长（天数，默认 3 天）。
     */
    private int forecastHorizonDays = 3;

    /**
     * CPU 利用率预警阈值（0-1，默认 0.8）。
     */
    private double cpuThreshold = 0.8;

    /**
     * 内存利用率预警阈值（0-1，默认 0.85）。
     */
    private double memoryThreshold = 0.85;

    /**
     * 提前扩容预警时长（小时，默认 24 小时）。
     */
    private int scaleAheadHours = 24;

    /**
     * 采样间隔（分钟，默认 5 分钟）。
     */
    private int sampleIntervalMinutes = 5;

    /**
     * 预测算法：linear（线性回归）/ ewma（指数加权移动平均）/ holt-winters（ Holt-Winters 三次指数平滑）。
     */
    private String algorithm = "linear";

    /**
     * EWMA 衰减因子（0-1，默认 0.3）。
     */
    private double ewmaAlpha = 0.3;

    /**
     * 监控的目标服务列表（服务名 -> PromQL 标签选择器）。
     */
    private List<String> targetServices = List.of(
            "sq-encaps-layer",
            "sq-sql-gateway",
            "sq-rule-engine",
            "sq-catalog",
            "sq-asset-exchange"
    );

    /**
     * WebClient 超时（毫秒）。
     */
    private long webClientTimeoutMs = 10000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPrometheusAddress() {
        return prometheusAddress;
    }

    public void setPrometheusAddress(String prometheusAddress) {
        this.prometheusAddress = prometheusAddress;
    }

    public int getHistoryWindowDays() {
        return historyWindowDays;
    }

    public void setHistoryWindowDays(int historyWindowDays) {
        this.historyWindowDays = historyWindowDays;
    }

    public int getForecastHorizonDays() {
        return forecastHorizonDays;
    }

    public void setForecastHorizonDays(int forecastHorizonDays) {
        this.forecastHorizonDays = forecastHorizonDays;
    }

    public double getCpuThreshold() {
        return cpuThreshold;
    }

    public void setCpuThreshold(double cpuThreshold) {
        this.cpuThreshold = cpuThreshold;
    }

    public double getMemoryThreshold() {
        return memoryThreshold;
    }

    public void setMemoryThreshold(double memoryThreshold) {
        this.memoryThreshold = memoryThreshold;
    }

    public int getScaleAheadHours() {
        return scaleAheadHours;
    }

    public void setScaleAheadHours(int scaleAheadHours) {
        this.scaleAheadHours = scaleAheadHours;
    }

    public int getSampleIntervalMinutes() {
        return sampleIntervalMinutes;
    }

    public void setSampleIntervalMinutes(int sampleIntervalMinutes) {
        this.sampleIntervalMinutes = sampleIntervalMinutes;
    }

    public String getAlgorithm() {
        return algorithm;
    }

    public void setAlgorithm(String algorithm) {
        this.algorithm = algorithm;
    }

    public double getEwmaAlpha() {
        return ewmaAlpha;
    }

    public void setEwmaAlpha(double ewmaAlpha) {
        this.ewmaAlpha = ewmaAlpha;
    }

    public List<String> getTargetServices() {
        return targetServices;
    }

    public void setTargetServices(List<String> targetServices) {
        this.targetServices = targetServices;
    }

    public long getWebClientTimeoutMs() {
        return webClientTimeoutMs;
    }

    public void setWebClientTimeoutMs(long webClientTimeoutMs) {
        this.webClientTimeoutMs = webClientTimeoutMs;
    }

    /**
     * 历史采样窗口 Duration。
     *
     * @return Duration
     */
    public Duration historyWindow() {
        return Duration.ofDays(historyWindowDays);
    }

    /**
     * 预测时长 Duration。
     *
     * @return Duration
     */
    public Duration forecastHorizon() {
        return Duration.ofDays(forecastHorizonDays);
    }
}