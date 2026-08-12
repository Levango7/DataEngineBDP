package com.levango7.dataenginebdp.encaps.crypto.gm;

import com.levango7.dataenginebdp.encaps.crypto.AlgorithmType;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Key;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Arrays;

/**
 * 国密 Provider 聚合类（信创 Profile）。
 *
 * <p>实现 {@link CryptoProvider} SPI 接口，内部委托给 {@link SM2Provider}/{@link SM3Provider}/{@link SM4Provider}，
 * 由调用方通过 {@link #getSm2()}/{@link #getSm3()}/{@link #getSm4()} 获取具体算法 Provider。</p>
 *
 * <h3>SPI 路由</h3>
 * <ul>
 *   <li>{@link #sign}/{@link #verifySign} → {@link SM2Provider}（GB/T 32918）</li>
 *   <li>{@link #digest} → {@link SM3Provider}（GB/T 32905）</li>
 *   <li>{@link #encrypt}/{@link #decrypt} → 按 Key 算法名路由：
 *       SM2 公钥/私钥走 {@link SM2Provider}，SM4 对称密钥走 {@link SM4Provider}</li>
 * </ul>
 *
 * <h3>密钥约定</h3>
 * <p>为兼容 SPI 接口的 {@link Key} 入参，本类约定：</p>
 * <ul>
 *   <li>SM2 私钥/公钥使用 {@link Sm2PrivateKey}/{@link Sm2PublicKey} 包装 byte[]</li>
 *   <li>SM4 对称密钥使用 {@link Sm4SecretKey} 包装 byte[]</li>
 * </ul>
 *
 * <h3>Profile</h3>
 * <p>{@link #getSupportedProfile()} 返回 {@link CryptoProfile#XINCHANG}，
 * 当 Spring Profile=xinchang 时由 {@link com.levango7.dataenginebdp.encaps.crypto.CryptoSpiFactory} 选为默认 Provider。</p>
 */
public class GmProvider implements CryptoProvider {

    private static final Logger log = LoggerFactory.getLogger(GmProvider.class);

    /** Provider 名称（与 CryptoProfile.XINCHANG.defaultProviderName 一致） */
    public static final String PROVIDER_NAME = "GM-Provider";

    private final SM2Provider sm2 = new SM2Provider();
    private final SM3Provider sm3 = new SM3Provider();
    private final SM4Provider sm4 = new SM4Provider();

    /**
     * 获取 SM2 Provider。
     *
     * @return SM2Provider 实例
     */
    public SM2Provider getSm2() {
        return sm2;
    }

    /**
     * 获取 SM3 Provider。
     *
     * @return SM3Provider 实例
     */
    public SM3Provider getSm3() {
        return sm3;
    }

    /**
     * 获取 SM4 Provider。
     *
     * @return SM4Provider 实例
     */
    public SM4Provider getSm4() {
        return sm4;
    }

    @Override
    public byte[] sign(byte[] data, PrivateKey key) {
        if (data == null || key == null) {
            throw new CryptoException("data and key must not be null");
        }
        byte[] d = extractSm2D(key);
        return sm2.sign(data, d);
    }

    @Override
    public boolean verifySign(byte[] data, byte[] sign, PublicKey key) {
        if (data == null || sign == null || key == null) {
            throw new CryptoException("data, sign and key must not be null");
        }
        byte[] q = extractSm2Q(key);
        return sm2.verify(data, sign, q);
    }

    @Override
    public byte[] encrypt(byte[] plaintext, Key key) {
        if (plaintext == null || key == null) {
            throw new CryptoException("plaintext and key must not be null");
        }
        if (isSm4Key(key)) {
            Sm4SecretKey sm4Key = (Sm4SecretKey) key;
            return sm4.encrypt(plaintext, sm4Key.getKeyBytes(), sm4Key.getMode(), sm4Key.getIv());
        }
        // 默认走 SM2 非对称加密
        byte[] q = extractSm2Q(key);
        return sm2.encrypt(plaintext, q);
    }

    @Override
    public byte[] decrypt(byte[] ciphertext, Key key) {
        if (ciphertext == null || key == null) {
            throw new CryptoException("ciphertext and key must not be null");
        }
        if (isSm4Key(key)) {
            Sm4SecretKey sm4Key = (Sm4SecretKey) key;
            return sm4.decrypt(ciphertext, sm4Key.getKeyBytes(), sm4Key.getMode(), sm4Key.getIv());
        }
        // 默认走 SM2 非对称解密
        byte[] d = extractSm2D(key);
        return sm2.decrypt(ciphertext, d);
    }

    @Override
    public byte[] digest(byte[] data) {
        return sm3.digest(data);
    }

    @Override
    public KeyPair getKeyPair() {
        return sm2.generateJcaKeyPair();
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public AlgorithmType getAlgorithmType() {
        // 国密 Provider 主算法类型为签名（SM2withSM3）
        return AlgorithmType.SIGN;
    }

    @Override
    public CryptoProfile getSupportedProfile() {
        return CryptoProfile.XINCHANG;
    }

    // ===== 密钥识别辅助 =====

    /**
     * 判断 Key 是否为 SM4 对称密钥。
     */
    private static boolean isSm4Key(Key key) {
        return key instanceof Sm4SecretKey;
    }

    /**
     * 从 PrivateKey 提取 SM2 D 值。
     */
    private byte[] extractSm2D(Key key) {
        if (key instanceof Sm2PrivateKey) {
            return ((Sm2PrivateKey) key).getD();
        }
        if (key instanceof PrivateKey) {
            return sm2.extractD((PrivateKey) key);
        }
        throw new CryptoException("unsupported key type for SM2 sign/decrypt: " + key.getClass());
    }

    /**
     * 从 PublicKey 提取 SM2 Q 点编码。
     */
    private byte[] extractSm2Q(Key key) {
        if (key instanceof Sm2PublicKey) {
            return ((Sm2PublicKey) key).getQ();
        }
        if (key instanceof PublicKey) {
            return sm2.extractQ((PublicKey) key);
        }
        throw new CryptoException("unsupported key type for SM2 verify/encrypt: " + key.getClass());
    }

    // ===== 密钥包装类 =====

    /**
     * SM2 私钥包装（byte[] D 值）。
     */
    public static final class Sm2PrivateKey implements PrivateKey {
        private static final long serialVersionUID = 1L;
        private final byte[] d;

        public Sm2PrivateKey(byte[] d) {
            if (d == null) {
                throw new CryptoException("d must not be null");
            }
            this.d = d.clone();
        }

        public byte[] getD() {
            return d.clone();
        }

        @Override
        public String getAlgorithm() {
            return GmAlgorithm.SM2;
        }

        @Override
        public String getFormat() {
            return "RAW";
        }

        @Override
        public byte[] getEncoded() {
            return d.clone();
        }
    }

    /**
     * SM2 公钥包装（byte[] Q 点编码）。
     */
    public static final class Sm2PublicKey implements PublicKey {
        private static final long serialVersionUID = 1L;
        private final byte[] q;

        public Sm2PublicKey(byte[] q) {
            if (q == null) {
                throw new CryptoException("q must not be null");
            }
            this.q = q.clone();
        }

        public byte[] getQ() {
            return q.clone();
        }

        @Override
        public String getAlgorithm() {
            return GmAlgorithm.SM2;
        }

        @Override
        public String getFormat() {
            return "RAW";
        }

        @Override
        public byte[] getEncoded() {
            return q.clone();
        }
    }

    /**
     * SM4 对称密钥包装（byte[] 密钥 + 模式 + IV）。
     */
    public static final class Sm4SecretKey implements Key {
        private static final long serialVersionUID = 1L;
        private final byte[] keyBytes;
        private final String mode;
        private final byte[] iv;

        /**
         * 构造 SM4 密钥（ECB 模式）。
         *
         * @param keyBytes 16 字节密钥
         */
        public Sm4SecretKey(byte[] keyBytes) {

            this(keyBytes, GmAlgorithm.SM4_MODE_ECB, null);
        }

        /**
         * 构造 SM4 密钥。
         *
         * @param keyBytes 16 字节密钥
         * @param mode     ECB / CBC
         * @param iv       CBC 模式 IV（16 字节）；ECB 传 null
         */
        public Sm4SecretKey(byte[] keyBytes, String mode, byte[] iv) {
            if (keyBytes == null || keyBytes.length != GmAlgorithm.SM4_KEY_LEN) {
                throw new CryptoException("SM4 key must be 16 bytes");
            }
            this.keyBytes = keyBytes.clone();
            this.mode = mode == null ? GmAlgorithm.SM4_MODE_ECB : mode.toUpperCase();
            this.iv = iv == null ? null : iv.clone();
        }

        public byte[] getKeyBytes() {
            return keyBytes.clone();
        }

        public String getMode() {
            return mode;
        }

        public byte[] getIv() {
            return iv == null ? null : iv.clone();
        }

        @Override
        public String getAlgorithm() {
            return GmAlgorithm.SM4;
        }

        @Override
        public String getFormat() {
            return "RAW";
        }

        @Override
        public byte[] getEncoded() {
            return keyBytes.clone();
        }
    }
}