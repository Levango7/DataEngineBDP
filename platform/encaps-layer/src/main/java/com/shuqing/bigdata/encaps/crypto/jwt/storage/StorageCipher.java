package com.shuqing.bigdata.encaps.crypto.jwt.storage;

import com.shuqing.bigdata.encaps.crypto.CryptoException;

/**
 * 存储加密接口。
 *
 * <p>对持久化层（数据库字段、文件存储、对象存储）中的敏感数据做透明加密/解密，
 * 屏蔽底层国密 SM4 / 国际 AES 算法差异，由 {@link StorageCipherFactory} 按 Profile 路由。</p>
 *
 * <h3>典型场景</h3>
 * <ul>
 *   <li>数据库敏感字段加密（如用户手机号、身份证号、API key）</li>
 *   <li>对象存储机密文件加密（如 OSS/S3 上的 PII 文件）</li>
 *   <li>配置中心敏感配置项加密（如数据库密码、第三方 token）</li>
 * </ul>
 *
 * <h3>密文格式</h3>
 * <p>实现类应保证密文自描述（包含算法标识、IV、密文），便于跨 Profile 解密与密钥轮换。
 * 默认实现采用 Base64 包装的 {@code alg|iv|ciphertext} 三段格式。</p>
 *
 * <h3>线程安全</h3>
 * <p>实现类应线程安全，可被并发调用。</p>
 */
public interface StorageCipher {

    /**
     * 加密明文为存储格式密文。
     *
     * @param plaintext 明文字节
     * @return 自描述密文（含算法/IV，Base64 包装）
     * @throws CryptoException 加密失败
     */
    byte[] encrypt(byte[] plaintext);

    /**
     * 解密存储格式密文为明文。
     *
     * @param ciphertext 自描述密文
     * @return 明文
     * @throws CryptoException 解密失败、密文格式非法
     */
    byte[] decrypt(byte[] ciphertext);

    /**
     * 加密字符串明文，返回 Base64 密文字符串。
     *
     * @param plaintext 明文字符串（UTF-8）
     * @return Base64 密文字符串
     * @throws CryptoException 加密失败
     */
    String encryptString(String plaintext);

    /**
     * 解密 Base64 密文字符串为明文字符串。
     *
     * @param ciphertext Base64 密文字符串
     * @return 明文字符串（UTF-8）
     * @throws CryptoException 解密失败
     */
    String decryptString(String ciphertext);

    /**
     * 加密算法标识。
     *
     * <p>用于密文头部的算法标记，便于跨 Profile 解密与密钥轮换。
     * 例如 {@code "SM4-CBC"} / {@code "AES-GCM"}。</p>
     *
     * @return 算法标识
     */
    String getAlgorithm();

    /**
     * 是否为国密算法。
     *
     * @return true 表示国密（信创辖区）；false 表示国际
     */
    boolean isGm();
}