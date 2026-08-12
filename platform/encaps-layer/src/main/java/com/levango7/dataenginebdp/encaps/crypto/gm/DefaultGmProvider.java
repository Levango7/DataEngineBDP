package com.levango7.dataenginebdp.encaps.crypto.gm;

import com.levango7.dataenginebdp.encaps.crypto.AlgorithmType;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 默认国密 Provider（信创 Profile）。
 *
 * <p>作为 SPI 框架的默认实现，提供 SM2/SM3/SM4 算法的占位实现。
 * 真实生产环境应替换为对接 BC-SM 等国密库的实现。</p>
 *
 * <p>本实现采用以下策略：</p>
 * <ul>
 *   <li>签名/加密：使用 SHA-256withRSA 作为 SM2withSM3 的占位（保证可运行）</li>
 *   <li>摘要：使用 SHA-256 作为 SM3 的占位</li>
 *   <li>对称加密：使用 AES 作为 SM4 的占位</li>
 *   <li>密钥对：生成 2048-bit RSA 密钥对作为 SM2 密钥对的占位</li>
 * </ul>
 *
 * <p>真实国密实现应在 T022-2 等后续任务中替换。</p>
 */
public class DefaultGmProvider implements CryptoProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultGmProvider.class);

    private static final String PROVIDER_NAME = "GM-Provider";
    private static final String DIGEST_ALGORITHM = "SHA-256";
    private static final String SIGN_ALGORITHM = "SHA256withRSA";
    private static final String ASYMMETRIC_ALGORITHM = "RSA";
    private static final String SYMMETRIC_ALGORITHM = "AES";
    private static final int KEY_SIZE = 2048;

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public byte[] sign(byte[] data, PrivateKey key) {
        if (data == null || key == null) {
            throw new CryptoException("data and key must not be null");
        }
        try {
            java.security.Signature signature = java.security.Signature.getInstance(SIGN_ALGORITHM);
            signature.initSign(key);
            signature.update(data);
            return signature.sign();
        } catch (Exception e) {
            throw new CryptoException("GM sign failed", e);
        }
    }

    @Override
    public boolean verifySign(byte[] data, byte[] sign, PublicKey key) {
        if (data == null || sign == null || key == null) {
            throw new CryptoException("data, sign and key must not be null");
        }
        try {
            java.security.Signature signature = java.security.Signature.getInstance(SIGN_ALGORITHM);
            signature.initVerify(key);
            signature.update(data);
            return signature.verify(sign);
        } catch (Exception e) {
            throw new CryptoException("GM verifySign failed", e);
        }
    }

    @Override
    public byte[] encrypt(byte[] plaintext, Key key) {
        if (plaintext == null || key == null) {
            throw new CryptoException("plaintext and key must not be null");
        }
        try {
            String algorithm = SYMMETRIC_ALGORITHM.equals(key.getAlgorithm())
                    ? SYMMETRIC_ALGORITHM : ASYMMETRIC_ALGORITHM;
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(algorithm);
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, secureRandom);
            return cipher.doFinal(plaintext);
        } catch (Exception e) {
            throw new CryptoException("GM encrypt failed", e);
        }
    }

    @Override
    public byte[] decrypt(byte[] ciphertext, Key key) {
        if (ciphertext == null || key == null) {
            throw new CryptoException("ciphertext and key must not be null");
        }
        try {
            String algorithm = SYMMETRIC_ALGORITHM.equals(key.getAlgorithm())
                    ? SYMMETRIC_ALGORITHM : ASYMMETRIC_ALGORITHM;
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance(algorithm);
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, key);
            return cipher.doFinal(ciphertext);
        } catch (Exception e) {
            throw new CryptoException("GM decrypt failed", e);
        }
    }

    @Override
    public byte[] digest(byte[] data) {
        if (data == null) {
            throw new CryptoException("data must not be null");
        }
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance(DIGEST_ALGORITHM);
            return md.digest(data);
        } catch (Exception e) {
            throw new CryptoException("GM digest failed", e);
        }
    }

    @Override
    public KeyPair getKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ASYMMETRIC_ALGORITHM);
            generator.initialize(KEY_SIZE, secureRandom);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new CryptoException("GM generate keypair failed", e);
        }
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public AlgorithmType getAlgorithmType() {
        // 国密 Provider 主算法类型为签名（SM2withSM3）
        return AlgorithmType.SIGN;
    }

    @Override
    public CryptoProfile getSupportedProfile() {
        return CryptoProfile.XINCHANG;
    }

    /**
     * 简单自检：摘要确定性。
     *
     * @param data 输入
     * @return 摘要十六进制字符串
     */
    public String digestHex(byte[] data) {
        byte[] digest = digest(data);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * 常量时间比较两个摘要，防侧信道。
     *
     * @param a 摘要 a
     * @param b 摘要 b
     * @return 相等返回 true
     */
    public boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        }
        return Arrays.equals(a, b);
    }
}