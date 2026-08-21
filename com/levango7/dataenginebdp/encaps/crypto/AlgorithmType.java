package com.shuqing.bigdata.encaps.crypto;

/**
 * 加密算法类型枚举。
 *
 * <p>覆盖四类核心密码学原语：</p>
 * <ul>
 *   <li>{@link #SIGN} — 数字签名（非对称，如 SM2/ECDSA/RSA-Sign）</li>
 *   <li>{@link #ENCRYPT} — 非对称加密（如 SM2/RSA-Cipher）</li>
 *   <li>{@link #DIGEST} — 摘要算法（如 SM3/SHA-256）</li>
 *   <li>{@link #SYMMETRIC} — 对称加密（如 SM4/AES）</li>
 * </ul>
 *
 * <p>用于在 {@link CryptoProvider#getAlgorithmType()} 中标识 Provider 主算法类别，
 * 便于工厂层做按类型路由与校验。</p>
 */
public enum AlgorithmType {

    /** 数字签名（非对称） */
    SIGN,

    /** 非对称加密 */
    ENCRYPT,

    /** 摘要算法 */
    DIGEST,

    /** 对称加密 */
    SYMMETRIC
}