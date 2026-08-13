package com.levango7.dataenginebdp.finops.dashboard.collector;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

/**
 * 账单查询客户端（dashboard → cost-model）。
 *
 * <p>透传调用 cost-model 的 {@code /api/v1/finops/billing/tenant} 端点，
 * 支持按日期窗口拉取当前租户的查询计费账单。</p>
 */
@Component
public class QueryBillingClient {

    private static final Logger log = LoggerFactory.getLogger(QueryBillingClient.class);

    private final RestClient restClient;
    private final String costModelUrl;

    public QueryBillingClient(@Value("${app.cost-model.url:http://localhost:18084}") String costModelUrl) {
        this.costModelUrl = costModelUrl;
        this.restClient = RestClient.builder()
                .baseUrl(costModelUrl)
                .build();
        log.info("账单查询客户端已初始化: {}", costModelUrl);
    }

    /**
     * 拉取某租户指定窗口的账单（透传 cost-model）。
     *
     * @param tenantId  租户（通常来自 TenantContext）
     * @param startDate 起始日期（含，可空）
     * @param endDate   结束日期（含，可空）
     * @return cost-model 返回的账单 Map
     */
    public Map<String, Object> fetchTenantBilling(String tenantId,
                                                  LocalDate startDate, LocalDate endDate) {
        log.debug("拉取账单: tenant={}, range=[{} ~ {}]", tenantId, startDate, endDate);
        return restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/api/v1/finops/billing/tenant");
                    if (startDate != null) {
                        builder.queryParam("startDate", startDate.toString());
                    }
                    if (endDate != null) {
                        builder.queryParam("endDate", endDate.toString());
                    }
                    // 租户 ID 以 cost-model 侧 TenantContext 为准（同集群 / 由 JWT 注入）
                    return builder.build();
                })
                .header("X-Tenant-Id", tenantId)
                .retrieve()
                .body(Map.class);
    }

    /**
     * 拉取某租户的按日账单趋势（透传 cost-model）。
     *
     * @param tenantId  租户（通常来自 TenantContext）
     * @param startDate 起始日期（含，可空，默认近 7 天）
     * @param endDate   结束日期（含，可空）
     * @return cost-model 返回的趋势 Map（含 points 列表）
     */
    public Map<String, Object> fetchTenantBillingTrend(String tenantId,
                                                       LocalDate startDate, LocalDate endDate) {
        return restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/api/v1/finops/billing/tenant/trend");
                    if (startDate != null) {
                        builder.queryParam("startDate", startDate.toString());
                    }
                    if (endDate != null) {
                        builder.queryParam("endDate", endDate.toString());
                    }
                    return builder.build();
                })
                .header("X-Tenant-Id", tenantId)
                .retrieve()
                .body(Map.class);
    }

    /**
     * 获取客户端连接超时（供监控使用）。
     */
    public Duration getTimeout() {
        return Duration.ofSeconds(10);
    }
}