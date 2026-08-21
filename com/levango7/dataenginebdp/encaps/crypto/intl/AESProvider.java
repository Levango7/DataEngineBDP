package com.shuqing.bigdata.encaps.crypto.intl;

import com.shuqing.bigdata.encaps.crypto.CryptoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Locale;

/**
 * AES 对称加密 Provider（符合 FIPS 197）。
 *
 * <p>基于 JDK 内置 {@code javax.crypto} 实现 AES 对称加密，满足 FIPS 197 规范要求：</p>
 *
 * <h3>支持配置</h3>
 * <ul>
 *   <li>密钥长度：AES-128（16 字节）/ AES-256（32 字节）</li>
 *   <li>模式：
 *     <ul>
 *       <li>GCM（Galois/Counter Mode）— AEAD 认证加密，IV 推荐 12 字节，标签 16 字节</li>
 *       <li>CBC（Cipher Block Chaining）— PKCS5Padding，IV 16 字节</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <h3>GCM 模式输出格式</h3>
 * <p>GCM 模式下 {@link #encrypt(byte[], byte[], String, byte[])} 返回
 * {@code ciphertext || tag}（密文后拼接 16 字节认证标签）。
 * {@link #decrypt(byte[], byte[], String, byte[])} 期望相同格式。</p>
 *
 * <h3>线程安全</h3>
 * <p>所有方法均创建新的 {@link Cipher} 实例，无共享可变状态，可安全并发调用。</p>
 *
 * <h3>性能</h3>
 * <p>AES-GCM 在 JDK 17+ 中使用硬件加速（AES-NI），AES-256-GCM 吞吐可达 500MB/s 以上。</p>
 */
public class AESProvider {

    private static final Logger log = LoggerFactory.getLogger(AESProvider.class);

    /** AES 密钥算法名 */
    public static final String AES_KEY_ALGORITHM = "AES";

    /** GCM 模式算法名 */
    public static final String GCM_MODE = "AES/GCM/NoPadding";

    /** CBC 模式算法名 */
    public static final String CBC_MODE = "AES/CBC/PKCS5Padding";

    /** GCM 默认 IV 长度（12 字节，NIST 推荐） */
    public static final int GCM_IV_LENGTH = 12;

    /** GCM 认证标签长度（字节） */
    public static final int GCM_TAG_LENGTH = 16;

    /** CBC IV 长度（16 字节，等于 AES 块大小） */
    public static final int CBC_IV_LENGTH = 16;

    /** GCM 标签长度（位） */
    private static final int GCM_TAG_LENGTH_BITS = GCM_TAG_LENGTH * 8;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * AES 加密。
     *
     * @param plaintext 明文，不可为 null
     * @param key       AES 密钥字节（16 或 32 字节），不可为 null
     * @param mode      加密模式：{@code GCM} 或 {@code CBC}（大小写不敏感）
     * @param iv        初始化向量；GCM 推荐 12 字节，CBC 必须 16 字节；为 null 时自动生成
     * @return 密文（GCM 模式含认证标签，格式为 {@code ciphertext || tag}）
     * @throws CryptoException 参数不合法或加密失败
     */
    public byte[] encrypt(byte[] plaintext, byte[] key, String mode, byte[] iv) {
        if (plaintext == null || key == null) {
            throw new CryptoException("plaintext and key must not be null");
        }
        validateKeyLength(key);
        String normalizedMode = normalizeMode(mode);
        byte[] actualIv = iv != null ? iv : generateIv(getIvLength(normalizedMode));
        validateIvLength(actualIv, normalizedMode);

        try {
            SecretKey secretKey = new SecretKeySpec(key, AES_KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(getJceName(normalizedMode));

            if ("GCM".equals(normalizedMode)) {
                GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, actualIv);
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            } else {
                IvParameterSpec spec = new IvParameterSpec(actualIv);
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            }
            return cipher.doFinal(plaintext);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("AES encrypt failed: " + normalizedMode, e);
        }
    }

    /**
     * AES 解密。
     *
     * @param ciphertext 密文（GCM 模式含认证标签）
     * @param key        AES 密钥字节
     * @param mode       加密模式：{@code GCM} 或 {@code CBC}
     * @param iv         初始化向量，与加密时一致
     * @return 明文
     * @throws CryptoException 解密失败（密钥错误、标签验证失败等）
     */
    public byte[] decrypt(byte[] ciphertext, byte[] key, String mode, byte[] iv) {
        if (ciphertext == null || key == null || iv == null) {
            throw new CryptoException("ciphertext, key and iv must not be null");
        }
        validateKeyLength(key);
        String normalizedMode = normalizeMode(mode);
        validateIvLength(iv, normalizedMode);

        try {
            SecretKey secretKey = new SecretKeySpec(key, AES_KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(getJceName(normalizedMode));

            if ("GCM".equals(normalizedMode)) {
                GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            } else {
                IvParameterSpec spec = new IvParameterSpec(iv);
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            }
            return cipher.doFinal(ciphertext);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("AES decrypt failed: " + normalizedMode, e);
        }
    }

    /**
     * AES-GCM 加密（带附加认证数据 AAD）。
     *
     * @param plaintext 明文
     * @param key       AES 密钥
     * @param iv        IV（推荐 12 字节）
     * @param aad       附加认证数据（不加密但参与认证）
     * @return 密文（含认证标签）
     * @throws CryptoException 加密失败
     */
    public byte[] encryptGcmWithAad(byte[] plaintext, byte[] key, byte[] iv, byte[] aad) {
        if (plaintext == null || key == null) {
            throw new CryptoException("plaintext and key must not be null");
        }
        validateKeyLength(key);
        byte[] actualIv = iv != null ? iv : generateIv(GCM_IV_LENGTH);
        validateIvLength(actualIv, "GCM");

        try {
            SecretKey secretKey = new SecretKeySpec(key, AES_KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(GCM_MODE);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, actualIv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(plaintext);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("AES-GCM encrypt with AAD failed", e);
        }
    }

    /**
     * AES-GCM 解密（带附加认证数据 AAD）。
     *
     * @param ciphertext 密文（含认证标签）
     * @param key        AES 密钥
     * @param iv         IV
     * @param aad        附加认证数据（必须与加密时一致）
     * @return 明文
     * @throws CryptoException 解密或认证失败
     */
    public byte[] decryptGcmWithAad(byte[] ciphertext, byte[] key, byte[] iv, byte[] aad) {
        if (ciphertext == null || key == null || iv == null) {
            throw new CryptoException("ciphertext, key and iv must not be null");
        }
        validateKeyLength(key);
        validateIvLength(iv, "GCM");

        try {
            SecretKey secretKey = new SecretKeySpec(key, AES_KEY_ALGORITHM);
            Cipher cipher = Cipher.getInstance(GCM_MODE);
            GCMParameterSpec spec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            return cipher.doFinal(ciphertext);
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("AES-GCM decrypt with AAD failed", e);
        }
    }

    /**
     * 生成 AES 密钥。
     *
     * @param keySize 密钥长度（位）：128 或 256
     * @return 密钥字节
     * @throws CryptoException 长度不合法或生成失败
     */
    public byte[] generateKey(int keySize) {
        if (keySize != 128 && keySize != 192 && keySize != 256) {
            throw new CryptoException("Unsupported AES key size: " + keySize
                    + ", supported: 128 / 192 / 256");
        }
        try {
            KeyGenerator kg = KeyGenerator.getInstance(AES_KEY_ALGORITHM);
            kg.init(keySize, secureRandom);
            return kg.generateKey().getEncoded();
        } catch (Exception e) {
            throw new CryptoException("AES generateKey failed: " + keySize, e);
        }
    }

    /**
     * 生成默认 AES-256 密钥。
     *
     * @return 32 字节密钥
     */
    public byte[] generateKey() {
        return generateKey(256);
    }

    /**
     * 生成 IV（初始化向量）。
     *
     * @param ivLength IV 长度（字节）
     * @return 随机 IV
     * @throws CryptoException 长度不合法
     */
    public byte[] generateIv(int ivLength) {
        if (ivLength <= 0) {
            throw new CryptoException("IV length must be positive: " + ivLength);
        }
        byte[] iv = new byte[ivLength];
        secureRandom.nextBytes(iv);
        return iv;
    }

    /**
     * 生成默认 GCM IV（12 字节）。
     *
     * @return 12 字节 IV
     */
    public byte[] generateGcmIv() {
        return generateIv(GCM_IV_LENGTH);
    }

    /**
     * 生成默认 CBC IV（16 字节）。
     *
     * @return 16 字节 IV
     */
    public byte[] generateCbcIv() {
        return generateIv(CBC_IV_LENGTH);
    }

    /**
     * 测量 AES-GCM 加密吞吐量。
     *
     * <p>使用大块数据 + 少量迭代来减少 Cipher 创建/init 开销，
     * 更准确反映 AES-GCM 的实际加密吞吐。</p>
     *
     * @param keySize  密钥长度（128 或 256）
     * @param dataSize 测试数据大小（字节）
     * @return 吞吐量（MB/s）
     * @throws CryptoException 测量失败
     */
    public double measureThroughput(int keySize, int dataSize) {
        byte[] key = generateKey(keySize);
        byte[] data = new byte[dataSize];
        secureRandom.nextBytes(data);

        try {
            SecretKey secretKey = new SecretKeySpec(key, AES_KEY_ALGORITHM);

            // 预热 JIT
            byte[] warmupIv = generateGcmIv();
            Cipher warmup = Cipher.getInstance(GCM_MODE);
            warmup.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, warmupIv));
            warmup.doFinal(data);

            // 测量 — 大块数据 + 少量迭代，减少 Cipher 创建开销
            int iterations = 5;
            byte[] iv = generateGcmIv();
            long startTime = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                iv[11] = (byte) (i & 0xff);
                Cipher cipher = Cipher.getInstance(GCM_MODE);
                cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
                cipher.doFinal(data);
            }
            long elapsedNanos = System.nanoTime() - startTime;

            double totalBytes = (double) dataSize * iterations;
            double mbPerSecond = totalBytes / (elapsedNanos / 1_000_000_000.0) / (1024 * 1024);
            log.debug("AES-{}-GCM throughput: {} MB/s (dataSize={}, iterations={})",
                    keySize, String.format("%.2f", mbPerSecond), dataSize, iterations);
            return mbPerSecond;
        } catch (Exception e) {
            throw new CryptoException("Throughput measurement failed", e);
        }
    }

    // ---------------- 内部方法 ----------------

    /**
     * 规范化模式名。
     */
    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "GCM";
        }
        String upper = mode.trim().toUpperCase(Locale.ROOT);
        if (upper.contains("GCM")) return "GCM";
        if (upper.contains("CBC")) return "CBC";
        throw new CryptoException("Unsupported AES mode: " + mode + ", supported: GCM / CBC");
    }

    /**
     * 获取 JCE 算法名。
     */
    private String getJceName(String mode) {
        return "GCM".equals(mode) ? GCM_MODE : CBC_MODE;
    }

    /**
     * 获取对应模式的 IV 长度。
     */
    private int getIvLength(String mode) {
        return "GCM".equals(mode) ? GCM_IV_LENGTH : CBC_IV_LENGTH;
    }

    /**
     * 校验密钥长度。
     */
    private void validateKeyLength(byte[] key) {
        if (key.length != 16 && key.length != 24 && key.length != 32) {
            throw new CryptoException("Invalid AES key length: " + key.length
                    + " bytes, supported: 16 (AES-128) / 24 (AES-192) / 32 (AES-256)");
        }
    }

    /**
     * 校验 IV 长度。
     */
    private void validateIvLength(byte[] iv, String mode) {
        if ("GCM".equals(mode)) {
            // GCM IV 通常 12 字节，但其他长度也支持（只是性能可能下降）
            if (iv.length < 1) {
                throw new CryptoException("GCM IV length must be at least 1 byte, got: " + iv.length);
            }
        } else {
            if (iv.length != CBC_IV_LENGTH) {
                throw new CryptoException("CBC IV length must be " + CBC_IV_LENGTH
                        + " bytes, got: " + iv.length);
            }
        }
    }
}