package com.levango7.dataenginebdp.encaps.crypto.jwt.transport;

import com.levango7.dataenginebdp.encaps.crypto.CryptoConfig;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import com.levango7.dataenginebdp.encaps.crypto.CryptoSpiFactory;
import com.levango7.dataenginebdp.encaps.crypto.gm.SM2Provider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 传输加密工厂。
 *
 * <p>按当前 {@link CryptoProfile} 路由到具体 {@link TransportCipher} 实现：</p>
 * <ul>
 *   <li>{@link CryptoProfile#XINCHANG} → {@link GmTransportCipher}（SM2+SM4 数字信封）</li>
 *   <li>{@link CryptoProfile#INTERNATIONAL} → 暂不支持，抛出 {@link CryptoException}（国际辖区传输加密
 *       通常由 TLS 保障，应用层不重复加密）</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * CryptoConfig config = new CryptoConfig();
 * config.setActiveProfile("xinchang");
 * TransportCipherFactory factory = new TransportCipherFactory(config);
 *
 * SM2Provider sm2 = new SM2Provider();
 * SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
 * TransportCipher cipher = factory.create(kp.getPublicKeyQ(), kp.getPrivateKeyD());
 *
 * String envelope = cipher.encryptString("sensitive-payload");
 * String plain = cipher.decryptString(envelope);
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>线程安全。</p>
 */
public class TransportCipherFactory {

    private static final Logger log = LoggerFactory.getLogger(TransportCipherFactory.class);

    private final CryptoConfig cryptoConfig;
    private final CryptoSpiFactory spiFactory;

    /**
     * 构造工厂。
     *
     * @param cryptoConfig 加密配置
     */
    public TransportCipherFactory(CryptoConfig cryptoConfig) {
        if (cryptoConfig == null) {
            throw new CryptoException("cryptoConfig must not be null");
        }
        this.cryptoConfig = cryptoConfig;
        this.spiFactory = new CryptoSpiFactory(cryptoConfig);
    }

    /**
     * 注入构造。
     *
     * @param cryptoConfig 加密配置
     * @param spiFactory   SPI 工厂
     */
    public TransportCipherFactory(CryptoConfig cryptoConfig, CryptoSpiFactory spiFactory) {
        if (cryptoConfig == null || spiFactory == null) {
            throw new CryptoException("cryptoConfig and spiFactory must not be null");
        }
        this.cryptoConfig = cryptoConfig;
        this.spiFactory = spiFactory;
    }

    /**
     * 按当前 Profile 创建传输加密器。
     *
     * @param publicKeyQ SM2 公钥；可为 null（仅解密场景）
     * @param privateKeyD SM2 私钥；可为 null（仅加密场景）
     * @return 当前 Profile 下的传输加密器
     * @throws CryptoException 当前 Profile 不支持传输加密
     */
    public TransportCipher create(byte[] publicKeyQ, byte[] privateKeyD) {
        CryptoProfile profile = spiFactory.getCurrentProfile();
        return create(profile, publicKeyQ, privateKeyD);
    }

    /**
     * 指定 Profile 创建传输加密器。
     *
     * @param profile     目标 Profile
     * @param publicKeyQ  SM2 公钥
     * @param privateKeyD SM2 私钥
     * @return 传输加密器
     */
    public TransportCipher create(CryptoProfile profile, byte[] publicKeyQ, byte[] privateKeyD) {
        if (profile == null) {
            throw new CryptoException("profile must not be null");
        }
        return switch (profile) {
            case XINCHANG -> {
                log.debug("Creating GmTransportCipher for profile XINCHANG");
                yield new GmTransportCipher(publicKeyQ, privateKeyD);
            }
            case INTERNATIONAL -> throw new CryptoException(
                    "Transport encryption not supported for INTERNATIONAL profile; "
                            + "use TLS for transport security in international jurisdiction");
        };
    }

    /**
     * 仅加密模式（仅持有公钥）。
     *
     * @param publicKeyQ SM2 公钥
     * @return 传输加密器
     */
    public TransportCipher createForEncrypt(byte[] publicKeyQ) {
        return create(publicKeyQ, null);
    }

    /**
     * 仅解密模式（仅持有私钥）。
     *
     * @param privateKeyD SM2 私钥
     * @return 传输加密器
     */
    public TransportCipher createForDecrypt(byte[] privateKeyD) {
        return create(null, privateKeyD);
    }

    /**
     * 生成新密钥对并创建双向传输加密器。
     *
     * <p>便捷方法：内部生成 SM2 密钥对，返回可同时加密与解密的 {@link GmTransportCipher}。
     * 适用于测试或单节点自洽场景；生产环境应使用固定密钥对。</p>
     *
     * @return 传输加密器（含新生成的密钥对）
     */
    public TransportCipher createWithNewKeyPair() {
        CryptoProfile profile = spiFactory.getCurrentProfile();
        if (profile != CryptoProfile.XINCHANG) {
            throw new CryptoException("createWithNewKeyPair only supported for XINCHANG profile");
        }
        SM2Provider sm2 = new SM2Provider();
        SM2Provider.Sm2KeyPair kp = sm2.generateKeyPair();
        return new GmTransportCipher(kp.getPublicKeyQ(), kp.getPrivateKeyD());
    }

    /**
     * 获取当前 Profile。
     *
     * @return 当前 Profile 枚举
     */
    public CryptoProfile getCurrentProfile() {
        return spiFactory.getCurrentProfile();
    }

    /**
     * 运行时切换 Profile。
     *
     * @param profile Profile 字符串
     */
    public void switchProfile(String profile) {
        spiFactory.setCurrentProfile(profile);
    }

    /**
     * 获取内部 SPI 工厂。
     *
     * @return CryptoSpiFactory 实例
     */
    public CryptoSpiFactory getSpiFactory() {
        return spiFactory;
    }

    /**
     * 获取加密配置。
     *
     * @return CryptoConfig 实例
     */
    public CryptoConfig getCryptoConfig() {
        return cryptoConfig;
    }
}