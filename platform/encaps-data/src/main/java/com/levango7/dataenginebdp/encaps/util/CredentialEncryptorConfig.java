package com.levango7.dataenginebdp.encaps.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 凭据加密器自动配置。
 *
 * <p>从 Spring 属性 {@code app.security.credential-encryption-key} 读取 AES-256 密钥
 * （Base64 编码的 32 字节），创建 {@link CredentialEncryptor} Bean。
 * 该属性在 application.yml 中桥接环境变量 {@code CREDENTIAL_ENCRYPTION_KEY}。</p>
 *
 * <h3>条件加载</h3>
 * <p>仅当属性 {@code app.security.credential-encryption-key} 非空时才创建 Bean
 * （{@link ConditionalOnExpression}）。未配置时不加载本配置类，避免测试环境 fail-fast。
 * 生产环境必须配置环境变量 {@code CREDENTIAL_ENCRYPTION_KEY}，否则
 * {@link DataSourceController} 因缺少凭据加密器而无法注入（启动失败）。</p>
 *
 * <h3>密钥生成</h3>
 * <p>可用以下命令生成 32 字节随机密钥的 Base64 编码：</p>
 * <pre>{@code
 * # Linux/Mac
 * openssl rand -base64 32
 *
 * # Java
 * java.util.Base64.getEncoder().encodeToString(
 *     java.security.SecureRandom.getInstanceStrong().generateSeed(32))
 * }</pre>
 */
@Configuration
@ConditionalOnExpression("'${app.security.credential-encryption-key:}'.length() > 0")
public class CredentialEncryptorConfig {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptorConfig.class);

    /** Spring 属性名：Base64 编码的 AES-256 密钥 */
    public static final String PROPERTY_KEY = "app.security.credential-encryption-key";

    /**
     * 创建凭据加密器 Bean（从 Spring 属性读取密钥，fail-fast）。
     *
     * @param base64Key Base64 编码的 32 字节 AES-256 密钥，来自属性 {@code app.security.credential-encryption-key}
     * @return 凭据加密器实例
     */
    @Bean
    @ConditionalOnMissingBean(CredentialEncryptor.class)
    public CredentialEncryptor credentialEncryptor(
            @Value("${" + PROPERTY_KEY + "}") String base64Key) {
        log.info("初始化凭据加密器（AES-256-GCM），密钥来源: Spring 属性 {}", PROPERTY_KEY);
        return CredentialEncryptor.fromBase64Key(base64Key);
    }
}
