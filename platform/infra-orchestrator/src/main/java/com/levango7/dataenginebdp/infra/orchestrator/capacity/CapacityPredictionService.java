package com.levango7.dataenginebdp.infra.orchestrator.capacity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 容量预测服务（v2.1 生产化加固）。
 *
 * <p>基于历史负载数据建立容量预测模型，提前预警扩容需求。
 * 支持三种预测算法：</p>
 * <ul>
 *   <li>{@code linear} - 线性回归：基于最小二乘法拟合趋势线</li>
 *   <li>{@code ewma} - 指数加权移动平均：近期数据权重更高</li>
 *   <li>{@code holt-winters} - Holt-Winters 三次指数平滑：捕捉趋势与季节性</li>
 * </ul>
 *
 * <p>核心流程：</p>
 * <ol>
 *   <li>从 Prometheus 采集历史负载数据（CPU/内存/QPS/副本数）</li>
 *   <li>基于选定算法预测未来 {@link CapacityConfig#getForecastHorizonDays()} 天的资源利用率</li>
 *   <li>当预测值超过阈值（{@link CapacityConfig#getCpuThreshold()} / {@link CapacityConfig#getMemoryThreshold()}）时生成扩容预警</li>
 *   <li>计算推荐副本数（基于预测 QPS 与单副本承载能力）</li>
 * </ol>
 */
@Service
public class CapacityPredictionService {

    private static final Logger log = LoggerFactory.getLogger(CapacityPredictionService.class);

    private final PrometheusMetricsService metricsService;
    private final CapacityConfig config;

    /**
     * 构造服务。
     *
     * @param metricsService Prometheus 指标采集服务
     * @param config         容量配置
     */
    public CapacityPredictionService(PrometheusMetricsService metricsService, CapacityConfig config) {
        this.metricsService = metricsService;
        this.config = config;
    }

    /**
     * 预测服务容量需求。
     *
     * @param serviceName 服务名
     * @return 容量预测结果
     */
    public CapacityForecast forecast(String serviceName) {
        log.info("开始预测服务[{}]容量需求，算法={}", serviceName, config.getAlgorithm());

        List<LoadSample> history = metricsService.queryHistoryLoad(serviceName);
        if (history.isEmpty()) {
            log.warn("服务[{}]无历史负载数据，返回默认预测", serviceName);
            return defaultForecast(serviceName);
        }

        int currentReplicas = metricsService.queryCurrentReplicas(serviceName);
        Instant forecastTime = Instant.now().plus(config.forecastHorizon());

        List<Double> cpuHistory = history.stream().map(LoadSample::cpuUsage).toList();
        List<Double> memoryHistory = history.stream().map(LoadSample::memoryUsage).toList();
        List<Double> qpsHistory = history.stream().map(LoadSample::qps).toList();

        int forecastSteps = (int) (config.forecastHorizon().toMinutes()
                / config.getSampleIntervalMinutes());
        Duration step = Duration.ofMinutes(config.getSampleIntervalMinutes());

        List<Double> cpuForecast = forecastSeries(cpuHistory, forecastSteps);
        List<Double> memoryForecast = forecastSeries(memoryHistory, forecastSteps);
        List<Double> qpsForecast = forecastSeries(qpsHistory, forecastSteps);

        double predictedCpu = cpuForecast.get(cpuForecast.size() - 1);
        double predictedMemory = memoryForecast.get(memoryForecast.size() - 1);
        double predictedQps = qpsForecast.get(qpsForecast.size() - 1);

        int recommendedReplicas = calculateRecommendedReplicas(
                history, predictedQps, predictedCpu, predictedMemory, currentReplicas);

        double confidence = calculateConfidence(history);

        List<CapacityForecast.ForecastPoint> points = buildForecastPoints(
                history.get(history.size() - 1).timestamp(),
                cpuForecast, memoryForecast, qpsForecast, step);

        log.info("服务[{}]预测完成: CPU={}%, 内存={}%, QPS={}, 推荐副本={}/{}",
                serviceName,
                String.format("%.1f", predictedCpu * 100),
                String.format("%.1f", predictedMemory * 100),
                String.format("%.1f", predictedQps),
                recommendedReplicas, currentReplicas);

        return new CapacityForecast(
                serviceName,
                forecastTime,
                predictedCpu,
                predictedMemory,
                predictedQps,
                currentReplicas,
                recommendedReplicas,
                config.getAlgorithm(),
                confidence,
                points
        );
    }

    /**
     * 批量预测所有目标服务。
     *
     * @return 预测结果列表
     */
    public List<CapacityForecast> forecastAll() {
        log.info("批量预测 {} 个目标服务", config.getTargetServices().size());
        return config.getTargetServices().stream()
                .map(this::forecast)
                .toList();
    }

    /**
     * 生成扩容预警。
     *
     * @param forecast 容量预测结果
     * @return 预警列表（CPU/内存）
     */
    public List<CapacityAlert> generateAlerts(CapacityForecast forecast) {
        List<CapacityAlert> alerts = new ArrayList<>();
        Instant triggerTime = Instant.now().plus(Duration.ofHours(config.getScaleAheadHours()));

        double cpuWarningThreshold = config.getCpuThreshold() * 0.9;
        if (forecast.predictedCpuUsage() >= cpuWarningThreshold) {
            alerts.add(CapacityAlert.cpuAlert(
                    forecast.serviceName(),
                    forecast.predictedCpuUsage() * 0.8,
                    forecast.predictedCpuUsage(),
                    config.getCpuThreshold(),
                    forecast.currentReplicas(),
                    forecast.recommendedReplicas(),
                    triggerTime
            ));
        }

        double memoryWarningThreshold = config.getMemoryThreshold() * 0.9;
        if (forecast.predictedMemoryUsage() >= memoryWarningThreshold) {
            alerts.add(CapacityAlert.memoryAlert(
                    forecast.serviceName(),
                    forecast.predictedMemoryUsage() * 0.8,
                    forecast.predictedMemoryUsage(),
                    config.getMemoryThreshold(),
                    forecast.currentReplicas(),
                    forecast.recommendedReplicas(),
                    triggerTime
            ));
        }

        return alerts;
    }

    /**
     * 批量生成所有目标服务的扩容预警。
     *
     * @return 预警列表
     */
    public List<CapacityAlert> generateAllAlerts() {
        return forecastAll().stream()
                .flatMap(f -> generateAlerts(f).stream())
                .toList();
    }

    /**
     * 预测时间序列。
     *
     * @param history 历史数据
     * @param steps   预测步数
     * @return 预测序列
     */
    private List<Double> forecastSeries(List<Double> history, int steps) {
        return switch (config.getAlgorithm()) {
            case "ewma" -> forecastEwma(history, steps);
            case "holt-winters" -> forecastHoltWinters(history, steps);
            default -> forecastLinear(history, steps);
        };
    }

    /**
     * 线性回归预测。
     *
     * <p>基于最小二乘法拟合 y = a * x + b，然后外推预测。</p>
     *
     * @param history 历史数据
     * @param steps   预测步数
     * @return 预测序列
     */
    private List<Double> forecastLinear(List<Double> history, int steps) {
        int n = history.size();
        if (n < 2) {
            return repeatLast(history, steps);
        }

        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumX2 = 0;
        for (int i = 0; i < n; i++) {
            sumX += i;
            sumY += history.get(i);
            sumXY += i * history.get(i);
            sumX2 += (double) i * i;
        }

        double denominator = n * sumX2 - sumX * sumX;
        if (Math.abs(denominator) < 1e-10) {
            return repeatLast(history, steps);
        }

        double slope = (n * sumXY - sumX * sumY) / denominator;
        double intercept = (sumY - slope * sumX) / n;

        List<Double> forecast = new ArrayList<>();
        for (int i = n; i < n + steps; i++) {
            forecast.add(Math.max(0.0, slope * i + intercept));
        }
        return forecast;
    }

    /**
     * 指数加权移动平均预测。
     *
     * @param history 历史数据
     * @param steps   预测步数
     * @return 预测序列
     */
    private List<Double> forecastEwma(List<Double> history, int steps) {
        if (history.isEmpty()) {
            return List.of();
        }

        double alpha = config.getEwmaAlpha();
        double ewma = history.get(0);
        for (int i = 1; i < history.size(); i++) {
            ewma = alpha * history.get(i) + (1 - alpha) * ewma;
        }

        double lastValue = history.get(history.size() - 1);
        double trend = lastValue - ewma;

        List<Double> forecast = new ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            forecast.add(Math.max(0.0, ewma + trend * i));
        }
        return forecast;
    }

    /**
     * Holt-Winters 三次指数平滑预测。
     *
     * @param history 历史数据
     * @param steps   预测步数
     * @return 预测序列
     */
    private List<Double> forecastHoltWinters(List<Double> history, int steps) {
        if (history.size() < 4) {
            return forecastLinear(history, steps);
        }

        double alpha = config.getEwmaAlpha();
        double beta = alpha * 0.3;
        double gamma = alpha * 0.2;
        int seasonLength = Math.min(12, history.size() / 2);

        double level = history.get(0);
        double trend = history.get(1) - history.get(0);
        double[] seasonals = new double[seasonLength];
        double seasonAvg = history.stream().mapToDouble(Double::doubleValue).sum() / seasonLength;
        for (int i = 0; i < seasonLength; i++) {
            seasonals[i] = history.get(i) - seasonAvg;
        }

        for (int i = seasonLength; i < history.size(); i++) {
            double lastLevel = level;
            int seasonIdx = i % seasonLength;
            level = alpha * (history.get(i) - seasonals[seasonIdx])
                    + (1 - alpha) * (level + trend);
            trend = beta * (level - lastLevel) + (1 - beta) * trend;
            seasonals[seasonIdx] = gamma * (history.get(i) - level)
                    + (1 - gamma) * seasonals[seasonIdx];
        }

        List<Double> forecast = new ArrayList<>();
        for (int i = 1; i <= steps; i++) {
            int seasonIdx = (history.size() + i - 1) % seasonLength;
            forecast.add(Math.max(0.0, level + i * trend + seasonals[seasonIdx]));
        }
        return forecast;
    }

    /**
     * 重复最后一个值（数据不足时的回退策略）。
     *
     * @param history 历史数据
     * @param steps   预测步数
     * @return 预测序列
     */
    private List<Double> repeatLast(List<Double> history, int steps) {
        double last = history.isEmpty() ? 0.0 : history.get(history.size() - 1);
        List<Double> forecast = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            forecast.add(last);
        }
        return forecast;
    }

    /**
     * 计算推荐副本数。
     *
     * <p>基于预测 QPS 与单副本承载能力（历史 QPS / 副本数）计算。</p>
     *
     * @param history           历史数据
     * @param predictedQps      预测 QPS
     * @param predictedCpu      预测 CPU 利用率
     * @param predictedMemory   预测内存利用率
     * @param currentReplicas   当前副本数
     * @return 推荐副本数
     */
    private int calculateRecommendedReplicas(List<LoadSample> history, double predictedQps,
                                             double predictedCpu, double predictedMemory,
                                             int currentReplicas) {
        double avgQpsPerReplica = LoadSample.averageQps(history)
                / Math.max(1, LoadSample.averageCpu(history) > 0 ? currentReplicas : 1);
        if (avgQpsPerReplica <= 0) {
            avgQpsPerReplica = 50.0;
        }

        int replicasByQps = (int) Math.ceil(predictedQps / avgQpsPerReplica * 1.2);
        int replicasByCpu = (int) Math.ceil(currentReplicas * predictedCpu / config.getCpuThreshold() * 1.2);
        int replicasByMemory = (int) Math.ceil(currentReplicas * predictedMemory
                / config.getMemoryThreshold() * 1.2);

        int recommended = Math.max(Math.max(replicasByQps, replicasByCpu), replicasByMemory);
        recommended = Math.max(recommended, currentReplicas);
        recommended = Math.min(recommended, currentReplicas * 4);

        return recommended;
    }

    /**
     * 计算预测置信度。
     *
     * <p>基于历史数据量与方差计算，数据越多方差越小置信度越高。</p>
     *
     * @param history 历史数据
     * @return 置信度（0-1）
     */
    private double calculateConfidence(List<LoadSample> history) {
        if (history.size() < 10) {
            return 0.3;
        }

        double meanCpu = LoadSample.averageCpu(history);
        double variance = history.stream()
                .mapToDouble(s -> Math.pow(s.cpuUsage() - meanCpu, 2))
                .average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        double dataConfidence = Math.min(1.0, history.size() / 100.0);
        double stabilityConfidence = Math.max(0.0, 1.0 - stdDev * 5);

        return Math.max(0.1, (dataConfidence + stabilityConfidence) / 2);
    }

    /**
     * 构造预测序列点。
     *
     * @param lastHistoryTime 最后一个历史数据点时间
     * @param cpuForecast     CPU 预测序列
     * @param memoryForecast  内存预测序列
     * @param qpsForecast     QPS 预测序列
     * @param step            步长
     * @return 预测点列表
     */
    private List<CapacityForecast.ForecastPoint> buildForecastPoints(
            Instant lastHistoryTime, List<Double> cpuForecast,
            List<Double> memoryForecast, List<Double> qpsForecast, Duration step) {
        List<CapacityForecast.ForecastPoint> points = new ArrayList<>();
        int size = Math.min(Math.min(cpuForecast.size(), memoryForecast.size()), qpsForecast.size());
        Instant time = lastHistoryTime.plus(step);
        for (int i = 0; i < size; i++) {
            points.add(new CapacityForecast.ForecastPoint(
                    time,
                    cpuForecast.get(i),
                    memoryForecast.get(i),
                    qpsForecast.get(i)
            ));
            time = time.plus(step);
        }
        return points;
    }

    /**
     * 默认预测（无历史数据时）。
     *
     * @param serviceName 服务名
     * @return 默认预测
     */
    private CapacityForecast defaultForecast(String serviceName) {
        int currentReplicas = metricsService.queryCurrentReplicas(serviceName);
        return new CapacityForecast(
                serviceName,
                Instant.now().plus(config.forecastHorizon()),
                0.0,
                0.0,
                0.0,
                currentReplicas,
                currentReplicas,
                config.getAlgorithm(),
                0.0,
                List.of()
        );
    }
}