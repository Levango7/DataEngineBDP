package com.levango7.dataenginebdp.encaps.security;

/**
 * 字段级加密算法类型。
 *
 * <p>用于 {@link Encrypt} 注解声明字段使用的国密算法，
 * 当前支持 SM4 对称加密（默认）与 SM3 摘要（不可逆，用于口令/指纹字段）。</p>
 */
public enum EncryptType {
    /** SM4 对称加密（可逆，加解密双向） */
    SM4,
    /** SM3 摘要（不可逆，仅加密不解密，适用于口令哈希字段） */
    SM3
}