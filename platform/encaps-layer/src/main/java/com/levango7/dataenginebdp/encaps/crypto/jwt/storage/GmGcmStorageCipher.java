package com.levango7.dataenginebdp.encaps.crypto.jwt.storage;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.gm.GmAlgorithm;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM4Provider;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 国密存储加密（SM4-GCM，AEAD 认证加密）。
 *
 * <p>基于 {@link SM4Provider} 的 SM4-GCM 实现（GB/T 32907 分组密码 + NIST SP 800-38D GCM 模式），
 * 适用于信创辖区（{@code spring.profiles.active=xinchang}）下的持久化敏感字段加密。</p>
 *
 * <h3>相比 SM4-CBC 的优势</h3>
 * <ul>
 *   <li><b>机密性 + 完整性</b>：GCM 为 AEAD，密文被篡改、密钥错误时解密抛异常，
 *       不会返回被篡改的明文（CBC 无认证，需额外 MAC）</li>
 *   <li>流式模式，无需 PKCS7 填充</li>
 * </ul>
 *
 * <h3>密文格式</h3>
 * <pre>{@code
 * Base64( "SM4-GCM" | 0x00 | IV(12B) | ciphertext || tag(16B) )
 * }</pre>
 * <ul>
 *   <li>前 7 字节为算法标识 "SM4-GCM"</li>
 *   <li>第 8 字节为分隔符 0x00</li>
 *   <li>第 9-20 字节为 12 字节 GCM IV</li>
 *   <li>第 21 字节起为密文，末尾 16 字节为 GCM 认证标签</li>
 * </ul>
 *
 * <h3>密钥管理</h3>
 * <p>SM4 密钥（16 字节）由调用方注入，生产环境应通过 KMS 或环境变量提供。
 * 同一逻辑密钥的多个实例（如多节点部署）必须使用相同密钥才能互相解密。</p>
 *
 * <h3>线程安全</h3>
 * <p>线程安全（{@link SM4Provider} 线程安全，密钥只读）。</p>
 */
public class GmGcmStorageCipher implements StorageCipher {

    /** 算法标识 */
    public static final String ALGORITHM = "SM4-GCM";

    /** 算法标识字节（含尾部 0x00 分隔符） */
    private static final byte[] ALGORITHM_BYTES;

    static {
        byte[] algBytes = ALGORITHM.getBytes(StandardCharsets.US_ASCII);
        ALGORITHM_BYTES = new byte[algBytes.length + 1];
        System.arraycopy(algBytes, 0, ALGORITHM_BYTES, 0, algBytes.length);
        // 0x00 分隔符已默认为 0
    }

    /** SM4 算法实现 */
    private final SM4Provider sm4;

    /** SM4 密钥（16 字节） */
    private final byte[] key;

    /** Base64 编码器（无换行，标准 padding） */
    private final Base64.Encoder base64Encoder = Base64.getEncoder();

    /** Base64 解码器 */
    private final Base64.Decoder base64Decoder = Base64.getDecoder();

    /**
     * 构造国密 AEAD 存储加密器。
     *
     * @param key SM4 密钥，必须 16 字节
     * @throws CryptoException 密钥长度非法
     */
    public GmGcmStorageCipher(byte[] key) {
        this(new SM4Provider(), key);
    }

    /**
     * 注入构造（便于测试与自定义）。
     *
     * @param sm4 SM4 算法实现
     * @param key SM4 密钥，必须 16 字节
     * @throws CryptoException 参数非法
     */
    public GmGcmStorageCipher(SM4Provider sm4, byte[] key) {
        if (sm4 == null) {
            throw new CryptoException("sm4 must not be null");
        }
        if (key == null || key.length != GmAlgorithm.SM4_KEY_LEN) {
            throw new CryptoException("SM4 key must be 16 bytes, got: "
                    + (key == null ? "null" : key.length));
        }
        this.sm4 = sm4;
        this.key = key.clone();
    }

    @Override
    public byte[] encrypt(byte[] plaintext) {
        if (plaintext == null) {
            throw new CryptoException("plaintext must not be null");
        }
        byte[] iv = sm4.generateGcmIv();
        byte[] ciphertext = sm4.encryptGcm(plaintext, key, iv, null);
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
        int minLen = ALGORITHM_BYTES.length + GmAlgorithm.SM4_GCM_IV_LEN
                + GmAlgorithm.SM4_GCM_TAG_LEN;
        if (raw.length < minLen) {
            throw new CryptoException("ciphertext too short");
        }
        for (int i = 0; i < ALGORITHM_BYTES.length; i++) {
            if (raw[i] != ALGORITHM_BYTES[i]) {
                throw new CryptoException("algorithm mismatch, expected: " + ALGORITHM
                        + ", got: " + new String(raw, 0, ALGORITHM_BYTES.length - 1,
                        StandardCharsets.US_ASCII));
            }
        }
        int pos = ALGORITHM_BYTES.length;
        byte[] iv = new byte[GmAlgorithm.SM4_GCM_IV_LEN];
        System.arraycopy(raw, pos, iv, 0, iv.length);
        pos += iv.length;
        byte[] actualCipher = new byte[raw.length - pos];
        System.arraycopy(raw, pos, actualCipher, 0, actualCipher.length);
        return sm4.decryptGcm(actualCipher, key, iv, null);
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
        return true;
    }
}
