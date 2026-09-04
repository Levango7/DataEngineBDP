package com.levango7.dataenginebdp.common.security.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * 速率限制自动装配（C1）。
 *
 * <p>配置项（均有默认值，默认启用）：</p>
 * <ul>
 *   <li>{@code app.security.ratelimit.enabled}：总开关（默认 true）</li>
 *   <li>{@code app.security.ratelimit.login-rpm}：登录/注册每分钟（默认 5，按 IP）</li>
 *   <li>{@code app.security.ratelimit.write-rpm}：写操作每分钟（默认 100，按租户）</li>
 *   <li>{@code app.security.ratelimit.read-rpm}：读操作每分钟（默认 600，按租户）</li>
 *   <li>{@code app.security.ratelimit.burst-factor}：突发倍率（默认 1.5）</li>
 * </ul>
 *
 * <p>规则映射（路径前缀 → 速率）：</p>
 * <ul>
 *   <li>{@code /api/v1/auth} → login-rpm（登录爆破防护，IP 维度）</li>
 *   <li>{@code POST/PUT/DELETE 无法在纯前缀层区分}——写操作通过给
 *       {@code /api/v1/gateway}、{@code /api/v1/dag} 等高风险前缀叠加 write-rpm；
 *       其余 {@code /api/} 全量 read-rpm 兜底。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "app.security.ratelimit.enabled", havingValue = "true", matchIfMissing = true)
public class RateLimitAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RateLimitAutoConfiguration.class);

    /**
     * 规则注册表：按配置构建规则集。
     *
     * <p>顺序即优先级：越靠前越先匹配。登录接口最严，高风险写前缀次之，通用读兜底。</p>
     */
    @Bean
    @ConditionalOnMissingBean(RateLimitRegistry.class)
    public RateLimitRegistry rateLimitRegistry(
            @Value("${app.security.ratelimit.login-rpm:5}") int loginRpm,
            @Value("${app.security.ratelimit.write-rpm:100}") int writeRpm,
            @Value("${app.security.ratelimit.read-rpm:600}") int readRpm,
            @Value("${app.security.ratelimit.burst-factor:1.5}") double burstFactor,
            @Value("${app.security.ratelimit.idle-eviction-seconds:600}") long idleEvictionSeconds) {

        List<RateLimitRule> rules = new ArrayList<>();
        // 登录/注册：最严，按 IP（未登录场景 TenantContext 为空自然走 IP 维度）
        rules.add(new RateLimitRule("/api/v1/auth", loginRpm, burstFactor));
        // 高风险写前缀：提交训练/DAG/推理调用/网关密钥操作
        rules.add(new RateLimitRule("/api/v1/dev-sched", writeRpm, burstFactor));
        rules.add(new RateLimitRule("/api/v1/dev-ml", writeRpm, burstFactor));
        rules.add(new RateLimitRule("/api/v1/gateway/keys", writeRpm, burstFactor));
        // 通用 API 兜底：读为主的稳态速率
        rules.add(new RateLimitRule("/api/", readRpm, burstFactor));

        log.info("速率限制已启用: login={}rpm, write={}rpm, read={}rpm, burst={}x",
                loginRpm, writeRpm, readRpm, burstFactor);
        return new RateLimitRegistry(rules, idleEvictionSeconds, 60);
    }

    /** 速率限制过滤器：挂到 SecurityFilterChain 最前端（JwtAuthFilter 之前）。 */
    @Bean
    @ConditionalOnMissingBean(RateLimitFilter.class)
    public RateLimitFilter rateLimitFilter(RateLimitRegistry registry) {
        return new RateLimitFilter(registry);
    }
}
