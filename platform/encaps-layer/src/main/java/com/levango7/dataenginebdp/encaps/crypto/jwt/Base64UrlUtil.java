package com.levango7.dataenginebdp.encaps.crypto.jwt;

import java.nio.charset.StandardCharsets;

/**
 * Base64URL 编解码工具（RFC 4648 §5，无填充）。
 *
 * <p>JWT 三段式（header.payload.signature）均使用 Base64URL 编码，
 * 相比标准 Base64 将 {@code +} 替换为 {@code -}、{@code /} 替换为 {@code _}，并去除末尾 {@code =} 填充。</p>
 *
 * <p>本类实现纯 JDK 版本，避免引入额外依赖；线程安全（无共享状态）。</p>
 */
public final class Base64UrlUtil {

    private Base64UrlUtil() {
        throw new UnsupportedOperationException("Utility class, no instance");
    }

    /** Base64URL 编码字符表 */
    private static final char[] ENCODE_TABLE = JwtAlgorithm.BASE64URL_CHARSET.toCharArray();

    /** 解码反向表（-1 表示非法字符） */
    private static final int[] DECODE_TABLE = buildDecodeTable();

    /**
     * 构建解码反向表。
     */
    private static int[] buildDecodeTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            table[i] = -1;
        }
        for (int i = 0; i < ENCODE_TABLE.length; i++) {
            table[ENCODE_TABLE[i]] = i;
        }
        return table;
    }

    /**
     * 将字节数组编码为 Base64URL 字符串（无填充）。
     *
     * @param bytes 字节数组；null 返回 null
     * @return Base64URL 字符串
     */
    public static String encode(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return encode(bytes, 0, bytes.length);
    }

    /**
     * 将字节数组指定区间编码为 Base64URL 字符串。
     *
     * @param bytes  字节数组
     * @param offset 起始偏移
     * @param length 长度
     * @return Base64URL 字符串
     */
    public static String encode(byte[] bytes, int offset, int length) {
        if (bytes == null) {
            return null;
        }
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IllegalArgumentException("invalid offset/length");
        }
        // 编码后字符数 = ceil(length * 4 / 3)，无填充
        int outLen = (length * 4 + 2) / 3;
        char[] out = new char[outLen];
        int op = 0;
        int ip = offset;
        int ipEnd = offset + length;
        while (ip < ipEnd) {
            int i0 = bytes[ip++] & 0xff;
            out[op++] = ENCODE_TABLE[i0 >>> 2];
            if (ip < ipEnd) {
                int i1 = bytes[ip++] & 0xff;
                out[op++] = ENCODE_TABLE[((i0 & 0x03) << 4) | (i1 >>> 4)];
                if (ip < ipEnd) {
                    int i2 = bytes[ip++] & 0xff;
                    out[op++] = ENCODE_TABLE[((i1 & 0x0f) << 2) | (i2 >>> 6)];
                    out[op++] = ENCODE_TABLE[i2 & 0x3f];
                } else {
                    out[op++] = ENCODE_TABLE[(i1 & 0x0f) << 2];
                }
            } else {
                out[op++] = ENCODE_TABLE[(i0 & 0x03) << 4];
            }
        }
        return new String(out, 0, op);
    }

    /**
     * 将字符串编码为 Base64URL（UTF-8 字节）。
     *
     * @param text 字符串
     * @return Base64URL 字符串
     */
    public static String encodeString(String text) {
        if (text == null) {
            return null;
        }
        return encode(text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 将 Base64URL 字符串解码为字节数组。
     *
     * <p>容忍末尾填充 {@code =}（虽然规范不要求），但不容忍标准 Base64 的 {@code +}/{@code /} 字符。</p>
     *
     * @param base64Url Base64URL 字符串
     * @return 字节数组；null 返回 null
     * @throws IllegalArgumentException 含非法字符或长度不合法
     */
    public static byte[] decode(String base64Url) {
        if (base64Url == null) {
            return null;
        }
        // 去除可能的填充
        int padCount = 0;
        int end = base64Url.length();
        while (end > 0 && base64Url.charAt(end - 1) == '=') {
            end--;
            padCount++;
        }
        if (padCount > 2) {
            throw new IllegalArgumentException("invalid Base64URL padding: " + base64Url);
        }
        int inLen = end;
        if (inLen == 0) {
            return new byte[0];
        }
        // 计算输出字节数
        int outLen = (inLen * 3) / 4;
        byte[] out = new byte[outLen];
        int op = 0;
        int ip = 0;
        int remaining = inLen;
        while (remaining >= 4) {
            int c0 = decodeChar(base64Url.charAt(ip++));
            int c1 = decodeChar(base64Url.charAt(ip++));
            int c2 = decodeChar(base64Url.charAt(ip++));
            int c3 = decodeChar(base64Url.charAt(ip++));
            out[op++] = (byte) ((c0 << 2) | (c1 >>> 4));
            out[op++] = (byte) (((c1 & 0x0f) << 4) | (c2 >>> 2));
            out[op++] = (byte) (((c2 & 0x03) << 6) | c3);
            remaining -= 4;
        }
        // 处理尾部 2 或 3 字符
        if (remaining == 2) {
            int c0 = decodeChar(base64Url.charAt(ip++));
            int c1 = decodeChar(base64Url.charAt(ip++));
            out[op++] = (byte) ((c0 << 2) | (c1 >>> 4));
        } else if (remaining == 3) {
            int c0 = decodeChar(base64Url.charAt(ip++));
            int c1 = decodeChar(base64Url.charAt(ip++));
            int c2 = decodeChar(base64Url.charAt(ip++));
            out[op++] = (byte) ((c0 << 2) | (c1 >>> 4));
            out[op++] = (byte) (((c1 & 0x0f) << 4) | (c2 >>> 2));
        } else if (remaining == 1) {
            throw new IllegalArgumentException("invalid Base64URL length: " + inLen);
        }
        return out;
    }

    /**
     * 将 Base64URL 字符串解码为 UTF-8 字符串。
     *
     * @param base64Url Base64URL 字符串
     * @return UTF-8 字符串
     */
    public static String decodeString(String base64Url) {
        if (base64Url == null) {
            return null;
        }
        return new String(decode(base64Url), StandardCharsets.UTF_8);
    }

    /**
     * 解码单个字符到 6 bit 值。
     */
    private static int decodeChar(char c) {
        if (c >= 256) {
            throw new IllegalArgumentException("invalid Base64URL char: " + c);
        }
        int v = DECODE_TABLE[c];
        if (v < 0) {
            throw new IllegalArgumentException("invalid Base64URL char: " + c);
        }
        return v;
    }
}