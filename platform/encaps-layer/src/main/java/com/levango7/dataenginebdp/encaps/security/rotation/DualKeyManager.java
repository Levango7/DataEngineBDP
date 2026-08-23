package com.levango7.dataenginebdp.encaps.security.rotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 双密钥管理器（线程安全）。
 *
 * <p>维护「当前签发密钥」与「过渡期旧密钥」两个密钥槽位，支持轮换过渡期内的
 * 双密钥并行验签。验签时按 JWT header 中的 {@code kid} 路由到对应密钥，
 * 同时接受新旧两个密钥签发的 token，保证轮换零停机。</p>
 *
 * <h3>密钥生命周期</h3>
 * <pre>
 * T0: 仅 K1（active）          → 验签集 = {K1}
 * T1: 轮换，K2 成为 active      → 验签集 = {K1, K2}（过渡期）
 * T2: 过渡期结束，废弃 K1       → 验签集 = {K2}
 * </pre>
 *
 * <p>持久化：密钥以 PEM 文件落盘到 {@link KeyRotationConfig#getKeyStorePath()}，
 * 文件名 {@code {kid}.private.pem} / {@code {kid}.public.pem}，便于审计与跨实例共享。</p>
 */
@Component
public class DualKeyManager {

    private static final Logger log = LoggerFactory.getLogger(DualKeyManager.class);

    private static final String PEM_PRIVATE_HEADER = "-----BEGIN PRIVATE KEY-----";
    private static final String PEM_PRIVATE_FOOTER = "-----END PRIVATE KEY-----";
    private static final String PEM_PUBLIC_HEADER = "-----BEGIN PUBLIC KEY-----";
    private static final String PEM_PUBLIC_FOOTER = "-----END PUBLIC KEY-----";
    private static final String RSA_ALGORITHM = "RSA";

    /** 密钥池：kid → 密钥条目。包含 active 与过渡期内的旧密钥。 */
    private final ConcurrentHashMap<String, KeyEntry> keys = new ConcurrentHashMap<>();

    /** 当前签发密钥的 kid；volatile 保证多线程可见性。 */
    private volatile String activeKid;

    /**
     * 密钥条目：一个 RSA 密钥对及其元数据。
     */
    public static final class KeyEntry {

        private final String kid;
        private final PrivateKey privateKey;
        private final PublicKey publicKey;
        private final Instant createdAt;
        /** 过渡期结束时间；超过此时间旧密钥应被废弃。 */
        private final Instant expireAt;

        /**
         * 构造密钥条目。
         *
         * @param kid        密钥标识（写入 JWT header kid）
         * @param privateKey RSA 私钥（签发用）
         * @param publicKey  RSA 公钥（验签用）
         * @param createdAt  创建时间
         * @param expireAt   过渡期结束时间（active 密钥可为 null）
         */
        public KeyEntry(String kid, PrivateKey privateKey, PublicKey publicKey,
                        Instant createdAt, Instant expireAt) {
            this.kid = kid;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
            this.createdAt = createdAt;
            this.expireAt = expireAt;
        }

        /**
         * 密钥标识。
         *
         * @return kid
         */
        public String getKid() {
            return kid;
        }

        /**
         * RSA 私钥。
         *
         * @return 私钥
         */
        public PrivateKey getPrivateKey() {
            return privateKey;
        }

        /**
         * RSA 公钥。
         *
         * @return 公钥
         */
        public PublicKey getPublicKey() {
            return publicKey;
        }

        /**
         * 创建时间。
         *
         * @return 创建时间
         */
        public Instant getCreatedAt() {
            return createdAt;
        }

        /**
         * 过渡期结束时间。
         *
         * @return 过期时间；null 表示不会自动过期
         */
        public Instant getExpireAt() {
            return expireAt;
        }
    }

    /**
     * 发布新密钥为 active，原 active 密钥降级为过渡期旧密钥。
     *
     * <p>线程安全：通过 ConcurrentHashMap 原子操作保证并发发布的一致性。</p>
     *
     * @param entry      新密钥条目
     * @param overlapEnd 旧密钥过渡期结束时间；null 表示不设过期（仅当无旧密钥时）
     */
    public synchronized void publishActive(KeyEntry entry, Instant overlapEnd) {
        String previousKid = this.activeKid;
        this.keys.put(entry.getKid(), entry);
        this.activeKid = entry.getKid();

        if (previousKid != null && !previousKid.equals(entry.getKid())) {
            KeyEntry previous = this.keys.get(previousKid);
            if (previous != null && overlapEnd != null) {
                // 旧密钥标记过期时间，过渡期结束后由调度器废弃
                this.keys.put(previousKid, new KeyEntry(
                        previous.kid, previous.privateKey, previous.publicKey,
                        previous.createdAt, overlapEnd));
                log.info("密钥轮换: active {} → {}, 旧密钥 {} 过渡期至 {}",
                        previousKid, entry.getKid(), previousKid, overlapEnd);
            } else {
                log.info("密钥轮换: active {} → {}", previousKid, entry.getKid());
            }
        } else {
            log.info("首次发布 active 密钥: {}", entry.getKid());
        }
    }

    /**
     * 获取当前 active 密钥条目。
     *
     * @return active 密钥条目；未初始化时返回 null
     */
    public KeyEntry getActive() {
        String kid = this.activeKid;
        return kid == null ? null : this.keys.get(kid);
    }

    /**
     * 当前 active 密钥的 kid。
     *
     * @return kid；未初始化时返回 null
     */
    public String getActiveKid() {
        return activeKid;
    }

    /**
     * 按 kid 获取密钥条目（验签路由用）。
     *
     * @param kid 密钥标识
     * @return 密钥条目；不存在时返回 null
     */
    public KeyEntry getKey(String kid) {
        return kid == null ? null : this.keys.get(kid);
    }

    /**
     * 当前所有验签接受的 kid 集合（active + 过渡期内旧密钥）。
     *
     * <p>返回快照副本，避免调用方迭代时因并发修改抛出 {@code ConcurrentModificationException}。</p>
     *
     * @return 不可变 kid 集合的快照
     */
    public Set<String> getAcceptedKids() {
        return Collections.unmodifiableSet(new java.util.HashSet<>(this.keys.keySet()));
    }

    /**
     * 废弃指定 kid 的密钥（过渡期结束后调用）。
     *
     * @param kid 要废弃的密钥标识
     * @return 被废弃的密钥条目；不存在时返回 null
     */
    public KeyEntry decommission(String kid) {
        if (kid == null || kid.equals(this.activeKid)) {
            // 不允许废弃当前 active 密钥
            log.warn("拒绝废弃 active 密钥或不存在的 kid: {}", kid);
            return null;
        }
        KeyEntry removed = this.keys.remove(kid);
        if (removed != null) {
            log.info("密钥废弃: {}（已过过渡期）", kid);
        }
        return removed;
    }

    /**
     * 清理已过过渡期的旧密钥。
     *
     * @param now 当前时间
     * @return 被清理的密钥数量
     */
    public int purgeExpired(Instant now) {
        int removed = 0;
        for (Map.Entry<String, KeyEntry> e : this.keys.entrySet()) {
            KeyEntry entry = e.getValue();
            if (entry.getExpireAt() != null
                    && now.isAfter(entry.getExpireAt())
                    && !e.getKey().equals(this.activeKid)) {
                this.keys.remove(e.getKey());
                removed++;
                log.info("自动清理过期旧密钥: {}（过期于 {}）", e.getKey(), entry.getExpireAt());
            }
        }
        return removed;
    }

    /**
     * 将指定密钥条目持久化为 PEM 文件。
     *
     * @param entry     密钥条目
     * @param storePath 持久化目录
     * @throws IOException 写文件失败
     */
    public void persist(KeyEntry entry, String storePath) throws IOException {
        if (storePath == null || storePath.isBlank()) {
            return;
        }
        Path dir = Paths.get(storePath);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(entry.getKid() + ".private.pem"),
                toPem(PEM_PRIVATE_HEADER, entry.getPrivateKey().getEncoded()),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(entry.getKid() + ".public.pem"),
                toPem(PEM_PUBLIC_HEADER, entry.getPublicKey().getEncoded()),
                StandardCharsets.UTF_8);
        log.debug("密钥 {} 已持久化到 {}", entry.getKid(), dir);
    }

    /**
     * 从 PEM 文件加载密钥对。
     *
     * @param kid       密钥标识
     * @param storePath 持久化目录
     * @param createdAt 创建时间
     * @param expireAt  过期时间
     * @return 密钥条目；文件不存在或解析失败时返回 null
     */
    public KeyEntry load(String kid, String storePath,
                         Instant createdAt, Instant expireAt) {
        if (storePath == null || storePath.isBlank() || kid == null) {
            return null;
        }
        try {
            Path dir = Paths.get(storePath);
            Path privFile = dir.resolve(kid + ".private.pem");
            Path pubFile = dir.resolve(kid + ".public.pem");
            if (!Files.exists(privFile) || !Files.exists(pubFile)) {
                return null;
            }
            KeyFactory factory = KeyFactory.getInstance(RSA_ALGORITHM);
            byte[] privDer = fromPem(Files.readString(privFile, StandardCharsets.UTF_8));
            byte[] pubDer = fromPem(Files.readString(pubFile, StandardCharsets.UTF_8));
            PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(privDer));
            PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(pubDer));
            return new KeyEntry(kid, privateKey, publicKey, createdAt, expireAt);
        } catch (Exception e) {
            log.warn("加载密钥 {} 失败: {}", kid, e.getMessage());
            return null;
        }
    }

    /**
     * DER 字节编码为 PEM 文本。
     */
    private static String toPem(String header, byte[] der) {
        String base64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder(header.length() + base64.length() + 32);
        sb.append(header).append('\n');
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length())).append('\n');
        }
        String footer = header.replace("BEGIN", "END");
        sb.append(footer).append('\n');
        return sb.toString();
    }

    /**
     * PEM 文本解码为 DER 字节。
     */
    private static byte[] fromPem(String pem) {
        String base64 = pem.lines()
                .filter(line -> !line.startsWith("-----"))
                .reduce("", String::concat);
        return Base64.getDecoder().decode(base64);
    }
}