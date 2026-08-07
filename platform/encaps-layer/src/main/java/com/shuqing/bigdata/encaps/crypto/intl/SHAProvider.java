package com.shuqing.bigdata.encaps.crypto.intl;

import com.shuqing.bigdata.encaps.crypto.CryptoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * SHA 摘要 Provider（符合 FIPS 180-4）。
 *
 * <p>基于 JDK 内置 {@link MessageDigest} 实现 SHA-256 / SHA-384 / SHA-512 摘要算法，
 * 满足 FIPS 180-4 规范要求：</p>
 *
 * <h3>支持算法</h3>
 * <ul>
 *   <li>{@code SHA-256} — 输出 32 字节（256 位）摘要</li>
 *   <li>{@code SHA-384} — 输出 48 字节（384 位）摘要</li>
 *   <li>{@code SHA-512} — 输出 64 字节（512 位）摘要</li>
 * </ul>
 *
 * <h3>特性</h3>
 * <ul>
 *   <li>确定性：相同输入始终产生相同输出</li>
 *   <li>抗碰撞性：寻找两个不同输入产生相同摘要在计算上不可行</li>
 *   <li>雪崩效应：输入的微小变化导致输出显著不同</li>
 *   <li>支持流式更新：通过 {@link #newDigest(String)} 获取 {@link MessageDigest} 实例</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>{@link MessageDigest} 不是线程安全的，但本类每次调用都创建新实例，
 * 可安全并发使用。流式场景请使用 {@link #newDigest(String)} 获取独立实例。</p>
 */
public class SHAProvider {

    private static final Logger log = LoggerFactory.getLogger(SHAProvider.class);

    /** 默认摘要算法 */
    public static final String DEFAULT_ALGORITHM = "SHA-256";

    /**
     * 计算摘要。
     *
     * @param data      原始数据，不可为 null
     * @param algorithm 摘要算法，支持 {@code SHA-256} / {@code SHA-384} / {@code SHA-512}；
     *                  为 null 或空白时使用默认 {@link #DEFAULT_ALGORITHM}
     * @return 摘要字节
     * @throws CryptoException 算法不支持或计算失败
     */
    public byte[] digest(byte[] data, String algorithm) {
        if (data == null) {
            throw new CryptoException("data must not be null");
        }
        String normalized = normalizeAlgorithm(algorithm);
        validateAlgorithm(normalized);
        try {
            MessageDigest md = MessageDigest.getInstance(normalized);
            return md.digest(data);
        } catch (Exception e) {
            throw new CryptoException("SHA digest failed: " + normalized, e);
        }
    }

    /**
     * 使用默认 SHA-256 计算摘要。
     *
     * @param data 原始数据
     * @return 32 字节摘要
     * @throws CryptoException 计算失败
     */
    public byte[] digest(byte[] data) {
        return digest(data, DEFAULT_ALGORITHM);
    }

    /**
     * 计算摘要并返回十六进制字符串。
     *
     * @param data      原始数据
     * @param algorithm 摘要算法
     * @return 摘要 hex 字符串（小写）
     * @throws CryptoException 计算失败
     */
    public String digestHex(byte[] data, String algorithm) {
        byte[] digest = digest(data, algorithm);
        return toHex(digest);
    }

    /**
     * 使用默认 SHA-256 计算摘要并返回十六进制字符串。
     *
     * @param data 原始数据
     * @return 摘要 hex 字符串
     */
    public String digestHex(byte[] data) {
        return digestHex(data, DEFAULT_ALGORITHM);
    }

    /**
     * 创建新的 {@link MessageDigest} 实例（用于流式更新）。
     *
     * <p>典型用法：</p>
     * <pre>{@code
     * MessageDigest md = shaProvider.newDigest("SHA-256");
     * md.update(chunk1);
     * md.update(chunk2);
     * byte[] digest = md.digest();
     * }</pre>
     *
     * @param algorithm 摘要算法
     * @return 新的 MessageDigest 实例
     * @throws CryptoException 算法不支持
     */
    public MessageDigest newDigest(String algorithm) {
        String normalized = normalizeAlgorithm(algorithm);
        validateAlgorithm(normalized);
        try {
            return MessageDigest.getInstance(normalized);
        } catch (Exception e) {
            throw new CryptoException("Failed to create MessageDigest: " + normalized, e);
        }
    }

    /**
     * 流式摘要计算。
     *
     * @param chunks    数据块数组
     * @param algorithm 摘要算法
     * @return 摘要字节
     * @throws CryptoException 计算失败
     */
    public byte[] digest(byte[][] chunks, String algorithm) {
        if (chunks == null) {
            throw new CryptoException("chunks must not be null");
        }
        MessageDigest md = newDigest(algorithm);
        for (byte[] chunk : chunks) {
            if (chunk != null) {
                md.update(chunk);
            }
        }
        return md.digest();
    }

    /**
     * 验证算法是否支持。
     *
     * @param algorithm 算法名
     * @return 支持返回 true
     */
    public boolean isSupported(String algorithm) {
        if (algorithm == null || algorithm.isBlank()) {
            return false;
        }
        String normalized = normalizeAlgorithm(algorithm);
        return "SHA-256".equals(normalized)
                || "SHA-384".equals(normalized)
                || "SHA-512".equals(normalized);
    }

    /**
     * 获取算法的输出长度（字节数）。
     *
     * @param algorithm 算法名
     * @return 输出长度（字节）
     * @throws CryptoException 算法不支持
     */
    public int getDigestLength(String algorithm) {
        String normalized = normalizeAlgorithm(algorithm);
        validateAlgorithm(normalized);
        return switch (normalized) {
            case "SHA-256" -> 32;
            case "SHA-384" -> 48;
            case "SHA-512" -> 64;
            default -> throw new CryptoException("Unsupported algorithm: " + algorithm);
        };
    }

    // ---------------- 内部方法 ----------------

    /**
     * 规范化算法名（大小写不敏感，统一为大写带连字符形式）。
     */
    private String normalizeAlgorithm(String algorithm) {
        if (algorithm == null || algorithm.isBlank()) {
            return DEFAULT_ALGORITHM;
        }
        String trimmed = algorithm.trim();
        // 支持 SHA256 / sha256 / SHA-256 等变体
        String upper = trimmed.toUpperCase(Locale.ROOT).replace("_", "-");
        if (upper.equals("SHA256")) return "SHA-256";
        if (upper.equals("SHA384")) return "SHA-384";
        if (upper.equals("SHA512")) return "SHA-512";
        return upper;
    }

    /**
     * 校验算法合法性。
     */
    private void validateAlgorithm(String algorithm) {
        if (!isSupported(algorithm)) {
            throw new CryptoException("Unsupported SHA algorithm: " + algorithm
                    + ", supported: SHA-256 / SHA-384 / SHA-512");
        }
    }

    /**
     * 字节数组转十六进制字符串（小写）。
     *
     * @param bytes 字节数组
     * @return hex 字符串
     */
    public static String toHex(byte[] bytes) {
        if (bytes == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * 十六进制字符串转字节数组。
     *
     * @param hex hex 字符串
     * @return 字节数组
     * @throws CryptoException 格式错误
     */
    public static byte[] fromHex(String hex) {
        if (hex == null || hex.length() % 2 != 0) {
            throw new CryptoException("Invalid hex string: " + hex);
        }
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(hex.charAt(2 * i), 16);
            int low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0) {
                throw new CryptoException("Invalid hex character in: " + hex);
            }
            result[i] = (byte) ((high << 4) + low);
        }
        return result;
    }
}