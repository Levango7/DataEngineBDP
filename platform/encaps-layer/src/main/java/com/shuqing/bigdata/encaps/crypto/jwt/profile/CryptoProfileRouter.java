package com.shuqing.bigdata.encaps.crypto.jwt.profile;

import com.shuqing.bigdata.encaps.crypto.CryptoConfig;
import com.shuqing.bigdata.encaps.crypto.CryptoException;
import com.shuqing.bigdata.encaps.crypto.CryptoProfile;
import com.shuqing.bigdata.encaps.crypto.CryptoSpiFactory;
import com.shuqing.bigdata.encaps.crypto.jwt.GmJwtProcessor;
import com.shuqing.bigdata.encaps.crypto.jwt.storage.StorageCipher;
import com.shuqing.bigdata.encaps.crypto.jwt.transport.TransportCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * 加密 Profile 路由器。
 *
 * <p>对外提供统一的加密能力入口，按当前 {@link CryptoProfile} 自动路由到国密或国际实现。
 * 业务层只需依赖本类，无需感知 Profile 切换细节。</p>
 *
 * <h3>路由策略</h3>
 * <table>
 *   <caption>表：Profile 路由策略对照表</caption>
 *   <tr><th>能力</th><th>信创</th><th>国际</th></tr>
 *   <tr><td>JWT 处理器</td><td>{@link GmJwtProcessor}</td><td>Spring Security OAuth2</td></tr>
 *   <tr><td>存储加密</td><td>{@link com.shuqing.bigdata.encaps.crypto.jwt.storage.GmStorageCipher}</td><td>{@link com.shuqing.bigdata.encaps.crypto.jwt.storage.IntlStorageCipher}</td></tr>
 *   <tr><td>传输加密</td><td>{@link com.shuqing.bigdata.encaps.crypto.jwt.transport.GmTransportCipher}</td><td>不适用（TLS）</td></tr>
 * </table>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * CryptoConfig config = new CryptoConfig();
 * config.setActiveProfile("xinchang");
 * CryptoProfileRouter router = new CryptoProfileRouter(config);
 *
 * // 获取 JWT 处理器（信创辖区返回 GmJwtProcessor）
 * GmJwtProcessor jwt = router.getJwtProcessor();
 *
 * // 获取存储加密器
 * StorageCipher storage = router.getStorageCipher(gmKey, intlKey);
 * }</pre>
 *
 * <h3>线程安全</h3>
 * <p>线程安全。</p>
 */
public class CryptoProfileRouter {

    private static final Logger log = LoggerFactory.getLogger(CryptoProfileRouter.class);

    private final XinchangProfileAdapter adapter;
    private final String jwtIssuer;

    /**
     * 构造路由器。
     *
     * @param cryptoConfig 加密配置
     */
    public CryptoProfileRouter(CryptoConfig cryptoConfig) {
        this(cryptoConfig, null, null);
    }

    /**
     * 完整构造。
     *
     * @param cryptoConfig 加密配置
     * @param environment  Spring Environment；可为 null
     * @param jwtIssuer    JWT issuer；可为 null
     */
    public CryptoProfileRouter(CryptoConfig cryptoConfig, Environment environment, String jwtIssuer) {
        this.adapter = new XinchangProfileAdapter(cryptoConfig, environment);
        this.jwtIssuer = jwtIssuer;
    }

    /**
     * 获取当前 Profile。
     *
     * @return 当前 Profile 枚举
     */
    public CryptoProfile getCurrentProfile() {
        return adapter.getCurrentProfile();
    }

    /**
     * 获取 JWT 处理器。
     *
     * <p>信创辖区返回 {@link GmJwtProcessor}；国际辖区抛出 {@link CryptoException}，
     * 国际辖区 JWT 由 Spring Security OAuth2 Resource Server 处理，不通过本路由器。</p>
     *
     * @return 国密 JWT 处理器
     * @throws CryptoException 当前为国际辖区
     */
    public GmJwtProcessor getJwtProcessor() {
        if (!adapter.isGmJwtSupported()) {
            throw new CryptoException("GmJwtProcessor not available for profile: "
                    + adapter.getCurrentProfileName()
                    + "; use Spring Security OAuth2 for international profile");
        }
        return new GmJwtProcessor(jwtIssuer);
    }

    /**
     * 获取存储加密器。
     *
     * @param gmKey   SM4 密钥
     * @param intlKey AES 密钥
     * @return 当前 Profile 下的存储加密器
     */
    public StorageCipher getStorageCipher(byte[] gmKey, byte[] intlKey) {
        return adapter.createStorageCipher(gmKey, intlKey);
    }

    /**
     * 获取传输加密器。
     *
     * @param publicKeyQ SM2 公钥
     * @param privateKeyD SM2 私钥
     * @return 传输加密器
     * @throws CryptoException 当前 Profile 不支持
     */
    public TransportCipher getTransportCipher(byte[] publicKeyQ, byte[] privateKeyD) {
        return adapter.createTransportCipher(publicKeyQ, privateKeyD);
    }

    /**
     * 运行时切换 Profile。
     *
     * @param profile Profile 字符串
     */
    public void switchProfile(String profile) {
        adapter.switchProfile(profile);
        log.info("CryptoProfileRouter switched to: {}", profile);
    }

    /**
     * 获取能力描述。
     *
     * @return 能力 Map
     */
    public java.util.Map<String, String> getCapabilities() {
        return adapter.getCapabilities();
    }

    /**
     * 自检。
     *
     * @return 自检结果 Map
     */
    public java.util.Map<String, String> selfCheck() {
        return adapter.selfCheck();
    }

    /**
     * 获取内部适配器。
     *
     * @return XinchangProfileAdapter 实例
     */
    public XinchangProfileAdapter getAdapter() {
        return adapter;
    }

    /**
     * 获取内部 SPI 工厂。
     *
     * @return CryptoSpiFactory 实例
     */
    public CryptoSpiFactory getSpiFactory() {
        return adapter.getSpiFactory();
    }
}