package com.shuqing.bigdata.encaps.crypto.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.encaps.crypto.CryptoException;
import com.shuqing.bigdata.encaps.crypto.gm.SM2Provider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 国密 JWT 验签器（SM3withSM2）。
 *
 * <p>解析 JWT 三段式，校验签名与标准声明（exp/nbf/iss/aud），返回 payload 声明。</p>
 *
 * <h3>验签流程</h3>
 * <ol>
 *   <li>按 "." 分割为三段，Base64URL 解码 header / payload / signature</li>
 *   <li>校验 header.alg == "SM3withSM2"（fail-closed，拒绝降级攻击）</li>
 *   <li>计算 signing input = base64url(header) + "." + base64url(payload)</li>
 *   <li>SM2Verify(signing input, signature, publicKeyQ)</li>
 *   <li>校验标准声明：exp、nbf、iss、aud</li>
 *   <li>返回 payload 声明 Map</li>
 * </ol>
 *
 * <h3>防降级攻击</h3>
 * <p>严格校验 {@code alg} 字段必须为 {@link JwtAlgorithm#SM3_WITH_SM2}，
 * 拒绝 {@code none} / {@code HS256} / {@code RS256} 等其他算法，
 * 防止攻击者篡改头部绕过验签。</p>
 *
 * <h3>线程安全</h3>
 * <p>线程安全（{@link ObjectMapper} 与 {@link SM2Provider} 均线程安全）。</p>
 */
public class GmJwtVerifier {

    /** Jackson JSON 解析器 */
    private final ObjectMapper objectMapper;

    /** SM2 算法实现 */
    private final SM2Provider sm2;

    /** 期望的 issuer；null 不校验 */
    private final String expectedIssuer;

    /** 期望的 audience；null 不校验 */
    private final String expectedAudience;

    /** 时钟偏移容忍秒数 */
    private final long clockSkewSeconds;

    /**
     * 默认构造（不校验 issuer/audience，时钟偏移 60 秒）。
     */
    public GmJwtVerifier() {
        this(null, null, JwtAlgorithm.DEFAULT_CLOCK_SKEW_SECONDS);
    }

    /**
     * 指定 issuer 构造。
     *
     * @param expectedIssuer 期望的 issuer；null 不校验
     */
    public GmJwtVerifier(String expectedIssuer) {
        this(expectedIssuer, null, JwtAlgorithm.DEFAULT_CLOCK_SKEW_SECONDS);
    }

    /**
     * 完整构造。
     *
     * @param expectedIssuer    期望的 issuer；null 不校验
     * @param expectedAudience  期望的 audience；null 不校验
     * @param clockSkewSeconds  时钟偏移容忍秒数；&lt;0 视为 0
     */
    public GmJwtVerifier(String expectedIssuer, String expectedAudience, long clockSkewSeconds) {
        this(new ObjectMapper(), new SM2Provider(), expectedIssuer, expectedAudience, clockSkewSeconds);
    }

    /**
     * 注入构造（便于测试与自定义）。
     *
     * @param objectMapper     JSON 解析器
     * @param sm2              SM2 算法实现
     * @param expectedIssuer   期望的 issuer
     * @param expectedAudience 期望的 audience
     * @param clockSkewSeconds 时钟偏移容忍秒数
     */
    public GmJwtVerifier(ObjectMapper objectMapper, SM2Provider sm2,
                         String expectedIssuer, String expectedAudience, long clockSkewSeconds) {
        if (objectMapper == null || sm2 == null) {
            throw new CryptoException("objectMapper and sm2 must not be null");
        }
        this.objectMapper = objectMapper;
        this.sm2 = sm2;
        this.expectedIssuer = expectedIssuer;
        this.expectedAudience = expectedAudience;
        this.clockSkewSeconds = Math.max(0, clockSkewSeconds);
    }

    /**
     * 验签并解析 JWT。
     *
     * @param jwt       JWT 字符串
     * @param publicKeyQ SM2 公钥点编码（65 字节未压缩）
     * @return payload 声明 Map
     * @throws JwtException 验签失败、声明校验失败、格式非法
     */
    public Map<String, Object> verify(String jwt, byte[] publicKeyQ) {
        if (jwt == null || jwt.isBlank()) {
            throw new JwtException("jwt must not be blank");
        }
        if (publicKeyQ == null) {
            throw new JwtException("publicKeyQ must not be null");
        }
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new JwtException("JWT must have 3 parts separated by '.', got: " + parts.length);
        }
        String headerB64 = parts[0];
        String payloadB64 = parts[1];
        String sigB64 = parts[2];

        // 解析 header
        JsonNode header;
        try {
            header = objectMapper.readTree(Base64UrlUtil.decode(headerB64));
        } catch (Exception e) {
            throw new JwtException("invalid JWT header: " + e.getMessage(), e);
        }
        if (!header.isObject()) {
            throw new JwtException("JWT header must be a JSON object");
        }
        // 防降级：严格校验 alg
        String alg = header.path(JwtAlgorithm.HEADER_ALG).asText(null);
        if (!JwtAlgorithm.SM3_WITH_SM2.equals(alg)) {
            throw new JwtException("unsupported alg: " + alg + ", expected: " + JwtAlgorithm.SM3_WITH_SM2);
        }

        // 解析 payload
        JsonNode payload;
        try {
            payload = objectMapper.readTree(Base64UrlUtil.decode(payloadB64));
        } catch (Exception e) {
            throw new JwtException("invalid JWT payload: " + e.getMessage(), e);
        }
        if (!payload.isObject()) {
            throw new JwtException("JWT payload must be a JSON object");
        }

        // 验签
        String signingInput = headerB64 + "." + payloadB64;
        byte[] sigBytes;
        try {
            sigBytes = Base64UrlUtil.decode(sigB64);
        } catch (Exception e) {
            throw new JwtException("invalid JWT signature encoding: " + e.getMessage(), e);
        }
        boolean verified;
        try {
            verified = sm2.verify(signingInput.getBytes(StandardCharsets.UTF_8), sigBytes, publicKeyQ);
        } catch (CryptoException e) {
            throw new JwtException("SM2 verify failed: " + e.getMessage(), e);
        }
        if (!verified) {
            throw new JwtException("JWT signature verification failed");
        }

        // 校验标准声明
        validateClaims(payload);

        // 转换为 Map
        return toMap(payload);
    }

    /**
     * 仅验签不校验声明（用于内部场景或测试）。
     *
     * @param jwt       JWT 字符串
     * @param publicKeyQ SM2 公钥
     * @return payload 声明 Map
     * @throws JwtException 验签失败
     */
    public Map<String, Object> verifySignatureOnly(String jwt, byte[] publicKeyQ) {
        if (jwt == null || jwt.isBlank()) {
            throw new JwtException("jwt must not be blank");
        }
        if (publicKeyQ == null) {
            throw new JwtException("publicKeyQ must not be null");
        }
        String[] parts = jwt.split("\\.");
        if (parts.length != 3) {
            throw new JwtException("JWT must have 3 parts, got: " + parts.length);
        }
        String headerB64 = parts[0];
        String payloadB64 = parts[1];
        String sigB64 = parts[2];

        JsonNode header;
        try {
            header = objectMapper.readTree(Base64UrlUtil.decode(headerB64));
        } catch (Exception e) {
            throw new JwtException("invalid JWT header", e);
        }
        String alg = header.path(JwtAlgorithm.HEADER_ALG).asText(null);
        if (!JwtAlgorithm.SM3_WITH_SM2.equals(alg)) {
            throw new JwtException("unsupported alg: " + alg);
        }

        JsonNode payload;
        try {
            payload = objectMapper.readTree(Base64UrlUtil.decode(payloadB64));
        } catch (Exception e) {
            throw new JwtException("invalid JWT payload", e);
        }

        String signingInput = headerB64 + "." + payloadB64;
        byte[] sigBytes;
        try {
            sigBytes = Base64UrlUtil.decode(sigB64);
        } catch (Exception e) {
            throw new JwtException("invalid JWT signature encoding", e);
        }
        boolean verified;
        try {
            verified = sm2.verify(signingInput.getBytes(StandardCharsets.UTF_8), sigBytes, publicKeyQ);
        } catch (CryptoException e) {
            throw new JwtException("SM2 verify failed", e);
        }
        if (!verified) {
            throw new JwtException("JWT signature verification failed");
        }
        return toMap(payload);
    }

    /**
     * 提取 header（不验签）。
     *
     * <p>用于调用方根据 header.kid 选择验签密钥（多密钥轮换场景）。</p>
     *
     * @param jwt JWT 字符串
     * @return header Map
     * @throws JwtException 格式非法
     */
    public Map<String, Object> extractHeader(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            throw new JwtException("jwt must not be blank");
        }
        int firstDot = jwt.indexOf('.');
        if (firstDot < 0) {
            throw new JwtException("invalid JWT format: no '.' separator");
        }
        String headerB64 = jwt.substring(0, firstDot);
        try {
            JsonNode header = objectMapper.readTree(Base64UrlUtil.decode(headerB64));
            return toMap(header);
        } catch (Exception e) {
            throw new JwtException("invalid JWT header", e);
        }
    }

    /**
     * 校验标准声明。
     */
    private void validateClaims(JsonNode payload) {
        long now = Instant.now().getEpochSecond();

        // exp 校验
        JsonNode expNode = payload.get(JwtAlgorithm.CLAIM_EXP);
        if (expNode != null && expNode.isNumber()) {
            long exp = expNode.asLong();
            if (now > exp + clockSkewSeconds) {
                throw new JwtException("JWT expired at " + exp + ", now=" + now);
            }
        }

        // nbf 校验
        JsonNode nbfNode = payload.get(JwtAlgorithm.CLAIM_NBF);
        if (nbfNode != null && nbfNode.isNumber()) {
            long nbf = nbfNode.asLong();
            if (now + clockSkewSeconds < nbf) {
                throw new JwtException("JWT not yet valid, nbf=" + nbf + ", now=" + now);
            }
        }

        // iss 校验
        if (expectedIssuer != null && !expectedIssuer.isBlank()) {
            JsonNode issNode = payload.get(JwtAlgorithm.CLAIM_ISS);
            String iss = issNode == null ? null : issNode.asText(null);
            if (!expectedIssuer.equals(iss)) {
                throw new JwtException("JWT issuer mismatch: expected=" + expectedIssuer + ", actual=" + iss);
            }
        }

        // aud 校验
        if (expectedAudience != null && !expectedAudience.isBlank()) {
            JsonNode audNode = payload.get(JwtAlgorithm.CLAIM_AUD);
            if (!audienceMatches(audNode, expectedAudience)) {
                throw new JwtException("JWT audience mismatch: expected=" + expectedAudience);
            }
        }
    }

    /**
     * 校验 audience 匹配。
     * <p>aud 可以是字符串或字符串数组。</p>
     */
    private static boolean audienceMatches(JsonNode audNode, String expected) {
        if (audNode == null) {
            return false;
        }
        if (audNode.isTextual()) {
            return expected.equals(audNode.asText());
        }
        if (audNode.isArray()) {
            for (JsonNode elem : audNode) {
                if (elem.isTextual() && expected.equals(elem.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * JsonNode 对象转 Map。
     */
    private Map<String, Object> toMap(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Collections.emptyMap();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> result.put(entry.getKey(), toObject(entry.getValue())));
        return result;
    }

    /**
     * JsonNode 转换为 Java 对象。
     */
    private static Object toObject(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isLong()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asDouble();
        }
        // 复杂类型保留 JsonNode
        return node;
    }

    /**
     * 获取内部 SM2 Provider。
     *
     * @return SM2Provider 实例
     */
    public SM2Provider getSm2() {
        return sm2;
    }
}