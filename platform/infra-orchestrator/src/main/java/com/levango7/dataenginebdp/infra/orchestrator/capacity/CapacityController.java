package com.levango7.dataenginebdp.infra.orchestrator.capacity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 容量规划 Controller（v2.1 生产化加固）。
 *
 * <p>提供容量预测与扩容预警 REST API：</p>
 * <ul>
 *   <li>{@code GET /api/v1/capacity/forecast/{service}} - 预测单个服务容量需求</li>
 *   <li>{@code GET /api/v1/capacity/forecast} - 批量预测所有目标服务</li>
 *   <li>{@code GET /api/v1/capacity/alerts} - 查询所有扩容预警</li>
 *   <li>{@code GET /api/v1/capacity/alerts/{service}} - 查询单个服务扩容预警</li>
 *   <li>{@code GET /api/v1/capacity/config} - 查询容量规划配置</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/capacity")
public class CapacityController {

    private static final Logger log = LoggerFactory.getLogger(CapacityController.class);

    private final CapacityPredictionService predictionService;
    private final CapacityConfig config;

    /**
     * 构造 Controller。
     *
     * @param predictionService 容量预测服务
     * @param config            容量配置
     */
    public CapacityController(CapacityPredictionService predictionService, CapacityConfig config) {
        this.predictionService = predictionService;
        this.config = config;
    }

    /**
     * 预测单个服务容量需求。
     *
     * @param service 服务名
     * @return 容量预测结果
     */
    @GetMapping("/forecast/{service}")
    public ResponseEntity<CapacityForecast> forecast(@PathVariable String service) {
        log.info("API 调用: 容量预测服务[{}]", service);
        if (!config.isEnabled()) {
            return ResponseEntity.ok(new CapacityForecast(
                    service, java.time.Instant.now(), 0, 0, 0, 0, 0,
                    config.getAlgorithm(), 0, List.of()));
        }
        CapacityForecast forecast = predictionService.forecast(service);
        return ResponseEntity.ok(forecast);
    }

    /**
     * 批量预测所有目标服务。
     *
     * @return 预测结果列表
     */
    @GetMapping("/forecast")
    public ResponseEntity<List<CapacityForecast>> forecastAll() {
        log.info("API 调用: 批量容量预测 {} 个服务", config.getTargetServices().size());
        if (!config.isEnabled()) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(predictionService.forecastAll());
    }

    /**
     * 查询所有扩容预警。
     *
     * @param service 可选服务名过滤
     * @return 预警列表
     */
    @GetMapping("/alerts")
    public ResponseEntity<List<CapacityAlert>> alerts(
            @RequestParam(required = false) String service) {
        log.info("API 调用: 查询扩容预警, service={}", service);
        if (!config.isEnabled()) {
            return ResponseEntity.ok(List.of());
        }

        List<CapacityAlert> alerts = predictionService.generateAllAlerts();
        if (service != null && !service.isBlank()) {
            alerts = alerts.stream()
                    .filter(a -> a.serviceName().equals(service))
                    .toList();
        }
        return ResponseEntity.ok(alerts);
    }

    /**
     * 查询单个服务扩容预警。
     *
     * @param service 服务名
     * @return 预警列表
     */
    @GetMapping("/alerts/{service}")
    public ResponseEntity<List<CapacityAlert>> alertsByService(@PathVariable String service) {
        log.info("API 调用: 查询服务[{}]扩容预警", service);
        if (!config.isEnabled()) {
            return ResponseEntity.ok(List.of());
        }
        CapacityForecast forecast = predictionService.forecast(service);
        return ResponseEntity.ok(predictionService.generateAlerts(forecast));
    }

    /**
     * 查询容量规划配置。
     *
     * @return 配置信息
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> config() {
        return ResponseEntity.ok(Map.of(
                "enabled", config.isEnabled(),
                "prometheusAddress", config.getPrometheusAddress(),
                "historyWindowDays", config.getHistoryWindowDays(),
                "forecastHorizonDays", config.getForecastHorizonDays(),
                "cpuThreshold", config.getCpuThreshold(),
                "memoryThreshold", config.getMemoryThreshold(),
                "scaleAheadHours", config.getScaleAheadHours(),
                "sampleIntervalMinutes", config.getSampleIntervalMinutes(),
                "algorithm", config.getAlgorithm(),
                "ewmaAlpha", config.getEwmaAlpha(),
                "targetServices", config.getTargetServices()
        ));
    }
}