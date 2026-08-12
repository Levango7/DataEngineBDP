package com.levango7.dataenginebdp.encaps.crypto.jwt.storage;

import com.levango7.dataenginebdp.encaps.crypto.CryptoConfig;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import com.levango7.dataenginebdp.encaps.crypto.CryptoSpiFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 存储加密工厂。
 *
 * <p>按当前 {@link CryptoProfile} 路由到具体 {@link StorageCipher} 实现：</p>
 * <ul>
 *   <li>{@link CryptoProfile#XINCHANG} → {@link GmStorageCipher}（SM4-CBC）</li>
 *   <li>{@link CryptoProfile#INTERNATIONAL} → {@link IntlStorageCipher}（AES-GCM）</li>
 * </ul>
 *
 * <h3>密钥来源</h3>
 * <p>密钥由调用方显式注入（生产环境通过 KMS 或环境变量），工厂仅负责按 Profile 选择算法实现。
 * 支持为不同 Profile 注入不同密钥，便于跨辖区部署。</p>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * CryptoConfig config = new CryptoConfig();
 * config.setActiveProfile("xinchang");
 * StorageCipherFactory factory = new StorageCipherFactory(config);
 * StorageCipher cipher = factory.create(gmKey, intlKey);
 * String encrypted = cipher.encryptString("sensitive-data");
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>线程安全（内部 {@link CryptoSpiFactory} 线程安全）。</p>
 */
public class StorageCipherFactory {

    private static final Logger log = LoggerFactory.getLogger(StorageCipherFactory.class);

    private final CryptoConfig cryptoConfig;
    private final CryptoSpiFactory spiFactory;

    /**
     * 构造工厂。
     *
     * @param cryptoConfig 加密配置
     */
    public StorageCipherFactory(CryptoConfig cryptoConfig) {
        if (cryptoConfig == null) {
            throw new CryptoException("cryptoConfig must not be null");
        }
        this.cryptoConfig = cryptoConfig;
        this.spiFactory = new CryptoSpiFactory(cryptoConfig);
    }

    /**
     * 注入构造（便于测试与自定义）。
     *
     * @param cryptoConfig 加密配置
     * @param spiFactory   SPI 工厂
     */
    public StorageCipherFactory(CryptoConfig cryptoConfig, CryptoSpiFactory spiFactory) {
        if (cryptoConfig == null || spiFactory == null) {
            throw new CryptoException("cryptoConfig and spiFactory must not be null");
        }
        this.cryptoConfig = cryptoConfig;
        this.spiFactory = spiFactory;
    }

    /**
     * 按当前 Profile 创建存储加密器。
     *
     * <p>使用对应 Profile 的密钥创建。两个密钥至少需提供当前 Profile 对应的密钥，
     * 另一个可为 null（仅在切换 Profile 时使用）。</p>
     *
     * @param gmKey   SM4 密钥（16 字节）；信创 Profile 必须非 null
     * @param intlKey AES 密钥（16 或 32 字节）；国际 Profile 必须非 null
     * @return 当前 Profile 下的存储加密器
     * @throws CryptoException 密钥缺失或非法
     */
    public StorageCipher create(byte[] gmKey, byte[] intlKey) {
        CryptoProfile profile = spiFactory.getCurrentProfile();
        return create(profile, gmKey, intlKey);
    }

    /**
     * 指定 Profile 创建存储加密器。
     *
     * @param profile 目标 Profile
     * @param gmKey   SM4 密钥
     * @param intlKey AES 密钥
     * @return 指定 Profile 下的存储加密器
     */
    public StorageCipher create(CryptoProfile profile, byte[] gmKey, byte[] intlKey) {
        if (profile == null) {
            throw new CryptoException("profile must not be null");
        }
        return switch (profile) {
            case XINCHANG -> {
                if (gmKey == null) {
                    throw new CryptoException("GM key must not be null for XINCHANG profile");
                }
                log.debug("Creating GmStorageCipher for profile XINCHANG");
                yield new GmStorageCipher(gmKey);
            }
            case INTERNATIONAL -> {
                if (intlKey == null) {
                    throw new CryptoException("Intl key must not be null for INTERNATIONAL profile");
                }
                log.debug("Creating IntlStorageCipher for profile INTERNATIONAL");
                yield new IntlStorageCipher(intlKey);
            }
        };
    }

    /**
     * 按当前 Profile 创建存储加密器（单密钥）。
     *
     * <p>调用方仅需提供当前 Profile 对应的密钥，无需关心另一 Profile。</p>
     *
     * @param key 当前 Profile 对应的密钥
     * @return 当前 Profile 下的存储加密器
     */
    public StorageCipher create(byte[] key) {
        if (key == null) {
            throw new CryptoException("key must not be null");
        }
        CryptoProfile profile = spiFactory.getCurrentProfile();
        return switch (profile) {
            case XINCHANG -> new GmStorageCipher(key);
            case INTERNATIONAL -> new IntlStorageCipher(key);
        };
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