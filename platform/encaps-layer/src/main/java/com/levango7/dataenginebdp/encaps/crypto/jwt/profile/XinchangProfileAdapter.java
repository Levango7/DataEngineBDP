package com.levango7.dataenginebdp.encaps.crypto.jwt.profile;

import com.levango7.dataenginebdp.encaps.crypto.CryptoConfig;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import com.levango7.dataenginebdp.encaps.crypto.CryptoSpiFactory;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.GmStorageCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.IntlStorageCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.StorageCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.StorageCipherFactory;
import com.levango7.dataenginebdp.encaps.crypto.jwt.transport.GmTransportCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.transport.TransportCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.transport.TransportCipherFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 信创 Profile 适配器。
 *
 * <p>聚合 JWT / 存储加密 / 传输加密三类能力，按当前 {@link CryptoProfile} 自动适配：
 * 在信创辖区（{@code xinchang}）下使用国密算法栈，在国际辖区（{@code international}）下使用国际算法栈。</p>
 *
 * <h3>适配能力</h3>
 * <table>
 *   <caption>表：信创与国际辖区能力对照表</caption>
 *   <tr><th>能力</th><th>信创（xinchang）</th><th>国际（international）</th></tr>
 *   <tr><td>JWT 签名</td><td>SM3withSM2</td><td>RS256（由 Spring Security 提供）</td></tr>
 *   <tr><td>存储加密</td><td>SM4-CBC</td><td>AES-GCM</td></tr>
 *   <tr><td>传输加密</td><td>SM2+SM4 数字信封</td><td>TLS（应用层不重复加密）</td></tr>
 * </table>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * CryptoConfig config = new CryptoConfig();
 * config.setActiveProfile("xinchang");
 * XinchangProfileAdapter adapter = new XinchangProfileAdapter(config);
 *
 * // 自检当前 Profile 能力
 * Map<String, String> capabilities = adapter.getCapabilities();
 * boolean supportsTransport = adapter.isTransportEncryptionSupported();
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>线程安全。</p>
 */
public class XinchangProfileAdapter {

    private static final Logger log = LoggerFactory.getLogger(XinchangProfileAdapter.class);

    /** 信创 Profile 名称 */
    public static final String XINCHANG_PROFILE = "xinchang";

    /** 国际 Profile 名称 */
    public static final String INTERNATIONAL_PROFILE = "international";

    private final CryptoConfig cryptoConfig;
    private final CryptoSpiFactory spiFactory;
    private final StorageCipherFactory storageCipherFactory;
    private final TransportCipherFactory transportCipherFactory;

    /**
     * 构造适配器。
     *
     * @param cryptoConfig 加密配置
     */
    public XinchangProfileAdapter(CryptoConfig cryptoConfig) {
        this(cryptoConfig, null);
    }

    /**
     * 注入 Spring Environment 构造。
     *
     * @param cryptoConfig 加密配置
     * @param environment  Spring Environment；可为 null
     */
    public XinchangProfileAdapter(CryptoConfig cryptoConfig, Environment environment) {
        if (cryptoConfig == null) {
            throw new CryptoException("cryptoConfig must not be null");
        }
        this.cryptoConfig = cryptoConfig;
        this.spiFactory = new CryptoSpiFactory(cryptoConfig, environment);
        this.storageCipherFactory = new StorageCipherFactory(cryptoConfig, spiFactory);
        this.transportCipherFactory = new TransportCipherFactory(cryptoConfig, spiFactory);
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
     * 判断当前是否为信创辖区。
     *
     * @return true 表示当前 Profile 为信创
     */
    public boolean isXinchang() {
        return getCurrentProfile() == CryptoProfile.XINCHANG;
    }

    /**
     * 判断当前是否为国际辖区。
     *
     * @return true 表示当前 Profile 为国际
     */
    public boolean isInternational() {
        return getCurrentProfile() == CryptoProfile.INTERNATIONAL;
    }

    /**
     * 获取当前 Profile 名称。
     *
     * @return Profile 名称字符串
     */
    public String getCurrentProfileName() {
        return getCurrentProfile().getProfileName();
    }

    /**
     * 运行时切换 Profile。
     *
     * @param profile Profile 字符串
     * @throws CryptoException Profile 不合法
     */
    public void switchProfile(String profile) {
        spiFactory.setCurrentProfile(profile);
        log.info("XinchangProfileAdapter switched to profile: {}", profile);
    }

    /**
     * 获取当前 Profile 的能力描述。
     *
     * @return 能力 Map（key=能力名, value=算法标识）
     */
    public Map<String, String> getCapabilities() {
        Map<String, String> caps = new LinkedHashMap<>();
        CryptoProfile profile = getCurrentProfile();
        switch (profile) {
            case XINCHANG -> {
                caps.put("jwt", "SM3withSM2");
                caps.put("storage", GmStorageCipher.ALGORITHM);
                caps.put("transport", GmTransportCipher.ALGORITHM);
                caps.put("digest", "SM3");
                caps.put("asymmetric", "SM2");
                caps.put("symmetric", "SM4");
            }
            case INTERNATIONAL -> {
                caps.put("jwt", "RS256");
                caps.put("storage", IntlStorageCipher.ALGORITHM);
                caps.put("transport", "TLS");
                caps.put("digest", "SHA-256");
                caps.put("asymmetric", "RSA");
                caps.put("symmetric", "AES");
            }
        }
        return caps;
    }

    /**
     * 判断当前 Profile 是否支持传输加密。
     *
     * <p>信创辖区支持应用层 SM2+SM4 数字信封；国际辖区依赖 TLS，应用层不重复加密。</p>
     *
     * @return true 表示支持
     */
    public boolean isTransportEncryptionSupported() {
        return isXinchang();
    }

    /**
     * 判断当前 Profile 是否支持国密 JWT。
     *
     * @return true 表示支持
     */
    public boolean isGmJwtSupported() {
        return isXinchang();
    }

    /**
     * 创建存储加密器。
     *
     * @param gmKey   SM4 密钥
     * @param intlKey AES 密钥
     * @return 当前 Profile 下的存储加密器
     */
    public StorageCipher createStorageCipher(byte[] gmKey, byte[] intlKey) {
        return storageCipherFactory.create(getCurrentProfile(), gmKey, intlKey);
    }

    /**
     * 创建存储加密器（单密钥）。
     *
     * @param key 当前 Profile 对应密钥
     * @return 存储加密器
     */
    public StorageCipher createStorageCipher(byte[] key) {
        return storageCipherFactory.create(getCurrentProfile(), key, key);
    }

    /**
     * 创建传输加密器。
     *
     * @param publicKeyQ SM2 公钥
     * @param privateKeyD SM2 私钥
     * @return 传输加密器
     * @throws CryptoException 当前 Profile 不支持
     */
    public TransportCipher createTransportCipher(byte[] publicKeyQ, byte[] privateKeyD) {
        return transportCipherFactory.create(getCurrentProfile(), publicKeyQ, privateKeyD);
    }

    /**
     * 自检当前 Profile 与算法栈是否一致。
     *
     * <p>用于启动健康检查，发现 Profile 与 Provider 不匹配的配置错误。</p>
     *
     * @return 自检结果 Map（key=检查项, value=通过/失败描述）
     */
    public Map<String, String> selfCheck() {
        Map<String, String> result = new LinkedHashMap<>();
        CryptoProfile profile = getCurrentProfile();
        result.put("profile", profile.getProfileName());

        try {
            var providers = spiFactory.getAvailableProviders(profile.getProfileName());
            result.put("providersLoaded", String.valueOf(providers.size()));
            if (providers.isEmpty()) {
                result.put("providersStatus", "FAIL: no provider for profile " + profile);
            } else {
                result.put("providersStatus", "OK");
                result.put("defaultProvider", providers.get(0).getProviderName());
            }
        } catch (Exception e) {
            result.put("providersStatus", "FAIL: " + e.getMessage());
        }

        result.put("jwtSupported", String.valueOf(isGmJwtSupported()));
        result.put("transportSupported", String.valueOf(isTransportEncryptionSupported()));
        result.put("capabilities", getCapabilities().toString());

        return result;
    }

    /**
     * 获取内部存储加密工厂。
     *
     * @return StorageCipherFactory 实例
     */
    public StorageCipherFactory getStorageCipherFactory() {
        return storageCipherFactory;
    }

    /**
     * 获取内部传输加密工厂。
     *
     * @return TransportCipherFactory 实例
     */
    public TransportCipherFactory getTransportCipherFactory() {
        return transportCipherFactory;
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