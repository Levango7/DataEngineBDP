package com.levango7.dataenginebdp.encaps.crypto.gm;

/**
 * 十六进制编解码工具。
 *
 * <p>国密算法测试向量通常以 hex 字符串表示，本类提供 byte[] ↔ hex 互转。</p>
 */
public final class HexUtil {

    private static final char[] HEX_CHARS = "0123456789abcdef".toCharArray();

    private HexUtil() {
    }

    /**
     * 字节数组转小写 hex 字符串。
     *
     * @param bytes 字节数组
     * @return hex 串；null 返回 null
     */
    static String toHex(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        return toHex(bytes, 0, bytes.length);
    }

    /**
     * 字节数组指定区间转小写 hex 字符串。
     *
     * @param bytes  字节数组
     * @param offset 起始偏移
     * @param len    长度
     * @return hex 串
     */
    static String toHex(byte[] bytes, int offset, int len) {
        if (bytes == null) {
            return null;
        }
        char[] out = new char[len * 2];
        for (int i = 0; i < len; i++) {
            int v = bytes[offset + i] & 0xff;
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
     * @throws IllegalArgumentException hex 长度非法或含非 hex 字符
     */
    static byte[] fromHex(String hex) {
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