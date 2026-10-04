package com.levango7.dataenginebdp.common.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.UUID;

/**
 * 服务间调用令牌签发器（裁决 A：服务账号 JWT 换发）。
 *
 * <p>背景：治理闭环的两跳（metadata-collector → real-time-pipeline → lineage-analyzer）
 * 是所有调用均为**服务发起、无用户请求上下文**的异步链路。而 lineage-analyzer 的
 * 租户过滤器只从 JWT 声明取租户（{@code TenantContextFilter.java:153-155}），
 * 且要求 {@code iss} 与 {@code tenantId} 同时存在（:40）——探针实测：只带
 * {@code X-Tenant-Id} 头会 403，接线存在但调用通不过（契约 T4）。
 *
 * <p>声明口径（三条为服务端可接受的最小集，另加可审计项）：
 * <ul>
 *   <li>{@code iss}      —— 签发者（服务端校验存在性）</li>
 *   <li>{@code tenantId} —— 租户归属（服务端据此写 TenantContext）</li>
 *   <li>{@code act}      —— 固定 {@code service}：让审计能区分"服务写"与"用户写"</li>
 *   <li>{@code jti}      —— 唯一标识，便于重放排查</li>
 *   <li>{@code sub}      —— 服务名（谁发的）</li>
 * </ul>
 *
 * <p><b>已知残余风险（不因本类消除）</b>：验签使用与用户令牌同一把对称密钥
 * （{@code SecurityConfig.java:105} {@code NimbusJwtDecoder.withSecretKey}；
 * {@code TenantContextFilter.java:38} 注释"同一把密钥"）。因此**持有 JWT_SECRET 者
 * 天然可伪造任意租户令牌**——本类只是让服务调用可审计、可归属、可过期，
 * 真正消除该风险需转非对称签名（私钥仅签发方持有），属独立改造。
 *
 * <p>TTL 取短（默认 5 分钟）：对称密钥无吊销机制，短 TTL 是唯一现实约束。
 */
public final class ServiceTokenMinter {

    /** 服务端要求的最小 TTL 上限（对称密钥无吊销，短 TTL 是唯一现实约束）。 */
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private ServiceTokenMinter() {
    }

    /**
     * 签发服务间调用令牌。
     *
     * @param secret      JWT 密钥（{@code app.security.jwt.secret}，至少 32 字节，否则拒绝签发）
     * @param issuer      签发者标识（对应服务端校验的 iss 声明）
     * @param tenantId    租户归属（服务端据此填充 TenantContext）
     * @param serviceName 服务名（写入 sub）
     * @return 紧凑序列化的 JWT
     */
    public static String mint(String secret, String issuer, String tenantId, String serviceName) {
        return mint(secret, issuer, tenantId, serviceName, DEFAULT_TTL);
    }

    public static String mint(String secret, String issuer, String tenantId,
                              String serviceName, Duration ttl) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            // fail-loud：与 AuthController.java:269 同一口径，不静默降级成无凭据调用
            throw new IllegalStateException("JWT_SECRET 未配置或不足 32 字节，拒绝签发服务令牌");
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalStateException("tenantId 为空，拒绝签发服务令牌（服务端会 403）");
        }
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer(issuer)
                .subject(serviceName)
                .claim("tenantId", tenantId)
                .claim("act", "service")
                .id(UUID.randomUUID().toString())
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttl.toMillis()))
                .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .compact();
    }
}
