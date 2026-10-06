package com.levango7.dataenginebdp.encaps.crypto.gm;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import org.bouncycastle.crypto.engines.SM4Engine;
import org.bouncycastle.crypto.params.KeyParameter;

import java.security.SecureRandom;

/**
 * SM4 分组密码算法 Provider（GB/T 32907）。
 *
 * <p>基于 Bouncy Castle {@link SM4Engine} 轻量级 API 实现，支持 ECB/CBC 模式与 PKCS7 填充。
 * 采用底层引擎直接批处理 + 手动 PKCS7 填充，避免 JCE Provider 查找与
 * PaddedBufferedBlockCipher 封装开销，吞吐性能优于 JCE API。</p>
 *
 * <h3>国标对照</h3>
 * <ul>
 *   <li>标准：GB/T 32907-2016《信息安全技术 SM4 分组密码算法》</li>
 *   <li>分组长度：128 bit（16 byte）</li>
 *   <li>密钥长度：128 bit（16 byte）</li>
 *   <li>支持模式：ECB、CBC（默认 PKCS7 填充）</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>无共享可变状态，线程安全。每次调用新建引擎实例。</p>
 */
public class SM4Provider {

    static {
        BcProviderHolder.ensureRegistered();
    }

    private static final int BLOCK = GmAlgorithm.SM4_BLOCK_LEN;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * SM4 加密（PKCS7 填充）。
     */
    public byte[] encrypt(byte[] plaintext, byte[] key, String mode, byte[] iv) {
        return process(plaintext, key, mode, iv, true);
    }

    /**
     * SM4 解密（PKCS7 填充）。
     */
    public byte[] decrypt(byte[] ciphertext, byte[] key, String mode, byte[] iv) {
        return process(ciphertext, key, mode, iv, false);
    }

    /**
     * 生成随机 SM4 密钥。
     */
    public byte[] generateKey() {
        byte[] key = new byte[GmAlgorithm.SM4_KEY_LEN];
        secureRandom.nextBytes(key);
        return key;
    }

    /**
     * 生成随机 IV。
     */
    public byte[] generateIv() {
        byte[] iv = new byte[BLOCK];
        secureRandom.nextBytes(iv);
        return iv;
    }

    /**
     * 加解密核心处理。
     */
    private byte[] process(byte[] data, byte[] key, String mode, byte[] iv, boolean encrypt) {
        if (data == null) {
            throw new CryptoException("data must not be null");
        }
        if (key == null || key.length != GmAlgorithm.SM4_KEY_LEN) {
            throw new CryptoException("SM4 key must be 16 bytes, got: "
                    + (key == null ? "null" : key.length));
        }
        String normalizedMode = normalizeMode(mode);
        boolean isCbc = GmAlgorithm.SM4_MODE_CBC.equals(normalizedMode);
        if (isCbc && (iv == null || iv.length != BLOCK)) {
            throw new CryptoException("SM4 CBC iv must be 16 bytes, got: "
                    + (iv == null ? "null" : iv.length));
        }

        try {
            SM4Engine engine = new SM4Engine();
            engine.init(encrypt, new KeyParameter(key));

            if (encrypt) {
                byte[] padded = pkcs7Pad(data);
                return processBlocks(engine, padded, isCbc ? iv.clone() : null, encrypt);
            } else {
                byte[] plain = processBlocks(engine, data, isCbc ? iv.clone() : null, encrypt);
                return pkcs7Unpad(plain);
            }
        } catch (CryptoException e) {
            throw e;
        } catch (Exception e) {
            throw new CryptoException("SM4 " + (encrypt ? "encrypt" : "decrypt") + " failed", e);
        }
    }

    /**
     * 批量处理分组（ECB 或 CBC）。
     *
     * @param engine  SM4 引擎（已 init）
     * @param data    输入数据（长度必须为 16 的倍数）
     * @param iv      CBC 模式的 IV（会被修改）；ECB 传 null
     * @param encrypt true=加密，false=解密
     * @return 输出数据
     */
    private static byte[] processBlocks(SM4Engine engine, byte[] data, byte[] iv, boolean encrypt) {
        int blocks = data.length / BLOCK;
        byte[] out = new byte[data.length];
        byte[] tmp = new byte[BLOCK];

        for (int i = 0; i < blocks; i++) {
            int off = i * BLOCK;
            if (iv != null) {
                if (encrypt) {
                    // CBC 加密：明文 XOR IV → 加密 → 更新 IV
                    for (int j = 0; j < BLOCK; j++) {
                        tmp[j] = (byte) (data[off + j] ^ iv[j]);
                    }
                    engine.processBlock(tmp, 0, out, off);
                    System.arraycopy(out, off, iv, 0, BLOCK);
                } else {
                    // CBC 解密：保存密文 → 解密 → XOR IV
                    System.arraycopy(data, off, tmp, 0, BLOCK);
                    engine.processBlock(data, off, out, off);
                    for (int j = 0; j < BLOCK; j++) {
                        out[off + j] ^= iv[j];
                    }
                    System.arraycopy(tmp, 0, iv, 0, BLOCK);
                }
            } else {
                // ECB
                engine.processBlock(data, off, out, off);
            }
        }
        return out;
    }

    /**
     * PKCS7 填充。
     */
    private static byte[] pkcs7Pad(byte[] data) {
        int padLen = BLOCK - (data.length % BLOCK);
        byte[] out = new byte[data.length + padLen];
        System.arraycopy(data, 0, out, 0, data.length);
        for (int i = data.length; i < out.length; i++) {
            out[i] = (byte) padLen;
        }
        return out;
    }

    /**
     * PKCS7 去填充。
     */
    private static byte[] pkcs7Unpad(byte[] data) {
        if (data.length == 0 || data.length % BLOCK != 0) {
            throw new CryptoException("invalid SM4 ciphertext length: " + data.length);
        }
        int padLen = data[data.length - 1] & 0xff;
        if (padLen < 1 || padLen > BLOCK) {
            throw new CryptoException("invalid PKCS7 padding length: " + padLen);
        }
        for (int i = data.length - padLen; i < data.length; i++) {
            if ((data[i] & 0xff) != padLen) {
                throw new CryptoException("invalid PKCS7 padding bytes");
            }
        }
        byte[] out = new byte[data.length - padLen];
        System.arraycopy(data, 0, out, 0, out.length);
        return out;
    }

    /**
     * 规范化模式名。
     */
    private String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            throw new CryptoException("SM4 mode must not be blank");
        }
        String upper = mode.trim().toUpperCase();
        if (!GmAlgorithm.SM4_MODE_ECB.equals(upper) && !GmAlgorithm.SM4_MODE_CBC.equals(upper)) {
            throw new CryptoException("Unsupported SM4 mode: " + mode + ", supported: ECB|CBC");
        }
        return upper;
    }
}
