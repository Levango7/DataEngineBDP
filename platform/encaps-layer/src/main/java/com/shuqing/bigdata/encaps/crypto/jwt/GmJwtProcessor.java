package com.shuqing.bigdata.encaps.crypto.jwt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shuqing.bigdata.encaps.crypto.gm.SM2Provider;
import com.shuqing.bigdata.encaps.crypto.gm.SM3Provider;

import java.util.Map;

/**
 * 国密 JWT 处理器（聚合签名器与验签器）。
 *
 * <p>对外提供统一的 JWT 签发/验签入口，内部委托给 {@link GmJwtSigner} 与 {@link GmJwtVerifier}，
 * 共享同一份 SM2/SM3 Provider 实例与 ObjectMapper，减少对象创建开销。</p>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * GmJwtProcessor processor = new GmJwtProcessor("shuqing-bigdata");
 * SM2Provider.Sm2KeyPair kp = processor.getSm2().generateKeyPair();
 *
 * // 签发
 * Map<String, Object> claims = GmJwtSigner.newClaims();
 * claims.put("tenantId", "tenant-001");
 * claims.put("roles", "admin,user");
 * String jwt = processor.sign(kp.getPrivateKeyD(), "user-123", claims, 3600);
 *
 * // 验签
 * Map<String, Object> payload = processor.verify(jwt, kp.getPublicKeyQ());
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>线程安全（内部组件均线程安全）。</p>
 */
public class GmJwtProcessor {

    private final GmJwtSigner signer;
    private final GmJwtVerifier verifier;

    /**
     * 默认构造（不校验 issuer/audience）。
     */
    public GmJwtProcessor() {
        this(null);
    }

    /**
     * 指定 issuer 构造（验签时校验 iss 声明）。
     *
     * @param issuer 期望的 issuer；null 不校验
     */
    public GmJwtProcessor(String issuer) {
        this(issuer, null, JwtAlgorithm.DEFAULT_CLOCK_SKEW_SECONDS);
    }

    /**
     * 完整构造。
     *
     * @param issuer           期望的 issuer
     * @param audience         期望的 audience
     * @param clockSkewSeconds 时钟偏移容忍秒数
     */
    public GmJwtProcessor(String issuer, String audience, long clockSkewSeconds) {
        ObjectMapper objectMapper = new ObjectMapper();
        SM2Provider sm2 = new SM2Provider();
        SM3Provider sm3 = new SM3Provider();
        this.signer = new GmJwtSigner(objectMapper, sm2, sm3, issuer);
        this.verifier = new GmJwtVerifier(objectMapper, sm2, issuer, audience, clockSkewSeconds);
    }

    /**
     * 注入构造（便于测试与自定义）。
     *
     * @param signer   签名器
     * @param verifier 验签器
     */
    public GmJwtProcessor(GmJwtSigner signer, GmJwtVerifier verifier) {
        if (signer == null || verifier == null) {
            throw new JwtException("signer and verifier must not be null");
        }
        this.signer = signer;
        this.verifier = verifier;
    }

    /**
     * 签发 JWT。
     *
     * @param privateKeyD   SM2 私钥 D 值
     * @param subject       subject 声明
     * @param claims        业务声明
     * @param expirySeconds 有效期（秒）
     * @return JWT 字符串
     */
    public String sign(byte[] privateKeyD, String subject, Map<String, Object> claims, long expirySeconds) {
        return signer.sign(privateKeyD, subject, claims, expirySeconds);
    }

    /**
     * 签发 JWT（带 key id）。
     *
     * @param privateKeyD   SM2 私钥 D 值
     * @param subject       subject 声明
     * @param claims        业务声明
     * @param expirySeconds 有效期（秒）
     * @param keyId         密钥 id
     * @return JWT 字符串
     */
    public String sign(byte[] privateKeyD, String subject, Map<String, Object> claims,
                       long expirySeconds, String keyId) {
        return signer.sign(privateKeyD, subject, claims, expirySeconds, keyId);
    }

    /**
     * 验签并解析 JWT。
     *
     * @param jwt        JWT 字符串
     * @param publicKeyQ SM2 公钥点编码
     * @return payload 声明 Map
     */
    public Map<String, Object> verify(String jwt, byte[] publicKeyQ) {
        return verifier.verify(jwt, publicKeyQ);
    }

    /**
     * 仅验签不校验声明。
     *
     * @param jwt        JWT 字符串
     * @param publicKeyQ SM2 公钥
     * @return payload 声明 Map
     */
    public Map<String, Object> verifySignatureOnly(String jwt, byte[] publicKeyQ) {
        return verifier.verifySignatureOnly(jwt, publicKeyQ);
    }

    /**
     * 提取 header（不验签）。
     *
     * @param jwt JWT 字符串
     * @return header Map
     */
    public Map<String, Object> extractHeader(String jwt) {
        return verifier.extractHeader(jwt);
    }

    /**
     * 获取内部签名器。
     *
     * @return GmJwtSigner 实例
     */
    public GmJwtSigner getSigner() {
        return signer;
    }

    /**
     * 获取内部验签器。
     *
     * @return GmJwtVerifier 实例
     */
    public GmJwtVerifier getVerifier() {
        return verifier;
    }

    /**
     * 获取共享 SM2 Provider。
     *
     * @return SM2Provider 实例
     */
    public SM2Provider getSm2() {
        return signer.getSm2();
    }

    /**
     * 获取共享 SM3 Provider。
     *
     * @return SM3Provider 实例
     */
    public SM3Provider getSm3() {
        return signer.getSm3();
    }
}