package com.shuqing.bigdata.encaps.crypto.jwt.transport;

import com.shuqing.bigdata.encaps.crypto.CryptoException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * 数字信封（Digital Envelope）数据结构。
 *
 * <p>采用 SM2+SM4 混合加密（也称数字信封技术）：</p>
 * <ol>
 *   <li>发送方生成一次性随机 SM4 会话密钥</li>
 *   <li>用 SM4 会话密钥加密大量明文（对称加密高效）</li>
 *   <li>用接收方 SM2 公钥加密 SM4 会话密钥（非对称加密解决密钥分发）</li>
 *   <li>将加密的会话密钥 + 密文组合为数字信封发送</li>
 * </ol>
 *
 * <p>接收方先用 SM2 私钥解密出 SM4 会话密钥，再用会话密钥解密密文。</p>
 *
 * <h3>信封二进制格式</h3>
 * <pre>{@code
 * magic(4B) | version(1B) | algId(1B) | ivLen(2B) | iv | encKeyLen(4B) | encKey | cipherLen(4B) | cipher
 * }</pre>
 * <ul>
 *   <li>magic: "SQDE"（ShuQing Digital Envelope）4 字节</li>
 *   <li>version: 1（1 字节）</li>
 *   <li>algId: 0x01 = SM2+SM4-CBC（1 字节）</li>
 *   <li>ivLen: IV 长度（2 字节大端）</li>
 *   <li>iv: SM4 CBC IV</li>
 *   <li>encKeyLen: 加密的会话密钥长度（4 字节大端）</li>
 *   <li>encKey: SM2 加密后的 SM4 会话密钥</li>
 *   <li>cipherLen: 密文长度（4 字节大端）</li>
 *   <li>cipher: SM4 加密后的密文</li>
 * </ul>
 *
 * <p>对外提供 Base64 字符串接口，便于在 HTTP/JSON 中传输。</p>
 */
public final class DigitalEnvelope {

    /** 魔数 "SQDE" */
    public static final byte[] MAGIC = "SQDE".getBytes(StandardCharsets.US_ASCII);

    /** 当前版本 */
    public static final byte CURRENT_VERSION = 1;

    /** 算法 id：SM2+SM4-CBC */
    public static final byte ALG_SM2_SM4_CBC = 0x01;

    private final byte[] iv;
    private final byte[] encryptedSessionKey;
    private final byte[] ciphertext;

    /**
     * 构造数字信封。
     *
     * @param iv                 SM4 CBC IV
     * @param encryptedSessionKey SM2 加密后的 SM4 会话密钥
     * @param ciphertext         SM4 加密后的密文
     */
    public DigitalEnvelope(byte[] iv, byte[] encryptedSessionKey, byte[] ciphertext) {
        if (iv == null || encryptedSessionKey == null || ciphertext == null) {
            throw new CryptoException("iv, encryptedSessionKey and ciphertext must not be null");
        }
        this.iv = iv.clone();
        this.encryptedSessionKey = encryptedSessionKey.clone();
        this.ciphertext = ciphertext.clone();
    }

    /**
     * 获取 IV。
     *
     * @return IV 副本
     */
    public byte[] getIv() {
        return iv.clone();
    }

    /**
     * 获取加密的会话密钥。
     *
     * @return 加密会话密钥副本
     */
    public byte[] getEncryptedSessionKey() {
        return encryptedSessionKey.clone();
    }

    /**
     * 获取密文。
     *
     * @return 密文副本
     */
    public byte[] getCiphertext() {
        return ciphertext.clone();
    }

    /**
     * 序列化为二进制格式。
     *
     * @return 二进制字节数组
     */
    public byte[] toBytes() {
        int totalLen = MAGIC.length + 1 + 1
                + 2 + iv.length
                + 4 + encryptedSessionKey.length
                + 4 + ciphertext.length;
        byte[] out = new byte[totalLen];
        int pos = 0;
        // magic
        System.arraycopy(MAGIC, 0, out, pos, MAGIC.length);
        pos += MAGIC.length;
        // version
        out[pos++] = CURRENT_VERSION;
        // algId
        out[pos++] = ALG_SM2_SM4_CBC;
        // ivLen (2 bytes big-endian)
        out[pos++] = (byte) ((iv.length >>> 8) & 0xff);
        out[pos++] = (byte) (iv.length & 0xff);
        // iv
        System.arraycopy(iv, 0, out, pos, iv.length);
        pos += iv.length;
        // encKeyLen (4 bytes big-endian)
        int encKeyLen = encryptedSessionKey.length;
        out[pos++] = (byte) ((encKeyLen >>> 24) & 0xff);
        out[pos++] = (byte) ((encKeyLen >>> 16) & 0xff);
        out[pos++] = (byte) ((encKeyLen >>> 8) & 0xff);
        out[pos++] = (byte) (encKeyLen & 0xff);
        // encKey
        System.arraycopy(encryptedSessionKey, 0, out, pos, encKeyLen);
        pos += encKeyLen;
        // cipherLen (4 bytes big-endian)
        int cipherLen = ciphertext.length;
        out[pos++] = (byte) ((cipherLen >>> 24) & 0xff);
        out[pos++] = (byte) ((cipherLen >>> 16) & 0xff);
        out[pos++] = (byte) ((cipherLen >>> 8) & 0xff);
        out[pos++] = (byte) (cipherLen & 0xff);
        // cipher
        System.arraycopy(ciphertext, 0, out, pos, cipherLen);
        return out;
    }

    /**
     * 序列化为 Base64 字符串。
     *
     * @return Base64 字符串
     */
    public String toBase64() {
        return Base64.getEncoder().encodeToString(toBytes());
    }

    /**
     * 从二进制格式反序列化。
     *
     * @param bytes 二进制字节数组
     * @return 数字信封实例
     * @throws CryptoException 格式非法
     */
    public static DigitalEnvelope fromBytes(byte[] bytes) {
        if (bytes == null) {
            throw new CryptoException("bytes must not be null");
        }
        int minLen = MAGIC.length + 1 + 1 + 2 + 4 + 4;
        if (bytes.length < minLen) {
            throw new CryptoException("envelope too short: " + bytes.length);
        }
        int pos = 0;
        // magic
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[pos + i] != MAGIC[i]) {
                throw new CryptoException("invalid magic, expected SQDE");
            }
        }
        pos += MAGIC.length;
        // version
        byte version = bytes[pos++];
        if (version != CURRENT_VERSION) {
            throw new CryptoException("unsupported version: " + version);
        }
        // algId
        byte algId = bytes[pos++];
        if (algId != ALG_SM2_SM4_CBC) {
            throw new CryptoException("unsupported algorithm id: " + algId);
        }
        // ivLen
        int ivLen = ((bytes[pos++] & 0xff) << 8) | (bytes[pos++] & 0xff);
        if (ivLen < 0 || pos + ivLen > bytes.length) {
            throw new CryptoException("invalid iv length: " + ivLen);
        }
        byte[] iv = new byte[ivLen];
        System.arraycopy(bytes, pos, iv, 0, ivLen);
        pos += ivLen;
        // encKeyLen
        if (pos + 4 > bytes.length) {
            throw new CryptoException("envelope truncated at encKeyLen");
        }
        int encKeyLen = ((bytes[pos++] & 0xff) << 24)
                | ((bytes[pos++] & 0xff) << 16)
                | ((bytes[pos++] & 0xff) << 8)
                | (bytes[pos++] & 0xff);
        if (encKeyLen < 0 || pos + encKeyLen > bytes.length) {
            throw new CryptoException("invalid encKey length: " + encKeyLen);
        }
        byte[] encKey = new byte[encKeyLen];
        System.arraycopy(bytes, pos, encKey, 0, encKeyLen);
        pos += encKeyLen;
        // cipherLen
        if (pos + 4 > bytes.length) {
            throw new CryptoException("envelope truncated at cipherLen");
        }
        int cipherLen = ((bytes[pos++] & 0xff) << 24)
                | ((bytes[pos++] & 0xff) << 16)
                | ((bytes[pos++] & 0xff) << 8)
                | (bytes[pos++] & 0xff);
        if (cipherLen < 0 || pos + cipherLen > bytes.length) {
            throw new CryptoException("invalid cipher length: " + cipherLen);
        }
        byte[] cipher = new byte[cipherLen];
        System.arraycopy(bytes, pos, cipher, 0, cipherLen);
        return new DigitalEnvelope(iv, encKey, cipher);
    }

    /**
     * 从 Base64 字符串反序列化。
     *
     * @param base64 Base64 字符串
     * @return 数字信封实例
     * @throws CryptoException 格式非法
     */
    public static DigitalEnvelope fromBase64(String base64) {
        if (base64 == null) {
            throw new CryptoException("base64 must not be null");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64);
            return fromBytes(bytes);
        } catch (IllegalArgumentException e) {
            throw new CryptoException("invalid Base64 envelope", e);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DigitalEnvelope that)) {
            return false;
        }
        return Arrays.equals(iv, that.iv)
                && Arrays.equals(encryptedSessionKey, that.encryptedSessionKey)
                && Arrays.equals(ciphertext, that.ciphertext);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(iv);
        result = 31 * result + Arrays.hashCode(encryptedSessionKey);
        result = 31 * result + Arrays.hashCode(ciphertext);
        return result;
    }

    @Override
    public String toString() {
        return "DigitalEnvelope{ivLen=" + iv.length
                + ", encKeyLen=" + encryptedSessionKey.length
                + ", cipherLen=" + ciphertext.length + '}';
    }
}