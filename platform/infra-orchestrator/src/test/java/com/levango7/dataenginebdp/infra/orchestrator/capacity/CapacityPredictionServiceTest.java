package com.levango7.dataenginebdp.infra.orchestrator.capacity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 容量预测服务单元测试（v2.1 容量规划）。
 */
class CapacityPredictionServiceTest {

    private PrometheusMetricsService metricsService;
    private CapacityConfig config;
    private CapacityPredictionService predictionService;

    @BeforeEach
    void setUp() {
        metricsService = mock(PrometheusMetricsService.class);
        config = new CapacityConfig();
        config.setEnabled(true);
        config.setAlgorithm("linear");
        config.setCpuThreshold(0.8);
        config.setMemoryThreshold(0.85);
        config.setForecastHorizonDays(1);
        config.setSampleIntervalMinutes(5);
        config.setScaleAheadHours(24);
        predictionService = new CapacityPredictionService(metricsService, config);
    }

    @Test
    void shouldReturnDefaultForecastWhenNoHistory() {
        when(metricsService.queryHistoryLoad("svc")).thenReturn(List.of());
        when(metricsService.queryCurrentReplicas("svc")).thenReturn(3);

        CapacityForecast forecast = predictionService.forecast("svc");

        assertNotNull(forecast);
        assertEquals("svc", forecast.serviceName());
        assertEquals(3, forecast.currentReplicas());
        assertEquals(3, forecast.recommendedReplicas());
        assertEquals(0.0, forecast.confidence());
    }

    @Test
    void shouldForecastWithLinearAlgorithm() {
        List<LoadSample> history = buildIncreasingLoadHistory("svc", 20);
        when(metricsService.queryHistoryLoad("svc")).thenReturn(history);
        when(metricsService.queryCurrentReplicas("svc")).thenReturn(3);

        CapacityForecast forecast = predictionService.forecast("svc");

        assertNotNull(forecast);
        assertEquals("svc", forecast.serviceName());
        assertEquals("linear", forecast.algorithm());
        assertTrue(forecast.predictedCpuUsage() > 0, "预测 CPU 应大于 0");
        assertTrue(forecast.confidence() > 0, "置信度应大于 0");
        assertFalse(forecast.forecastPoints().isEmpty(), "预测序列点不应为空");
    }

    @Test
    void shouldGenerateCpuAlertWhenExceedingThreshold() {
        CapacityForecast forecast = new CapacityForecast(
                "svc", Instant.now(), 0.9, 0.5, 100, 3, 5,
                "linear", 0.8, List.of());

        List<CapacityAlert> alerts = predictionService.generateAlerts(forecast);

        assertFalse(alerts.isEmpty(), "应生成 CPU 预警");
        assertTrue(alerts.stream().anyMatch(a -> a.metric().equals("CPU")));
    }

    @Test
    void shouldGenerateMemoryAlertWhenExceedingThreshold() {
        CapacityForecast forecast = new CapacityForecast(
                "svc", Instant.now(), 0.5, 0.9, 100, 3, 5,
                "linear", 0.8, List.of());

        List<CapacityAlert> alerts = predictionService.generateAlerts(forecast);

        assertFalse(alerts.isEmpty(), "应生成内存预警");
        assertTrue(alerts.stream().anyMatch(a -> a.metric().equals("MEMORY")));
    }

    @Test
    void shouldNotGenerateAlertWhenBelowThreshold() {
        CapacityForecast forecast = new CapacityForecast(
                "svc", Instant.now(), 0.3, 0.4, 100, 3, 3,
                "linear", 0.8, List.of());

        List<CapacityAlert> alerts = predictionService.generateAlerts(forecast);

        assertTrue(alerts.isEmpty(), "低于阈值不应生成预警");
    }

    @Test
    void shouldForecastWithEwmaAlgorithm() {
        config.setAlgorithm("ewma");
        List<LoadSample> history = buildIncreasingLoadHistory("svc", 20);
        when(metricsService.queryHistoryLoad("svc")).thenReturn(history);
        when(metricsService.queryCurrentReplicas("svc")).thenReturn(3);

        CapacityForecast forecast = predictionService.forecast("svc");

        assertNotNull(forecast);
        assertEquals("ewma", forecast.algorithm());
        assertTrue(forecast.predictedCpuUsage() >= 0);
    }

    @Test
    void shouldForecastWithHoltWintersAlgorithm() {
        config.setAlgorithm("holt-winters");
        List<LoadSample> history = buildIncreasingLoadHistory("svc", 30);
        when(metricsService.queryHistoryLoad("svc")).thenReturn(history);
        when(metricsService.queryCurrentReplicas("svc")).thenReturn(3);

        CapacityForecast forecast = predictionService.forecast("svc");

        assertNotNull(forecast);
        assertEquals("holt-winters", forecast.algorithm());
    }

    @Test
    void shouldCalculateRecommendedReplicasNotLessThanCurrent() {
        List<LoadSample> history = buildStableLoadHistory("svc", 20);
        when(metricsService.queryHistoryLoad("svc")).thenReturn(history);
        when(metricsService.queryCurrentReplicas("svc")).thenReturn(5);

        CapacityForecast forecast = predictionService.forecast("svc");

        assertTrue(forecast.recommendedReplicas() >= forecast.currentReplicas(),
                "推荐副本数不应小于当前副本数");
    }

    /**
     * 构造递增负载历史数据。
     *
     * @param serviceName 服务名
     * @param size        数据点数量
     * @return 历史数据列表
     */
    private List<LoadSample> buildIncreasingLoadHistory(String serviceName, int size) {
        List<LoadSample> samples = new ArrayList<>();
        Instant start = Instant.now().minusSeconds(size * 300L);
        for (int i = 0; i < size; i++) {
            double cpu = 0.3 + i * 0.02;
            double memory = 0.4 + i * 0.015;
            double qps = 50 + i * 5;
            samples.add(new LoadSample(start.plusSeconds(i * 300L), cpu, memory, 3, qps));
        }
        return samples;
    }

    /**
     * 构造稳定负载历史数据。
     *
     * @param serviceName 服务名
     * @param size        数据点数量
     * @return 历史数据列表
     */
    private List<LoadSample> buildStableLoadHistory(String serviceName, int size) {
        List<LoadSample> samples = new ArrayList<>();
        Instant start = Instant.now().minusSeconds(size * 300L);
        for (int i = 0; i < size; i++) {
            samples.add(new LoadSample(start.plusSeconds(i * 300L), 0.4, 0.5, 5, 100));
        }
        return samples;
    }
}