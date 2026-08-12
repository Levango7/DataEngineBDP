package com.levango7.dataenginebdp.encaps.crypto.intl;

import com.levango7.dataenginebdp.encaps.crypto.AlgorithmType;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Key;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

/**
 * 国际 Provider 聚合类（实现 {@link CryptoProvider} SPI 接口）。
 *
 * <p>作为国际 Profile（{@link CryptoProfile#INTERNATIONAL}）的默认 Provider，
 * 内部委托给 {@link RSAProvider}、{@link SHAProvider}、{@link AESProvider} 三个
 * 独立算法实现，提供国际通用密码学能力：</p>
 *
 * <h3>算法委托映射</h3>
 * <ul>
 *   <li>签名/验签 → {@link RSAProvider}（SHA256withRSA，PKCS#1 v2.1）</li>
 *   <li>非对称加密/解密 → {@link RSAProvider}（RSA/ECB/PKCS1Padding）</li>
 *   <li>摘要 → {@link SHAProvider}（SHA-256，FIPS 180-4）</li>
 *   <li>对称加密/解密 → {@link AESProvider}（AES/GCM/NoPadding，FIPS 197）</li>
 * </ul>
 *
 * <h3>SPI 注册</h3>
 * <p>通过 {@code META-INF/services/com.levango7.dataenginebdp.encaps.crypto.CryptoProvider}
 * 注册，{@link com.levango7.dataenginebdp.encaps.crypto.CryptoSpiFactory} 按 Profile=international
 * 自动加载本类。</p>
 *
 * <h3>线程安全</h3>
 * <p>内部三个 Provider 均线程安全，本类可安全并发调用。</p>
 */
public class IntlProvider implements CryptoProvider {

    private static final Logger log = LoggerFactory.getLogger(IntlProvider.class);

    /** Provider 唯一名称（与 CryptoConfig 默认国际 Provider 名一致） */
    private static final String PROVIDER_NAME = "INTL-Provider";

    /** 默认 RSA 密钥长度 */
    private static final int DEFAULT_RSA_KEY_SIZE = 2048;

    /** 默认 AES 密钥长度（位） */
    private static final int DEFAULT_AES_KEY_SIZE = 256;

    /** 默认 AES 模式 */
    private static final String DEFAULT_AES_MODE = "GCM";

    /** RSA 算法实现 */
    private final RSAProvider rsaProvider;

    /** SHA 算法实现 */
    private final SHAProvider shaProvider;

    /** AES 算法实现 */
    private final AESProvider aesProvider;

    /** 默认 AES IV（CBC 模式解密时使用，GCM 模式自动生成） */
    private final byte[] defaultAesIv;

    /**
     * 默认构造（ServiceLoader 要求无参构造）。
     */
    public IntlProvider() {
        this(new RSAProvider(), new SHAProvider(), new AESProvider());
    }

    /**
     * 注入构造（便于测试与自定义）。
     *
     * @param rsaProvider RSA 实现
     * @param shaProvider SHA 实现
     * @param aesProvider AES 实现
     */
    public IntlProvider(RSAProvider rsaProvider, SHAProvider shaProvider, AESProvider aesProvider) {
        this.rsaProvider = rsaProvider;
        this.shaProvider = shaProvider;
        this.aesProvider = aesProvider;
        this.defaultAesIv = aesProvider.generateCbcIv();
    }

    /**
     * 获取内部 RSA Provider。
     *
     * @return RSAProvider 实例
     */
    public RSAProvider getRsaProvider() {
        return rsaProvider;
    }

    /**
     * 获取内部 SHA Provider。
     *
     * @return SHAProvider 实例
     */
    public SHAProvider getShaProvider() {
        return shaProvider;
    }

    /**
     * 获取内部 AES Provider。
     *
     * @return AESProvider 实例
     */
    public AESProvider getAesProvider() {
        return aesProvider;
    }

    // ===== CryptoProvider 接口实现 =====

    @Override
    public byte[] sign(byte[] data, PrivateKey key) {
        if (data == null || key == null) {
            throw new CryptoException("data and key must not be null");
        }
        return rsaProvider.sign(data, key.getEncoded());
    }

    @Override
    public boolean verifySign(byte[] data, byte[] sign, PublicKey key) {
        if (data == null || sign == null || key == null) {
            throw new CryptoException("data, sign and key must not be null");
        }
        return rsaProvider.verify(data, sign, key.getEncoded());
    }

    @Override
    public byte[] encrypt(byte[] plaintext, Key key) {
        if (plaintext == null || key == null) {
            throw new CryptoException("plaintext and key must not be null");
        }
        String algorithm = key.getAlgorithm();
        if ("RSA".equals(algorithm)) {
            // 非对称加密
            if (key instanceof PublicKey) {
                return rsaProvider.encrypt(plaintext, key.getEncoded());
            } else {
                throw new CryptoException("RSA encrypt requires PublicKey, got: " + key.getClass());
            }
        } else if ("AES".equals(algorithm)) {
            // 对称加密（GCM 模式，自动生成 IV）
            byte[] iv = aesProvider.generateGcmIv();
            byte[] ciphertext = aesProvider.encrypt(plaintext, key.getEncoded(), DEFAULT_AES_MODE, iv);
            // 返回 iv || ciphertext，便于解密时恢复
            byte[] result = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, result, 0, iv.length);
            System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);
            return result;
        } else {
            throw new CryptoException("Unsupported key algorithm: " + algorithm);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext, Key key) {
        if (ciphertext == null || key == null) {
            throw new CryptoException("ciphertext and key must not be null");
        }
        String algorithm = key.getAlgorithm();
        if ("RSA".equals(algorithm)) {
            if (key instanceof PrivateKey) {
                return rsaProvider.decrypt(ciphertext, key.getEncoded());
            } else {
                throw new CryptoException("RSA decrypt requires PrivateKey, got: " + key.getClass());
            }
        } else if ("AES".equals(algorithm)) {
            // 对称解密：从密文头部提取 IV（GCM 12 字节）
            int ivLen = AESProvider.GCM_IV_LENGTH;
            if (ciphertext.length < ivLen) {
                throw new CryptoException("AES ciphertext too short to contain IV");
            }
            byte[] iv = Arrays.copyOfRange(ciphertext, 0, ivLen);
            byte[] actualCiphertext = Arrays.copyOfRange(ciphertext, ivLen, ciphertext.length);
            return aesProvider.decrypt(actualCiphertext, key.getEncoded(), DEFAULT_AES_MODE, iv);
        } else {
            throw new CryptoException("Unsupported key algorithm: " + algorithm);
        }
    }

    @Override
    public byte[] digest(byte[] data) {
        return shaProvider.digest(data);
    }

    @Override
    public KeyPair getKeyPair() {
        return rsaProvider.generateKeyPair(DEFAULT_RSA_KEY_SIZE);
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public AlgorithmType getAlgorithmType() {
        return AlgorithmType.SIGN;
    }

    @Override
    public CryptoProfile getSupportedProfile() {
        return CryptoProfile.INTERNATIONAL;
    }

    // ===== 便捷方法 =====

    /**
     * SHA-256 摘要十六进制表示。
     *
     * @param data 输入
     * @return 摘要 hex 字符串
     */
    public String digestHex(byte[] data) {
        return shaProvider.digestHex(data);
    }

    /**
     * SHA 摘要（指定算法）。
     *
     * @param data      输入
     * @param algorithm 算法名（SHA-256/384/512）
     * @return 摘要字节
     */
    public byte[] digest(byte[] data, String algorithm) {
        return shaProvider.digest(data, algorithm);
    }

    /**
     * 常量时间比较（防侧信道）。
     *
     * @param a 字节数组 a
     * @param b 字节数组 b
     * @return 相等返回 true
     */
    public boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.length != b.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length; i++) {
            result |= a[i] ^ b[i];
        }
        return result == 0;
    }

    /**
     * 生成 RSA 密钥对（指定位数）。
     *
     * @param keySize 密钥长度（2048/4096）
     * @return RSA 密钥对
     */
    public KeyPair generateRsaKeyPair(int keySize) {
        return rsaProvider.generateKeyPair(keySize);
    }

    /**
     * 生成 AES 密钥。
     *
     * @param keySize 密钥长度（128/256）
     * @return AES 密钥字节
     */
    public byte[] generateAesKey(int keySize) {
        return aesProvider.generateKey(keySize);
    }
}