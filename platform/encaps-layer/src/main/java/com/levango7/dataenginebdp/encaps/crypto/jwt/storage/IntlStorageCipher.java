package com.levango7.dataenginebdp.encaps.crypto.jwt.storage;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.intl.AESProvider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 国际存储加密（AES-GCM）。
 *
 * <p>基于 T022 双栈的 {@link AESProvider} 实现，使用 AES-GCM（AEAD 认证加密），
 * 适用于国际辖区（{@code spring.profiles.active=international}）下的持久化敏感字段加密。</p>
 *
 * <h3>密文格式</h3>
 * <p>采用自描述格式：</p>
 * <pre>{@code
 * Base64( "AES-GCM" | 0x00 | IV(12B) | ciphertext||tag )
 * }</pre>
 * <ul>
 *   <li>前 7 字节为算法标识 "AES-GCM"</li>
 *   <li>第 8 字节为分隔符 0x00</li>
 *   <li>第 9-20 字节为 12 字节 GCM IV</li>
 *   <li>第 21 字节起为 AES-GCM 密文（含 16 字节认证标签）</li>
 * </ul>
 *
 * <h3>密钥管理</h3>
 * <p>AES 密钥（16 或 32 字节）由调用方注入。GCM 模式提供机密性与完整性双重保证，
 * 密钥错误时解密会抛出 {@link CryptoException}（AEAD 标签验证失败）。</p>
 *
 * <h3>线程安全</h3>
 * <p>线程安全（{@link AESProvider} 线程安全，密钥只读）。</p>
 */
public class IntlStorageCipher implements StorageCipher {

    /** 算法标识 */
    public static final String ALGORITHM = "AES-GCM";

    /** 算法标识字节（含尾部 0x00 分隔符） */
    private static final byte[] ALGORITHM_BYTES;

    static {
        byte[] algBytes = ALGORITHM.getBytes(StandardCharsets.US_ASCII);
        ALGORITHM_BYTES = new byte[algBytes.length + 1];
        System.arraycopy(algBytes, 0, ALGORITHM_BYTES, 0, algBytes.length);
    }

    /** AES 算法实现 */
    private final AESProvider aes;

    /** AES 密钥 */
    private final byte[] key;

    /** Base64 编码器 */
    private final Base64.Encoder base64Encoder = Base64.getEncoder();

    /** Base64 解码器 */
    private final Base64.Decoder base64Decoder = Base64.getDecoder();

    /**
     * 构造国际存储加密器。
     *
     * @param key AES 密钥，16 或 32 字节
     * @throws CryptoException 密钥长度非法
     */
    public IntlStorageCipher(byte[] key) {
        this(new AESProvider(), key);
    }

    /**
     * 注入构造。
     *
     * @param aes AES 算法实现
     * @param key AES 密钥
     * @throws CryptoException 参数非法
     */
    public IntlStorageCipher(AESProvider aes, byte[] key) {
        if (aes == null) {
            throw new CryptoException("aes must not be null");
        }
        if (key == null || (key.length != 16 && key.length != 32)) {
            throw new CryptoException("AES key must be 16 or 32 bytes, got: "
                    + (key == null ? "null" : key.length));
        }
        this.aes = aes;
        this.key = key.clone();
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        if (plaintext == null) {
            throw new CryptoException("plaintext must not be null");
        }
        byte[] iv = aes.generateGcmIv();
        byte[] ciphertext = aes.encrypt(plaintext, key, "GCM", iv);
        byte[] result = new byte[ALGORITHM_BYTES.length + iv.length + ciphertext.length];
        int pos = 0;
        System.arraycopy(ALGORITHM_BYTES, 0, result, pos, ALGORITHM_BYTES.length);
        pos += ALGORITHM_BYTES.length;
        System.arraycopy(iv, 0, result, pos, iv.length);
        pos += iv.length;
        System.arraycopy(ciphertext, 0, result, pos, ciphertext.length);
        return base64Encoder.encode(result);
    }

    @Override
    public byte[] decrypt(byte[] ciphertext) {
        if (ciphertext == null) {
            throw new CryptoException("ciphertext must not be null");
        }
        byte[] raw;
        try {
            raw = base64Decoder.decode(ciphertext);
        } catch (IllegalArgumentException e) {
            throw new CryptoException("invalid Base64 ciphertext", e);
        }
        if (raw.length < ALGORITHM_BYTES.length + AESProvider.GCM_IV_LENGTH) {
            throw new CryptoException("ciphertext too short");
        }
        for (int i = 0; i < ALGORITHM_BYTES.length; i++) {
            if (raw[i] != ALGORITHM_BYTES[i]) {
                throw new CryptoException("algorithm mismatch, expected: " + ALGORITHM);
            }
        }
        int pos = ALGORITHM_BYTES.length;
        byte[] iv = new byte[AESProvider.GCM_IV_LENGTH];
        System.arraycopy(raw, pos, iv, 0, iv.length);
        pos += iv.length;
        byte[] actualCipher = new byte[raw.length - pos];
        System.arraycopy(raw, pos, actualCipher, 0, actualCipher.length);
        return aes.decrypt(actualCipher, key, "GCM", iv);
    }

    @Override
    public String encryptString(String plaintext) {
        if (plaintext == null) {
            throw new CryptoException("plaintext must not be null");
        }
        byte[] cipher = encrypt(plaintext.getBytes(StandardCharsets.UTF_8));
        return new String(cipher, StandardCharsets.US_ASCII);
    }

    @Override
    public String decryptString(String ciphertext) {
        if (ciphertext == null) {
            throw new CryptoException("ciphertext must not be null");
        }
        byte[] plain = decrypt(ciphertext.getBytes(StandardCharsets.US_ASCII));
        return new String(plain, StandardCharsets.UTF_8);
    }

    @Override
    public String getAlgorithm() {
        return ALGORITHM;
    }

    @Override
    public boolean isGm() {
        return false;
    }
}