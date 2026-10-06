package com.levango7.dataenginebdp.encaps.util;

import com.levango7.dataenginebdp.encaps.crypto.CryptoException;
import com.levango7.dataenginebdp.encaps.crypto.CryptoProfile;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.GmStorageCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.IntlStorageCipher;
import com.levango7.dataenginebdp.encaps.crypto.jwt.storage.StorageCipher;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 凭据加密器（按加密辖区 Profile 路由 SM4 / AES）。
 *
 * <p>用于数据源密码、API Key 等敏感凭据的持久化加密存储。算法由
 * {@link CryptoProfile} 决定：</p>
 * <ul>
 *   <li>{@link CryptoProfile#XINCHANG}（信创/国密辖区）→ {@link GmStorageCipher}（SM4-CBC）</li>
 *   <li>{@link CryptoProfile#INTERNATIONAL}（国际辖区）→ {@link IntlStorageCipher}（AES-256-GCM）</li>
 * </ul>
 *
 * <h3>算法由 Profile 决定，而非写死</h3>
 * <p>历史实现的缺陷在于把底层算法**硬编码为 AES-GCM**，导致信创部署也使用国际算法、
 * 「国密字段加密」名不副实。本类通过 {@link #fromProfile} 依据 Profile 选择主加密器，
 * 使信创辖区的凭据加密真正走 SM4。</p>
 *
 * <h3>密文自描述与双读迁移</h3>
 * <p>两种算法的密文均以算法标识开头（{@code Base64("SM4-CBC"|0x00|...)}
 * 或 {@code Base64("AES-GCM"|0x00|...)}）。{@link #decrypt(String)} 按密文头部标识
 * 分派到对应解密器，因此**切换 Profile 不会导致历史密文不可读**：
 * 信创上线后仍可解密存量 AES 密文，反之亦然。写入（{@link #encrypt(String)}）
 * 始终使用当前 Profile 的主算法。</p>
 *
 * <h3>密钥来源</h3>
 * <ul>
 *   <li>国际：{@code app.security.credential-encryption-key}（环境变量
 *       {@code CREDENTIAL_ENCRYPTION_KEY}），Base64 编码的 32 字节 AES-256 密钥</li>
 *   <li>信创：{@code app.security.encrypt-key}（环境变量 {@code ENCRYPT_KEY}），
 *       32 位 hex 字符串（16 字节 SM4 密钥）</li>
 * </ul>
 * <p>密钥缺失时的 fail-fast 由 {@link CredentialEncryptorConfig} 负责。</p>
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

    /**
     * 已知的密文算法标识集合。
     *
     * <p>用于 {@link #isEncrypted(String)}：判定与「当前实例持有哪种密钥」无关，
     * 只要密文头部是已知算法即认定为密文，避免迁移期误判。
     */
    private static final Set<String> KNOWN_ALGORITHMS =
            Set.of(IntlStorageCipher.ALGORITHM, GmStorageCipher.ALGORITHM);

    /** 主加密器（用于写入），由 Profile 决定 */
    private final StorageCipher primary;

    /** 解密器表：算法标识 → 对应解密器（含主加密器，支持双读） */
    private final Map<String, StorageCipher> decryptors;

    /**
     * 构造 AES-256-GCM 凭据加密器（向后兼容）。
     *
     * <p>仅持有 AES 密钥，用于历史调用方与单元测试。加密与解密均走 AES-GCM。
     * 需要按 Profile 路由时请使用 {@link #fromProfile}。</p>
     *
     * @param key AES-256 密钥（32 字节，会被克隆保护）
     * @throws NullPointerException 密钥为 null
     * @throws CryptoException      密钥长度非法
     */
    public CredentialEncryptor(byte[] key) {
        Objects.requireNonNull(key, "加密密钥不可为 null");
        if (key.length != KEY_LENGTH) {
            throw new CryptoException("AES-256 密钥必须为 " + KEY_LENGTH
                    + " 字节，实际: " + key.length + " 字节");
        }
        StorageCipher aes = new IntlStorageCipher(key.clone());
        this.primary = aes;
        this.decryptors = Map.of(aes.getAlgorithm(), aes);
    }

    /**
     * 构造多算法凭据加密器。
     *
     * <p>写入使用 {@code primary}；解密按密文头部算法标识在 {@code ciphers} 中分派，
     * 因而可同时解密新旧算法的密文（双读）。{@code primary} 会被自动并入解密器表。</p>
     *
     * @param primary 主加密器（用于写入），不可为 null
     * @param ciphers 可用于解密的加密器集合（可为 null）
     * @throws NullPointerException primary 为 null
     */
    public CredentialEncryptor(StorageCipher primary, Collection<StorageCipher> ciphers) {
        this.primary = Objects.requireNonNull(primary, "primary cipher 不可为 null");
        Map<String, StorageCipher> map = new LinkedHashMap<>();
        if (ciphers != null) {
            for (StorageCipher cipher : ciphers) {
                if (cipher != null) {
                    map.putIfAbsent(cipher.getAlgorithm(), cipher);
                }
            }
        }
        map.putIfAbsent(primary.getAlgorithm(), primary);
        this.decryptors = Map.copyOf(map);
    }

    /**
     * 按加密辖区 Profile 构造凭据加密器（推荐入口）。
     *
     * <p>仅在 Profile 允许的算法之间路由；对应密钥缺失时 fail-fast，避免静默降级：</p>
     * <ul>
     *   <li>{@code XINCHANG} 必须有 SM4 密钥，主算法 = SM4-CBC</li>
     *   <li>{@code INTERNATIONAL} 必须有 AES 密钥，主算法 = AES-GCM</li>
     * </ul>
     * <p>两个密钥都提供时，解密支持两种算法的双读。</p>
     *
     * @param profile 加密辖区 Profile
     * @param sm4Key  SM4 密钥（16 字节）；信创 Profile 必填
     * @param aesKey  AES 密钥（16 或 32 字节）；国际 Profile 必填
     * @return 凭据加密器实例
     * @throws NullPointerException profile 为 null
     * @throws CryptoException      Profile 所需密钥缺失或密钥长度非法
     */
    public static CredentialEncryptor fromProfile(CryptoProfile profile, byte[] sm4Key, byte[] aesKey) {
        Objects.requireNonNull(profile, "profile 不可为 null");
        List<StorageCipher> all = new ArrayList<>(2);
        StorageCipher gm = null;
        StorageCipher intl = null;
        if (sm4Key != null && sm4Key.length > 0) {
            gm = new GmStorageCipher(sm4Key.clone());
            all.add(gm);
        }
        if (aesKey != null && aesKey.length > 0) {
            intl = new IntlStorageCipher(aesKey.clone());
            all.add(intl);
        }
        StorageCipher primary = switch (profile) {
            case XINCHANG -> {
                if (gm == null) {
                    throw new CryptoException("信创（xinchang）Profile 需要 SM4 密钥"
                            + "（app.security.encrypt-key / ENCRYPT_KEY，32 位 hex）");
                }
                yield gm;
            }
            case INTERNATIONAL -> {
                if (intl == null) {
                    throw new CryptoException("国际（international）Profile 需要 AES 密钥"
                            + "（app.security.credential-encryption-key / CREDENTIAL_ENCRYPTION_KEY，Base64 32 字节）");
                }
                yield intl;
            }
        };
        return new CredentialEncryptor(primary, all);
    }

    /**
     * 加密明文凭据为存储格式密文。
     *
     * @param plaintext 明文凭据（如数据库密码），不可为 null
     * @return Base64 密文字符串（含算法标识 + IV + 密文；AES 另含认证标签）
     * @throws NullPointerException 明文为 null
     * @throws CryptoException      加密失败
     */
    public String encrypt(String plaintext) {
        Objects.requireNonNull(plaintext, "明文不可为 null");
        if (plaintext.isEmpty()) {
            return plaintext;
        }
        return primary.encryptString(plaintext);
    }

    /**
     * 解密存储格式密文为明文凭据。
     *
     * <p>按密文头部的算法标识分派到对应解密器，支持跨 Profile 双读。
     * 密文算法不在本实例持有的解密器表内时抛出异常（而非静默返回原值）。</p>
     *
     * @param ciphertext Base64 密文字符串
     * @return 明文凭据；输入为空则原样返回
     * @throws NullPointerException 密文为 null
     * @throws CryptoException      解密失败（算法不可用、密钥错误、密文损坏等）
     */
    public String decrypt(String ciphertext) {
        Objects.requireNonNull(ciphertext, "密文不可为 null");
        if (ciphertext.isEmpty()) {
            return ciphertext;
        }
        String algorithm = detectAlgorithm(ciphertext);
        StorageCipher cipher = algorithm == null ? null : decryptors.get(algorithm);
        if (cipher == null) {
            throw new CryptoException("无法解密凭据密文：算法标识 "
                    + (algorithm == null ? "未知/非法" : algorithm)
                    + "，当前可解密算法: " + decryptors.keySet());
        }
        return cipher.decryptString(ciphertext);
    }

    /**
     * 判断字符串是否为受支持的加密密文（以已知算法标识开头）。
     *
     * <p>用于区分明文与密文（如历史数据迁移场景：明文密码需在首次访问时加密）。
     * 判定基于密文头部标识，与当前实例持有哪种密钥无关，因此迁移期不会把
     * 「另一种算法的密文」误判为明文。</p>
     *
     * @param value 待判断的字符串
     * @return true 表示可能是受支持的加密密文
     */
    public boolean isEncrypted(String value) {
        return detectAlgorithm(value) != null;
    }

    /**
     * 当前写入使用的算法标识。
     *
     * @return 主加密器的算法标识，如 {@code SM4-CBC} / {@code AES-GCM}
     */
    public String getAlgorithm() {
        return primary.getAlgorithm();
    }

    /**
     * 识别密文头部的算法标识。
     *
     * @param value 待识别字符串
     * @return 已知算法标识；非密文或未知算法返回 null
     */
    static String detectAlgorithm(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(value);
            int sep = -1;
            for (int i = 0; i < raw.length; i++) {
                if (raw[i] == 0) {
                    sep = i;
                    break;
                }
            }
            if (sep <= 0) {
                return null;
            }
            String algorithm = new String(raw, 0, sep, StandardCharsets.US_ASCII);
            return KNOWN_ALGORITHMS.contains(algorithm) ? algorithm : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ===== 静态工厂方法 =====

    /**
     * 从 Base64 编码的密钥字符串构造 AES 加密器。
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
     * 从环境变量读取密钥并构造 AES 加密器（fail-fast）。
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
     * 从指定环境变量读取密钥并构造 AES 加密器（fail-fast）。
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
