package com.levango7.dataenginebdp.finops.dashboard.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RealtimeMetricsService} 单元测试。
 *
 * <p>验证返回指标数量、名称、单位、数值范围与时间戳格式。</p>
 */
class RealtimeMetricsServiceTest {

    private RealtimeMetricsService service = new RealtimeMetricsService();

    @Test
    void getRealtimeMetrics_returnsFiveMetrics() {
        List<Map<String, Object>> metrics = service.getRealtimeMetrics("tenant_a");

        assertThat(metrics).hasSize(5);
    }

    @Test
    void getRealtimeMetrics_containsExpectedMetricNames() {
        List<Map<String, Object>> metrics = service.getRealtimeMetrics("tenant_a");

        List<String> names = metrics.stream().map(m -> (String) m.get("name")).toList();
        assertThat(names).containsExactly(
                "cpuUsage", "memoryUsage", "qps", "latency", "activeJobs");
    }

    @Test
    void getRealtimeMetrics_cpuUsageInRange() {
        List<Map<String, Object>> metrics = service.getRealtimeMetrics("tenant_a");

        Map<String, Object> cpu = metrics.get(0);
        assertThat(cpu.get("name")).isEqualTo("cpuUsage");
        assertThat(cpu.get("unit")).isEqualTo("%");
        double value = ((Number) cpu.get("value")).doubleValue();
        assertThat(value).isGreaterThanOrEqualTo(0.0).isLessThan(100.0);
    }

    @Test
    void getRealtimeMetrics_memoryUsageInRange() {
        List<Map<String, Object>> metrics = service.getRealtimeMetrics("tenant_a");

        Map<String, Object> mem = metrics.get(1);
        assertThat(mem.get("name")).isEqualTo("memoryUsage");
        assertThat(mem.get("unit")).isEqualTo("%");
        double value = ((Number) mem.get("value")).doubleValue();
        assertThat(value).isGreaterThanOrEqualTo(0.0).isLessThan(100.0);
    }

    @Test
    void getRealtimeMetrics_qpsInRange() {
        List<Map<String, Object>> metrics = service.getRealtimeMetrics("tenant_a");

        Map<String, Object> qps = metrics.get(2);
        assertThat(qps.get("name")).isEqualTo("qps");
        assertThat(qps.get("unit")).isEqualTo("req/s");
        double value = ((Number) qps.get("value")).doubleValue();
        assertThat(value).isGreaterThanOrEqualTo(0.0).isLessThan(10000.0);
    }

    @Test
    void getRealtimeMetrics_latencyInRange() {
        List<Map<String, Object>> metrics = service.getRealtimeMetrics("tenant_a");

        Map<String, Object> latency = metrics.get(3);
        assertThat(latency.get("name")).isEqualTo("latency");
        assertThat(latency.get("unit")).isEqualTo("ms");
        double value = ((Number) latency.get("value")).doubleValue();
        assertThat(value).isGreaterThanOrEqualTo(1.0).isLessThan(500.0);
    }

    @Test
    void getRealtimeMetrics_activeJobsInRange() {
        List<Map<String, Object>> metrics = service.getRealtimeMetrics("tenant_a");

        Map<String, Object> jobs = metrics.get(4);
        assertThat(jobs.get("name")).isEqualTo("activeJobs");
        assertThat(jobs.get("unit")).isEqualTo("count");
        int value = ((Number) jobs.get("value")).intValue();
        assertThat(value).isGreaterThanOrEqualTo(0).isLessThanOrEqualTo(100);
    }

    @Test
    void getRealtimeMetrics_hasTimestamp() {
        List<Map<String, Object>> metrics = service.getRealtimeMetrics("tenant_a");

        for (Map<String, Object> metric : metrics) {
            assertThat(metric.get("timestamp")).isNotNull();
            assertThat(metric.get("timestamp").toString()).isNotBlank();
        }
    }

    @Test
    void getRealtimeMetrics_nullTenantId_stillReturnsMetrics() {
        List<Map<String, Object>> metrics = service.getRealtimeMetrics(null);

        assertThat(metrics).hasSize(5);
    }
}