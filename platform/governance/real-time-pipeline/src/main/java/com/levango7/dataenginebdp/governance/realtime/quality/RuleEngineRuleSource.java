package com.levango7.dataenginebdp.governance.realtime.quality;

import com.levango7.dataenginebdp.common.security.ServiceTokenMinter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 从 rule-engine 拉取质量规则（台账 #6 阶段 3，Q1 裁决：rule-engine 为权威）。
 *
 * <p>这是 pipeline 侧"质量规则唯一来源"的接线：拉 {@code GET /api/v1/quality/rules}
 * （rule-engine 的持久化规则，含租户隔离），经 {@link RuleEngineRuleMapper} 归一为
 * {@link QualityRule} 交给引擎。**默认关**（{@code governance.quality.source-enabled=false}），
 * 保持改动 additive；开启后由 {@link QualityRuleRefreshScheduler} 启动 + 定时刷新。
 *
 * <p>鉴权：与 {@code LineageIngestClient} 同源——服务端只认 JWT 声明租户，
 * 故随调用用 {@link ServiceTokenMinter} 签发短 TTL 服务令牌（{@code iss/tenantId/act=service}）。
 *
 * <p>已知边界（后续项）：当前按**单个服务租户**拉取（多租户逐租户拉取需按租户编排，
 * 见草案阶段 3 的后续细化）；rule-engine 不可用时**抛异常**，由引擎降级为"沿用上次缓存"。
 */
@Component
public class RuleEngineRuleSource implements QualityRuleSource {

    private static final Logger log = LoggerFactory.getLogger(RuleEngineRuleSource.class);

    /** rule-engine 质量规则列表路径（分页契约 page/pageSize）。 */
    static final String LIST_PATH = "/api/v1/quality/rules?page=1&pageSize=100";

    @Value("${app.security.jwt.secret:}")
    private String jwtSecret;

    @Value("${governance.service.issuer:dataenginebdp-pipeline}")
    private String issuer;

    private final RestClient restClient;
    private final boolean enabled;
    private final String serviceTenant;

    public RuleEngineRuleSource(
            @Value("${governance.rule-engine.url:http://rule-engine:8083}") String baseUrl,
            @Value("${governance.quality.source-enabled:false}") boolean enabled,
            @Value("${governance.quality.service-tenant:platform-admin}") String serviceTenant) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.enabled = enabled;
        this.serviceTenant = serviceTenant;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<QualityRule> fetchRules() {
        String bearer = ServiceTokenMinter.mint(jwtSecret, issuer, serviceTenant, "real-time-pipeline");
        Map<String, Object> body = restClient.get()
                .uri(LIST_PATH)
                .header("Authorization", "Bearer " + bearer)
                .header("X-Tenant-Id", serviceTenant)
                .retrieve()
                .body(Map.class);

        List<Map<String, Object>> views = RuleEngineRuleMapper.extractList(body);
        List<QualityRule> rules = RuleEngineRuleMapper.mapAll(views, serviceTenant);
        if (rules.size() < views.size()) {
            log.warn("部分 rule-engine 规则无法映射被跳过：views={} mapped={}", views.size(), rules.size());
        }
        return rules;
    }
}
