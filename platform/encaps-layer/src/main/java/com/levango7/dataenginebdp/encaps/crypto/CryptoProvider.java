package com.levango7.dataenginebdp.encaps.crypto;

import java.security.Key;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * 加密 Provider 抽象接口。
 *
 * <p>定义统一的密码学原语契约，屏蔽底层具体实现差异（国密 SM2/SM3/SM4 vs 国际 RSA/SHA/AES）。
 * 具体实现通过 Java SPI（{@link java.util.ServiceLoader}）注册，由
 * {@link CryptoSpiFactory} 按 Profile 加载。</p>
 *
 * <p>覆盖四类算法：</p>
 * <ul>
 *   <li>签名/验签 — {@link #sign(byte[], PrivateKey)} / {@link #verifySign(byte[], byte[], PublicKey)}</li>
 *   <li>非对称加密/解密 — {@link #encrypt(byte[], Key)} / {@link #decrypt(byte[], Key)}</li>
 *   <li>摘要 — {@link #digest(byte[])}</li>
 *   <li>对称加密/解密 — 复用 {@link #encrypt}/{@link #decrypt}，由 {@link #getAlgorithmType()} 区分</li>
 * </ul>
 *
 * <p>实现类需满足：</p>
 * <ol>
 *   <li>提供无参构造（ServiceLoader 要求）</li>
 *   <li>线程安全（方法可被并发调用）</li>
 *   <li>{@link #getProviderName()} 全局唯一，与 SPI 注册文件一致</li>
 *   <li>{@link #getSupportedProfile()} 返回该 Provider 适配的 Profile</li>
 * </ol>
 */
public interface CryptoProvider {

    /**
     * 对数据做数字签名。
     *
     * @param data 待签名数据
     * @param key  私钥
     * @return 签名值
     * @throws CryptoException 签名失败（密钥不匹配、数据为空等）
     */
    byte[] sign(byte[] data, PrivateKey key);

    /**
     * 验证签名。
     *
     * @param data 原始数据
     * @param sign 签名值
     * @param key  公钥
     * @return 验签通过返回 true；否则 false
     * @throws CryptoException 验签过程异常
     */
    boolean verifySign(byte[] data, byte[] sign, PublicKey key);

    /**
     * 加密。
     *
     * <p>当 {@link #getAlgorithmType()} 为 {@link AlgorithmType#ENCRYPT} 时为非对称加密，
     * 为 {@link AlgorithmType#SYMMETRIC} 时为对称加密。</p>
     *
     * @param plaintext 明文
     * @param key       加密密钥（公钥或对称密钥）
     * @return 密文
     * @throws CryptoException 加密失败
     */
    byte[] encrypt(byte[] plaintext, Key key);

    /**
     * 解密。
     *
     * @param ciphertext 密文
     * @param key        解密密钥（私钥或对称密钥）
     * @return 明文
     * @throws CryptoException 解密失败
     */
    byte[] decrypt(byte[] ciphertext, Key key);

    /**
     * 计算摘要。
     *
     * @param data 原始数据
     * @return 摘要字节
     * @throws CryptoException 摘要计算失败
     */
    byte[] digest(byte[] data);

    /**
     * 生成/获取该 Provider 默认密钥对。
     *
     * <p>用于测试或默认场景；生产环境应由独立 KMS 提供。</p>
     *
     * @return 密钥对
     * @throws CryptoException 生成密钥对失败
     */
    KeyPair getKeyPair();

    /**
     * Provider 唯一名称。
     *
     * <p>用于在 SPI 注册集合中按名定位、运行时切换、日志输出。
     * 与 {@code META-INF/services} 注册的实现类对应。</p>
     *
     * @return Provider 名称，如 {@code GM-Provider} / {@code INTL-Provider}
     */
    String getProviderName();

    /**
     * 主算法类型。
     *
     * @return 算法类型枚举
     */
    AlgorithmType getAlgorithmType();

    /**
     * 该 Provider 适配的 Profile。
     *
     * <p>用于 {@link CryptoSpiFactory} 在按 Profile 选择 Provider 时进行匹配。
     * 一个 Provider 仅归属一个 Profile；跨 Profile 共享能力应分别实现两个 Provider。</p>
     *
     * @return 适配的 Profile
     */
    CryptoProfile getSupportedProfile();
}