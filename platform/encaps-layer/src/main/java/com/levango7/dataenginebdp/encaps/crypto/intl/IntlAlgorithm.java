package com.levango7.dataenginebdp.encaps.crypto.intl;

import java.util.Arrays;
import java.util.Locale;

/**
 * 国际算法类型枚举/常量。
 *
 * <p>定义国际通用密码学算法的标准名称与参数，供
 * {@link RSAProvider}/{@link SHAProvider}/{@link AESProvider} 引用，
 * 确保算法名与 FIPS/NIST 标准一致：</p>
 *
 * <h3>覆盖算法</h3>
 * <ul>
 *   <li>RSA — PKCS#1 v2.1 签名（SHA256withRSA/SHA384withRSA/SHA512withRSA）与加密（RSA/ECB/PKCS1Padding）</li>
 *   <li>SHA — FIPS 180-4 摘要（SHA-256/SHA-384/SHA-512）</li>
 *   <li>AES — FIPS 197 对称加密（AES-128/AES-256，GCM/CBC模式）</li>
 * </ul>
 *
 * <p>所有算法名大小写不敏感，{@link #fromString(String)} 校验合法性。</p>
 */
public enum IntlAlgorithm {

    // ===== RSA 签名算法（PKCS#1 v2.1） =====
    /** SHA-256 with RSA 签名（PKCS#1 v2.1） */
    SHA256_WITH_RSA("SHA256withRSA", "RSA签名(SHA-256摘要)", 32),

    /** SHA-384 with RSA 签名 */
    SHA384_WITH_RSA("SHA384withRSA", "RSA签名(SHA-384摘要)", 48),

    /** SHA-512 with RSA 签名 */
    SHA512_WITH_RSA("SHA512withRSA", "RSA签名(SHA-512摘要)", 64),

    // ===== SHA 摘要算法（FIPS 180-4） =====
    /** SHA-256 摘要（FIPS 180-4），输出 32 字节 */
    SHA_256("SHA-256", "SHA-256摘要(FIPS 180-4)", 32),

    /** SHA-384 摘要（FIPS 180-4），输出 48 字节 */
    SHA_384("SHA-384", "SHA-384摘要(FIPS 180-4)", 48),

    /** SHA-512 摘要（FIPS 180-4），输出 64 字节 */
    SHA_512("SHA-512", "SHA-512摘要(FIPS 180-4)", 64),

    // ===== AES 加密模式（FIPS 197） =====
    /** AES-GCM 模式（AEAD，FIPS 197 + NIST SP 800-38D） */
    AES_GCM("AES/GCM/NoPadding", "AES-GCM模式(AEAD)", 12),

    /** AES-CBC 模式（PKCS5Padding，FIPS 197 + NIST SP 800-38A） */
    AES_CBC("AES/CBC/PKCS5Padding", "AES-CBC模式(PKCS5)", 16);

    /** JDK/JCE 标准算法名 */
    private final String jceName;

    /** 描述（便于日志/调试） */
    private final String description;

    /** 输出长度（摘要字节数 / IV 默认字节数） */
    private final int outputLength;

    IntlAlgorithm(String jceName, String description, int outputLength) {
        this.jceName = jceName;
        this.description = description;
        this.outputLength = outputLength;
    }

    /**
     * JDK/JCE 标准算法名。
     *
     * @return 可直接传给 {@code MessageDigest.getInstance} /
     *         {@code Cipher.getInstance} / {@code Signature.getInstance} 的算法名
     */
    public String getJceName() {
        return jceName;
    }

    /**
     * 人类可读描述。
     *
     * @return 描述字符串
     */
    public String getDescription() {
        return description;
    }

    /**
     * 输出长度。
     *
     * <p>对于摘要算法，返回摘要字节数；对于 AES 模式，返回默认 IV 字节数。</p>
     *
     * @return 长度（字节）
     */
    public int getOutputLength() {
        return outputLength;
    }

    /**
     * 由 JCE 算法名解析为枚举。
     *
     * <p>大小写不敏感；无法识别时返回 null（不抛异常，便于灵活使用）。</p>
     *
     * @param name JCE 算法名
     * @return 对应枚举；不识别返回 null
     */
    public static IntlAlgorithm fromString(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String normalized = name.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(a -> a.jceName.toUpperCase(Locale.ROOT).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    /**
     * 判断是否为 SHA 摘要算法。
     *
     * @return 是返回 true
     */
    public boolean isDigest() {
        return this == SHA_256 || this == SHA_384 || this == SHA_512;
    }

    /**
     * 判断是否为 RSA 签名算法。
     *
     * @return 是返回 true
     */
    public boolean isRsaSign() {
        return this == SHA256_WITH_RSA || this == SHA384_WITH_RSA || this == SHA512_WITH_RSA;
    }

    /**
     * 判断是否为 AES 加密模式。
     *
     * @return 是返回 true
     */
    public boolean isAesMode() {
        return this == AES_GCM || this == AES_CBC;
    }
}