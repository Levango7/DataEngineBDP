package com.levango7.dataenginebdp.encaps.crypto.jwt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.gm.GmAlgorithm;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM2Provider;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM3Provider;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 国密 JWT 签名器（SM3withSM2）。
 *
 * <p>基于 T022 双栈的 {@link SM2Provider} 与 {@link SM3Provider} 实现 RFC 7519 JWT 签名：
 * 签名算法为 SM3withSM2（GB/T 32918.2 + GB/T 32905），私钥签名、公钥验签。</p>
 *
 * <h3>JWT 结构</h3>
 * <pre>{@code
 * base64url(header) . base64url(payload) . base64url(signature)
 * }</pre>
 *
 * <h3>头部默认值</h3>
 * <pre>{@code
 * {
 *   "alg": "SM3withSM2",
 *   "typ": "JWT"
 * }
 * }</pre>
 *
 * <h3>签名流程</h3>
 * <ol>
 *   <li>构造 header（alg=SM3withSM2, typ=JWT, 可选 kid）</li>
 *   <li>构造 payload（标准声明 + 业务声明）</li>
 *   <li>计算 signing input = base64url(header) + "." + base64url(payload)</li>
 *   <li>signature = SM2Sign(signing input, privateKeyD)（DER 编码）</li>
 *   <li>返回 signing input + "." + base64url(signature)</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * <p>内部 {@link ObjectMapper} 线程安全，{@link SM2Provider}/{@link SM3Provider} 线程安全，
 * 本类可安全并发调用。</p>
 */
public class GmJwtSigner {

    /** Jackson JSON 序列化器（线程安全） */
    private final ObjectMapper objectMapper;

    /** SM2 算法实现 */
    private final SM2Provider sm2;

    /** SM3 算法实现（用于预摘要或 KDF，签名内部已使用 SM3） */
    private final SM3Provider sm3;

    /** 默认 issuer */
    private final String defaultIssuer;

    /**
     * 默认构造。
     *
     * <p>使用默认 issuer=null（不强制写入 iss 声明），由调用方在 payload 中显式设置。</p>
     */
    public GmJwtSigner() {
        this(null);
    }

    /**
     * 指定默认 issuer 构造。
     *
     * @param defaultIssuer 默认 issuer；null 表示不强制写入
     */
    public GmJwtSigner(String defaultIssuer) {
        this(new ObjectMapper(), new SM2Provider(), new SM3Provider(), defaultIssuer);
    }

    /**
     * 注入构造（便于测试与自定义）。
     *
     * @param objectMapper JSON 序列化器
     * @param sm2          SM2 算法实现
     * @param sm3          SM3 算法实现
     * @param defaultIssuer 默认 issuer
     */
    public GmJwtSigner(ObjectMapper objectMapper, SM2Provider sm2, SM3Provider sm3, String defaultIssuer) {
        if (objectMapper == null || sm2 == null || sm3 == null) {
            throw new CryptoException("objectMapper, sm2 and sm3 must not be null");
        }
        this.objectMapper = objectMapper;
        this.sm2 = sm2;
        this.sm3 = sm3;
        this.defaultIssuer = defaultIssuer;
    }

    /**
     * 签发 JWT。
     *
     * <p>自动写入 {@code iat}、{@code exp} 声明；若 {@code iss} 未显式提供且 defaultIssuer 非空，则写入 defaultIssuer。</p>
     *
     * @param privateKeyD SM2 私钥 D 值（32 字节）
     * @param subject     subject 声明（用户 id），不可为空
     * @param claims      业务声明（将合并到 payload）；null 表示无额外声明
     * @param expirySeconds 有效期（秒）；&lt;=0 使用默认 3600
     * @return 紧凑型 JWT 字符串
     * @throws JwtException 签名失败
     */
    public String sign(byte[] privateKeyD, String subject, Map<String, Object> claims, long expirySeconds) {
        return sign(privateKeyD, subject, claims, expirySeconds, null);
    }

    /**
     * 签发 JWT（带 key id）。
     *
     * @param privateKeyD   SM2 私钥 D 值
     * @param subject       subject 声明
     * @param claims        业务声明
     * @param expirySeconds 有效期（秒）
     * @param keyId         密钥 id（写入 header.kid）；null 不写入
     * @return JWT 字符串
     * @throws JwtException 签名失败
     */
    public String sign(byte[] privateKeyD, String subject, Map<String, Object> claims,
                       long expirySeconds, String keyId) {
        if (privateKeyD == null) {
            throw new JwtException("privateKeyD must not be null");
        }
        if (subject == null || subject.isBlank()) {
            throw new JwtException("subject must not be blank");
        }
        long expiry = expirySeconds > 0 ? expirySeconds : JwtAlgorithm.DEFAULT_EXPIRY_SECONDS;
        long now = Instant.now().getEpochSecond();

        // 构造 header
        ObjectNode header = objectMapper.createObjectNode();
        header.put(JwtAlgorithm.HEADER_ALG, JwtAlgorithm.SM3_WITH_SM2);
        header.put(JwtAlgorithm.HEADER_TYP, JwtAlgorithm.HEADER_TYP_VALUE);
        if (keyId != null && !keyId.isBlank()) {
            header.put(JwtAlgorithm.HEADER_KID, keyId);
        }

        // 构造 payload
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put(JwtAlgorithm.CLAIM_SUB, subject);
        if (defaultIssuer != null && !defaultIssuer.isBlank()) {
            payload.put(JwtAlgorithm.CLAIM_ISS, defaultIssuer);
        }
        payload.put(JwtAlgorithm.CLAIM_IAT, now);
        payload.put(JwtAlgorithm.CLAIM_EXP, now + expiry);
        // 合并业务声明
        if (claims != null) {
            for (Map.Entry<String, Object> entry : claims.entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    continue;
                }
                // 标准声明不允许业务覆盖
                if (isReservedClaim(key)) {
                    continue;
                }
                putPayloadValue(payload, key, entry.getValue());
            }
        }

        return doSign(privateKeyD, header, payload);
    }

    /**
     * 签发 JWT（自定义完整 payload）。
     *
     * <p>调用方完全控制 payload 内容，本方法仅写入 header.alg=SM3withSM2 与 typ=JWT。
     * 不自动添加 iat/exp/iss，由调用方负责。</p>
     *
     * @param privateKeyD SM2 私钥 D 值
     * @param payload     完整 payload 声明；null 视为空对象
     * @param keyId       密钥 id；null 不写入
     * @return JWT 字符串
     * @throws JwtException 签名失败
     */
    public String signRaw(byte[] privateKeyD, Map<String, Object> payload, String keyId) {
        if (privateKeyD == null) {
            throw new JwtException("privateKeyD must not be null");
        }
        ObjectNode header = objectMapper.createObjectNode();
        header.put(JwtAlgorithm.HEADER_ALG, JwtAlgorithm.SM3_WITH_SM2);
        header.put(JwtAlgorithm.HEADER_TYP, JwtAlgorithm.HEADER_TYP_VALUE);
        if (keyId != null && !keyId.isBlank()) {
            header.put(JwtAlgorithm.HEADER_KID, keyId);
        }
        ObjectNode payloadNode = objectMapper.createObjectNode();
        if (payload != null) {
            for (Map.Entry<String, Object> entry : payload.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()) {
                    putPayloadValue(payloadNode, entry.getKey(), entry.getValue());
                }
            }
        }
        return doSign(privateKeyD, header, payloadNode);
    }

    /**
     * 实际签名核心。
     */
    private String doSign(byte[] privateKeyD, ObjectNode header, ObjectNode payload) {
        try {
            String headerJson = objectMapper.writeValueAsString(header);
            String payloadJson = objectMapper.writeValueAsString(payload);
            String headerB64 = Base64UrlUtil.encodeString(headerJson);
            String payloadB64 = Base64UrlUtil.encodeString(payloadJson);
            String signingInput = headerB64 + "." + payloadB64;
            byte[] sigBytes = sm2.sign(signingInput.getBytes(StandardCharsets.UTF_8), privateKeyD);
            String sigB64 = Base64UrlUtil.encode(sigBytes);
            return signingInput + "." + sigB64;
        } catch (CryptoException e) {
            throw new JwtException("JWT sign failed: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new JwtException("JWT sign failed", e);
        }
    }

    /**
     * 判断是否为标准保留声明（业务不能覆盖）。
     */
    private static boolean isReservedClaim(String key) {
        return JwtAlgorithm.CLAIM_ISS.equals(key)
                || JwtAlgorithm.CLAIM_SUB.equals(key)
                || JwtAlgorithm.CLAIM_AUD.equals(key)
                || JwtAlgorithm.CLAIM_EXP.equals(key)
                || JwtAlgorithm.CLAIM_NBF.equals(key)
                || JwtAlgorithm.CLAIM_IAT.equals(key)
                || JwtAlgorithm.CLAIM_JTI.equals(key);
    }

    /**
     * 将值写入 payload 节点（按类型分发）。
     */
    private void putPayloadValue(ObjectNode node, String key, Object value) {
        if (value == null) {
            node.putNull(key);
        } else if (value instanceof String) {
            node.put(key, (String) value);
        } else if (value instanceof Integer) {
            node.put(key, (Integer) value);
        } else if (value instanceof Long) {
            node.put(key, (Long) value);
        } else if (value instanceof Double) {
            node.put(key, (Double) value);
        } else if (value instanceof Float) {
            node.put(key, (Float) value);
        } else if (value instanceof Boolean) {
            node.put(key, (Boolean) value);
        } else if (value instanceof Number) {
            node.put(key, ((Number) value).doubleValue());
        } else if (value instanceof JsonNode) {
            node.set(key, (JsonNode) value);
        } else {
            // 复杂对象委托 Jackson 序列化
            node.set(key, objectMapper.valueToTree(value));
        }
    }

    /**
     * 获取内部 SM2 Provider（供验签器复用）。
     *
     * @return SM2Provider 实例
     */
    public SM2Provider getSm2() {
        return sm2;
    }

    /**
     * 获取内部 SM3 Provider。
     *
     * @return SM3Provider 实例
     */
    public SM3Provider getSm3() {
        return sm3;
    }

    /**
     * 获取默认 issuer。
     *
     * @return 默认 issuer；可能为 null
     */
    public String getDefaultIssuer() {
        return defaultIssuer;
    }

    /**
     * 便捷构造：使用 LinkedHashMap 保留声明插入顺序。
     *
     * @return 新的 LinkedHashMap
     */
    public static Map<String, Object> newClaims() {
        return new LinkedHashMap<>();
    }
}