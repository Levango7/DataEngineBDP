package com.shuqing.bigdata.encaps.crypto.intl;

import com.shuqing.bigdata.encaps.crypto.CryptoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * RSA 密码学 Provider（符合 PKCS#1 v2.1）。
 *
 * <p>基于 JDK 内置 {@code java.security} / {@code javax.crypto} 实现 RSA 签名与加密，
 * 满足以下规范：</p>
 * <ul>
 *   <li>签名：PKCS#1 v2.1（RSASSA-PSS 或 RSASSA-PKCS1-v1_5），默认 SHA256withRSA</li>
 *   <li>加密：RSAES-PKCS1-v1_5（{@code RSA/ECB/PKCS1Padding}）</li>
 *   <li>密钥长度：支持 2048 / 4096 位</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有方法均创建新的 {@link Cipher}/{@link Signature} 实例，无共享可变状态，
 * 可安全并发调用。</p>
 *
 * <h3>密钥编码</h3>
 * <ul>
 *   <li>公钥：X.509 SubjectPublicKeyInfo（{@link X509EncodedKeySpec}）</li>
 *   <li>私钥：PKCS#8 PrivateKeyInfo（{@link PKCS8EncodedKeySpec}）</li>
 * </ul>
 */
public class RSAProvider {

    private static final Logger log = LoggerFactory.getLogger(RSAProvider.class);

    /** RSA 加密算法名（PKCS#1 v1.5 padding） */
    public static final String RSA_CIPHER = "RSA/ECB/PKCS1Padding";

    /** RSA 密钥算法名 */
    public static final String RSA_KEY_ALGORITHM = "RSA";

    /** 默认签名算法（SHA-256 with RSA，PKCS#1 v1.5） */
    public static final String DEFAULT_SIGN_ALGORITHM = "SHA256withRSA";

    /** 默认密钥长度 */
    public static final int DEFAULT_KEY_SIZE = 2048;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 对数据做 RSA 数字签名。
     *
     * @param data       待签名数据，不可为 null
     * @param privateKey PKCS#8 编码的私钥字节，不可为 null
     * @return 签名值
     * @throws CryptoException 签名失败（密钥格式错误、数据为空等）
     */
    public byte[] sign(byte[] data, byte[] privateKey) {
        return sign(data, privateKey, DEFAULT_SIGN_ALGORITHM);
    }

    /**
     * 对数据做 RSA 数字签名（指定签名算法）。
     *
     * @param data       待签名数据
     * @param privateKey PKCS#8 编码的私钥字节
     * @param algorithm  签名算法，如 {@code SHA256withRSA} / {@code SHA384withRSA} / {@code SHA512withRSA}
     * @return 签名值
     * @throws CryptoException 签名失败
     */
    public byte[] sign(byte[] data, byte[] privateKey, String algorithm) {
        if (data == null || privateKey == null) {
            throw new CryptoException("data and privateKey must not be null");
        }
        if (algorithm == null || algorithm.isBlank()) {
            algorithm = DEFAULT_SIGN_ALGORITHM;
        }
        try {
            PrivateKey key = decodePrivateKey(privateKey);
            Signature signature = Signature.getInstance(algorithm);
            signature.initSign(key);
            signature.update(data);
            return signature.sign();
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("RSA sign failed: " + algorithm, e);
        }
    }

    /**
     * 验证 RSA 签名。
     *
     * @param data      原始数据
     * @param signature 签名值
     * @param publicKey X.509 编码的公钥字节
     * @return 验签通过返回 true；否则 false
     * @throws CryptoException 验签过程异常（密钥格式错误等）
     */
    public boolean verify(byte[] data, byte[] signature, byte[] publicKey) {
        return verify(data, signature, publicKey, DEFAULT_SIGN_ALGORITHM);
    }

    /**
     * 验证 RSA 签名（指定签名算法）。
     *
     * @param data      原始数据
     * @param signature 签名值
     * @param publicKey X.509 编码的公钥字节
     * @param algorithm 签名算法
     * @return 验签通过返回 true；否则 false
     * @throws CryptoException 验签过程异常
     */
    public boolean verify(byte[] data, byte[] signature, byte[] publicKey, String algorithm) {
        if (data == null || signature == null || publicKey == null) {
            throw new CryptoException("data, signature and publicKey must not be null");
        }
        if (algorithm == null || algorithm.isBlank()) {
            algorithm = DEFAULT_SIGN_ALGORITHM;
        }
        try {
            PublicKey key = decodePublicKey(publicKey);
            Signature sig = Signature.getInstance(algorithm);
            sig.initVerify(key);
            sig.update(data);
            return sig.verify(signature);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("RSA verify failed: " + algorithm, e);
        }
    }

    /**
     * RSA 公钥加密（RSAES-PKCS1-v1_5）。
     *
     * <p>明文长度限制：keySize/8 - 11 字节。2048 位密钥最多 245 字节。</p>
     *
     * @param plaintext 明文
     * @param publicKey X.509 编码的公钥字节
     * @return 密文
     * @throws CryptoException 加密失败
     */
    public byte[] encrypt(byte[] plaintext, byte[] publicKey) {
        if (plaintext == null || publicKey == null) {
            throw new CryptoException("plaintext and publicKey must not be null");
        }
        try {
            PublicKey key = decodePublicKey(publicKey);
            Cipher cipher = Cipher.getInstance(RSA_CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, key, secureRandom);
            return cipher.doFinal(plaintext);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("RSA encrypt failed", e);
        }
    }

    /**
     * RSA 私钥解密（RSAES-PKCS1-v1_5）。
     *
     * @param ciphertext 密文
     * @param privateKey PKCS#8 编码的私钥字节
     * @return 明文
     * @throws CryptoException 解密失败
     */
    public byte[] decrypt(byte[] ciphertext, byte[] privateKey) {
        if (ciphertext == null || privateKey == null) {
            throw new CryptoException("ciphertext and privateKey must not be null");
        }
        try {
            PrivateKey key = decodePrivateKey(privateKey);
            Cipher cipher = Cipher.getInstance(RSA_CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, key);
            return cipher.doFinal(ciphertext);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("RSA decrypt failed", e);
        }
    }

    /**
     * 生成 RSA 密钥对。
     *
     * @param keySize 密钥长度（位），支持 2048 / 4096
     * @return RSA 密钥对
     * @throws CryptoException 密钥长度不合法或生成失败
     */
    public KeyPair generateKeyPair(int keySize) {
        if (keySize != 2048 && keySize != 3072 && keySize != 4096) {
            throw new CryptoException("Unsupported RSA key size: " + keySize
                    + ", supported: 2048 / 3072 / 4096");
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA_KEY_ALGORITHM);
            generator.initialize(keySize, secureRandom);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new CryptoException("RSA generateKeyPair failed: " + keySize, e);
        }
    }

    /**
     * 生成默认 2048 位 RSA 密钥对。
     *
     * @return RSA 密钥对
     */
    public KeyPair generateKeyPair() {
        return generateKeyPair(DEFAULT_KEY_SIZE);
    }

    /**
     * 解码 X.509 公钥。
     *
     * @param encoded 公钥字节
     * @return PublicKey 对象
     * @throws CryptoException 解码失败
     */
    public PublicKey decodePublicKey(byte[] encoded) {
        try {
            KeyFactory factory = KeyFactory.getInstance(RSA_KEY_ALGORITHM);
            return factory.generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new CryptoException("Failed to decode RSA public key", e);
        }
    }

    /**
     * 解码 PKCS#8 私钥。
     *
     * @param encoded 私钥字节
     * @return PrivateKey 对象
     * @throws CryptoException 解码失败
     */
    public PrivateKey decodePrivateKey(byte[] encoded) {
        try {
            KeyFactory factory = KeyFactory.getInstance(RSA_KEY_ALGORITHM);
            return factory.generatePrivate(new PKCS8EncodedKeySpec(encoded));
        } catch (Exception e) {
            throw new CryptoException("Failed to decode RSA private key", e);
        }
    }
}