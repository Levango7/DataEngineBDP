package com.levango7.dataenginebdp.finops.dashboard.service;

import com.levango7.dataenginebdp.finops.dashboard.collector.PrometheusQueryClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时指标服务。
 *
 * <p>为 {@link com.levango7.dataenginebdp.finops.dashboard.controller.BiDashboardController}
 * 的 {@code realtime} 端点提供实时指标数据。</p>
 *
 * <p><b>2026-11-09 改造（可售性补缺批 3）</b>：原实现用 {@code ThreadLocalRandom}
 * 生成「合理范围内的模拟数据」——政务演示场景下一旦被识破即重大风险，且与
 * 「宁缺毋假」的部署口径冲突。现改为经 {@link PrometheusQueryClient} 执行 PromQL
 * instant query 取真实值：<b>Prometheus 未接入 / 查询失败 / 无结果时诚实返回空
 * 列表</b>（不再伪造）。注意：本服务所在 finops/dashboard 的
 * {@code /api/v1/dashboards/**} 前缀在网关侧由 business-portal 承接（PREFIX_OWNER_OVERRIDES
 * 既有裁决），本端点当前主要在集群内/直连场景使用；对外的 business-portal 版
 * realtime 本就是「provider 缺省 ⇒ 空列表」的诚实实现。</p>
 *
 * <p>PromQL 表达式按 node_exporter 与 Micrometer/Flink 命名口径编写，现场按实际
 * exporter 命名可调；<b>指标口径可调不影响契约</b>（返回项仍为 name/value/unit/timestamp）。</p>
 */
@Service
public class RealtimeMetricsService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeMetricsService.class);

    /** 指标 → PromQL + 单位（有序：与前端展示顺序一致）。 */
    static final Map<String, Query> QUERIES = new LinkedHashMap<>();

    static {
        QUERIES.put("cpuUsage",
                new Query("avg(1 - rate(node_cpu_seconds_total{mode=\"idle\"}[5m])) * 100", "%"));
        QUERIES.put("memoryUsage",
                new Query("(1 - avg(node_memory_MemAvailable_bytes / node_memory_MemTotal_bytes)) * 100", "%"));
        QUERIES.put("qps",
                new Query("sum(rate(http_requests_total[5m]))", "req/s"));
        QUERIES.put("latency",
                new Query("avg(rate(http_server_requests_seconds_sum[5m])"
                        + " / rate(http_server_requests_seconds_count[5m])) * 1000", "ms"));
        QUERIES.put("activeJobs",
                new Query("sum(flink_jobmanager_numRunningJobs)", "count"));
    }

    /** 查询定义（PromQL 与单位）。 */
    record Query(String promql, String unit) {
    }

    private final PrometheusQueryClient prometheus;

    public RealtimeMetricsService(PrometheusQueryClient prometheus) {
        this.prometheus = prometheus;
    }

    /**
     * 查询实时指标列表（Prometheus 真实查询；不可达/无结果时返回空列表）。
     *
     * @param tenantId 租户 ID（用于日志与后续多租户指标隔离）
     * @return 实时指标列表，每项包含 name/value/unit/timestamp；无可用数据时为空列表
     */
    public List<Map<String, Object>> getRealtimeMetrics(String tenantId) {
        Instant now = Instant.now();
        List<Map<String, Object>> metrics = new ArrayList<>();
        for (Map.Entry<String, Query> e : QUERIES.entrySet()) {
            Double value = queryScalar(e.getValue().promql());
            if (value != null) {
                metrics.add(metric(e.getKey(), value, e.getValue().unit(), now));
            }
        }
        if (metrics.isEmpty()) {
            // 宁缺毋假：不伪造数据，如实上报不可用
            log.info("实时指标不可用（Prometheus 未接入或查询无结果）: tenant={}, url={}",
                    tenantId, prometheus.getPrometheusUrl());
        } else {
            log.info("查询实时指标: tenant={}, metricCount={}", tenantId, metrics.size());
        }
        return metrics;
    }

    /** 执行 instant query 并提取标量；不可达/无结果/解析失败一律返回 null（诚实跳过）。 */
    private Double queryScalar(String promql) {
        try {
            return extractScalar(prometheus.instantQuery(promql));
        } catch (Exception e) {
            log.debug("PromQL 查询失败: query={}, err={}", promql, e.getMessage());
            return null;
        }
    }

    /**
     * 从 Prometheus instant query 响应提取标量值。
     *
     * <p>响应形状：{@code {"status":"success","data":{"resultType":"vector",
     * "result":[{"metric":{...},"value":[<ts>,"<value>"]}]}}}——取
     * {@code data.result[0].value[1]} 并解析为 double。</p>
     *
     * @param resp instant query 响应（可空）
     * @return 标量值；结构缺失或数值非法时返回 {@code null}
     */
    static Double extractScalar(Map<String, Object> resp) {
        if (resp == null) {
            return null;
        }
        Object data = resp.get("data");
        if (!(data instanceof Map<?, ?> d)) {
            return null;
        }
        Object result = d.get("result");
        if (!(result instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        if (!(list.get(0) instanceof Map<?, ?> first)) {
            return null;
        }
        Object value = first.get("value");
        if (!(value instanceof List<?> v) || v.size() < 2) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(v.get(1)));
        } catch (NumberFormatException e) {
            return null;
        }
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
}
