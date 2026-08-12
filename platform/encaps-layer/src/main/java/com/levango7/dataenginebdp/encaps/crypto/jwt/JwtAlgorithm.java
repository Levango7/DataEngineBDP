package com.levango7.dataenginebdp.encaps.crypto.jwt;

/**
 * 国密 JWT 算法常量定义。
 *
 * <p>对应 RFC 7519《JSON Web Token (JWT)》中 {@code alg} 头部字段，
 * 在信创辖区下使用国密算法替代国际算法：</p>
 *
 * <h3>算法对照表</h3>
 * <table>
 *   <caption>表：国密与国际JWT算法对照表</caption>
 *   <tr><th>用途</th><th>国密（信创）</th><th>国际</th><th>说明</th></tr>
 *   <tr><td>签名</td><td>SM3withSM2</td><td>RS256</td><td>非对称签名，私钥签名/公钥验签</td></tr>
 *   <tr><td>摘要</td><td>SM3</td><td>SHA-256</td><td>256 bit 摘要</td></tr>
 *   <tr><td>对称加密</td><td>SM4</td><td>AES</td><td>128 bit 分组密码</td></tr>
 *   <tr><td>非对称加密</td><td>SM2</td><td>RSA</td><td>椭圆曲线 256 bit</td></tr>
 * </table>
 *
 * <h3>JWT 头部示例</h3>
 * <pre>{@code
 * {
 *   "alg": "SM3withSM2",
 *   "typ": "JWT",
 *   "cty": "json"
 * }
 * }</pre>
 *
 * @see com.levango7.dataenginebdp.encaps.crypto.gm.GmAlgorithm
 */
public final class JwtAlgorithm {

    private JwtAlgorithm() {
        throw new UnsupportedOperationException("Constants class, no instance");
    }

    // ===== JWT 算法标识 =====

    /** 国密 JWT 签名算法（SM2withSM3，对应 RFC 7518 自定义 alg） */
    public static final String SM3_WITH_SM2 = "SM3withSM2";

    /** 国际 JWT 签名算法（RS256 = RSASSA-PKCS1-v1_5 with SHA-256） */
    public static final String RS256 = "RS256";

    /** HMAC-SHA256 对称签名（兼容现有 JwtAuthFilter） */
    public static final String HS256 = "HS256";

    // ===== JWT 头部字段 =====

    /** JWT 头部 {@code alg} 字段 */
    public static final String HEADER_ALG = "alg";

    /** JWT 头部 {@code typ} 字段名 */
    public static final String HEADER_TYP = "typ";

    /** JWT 头部 {@code typ} 字段值，固定为 "JWT" */
    public static final String HEADER_TYP_VALUE = "JWT";

    /** JWT 头部 {@code cty} 字段（内容类型，可选） */
    public static final String HEADER_CTY = "cty";

    /** JWT 头部 {@code kid} 字段（密钥 id，用于多密钥轮换） */
    public static final String HEADER_KID = "kid";

    // ===== JWT 标准声明（RFC 7519 §4.1） =====

    /** issuer 声明 */
    public static final String CLAIM_ISS = "iss";

    /** subject 声明 */
    public static final String CLAIM_SUB = "sub";

    /** audience 声明 */
    public static final String CLAIM_AUD = "aud";

    /** expiration time 声明（秒级 Unix 时间戳） */
    public static final String CLAIM_EXP = "exp";

    /** not before 声明（秒级 Unix 时间戳） */
    public static final String CLAIM_NBF = "nbf";

    /** issued at 声明（秒级 Unix 时间戳） */
    public static final String CLAIM_IAT = "iat";

    /** JWT ID 声明（唯一标识，用于防重放） */
    public static final String CLAIM_JTI = "jti";

    // ===== 业务扩展声明 =====

    /** 租户 id 声明 */
    public static final String CLAIM_TENANT_ID = "tenantId";

    /** 角色 声明 */
    public static final String CLAIM_ROLES = "roles";

    // ===== 默认参数 =====

    /** 默认 JWT 有效期：1 小时（秒） */
    public static final long DEFAULT_EXPIRY_SECONDS = 3600L;

    /** 默认 JWT not-before 容忍时钟偏移：60 秒 */
    public static final long DEFAULT_CLOCK_SKEW_SECONDS = 60L;

    /** Base64 URL 字符集（RFC 4648 §5，无填充） */
    public static final String BASE64URL_CHARSET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
}