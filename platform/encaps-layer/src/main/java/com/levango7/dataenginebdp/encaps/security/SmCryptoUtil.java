package com.levango7.dataenginebdp.encaps.security;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.gm.GmAlgorithm;

import com.levango7.dataenginebdp.encaps.crypto.gm.SM2Provider;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM3Provider;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM4Provider;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * 国密 SM2/SM3/SM4 工具类（静态门面）。
 *
 * <p>对内已有 {@code SM2Provider}/{@code SM3Provider}/{@code SM4Provider} 的轻量 API
 * 做静态包装，提供业务侧常用的字符串/字节数组入参签名、哈希、对称加密等方法，
 * 替代 SHA-256（用 SM3）与 AES（用 SM4）场景。</p>
 *
 * <h3>方法概览</h3>
 * <ul>
 *   <li>SM2：{@link #sm2Sign}、{@link #sm2Verify}、{@link #sm2Encrypt}、{@link #sm2Decrypt}、
 *           {@link #sm2GenerateKeyPair}</li>
 *   <li>SM3：{@link #sm3Hash}、{@link #sm3HashHex}</li>
 *   <li>SM4：{@link #sm4Encrypt}、{@link #sm4Decrypt}（ECB/CBC）</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>所有方法均委托给线程安全的 Provider 实例，且仅使用局部变量，可安全并发调用。</p>
 *
 * <h3>国标对照</h3>
 * <ul>
 *   <li>SM2 — GB/T 32918 椭圆曲线公钥密码算法（默认曲线 sm2p256v1，SM3withSM2 签名）</li>
 *   <li>SM3 — GB/T 32905 密码杂凑算法（256 bit 摘要）</li>
 *   <li>SM4 — GB/T 32907 分组密码算法（128 bit 分组/密钥，PKCS7 填充）</li>
 * </ul>
 */
public final class SmCryptoUtil {

    /** 内部共享 SM2 Provider（无状态，线程安全） */
    private static final SM2Provider SM2 = new SM2Provider();
    /** 内部共享 SM3 Provider（无状态，线程安全） */
    private static final SM3Provider SM3 = new SM3Provider();
    /** 内部共享 SM4 Provider（无状态，线程安全） */
    private static final SM4Provider SM4 = new SM4Provider();

    private SmCryptoUtil() {
        throw new UnsupportedOperationException("Utility class, no instance");
    }

    // ===== SM2 =====

    /**
     * SM2 数字签名（SM3withSM2）。
     *
     * <p>对原始数据使用私钥 D 值生成 DER 编码签名，适用于接口请求签名、
     * 关键操作不可否认性证明等场景。</p>
     *
     * @param data         待签名数据（UTF-8 字节）
     * @param privateKeyD  私钥 D 值（32 字节）
     * @return 签名值（DER 编码字节）
     * @throws CryptoException 参数非法或签名失败
     */
    public static byte[] sm2Sign(byte[] data, byte[] privateKeyD) {
        return SM2.sign(data, privateKeyD);
    }

    /**
     * SM2 数字签名（字符串重载，UTF-8 编码）。
     *
     * @param data         待签名文本
     * @param privateKeyD  私钥 D 值（32 字节）
     * @return 签名值（DER 编码字节）
     */
    public static byte[] sm2Sign(String data, byte[] privateKeyD) {
        Objects.requireNonNull(data, "data");
        return SM2.sign(data.getBytes(StandardCharsets.UTF_8), privateKeyD);
    }

    /**
     * SM2 验签（SM3withSM2）。
     *
     * @param data        原始数据
     * @param signature   签名值（DER 编码）
     * @param publicKeyQ  公钥点编码（65 字节未压缩或 33 字节压缩）
     * @return 验签通过返回 true
     * @throws CryptoException 参数非法
     */
    public static boolean sm2Verify(byte[] data, byte[] signature, byte[] publicKeyQ) {
        return SM2.verify(data, signature, publicKeyQ);
    }

    /**
     * SM2 验签（字符串重载）。
     *
     * @param data        原始文本
     * @param signature   签名值
     * @param publicKeyQ  公钥点编码
     * @return 验签通过返回 true
     */
    public static boolean sm2Verify(String data, byte[] signature, byte[] publicKeyQ) {
        Objects.requireNonNull(data, "data");
        return SM2.verify(data.getBytes(StandardCharsets.UTF_8), signature, publicKeyQ);
    }

    /**
     * SM2 非对称加密（C1||C3||C2 格式，SM3 作为 KDF 摘要）。
     *
     * <p>适用于短明文（如对称密钥、ID）加密；大块数据请用 SM4 对称加密后
     * 再用 SM2 加密 SM4 密钥的混合方案。</p>
     *
     * @param plaintext  明文
     * @param publicKeyQ 公钥点编码
     * @return 密文（C1||C3||C2）
     */
    public static byte[] sm2Encrypt(byte[] plaintext, byte[] publicKeyQ) {
        return SM2.encrypt(plaintext, publicKeyQ);
    }

    /**
     * SM2 非对称解密。
     *
     * @param ciphertext 密文（C1||C3||C2）
     * @param privateKeyD 私钥 D 值
     * @return 明文
     */
    public static byte[] sm2Decrypt(byte[] ciphertext, byte[] privateKeyD) {
        return SM2.decrypt(ciphertext, privateKeyD);
    }

    /**
     * 生成 SM2 密钥对（裸 byte[] 形式）。
     *
     * @return 密钥对，privateKeyD 为 32 字节，publicKeyQ 为 65 字节未压缩点编码
     */
    public static SM2Provider.Sm2KeyPair sm2GenerateKeyPair() {
        return SM2.generateKeyPair();
    }

    // ===== SM3 =====

    /**
     * SM3 密码杂凑（替代 SHA-256 场景）。
     *
     * <p>输出 32 字节（256 bit）摘要，适用于口令哈希、数据完整性校验、
     * 区块链/审计链指纹等国密合规场景。</p>
     *
     * @param data 原始数据
     * @return 32 字节摘要
     * @throws CryptoException data 为 null
     */
    public static byte[] sm3Hash(byte[] data) {
        return SM3.digest(data);
    }

    /**
     * SM3 杂凑（字符串重载，UTF-8 编码）。
     *
     * @param data 原始文本
     * @return 32 字节摘要
     */
    public static byte[] sm3Hash(String data) {
        Objects.requireNonNull(data, "data");
        return SM3.digest(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SM3 杂凑并返回十六进制字符串。
     *
     * @param data 原始数据
     * @return 64 字符 hex 串
     */
    public static String sm3HashHex(byte[] data) {
        return toHex(SM3.digest(data));
    }

    /**
     * SM3 杂凑并返回十六进制字符串（字符串重载）。
     *
     * @param data 原始文本
     * @return 64 字符 hex 串
     */
    public static String sm3HashHex(String data) {
        Objects.requireNonNull(data, "data");
        return toHex(SM3.digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    // ===== SM4 =====

    /**
     * SM4 对称加密（ECB 模式，PKCS7 填充）。
     *
     * <p>替代 AES 场景的国密对称加密；ECB 适用于短数据/密钥加密，
     * 大块或流式数据建议使用 CBC 模式。</p>
     *
     * @param plaintext 明文
     * @param key       16 字节密钥
     * @return 密文
     * @throws CryptoException 参数非法
     */
    public static byte[] sm4Encrypt(byte[] plaintext, byte[] key) {
        return SM4.encrypt(plaintext, key, GmAlgorithm.SM4_MODE_ECB, null);
    }

    /**
     * SM4 对称解密（ECB 模式，PKCS7 填充）。
     *
     * @param ciphertext 密文
     * @param key        16 字节密钥
     * @return 明文
     */
    public static byte[] sm4Decrypt(byte[] ciphertext, byte[] key) {
        return SM4.decrypt(ciphertext, key, GmAlgorithm.SM4_MODE_ECB, null);
    }

    /**
     * SM4 对称加密（CBC 模式，PKCS7 填充）。
     *
     * @param plaintext 明文
     * @param key       16 字节密钥
     * @param iv        16 字节初始化向量
     * @return 密文
     */
    public static byte[] sm4Encrypt(byte[] plaintext, byte[] key, byte[] iv) {
        return SM4.encrypt(plaintext, key, GmAlgorithm.SM4_MODE_CBC, iv);
    }

    /**
     * SM4 对称解密（CBC 模式，PKCS7 填充）。
     *
     * @param ciphertext 密文
     * @param key        16 字节密钥
     * @param iv         16 字节初始化向量
     * @return 明文
     */
    public static byte[] sm4Decrypt(byte[] ciphertext, byte[] key, byte[] iv) {
        return SM4.decrypt(ciphertext, key, GmAlgorithm.SM4_MODE_CBC, iv);
    }

    /**
     * 生成随机 16 字节 SM4 密钥。
     *
     * @return 16 字节密钥
     */
    public static byte[] sm4GenerateKey() {
        return SM4.generateKey();
    }

    /**
     * 生成随机 16 字节 SM4 IV。
     *
     * @return 16 字节 IV
     */
    public static byte[] sm4GenerateIv() {
        return SM4.generateIv();
    }

    /**
     * 将 hex 字符串密钥转为 16 字节 SM4 密钥。
     *
     * @param hexKey 32 字符 hex 串
     * @return 16 字节密钥
     */
    public static byte[] sm4KeyFromHex(String hexKey) {
        Objects.requireNonNull(hexKey, "hexKey");
        byte[] key = fromHex(hexKey);
        if (key == null || key.length != GmAlgorithm.SM4_KEY_LEN) {
            throw new CryptoException("SM4 hex key must be 32 chars (16 bytes), got: "
                    + (key == null ? "null" : key.length));
        }
        return key;
    }

    // ===== 内部 hex 工具（避免依赖包级私有的 HexUtil） =====

    /** hex 字符表 */
    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    /**
     * 字节数组转小写 hex 字符串。
     *
     * @param bytes 字节数组
     * @return hex 串
     */
    private static String toHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            out[i * 2] = HEX_CHARS[v >>> 4];
            out[i * 2 + 1] = HEX_CHARS[v & 0x0f];
        }
        return new String(out);
    }

    /**
     * hex 字符串转字节数组。
     *
     * @param hex hex 串（长度必须为偶数）
     * @return 字节数组
     */
    private static byte[] fromHex(String hex) {
        if (hex == null) {
            return null;
        }
        String s = hex.toLowerCase();
        if ((s.length() & 1) != 0) {
            throw new IllegalArgumentException("hex length must be even: " + hex);
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("invalid hex char in: " + hex);
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}