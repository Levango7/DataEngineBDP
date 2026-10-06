package com.levango7.dataenginebdp.encaps.util;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.util.Base64;

/**
 * 凭据加密器自动配置（按加密辖区 Profile 路由 SM4 / AES）。
 *
 * <p>从 Spring 属性读取两种算法的密钥，并结合当前加密 Profile 构造
 * {@link CredentialEncryptor}：</p>
 * <ul>
 *   <li>AES（国际）：{@code app.security.credential-encryption-key}（Base64 编码的 32 字节），
 *       application.yml 桥接环境变量 {@code CREDENTIAL_ENCRYPTION_KEY}</li>
 *   <li>SM4（信创）：{@code app.security.encrypt-key}（32 位 hex = 16 字节），
 *       application.yml 桥接环境变量 {@code ENCRYPT_KEY}</li>
 * </ul>
 *
 * <h3>Profile 解析与向后兼容</h3>
 * <ol>
 *   <li>{@code app.crypto.active-profile} 显式配置</li>
 *   <li>{@code spring.profiles.active}（取第一个可识别的 xinchang/international）</li>
 *   <li>均未声明时：为保持既有行为，**优先 AES**；若仅配置了 SM4 密钥则使用 SM4</li>
 * </ol>
 * <p>当 Profile 被**显式**声明为 xinchang/international 而对应密钥缺失时，
 * 由 {@link CredentialEncryptor#fromProfile} fail-fast，避免「名为国密、实为 AES」的静默降级。</p>
 *
 * <h3>条件加载</h3>
 * <p>仅当两种密钥属性至少有一个非空时才创建 Bean（{@link ConditionalOnExpression}）。
 * 未配置时不加载本配置类，避免测试环境 fail-fast。生产环境必须注入真实密钥。</p>
 *
 * <h3>密钥生成</h3>
 * <pre>{@code
 * # AES-256（Base64，32 字节）
 * openssl rand -base64 32
 * # SM4（hex，16 字节 = 32 位 hex）
 * openssl rand -hex 16
 * }</pre>
 */
@Configuration
@ConditionalOnExpression("'${app.security.credential-encryption-key:}'.length() > 0"
        + " || '${app.security.encrypt-key:}'.length() > 0")
public class CredentialEncryptorConfig {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptorConfig.class);

    /** Spring 属性名：Base64 编码的 AES-256 密钥（国际算法） */
    public static final String PROPERTY_AES_KEY = "app.security.credential-encryption-key";

    /** Spring 属性名：32 位 hex 的 SM4 密钥（信创算法） */
    public static final String PROPERTY_SM4_KEY = "app.security.encrypt-key";

    /** Spring 属性名：显式加密 Profile 覆盖 */
    public static final String PROPERTY_CRYPTO_PROFILE = "app.crypto.active-profile";

    /** Spring Profile 属性名 */
    private static final String SPRING_PROFILE_KEY = "spring.profiles.active";

    /**
     * 创建按 Profile 路由的凭据加密器 Bean（fail-fast）。
     *
     * @param base64AesKey AES 密钥（Base64 编码的 32 字节），可为空
     * @param hexSm4Key    SM4 密钥（32 位 hex），可为空
     * @param explicitProfile 显式加密 Profile（{@code app.crypto.active-profile}），可为空
     * @param environment  Spring Environment，用于解析 {@code spring.profiles.active}
     * @return 凭据加密器实例
     */
    @Bean
    @ConditionalOnMissingBean(CredentialEncryptor.class)
    public CredentialEncryptor credentialEncryptor(
            @Value("${" + PROPERTY_AES_KEY + ":}") String base64AesKey,
            @Value("${" + PROPERTY_SM4_KEY + ":}") String hexSm4Key,
            @Value("${" + PROPERTY_CRYPTO_PROFILE + ":}") String explicitProfile,
            Environment environment) {

        byte[] aesKey = decodeAesKey(base64AesKey);
        byte[] sm4Key = decodeSm4Key(hexSm4Key);

        CryptoProfile profile = resolveProfile(explicitProfile, environment, aesKey, sm4Key);
        CredentialEncryptor encryptor = CredentialEncryptor.fromProfile(profile, sm4Key, aesKey);
        log.info("初始化凭据加密器：profile={}，加密算法={}，可解密算法={}",
                profile.getProfileName(), encryptor.getAlgorithm(),
                (aesKey != null && sm4Key != null) ? "SM4-CBC,AES-GCM"
                        : (sm4Key != null ? "SM4-CBC" : "AES-GCM"));
        return encryptor;
    }

    /**
     * 解析当前加密 Profile。
     *
     * @param explicit  显式配置的 Profile（可空）
     * @param env       Spring Environment（可空）
     * @param aesKey    已解析的 AES 密钥（可空）
     * @param sm4Key    已解析的 SM4 密钥（可空）
     * @return 加密 Profile；未显式声明时按可用密钥回退（AES 优先）
     * @throws CryptoException 显式声明的 Profile 非法
     */
    static CryptoProfile resolveProfile(String explicit, Environment env,
                                        byte[] aesKey, byte[] sm4Key) {
        if (explicit != null && !explicit.isBlank()) {
            return CryptoProfile.fromString(explicit);
        }
        if (env != null) {
            String springProfiles = env.getProperty(SPRING_PROFILE_KEY);
            if (springProfiles != null && !springProfiles.isBlank()) {
                for (String candidate : springProfiles.split(",")) {
                    String trimmed = candidate.trim();
                    if (trimmed.isEmpty()) {
                        continue;
                    }
                    try {
                        return CryptoProfile.fromString(trimmed);
                    } catch (CryptoException ignored) {
                        // 非加密 Profile（如 prod/test），继续尝试下一个
                    }
                }
            }
        }
        // 未显式声明辖区：保持向后兼容——优先 AES；仅当只配了 SM4 时才用信创算法。
        if (aesKey != null) {
            return CryptoProfile.INTERNATIONAL;
        }
        if (sm4Key != null) {
            log.warn("未声明加密 Profile 且仅配置了 SM4 密钥，回退为信创（xinchang）算法；"
                    + "建议显式设置 spring.profiles.active 或 app.crypto.active-profile");
            return CryptoProfile.XINCHANG;
        }
        throw new CryptoException("未配置任何凭据加密密钥（credential-encryption-key / encrypt-key）");
    }

    /**
     * 解析 Base64 编码的 AES 密钥。
     *
     * @param base64Key Base64 字符串（可空/空）
     * @return 32 字节 AES 密钥；未配置返回 null
     * @throws CryptoException 非法 Base64 或长度非 32 字节
     */
    static byte[] decodeAesKey(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            return null;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new CryptoException("credential-encryption-key 不是合法 Base64: " + e.getMessage(), e);
        }
        if (key.length != 32) {
            throw new CryptoException("credential-encryption-key 必须为 32 字节（AES-256）Base64，"
                    + "实际: " + key.length + " 字节");
        }
        return key;
    }

    /**
     * 解析 32 位 hex 的 SM4 密钥。
     *
     * @param hexKey hex 字符串（可空/空）
     * @return 16 字节 SM4 密钥；未配置返回 null
     * @throws CryptoException 长度非 32 位或含非 hex 字符
     */
    static byte[] decodeSm4Key(String hexKey) {
        if (hexKey == null || hexKey.isBlank()) {
            return null;
        }
        String hex = hexKey.trim();
        if (hex.length() != 32) {
            throw new CryptoException("encrypt-key 必须为 32 位 hex（16 字节 SM4），"
                    + "实际长度: " + hex.length());
        }
        byte[] key = new byte[16];
        for (int i = 0; i < 16; i++) {
            int high = Character.digit(hex.charAt(i * 2), 16);
            int low = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                throw new CryptoException("encrypt-key 含非 hex 字符，位置 " + (i * 2));
            }
            key[i] = (byte) ((high << 4) | low);
        }
        return key;
    }
}
