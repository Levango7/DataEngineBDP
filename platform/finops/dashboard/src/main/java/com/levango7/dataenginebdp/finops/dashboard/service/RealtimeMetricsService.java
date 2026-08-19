package com.levango7.dataenginebdp.finops.dashboard.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 实时指标服务。
 *
 * <p>为 {@link com.levango7.dataenginebdp.finops.dashboard.controller.BiDashboardController}
 * 的 {@code realtime} 端点提供实时指标数据。</p>
 *
 * <p>MVP 阶段使用 {@link ThreadLocalRandom} 生成合理范围内的模拟数据：
 * <ul>
 *   <li>cpuUsage：CPU 利用率，0%–100%</li>
 *   <li>memoryUsage：内存利用率，0%–100%</li>
 *   <li>qps：每秒请求数，0–10000</li>
 *   <li>latency：平均延迟，1–500 ms</li>
 *   <li>activeJobs：活跃任务数，0–100</li>
 * </ul>
 * 后续可替换为 Prometheus 查询（参见
 * {@link com.levango7.dataenginebdp.finops.dashboard.collector.PrometheusQueryClient}）。</p>
 */
@Service
public class RealtimeMetricsService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeMetricsService.class);

    /**
     * 查询实时指标列表。
     *
     * @param tenantId 租户 ID（用于后续多租户指标隔离）
     * @return 实时指标列表，每项包含 name/value/unit/timestamp
     */
    public List<Map<String, Object>> getRealtimeMetrics(String tenantId) {
        Instant now = Instant.now();
        ThreadLocalRandom random = ThreadLocalRandom.current();

        List<Map<String, Object>> metrics = new ArrayList<>();
        metrics.add(metric("cpuUsage", random.nextDouble(0.0, 100.0), "%", now));
        metrics.add(metric("memoryUsage", random.nextDouble(0.0, 100.0), "%", now));
        metrics.add(metric("qps", random.nextDouble(0.0, 10000.0), "req/s", now));
        metrics.add(metric("latency", random.nextDouble(1.0, 500.0), "ms", now));
        metrics.add(metric("activeJobs", random.nextInt(0, 101), "count", now));

        log.info("查询实时指标: tenant={}, metricCount={}", tenantId, metrics.size());
        return metrics;
    }

    /**
     * 构造单个指标视图。
     *
     * @param name      指标名
     * @param value     指标值
     * @param unit      单位
     * @param timestamp 时间戳
     * @return 指标视图
     */
    private Map<String, Object> metric(String name, double value, String unit, Instant timestamp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        m.put("unit", unit);
        m.put("timestamp", timestamp.toString());
        return m;
    }

    /**
     * 构造单个指标视图（int 重载，避免 activeJobs 序列化为 double）。
     *
     * @param name      指标名
     * @param value     指标值
     * @param unit      单位
     * @param timestamp 时间戳
     * @return 指标视图
     */
    private Map<String, Object> metric(String name, int value, String unit, Instant timestamp) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("value", value);
        m.put("unit", unit);
        m.put("timestamp", timestamp.toString());
        return m;
    }
}