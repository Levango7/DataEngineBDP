package com.levango7.dataenginebdp.finops.dashboard.service;

import com.levango7.dataenginebdp.finops.dashboard.collector.PrometheusQueryClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link RealtimeMetricsService} 单元测试。
 *
 * <p>2026-11-09 批 3 语义更新：实现从「ThreadLocalRandom 模拟数据」改为
 * 「Prometheus 真实查询；不可达/无结果返回空列表」。原 8 例中的「数值范围」
 * 断言（随机数产物）改写为「mock 查询值被精确映射」（强度更高），并新增诚实性
 * 钉子：不可达 ⇒ 空、空结果 ⇒ 空、部分失败 ⇒ 仅返回可用部分——<b>绝不伪造</b>。</p>
 */
class RealtimeMetricsServiceTest {

    private static Map<String, Object> scalarResponse(String value) {
        return Map.of("status", "success", "data",
                Map.of("resultType", "vector", "result",
                        List.of(Map.of("metric", Map.of(),
                                "value", List.of(1731000000.0, value)))));
    }

    /** 默认桩：所有 PromQL 查询返回同一标量。 */
    private static RealtimeMetricsService serviceWithAll(String value) {
        PrometheusQueryClient client = mock(PrometheusQueryClient.class);
        when(client.instantQuery(anyString())).thenReturn(scalarResponse(value));
        return new RealtimeMetricsService(client);
    }

    @Test
    void getRealtimeMetrics_returnsFiveMetrics() {
        assertThat(serviceWithAll("42.5").getRealtimeMetrics("tenant_a")).hasSize(5);
    }

    @Test
    void getRealtimeMetrics_containsExpectedMetricNames() {
        List<Map<String, Object>> metrics = serviceWithAll("42.5").getRealtimeMetrics("tenant_a");

        List<String> names = metrics.stream().map(m -> (String) m.get("name")).toList();
        assertThat(names).containsExactly(
                "cpuUsage", "memoryUsage", "qps", "latency", "activeJobs");
    }

    @Test
    void getRealtimeMetrics_mapsValuesUnitsAndTimestamp() {
        List<Map<String, Object>> metrics = serviceWithAll("42.5").getRealtimeMetrics("tenant_a");

        Map<String, Object> cpu = metrics.get(0);
        assertThat(cpu.get("name")).isEqualTo("cpuUsage");
        assertThat(cpu.get("unit")).isEqualTo("%");
        assertThat(((Number) cpu.get("value")).doubleValue()).isEqualTo(42.5);
        assertThat(cpu.get("timestamp")).isNotNull();
        assertThat(cpu.get("timestamp").toString()).isNotBlank();
        assertThat(metrics.get(1).get("unit")).isEqualTo("%"); // memoryUsage
        assertThat(metrics.get(2).get("unit")).isEqualTo("req/s"); // qps
        assertThat(metrics.get(3).get("unit")).isEqualTo("ms"); // latency
        assertThat(metrics.get(4).get("unit")).isEqualTo("count"); // activeJobs
    }

    @Test
    void getRealtimeMetrics_prometheus不可达时返回空列表而非伪造数据() {
        PrometheusQueryClient client = mock(PrometheusQueryClient.class);
        when(client.instantQuery(anyString())).thenThrow(new RuntimeException("connection refused"));

        assertThat(new RealtimeMetricsService(client).getRealtimeMetrics("tenant_a")).isEmpty();
    }

    @Test
    void getRealtimeMetrics_空结果集返回空列表() {
        PrometheusQueryClient client = mock(PrometheusQueryClient.class);
        when(client.instantQuery(anyString()))
                .thenReturn(Map.of("data", Map.of("result", List.of())));

        assertThat(new RealtimeMetricsService(client).getRealtimeMetrics("tenant_a")).isEmpty();
    }

    @Test
    void getRealtimeMetrics_部分指标失败仅返回可用指标() {
        PrometheusQueryClient client = mock(PrometheusQueryClient.class);
        // 前两次成功、其后抛异常
        when(client.instantQuery(anyString()))
                .thenReturn(scalarResponse("10"))
                .thenReturn(scalarResponse("20"))
                .thenThrow(new RuntimeException("boom"));

        List<Map<String, Object>> metrics =
                new RealtimeMetricsService(client).getRealtimeMetrics("tenant_a");

        assertThat(metrics).hasSize(2);
        assertThat(((Number) metrics.get(0).get("value")).doubleValue()).isEqualTo(10.0);
        assertThat(((Number) metrics.get(1).get("value")).doubleValue()).isEqualTo(20.0);
    }

    @Test
    void getRealtimeMetrics_nullTenantId_stillReturnsMetrics() {
        assertThat(serviceWithAll("1").getRealtimeMetrics(null)).hasSize(5);
    }

    @Test
    void extractScalar边界() {
        assertThat(RealtimeMetricsService.extractScalar(null)).isNull();
        assertThat(RealtimeMetricsService.extractScalar(Map.of())).isNull();
        assertThat(RealtimeMetricsService.extractScalar(
                Map.of("data", Map.of("result", List.of())))).isNull();
        assertThat(RealtimeMetricsService.extractScalar(scalarResponse("7.25"))).isEqualTo(7.25);
        // 数值非法
        assertThat(RealtimeMetricsService.extractScalar(scalarResponse("NaN-text"))).isNull();
    }
}
