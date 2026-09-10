package com.levango7.dataenginebdp.encaps.util;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.IntlStorageCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.StorageCipher;

import java.util.Base64;
import java.util.Objects;

/**
 * 凭据加密器（AES-256-GCM）。
 *
 * <p>复用项目已有的 {@link IntlStorageCipher}（AES-GCM AEAD 认证加密），
 * 用于数据源密码等敏感凭据的持久化加密存储。</p>
 *
 * <h3>密钥来源</h3>
 * <p>密钥从环境变量 {@code CREDENTIAL_ENCRYPTION_KEY} 读取，格式为 Base64 编码的
 * 32 字节（AES-256）密钥。启动时若密钥缺失或长度非法则 fail-fast。</p>
 *
 * <h3>密文格式</h3>
 * <p>委托给 {@link IntlStorageCipher}，密文为自描述格式：
 * {@code Base64("AES-GCM" | 0x00 | IV(12B) | ciphertext||tag(16B))}，
 * 包含算法标识、随机 IV 与认证标签，保证机密性与完整性。</p>
 *
 * <h3>线程安全</h3>
 * <p>线程安全（委托给线程安全的 {@link StorageCipher} 实现）。</p>
 *
 * <h3>设计说明</h3>
 * <p>本类为纯 POJO，不依赖 Spring 容器，便于单元测试直接构造。
 * Spring Bean 注册由 {@link CredentialEncryptorConfig} 完成。</p>
 */
public final class CredentialEncryptor {

    /** 环境变量名：Base64 编码的 AES-256 密钥（32 字节） */
    public static final String ENV_KEY_NAME = "CREDENTIAL_ENCRYPTION_KEY";

    /** AES-256 密钥长度（字节） */
    private static final int KEY_LENGTH = 32;

    /** 底层存储加密器 */
    private final StorageCipher cipher;

    /**
     * 构造凭据加密器。
     *
     * @param key AES-256 密钥（32 字节，会被克隆保护）
     * @throws CryptoException 密钥为 null 或长度非法
     */
    public CredentialEncryptor(byte[] key) {
        Objects.requireNonNull(key, "加密密钥不可为 null");
        if (key.length != KEY_LENGTH) {
            throw new CryptoException("AES-256 密钥必须为 " + KEY_LENGTH
                    + " 字节，实际: " + key.length + " 字节");
        }
        this.cipher = new IntlStorageCipher(key.clone());
    }

    /**
     * 加密明文凭据为存储格式密文。
     *
     * @param plaintext 明文凭据（如数据库密码），不可为 null
     * @return Base64 密文字符串（含算法标识 + IV + 密文 + 认证标签）
     * @throws CryptoException 加密失败
     */
    public String encrypt(String plaintext) {
        Objects.requireNonNull(plaintext, "明文不可为 null");
        if (plaintext.isEmpty()) {
            return plaintext;
        }
        return cipher.encryptString(plaintext);
    }

    /**
     * 解密存储格式密文为明文凭据。
     *
     * @param ciphertext Base64 密文字符串
     * @return 明文凭据；输入为空则原样返回
     * @throws CryptoException 解密失败（密钥错误、密文损坏等）
     */
    public String decrypt(String ciphertext) {
        Objects.requireNonNull(ciphertext, "密文不可为 null");
        if (ciphertext.isEmpty()) {
            return ciphertext;
        }
        return cipher.decryptString(ciphertext);
    }

    /**
     * 判断字符串是否为加密密文（以算法标识开头）。
     *
     * <p>用于区分明文与密文（如历史数据迁移场景：明文密码需在首次访问时加密）。
     * 检查 Base64 解码后是否以 "AES-GCM" 标识开头。</p>
     *
     * @param value 待判断的字符串
     * @return true 表示可能为加密密文
     */
    public boolean isEncrypted(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(value);
            String prefix = new String(raw, 0,
                    Math.min(IntlStorageCipher.ALGORITHM.length(), raw.length),
                    java.nio.charset.StandardCharsets.US_ASCII);
            return IntlStorageCipher.ALGORITHM.equals(prefix);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ===== 静态工厂方法 =====

    /**
     * 从 Base64 编码的密钥字符串构造加密器。
     *
     * @param base64Key Base64 编码的 32 字节 AES-256 密钥
     * @return 凭据加密器实例
     * @throws CryptoException 密钥格式非法或长度不符
     */
    public static CredentialEncryptor fromBase64Key(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new CryptoException("加密密钥不可为空，请配置环境变量 " + ENV_KEY_NAME);
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64Key.trim());
        } catch (IllegalArgumentException e) {
            throw new CryptoException("加密密钥不是合法的 Base64 格式: " + e.getMessage(), e);
        }
        return new CredentialEncryptor(key);
    }

    /**
     * 从环境变量读取密钥并构造加密器（fail-fast）。
     *
     * <p>读取环境变量 {@link #ENV_KEY_NAME}，要求为 Base64 编码的 32 字节密钥。
     * 缺失或非法时抛出异常，阻止应用启动。</p>
     *
     * @return 凭据加密器实例
     * @throws CryptoException 环境变量未设置或密钥非法
     */
    public static CredentialEncryptor fromEnv() {
        return fromEnv(ENV_KEY_NAME);
    }

    /**
     * 从指定环境变量读取密钥并构造加密器（fail-fast）。
     *
     * @param envName 环境变量名
     * @return 凭据加密器实例
     * @throws CryptoException 环境变量未设置或密钥非法
     */
    public static CredentialEncryptor fromEnv(String envName) {
        String base64Key = System.getenv(envName);
        if (base64Key == null || base64Key.isBlank()) {
            throw new CryptoException("环境变量 " + envName
                    + " 未设置或为空，凭据加密器无法初始化（fail-fast）。"
                    + " 请配置 Base64 编码的 32 字节 AES-256 密钥。");
        }
        return fromBase64Key(base64Key);
    }
}