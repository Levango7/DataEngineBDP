package com.levango7.dataenginebdp.encaps.crypto.intl;

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
 * 默认国际 Provider（国际 Profile）。
 *
 * <p>作为 SPI 框架的默认实现，提供国际通用算法：RSA / SHA-256 / AES。
 * 基于 JDK 标准 {@code java.security} / {@code javax.crypto} 实现，无需第三方依赖。</p>
 *
 * <p>算法选择：</p>
 * <ul>
 *   <li>签名：SHA256withRSA</li>
 *   <li>非对称加密：RSA 2048-bit（PKCS1Padding）</li>
 *   <li>摘要：SHA-256</li>
 *   <li>对称加密：AES</li>
 * </ul>
 */
public class DefaultIntlProvider implements CryptoProvider {

    private static final Logger log = LoggerFactory.getLogger(DefaultIntlProvider.class);

    private static final String PROVIDER_NAME = "INTL-Provider";
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
            throw new CryptoException("INTL sign failed", e);
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
            throw new CryptoException("INTL verifySign failed", e);
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
            throw new CryptoException("INTL encrypt failed", e);
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
            throw new CryptoException("INTL decrypt failed", e);
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
            throw new CryptoException("INTL digest failed", e);
        }
    }

    @Override
    public KeyPair getKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ASYMMETRIC_ALGORITHM);
            generator.initialize(KEY_SIZE, secureRandom);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new CryptoException("INTL generate keypair failed", e);
        }
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

    /**
     * 摘要十六进制表示。
     *
     * @param data 输入
     * @return 摘要 hex
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
     * 常量时间比较。
     *
     * @param a 字节数组 a
     * @param b 字节数组 b
     * @return 相等返回 true
     */
    public boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null) {
            return false;
        }
        return Arrays.equals(a, b);
    }
}