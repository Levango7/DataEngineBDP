package com.shuqing.bigdata.encaps.crypto.jwt.transport;

import com.shuqing.bigdata.encaps.crypto.CryptoException;

/**
 * 传输加密接口。
 *
 * <p>对 API 请求/响应中的大体积敏感数据做传输加密，采用数字信封（SM2+SM4 混合）模式：
 * SM4 加密数据本体，SM2 加密 SM4 会话密钥，兼顾性能与密钥分发安全。</p>
 *
 * <h3>典型场景</h3>
 * <ul>
 *   <li>跨服务 API 调用时的大体积 PII 数据传输</li>
 *   <li>第三方数据交换场景的端到端加密</li>
 *   <li>多租户场景下租户间数据隔离传输</li>
 * </ul>
 *
 * <h3>与存储加密的区别</h3>
 * <ul>
 *   <li>存储加密：长期密钥，密文持久化，需支持密钥轮换</li>
 *   <li>传输加密：一次性会话密钥，密文短期有效，无需密钥轮换</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>实现类应线程安全。</p>
 */
public interface TransportCipher {

    /**
     * 加密明文为数字信封。
     *
     * @param plaintext 明文
     * @return 数字信封
     * @throws CryptoException 加密失败
     */
    DigitalEnvelope encrypt(byte[] plaintext);

    /**
     * 解密数字信封为明文。
     *
     * @param envelope 数字信封
     * @return 明文
     * @throws CryptoException 解密失败
     */
    byte[] decrypt(DigitalEnvelope envelope);

    /**
     * 加密字符串明文，返回 Base64 信封字符串。
     *
     * @param plaintext 明文字符串
     * @return Base64 信封字符串
     */
    String encryptString(String plaintext);

    /**
     * 解密 Base64 信封字符串为明文字符串。
     *
     * @param envelopeBase64 Base64 信封字符串
     * @return 明文字符串
     */
    String decryptString(String envelopeBase64);

    /**
     * 加密算法标识。
     *
     * @return 算法标识，如 "SM2+SM4-CBC"
     */
    String getAlgorithm();

    /**
     * 是否为国密算法。
     *
     * @return true 表示国密
     */
    boolean isGm();
}