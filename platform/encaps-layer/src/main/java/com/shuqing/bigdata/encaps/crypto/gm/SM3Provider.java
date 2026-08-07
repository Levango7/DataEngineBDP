package com.shuqing.bigdata.encaps.crypto.gm;

import com.shuqing.bigdata.encaps.crypto.CryptoException;
import org.bouncycastle.crypto.digests.SM3Digest;

/**
 * SM3 密码杂凑算法 Provider（GB/T 32905）。
 *
 * <p>基于 Bouncy Castle {@link SM3Digest} 实现，输出 256 位（32 字节）摘要。</p>
 *
 * <h3>国标对照</h3>
 * <ul>
 *   <li>标准：GB/T 32905-2016《信息安全技术 SM3 密码杂凑算法》</li>
 *   <li>输出长度：256 bit（32 byte）</li>
 *   <li>分组长度：512 bit（64 byte）</li>
 *   <li>特性：确定性、抗碰撞、抗原像</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>{@link #digest(byte[])} 每次调用内部新建 {@link SM3Digest}，无共享状态，线程安全。
 * 流式接口 {@link SM3StreamDigester} 由调用方持有实例，单实例非线程安全。</p>
 */
public class SM3Provider {

    /**
     * 计算 SM3 摘要。
     *
     * @param data 原始数据，不可为 null
     * @return 32 字节摘要
     * @throws CryptoException data 为 null
     */
    public byte[] digest(byte[] data) {
        if (data == null) {
            throw new CryptoException("data must not be null");
        }
        SM3Digest digest = new SM3Digest();
        digest.update(data, 0, data.length);
        byte[] out = new byte[digest.getDigestSize()];
        digest.doFinal(out, 0);
        return out;
    }

    /**
     * 创建流式 SM3 摘要器。
     *
     * <p>用于大文件/流式数据场景，支持多次 {@link SM3StreamDigester#update} 后
     * 一次性 {@link SM3StreamDigester#doFinal}。</p>
     *
     * @return 流式摘要器实例
     */
    public SM3StreamDigester newStreamDigester() {
        return new SM3StreamDigester();
    }

    /**
     * 计算 SM3 摘要并返回十六进制字符串。
     *
     * @param data 原始数据
     * @return 64 字符 hex 串
     */
    public String digestHex(byte[] data) {
        return HexUtil.toHex(digest(data));
    }

    /**
     * 流式 SM3 摘要器。
     *
     * <p>持有内部 {@link SM3Digest} 状态，支持分批 update。
     * <b>非线程安全</b>，单实例仅供单线程使用。</p>
     */
    public static final class SM3StreamDigester {

        private final SM3Digest digest;

        private SM3StreamDigester() {
            this.digest = new SM3Digest();
        }

        /**
         * 更新一批数据。
         *
         * @param data 数据块
         */
        public void update(byte[] data) {
            if (data == null) {
                throw new CryptoException("data must not be null");
            }
            update(data, 0, data.length);
        }

        /**
         * 更新一批数据（指定偏移与长度）。
         *
         * @param data   数据缓冲
         * @param offset 起始偏移
         * @param len    长度
         */
        public void update(byte[] data, int offset, int len) {
            if (data == null) {
                throw new CryptoException("data must not be null");
            }
            if (offset < 0 || len < 0 || offset + len > data.length) {
                throw new CryptoException("invalid offset/len: offset=" + offset + ", len=" + len + ", data.length=" + data.length);
            }
            digest.update(data, offset, len);
        }

        /**
         * 终结并输出摘要。
         *
         * @return 32 字节摘要
         */
        public byte[] doFinal() {
            byte[] out = new byte[digest.getDigestSize()];
            digest.doFinal(out, 0);
            return out;
        }

        /**
         * 终结并输出摘要（hex）。
         *
         * @return 64 字符 hex 串
         */
        public String doFinalHex() {
            return HexUtil.toHex(doFinal());
        }
    }
}