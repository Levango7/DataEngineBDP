package com.levango7.dataenginebdp.encaps.security.facade.crypto;

import com.levango7.dataenginebdp.encaps.crypto.AlgorithmType;
import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProvider;
import com.levango7.dataenginebdp.encaps.crypto.CryptoSpiFactory;
import com.levango7.dataenginebdp.encaps.security.facade.config.SecurityFacadeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.List;

/**
 * 加解密统一门面（CryptoFacade）。
 *
 * <p>委托底层 T022 已实现的 {@link CryptoSpiFactory}，对外暴露简化 API：
 * 屏蔽 Profile/Provider 选择细节，仅提供"加密/解密/签名/验签/摘要"五类语义方法。</p>
 *
 * <h3>设计动机</h3>
 * <ul>
 *   <li>业务方无需感知国密/国际算法差异，由 Profile 自动路由</li>
 *   <li>统一返回 Base64 字符串，便于跨语言/HTTP 传输</li>
 *   <li>所有调用经此门面，便于审计拦截与证据收集</li>
 * </ul>
 *
 * <h3>线程安全</h3>
 * <p>本类无状态，依赖的 {@link CryptoSpiFactory} 与 {@link CryptoProvider} 实现均线程安全，
 * 可作为 Spring 单例被并发调用。</p>
 */
@Component
public class CryptoFacade {

    private static final Logger log = LoggerFactory.getLogger(CryptoFacade.class);

    private final CryptoSpiFactory spiFactory;
    private final SecurityFacadeConfig config;

    /**
     * 构造 CryptoFacade。
     *
     * @param spiFactory T022 加密 SPI 工厂
     * @param config     SecurityFacade 配置
     */
    public CryptoFacade(CryptoSpiFactory spiFactory, SecurityFacadeConfig config) {
        this.spiFactory = spiFactory;
        this.config = config;
    }

    /**
     * 加密明文（自动选择当前 Profile 默认 Provider）。
     *
     * @param plaintext 明文字节
     * @param key       加密密钥
     * @return Base64 编码的密文
     * @throws CryptoException 加密失败或能力被禁用
     */
    public String encrypt(byte[] plaintext, Key key) {
        ensureEnabled();
        CryptoProvider provider = resolveProvider();
        byte[] cipher = provider.encrypt(plaintext, key);
        log.debug("encrypt: provider={}, algorithm={}, inLen={}, outLen={}",
                provider.getProviderName(), provider.getAlgorithmType(),
                plaintext.length, cipher.length);
        return Base64.getEncoder().encodeToString(cipher);
    }

    /**
     * 解密密文（Base64 字符串）。
     *
     * @param cipherBase64 Base64 密文
     * @param key          解密密钥
     * @return 明文字节
     * @throws CryptoException 解密失败
     */
    public byte[] decrypt(String cipherBase64, Key key) {
        ensureEnabled();
        CryptoProvider provider = resolveProvider();
        byte[] cipher = Base64.getDecoder().decode(cipherBase64);
        return provider.decrypt(cipher, key);
    }

    /**
     * 数字签名。
     *
     * @param data 待签名数据
     * @param key  私钥
     * @return Base64 签名值
     * @throws CryptoException 签名失败
     */
    public String sign(byte[] data, PrivateKey key) {
        ensureEnabled();
        CryptoProvider provider = resolveProvider();
        byte[] signature = provider.sign(data, key);
        return Base64.getEncoder().encodeToString(signature);
    }

    /**
     * 验签。
     *
     * @param data         原始数据
     * @param signBase64   Base64 签名值
     * @param key          公钥
     * @return 验签通过返回 true
     * @throws CryptoException 验签过程异常
     */
    public boolean verify(byte[] data, String signBase64, PublicKey key) {
        ensureEnabled();
        CryptoProvider provider = resolveProvider();
        byte[] signature = Base64.getDecoder().decode(signBase64);
        return provider.verifySign(data, signature, key);
    }

    /**
     * 计算摘要。
     *
     * @param data 原始数据
     * @return Base64 摘要
     * @throws CryptoException 摘要计算失败
     */
    public String digest(byte[] data) {
        ensureEnabled();
        CryptoProvider provider = resolveProvider();
        byte[] hash = provider.digest(data);
        return Base64.getEncoder().encodeToString(hash);
    }

    /**
     * 获取默认密钥对（用于测试或默认场景）。
     *
     * @return 密钥对
     */
    public KeyPair getKeyPair() {
        ensureEnabled();
        return resolveProvider().getKeyPair();
    }

    /**
     * 当前生效 Profile。
     *
     * @return Profile 枚举
     */
    public CryptoProfile currentProfile() {
        return spiFactory.getCurrentProfile();
    }

    /**
     * 当前生效 Provider 名称。
     *
     * @return Provider 名
     */
    public String currentProviderName() {
        return resolveProvider().getProviderName();
    }

    /**
     * 当前 Provider 主算法类型。
     *
     * @return 算法类型
     */
    public AlgorithmType currentAlgorithmType() {
        return resolveProvider().getAlgorithmType();
    }

    /**
     * 列出所有可用 Provider 名。
     *
     * @return Provider 名列表
     */
    public List<String> availableProviderNames() {
        return spiFactory.getAvailableProviders().stream()
                .map(CryptoProvider::getProviderName)
                .toList();
    }

    // ===== 内部方法 =====

    private void ensureEnabled() {
        if (!config.isEnabled() || !config.getCrypto().isEnabled()) {
            throw new CryptoException("CryptoFacade is disabled (app.security.facade.enabled="
                    + config.isEnabled() + ", crypto.enabled=" + config.getCrypto().isEnabled() + ")");
        }
    }

    private CryptoProvider resolveProvider() {
        String profile = config.getCrypto().getDefaultProfile();
        if (profile == null || profile.isBlank()) {
            return spiFactory.getProvider();
        }
        return spiFactory.getProvider(profile);
    }
}