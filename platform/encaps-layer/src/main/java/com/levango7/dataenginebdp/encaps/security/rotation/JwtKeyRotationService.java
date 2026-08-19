package com.levango7.dataenginebdp.encaps.security.rotation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JWT 密钥自动轮换服务。
 *
 * <p>使用 Spring {@link Scheduled} 定时触发轮换，默认 90 天一次（可配置）。
 * 轮换策略遵循双密钥过渡期方案（见 {@code docs/JWT-KEY-ROTATION-GUIDE.md} §2）：</p>
 * <ol>
 *   <li>生成新 RSA 2048 位密钥对（{@link KeyPairGenerator}，标准 Java API）；</li>
 *   <li>发布到 {@link DualKeyManager}，新密钥成为 active，旧密钥保留过渡期；</li>
 *   <li>过渡期（默认 7 天）后自动废弃旧密钥，验签集收缩为单密钥。</li>
 * </ol>
 *
 * <p>默认 {@code enabled=false}，不影响现有 HMAC/OIDC 验签链路。
 * 生产环境通过 {@code security.jwt.rotation.enabled=true} 开启。</p>
 *
 * <h3>调度说明</h3>
 * <ul>
 *   <li>主轮换：{@code fixedDelay} = {@code interval-days} 天（毫秒），首次延迟一个完整周期；</li>
 *   <li>过期清理：每 24h 扫描一次，废弃已过过渡期的旧密钥。</li>
 * </ul>
 */
@Service
public class JwtKeyRotationService {

    private static final Logger log = LoggerFactory.getLogger(JwtKeyRotationService.class);

    /** RSA 密钥位数（满足等保与 JR/T 0071 要求）。 */
    private static final int RSA_KEY_SIZE = 2048;
    /** 过期清理调度间隔：24 小时（毫秒）。 */
    private static final long PURGE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000;
    /** 一天的毫秒数，用于 interval-days → 毫秒换算。 */
    private static final long MILLIS_PER_DAY = 24L * 60 * 60 * 1000;
    /** kid 时间戳格式。 */
    private static final DateTimeFormatter KID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final KeyRotationConfig config;
    private final DualKeyManager dualKeyManager;
    private final KeyPairGenerator keyPairGenerator;

    /** 轮换事件审计日志（内存保留最近 100 条，生产可扩展为持久化）。 */
    private final List<KeyRotationEvent> eventHistory = Collections.synchronizedList(new ArrayList<>());

    /**
     * 构造轮换服务。
     *
     * @param config         轮换配置
     * @param dualKeyManager 双密钥管理器
     * @throws NoSuchAlgorithmException RSA 算法不可用（JDK 缺失，理论不会发生）
     */
    public JwtKeyRotationService(KeyRotationConfig config, DualKeyManager dualKeyManager)
            throws NoSuchAlgorithmException {
        this.config = config;
        this.dualKeyManager = dualKeyManager;
        this.keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        this.keyPairGenerator.initialize(RSA_KEY_SIZE);
    }

    /**
     * 启动时初始化：若启用轮换且当前无 active 密钥，则生成初始密钥。
     */
    @PostConstruct
    public void init() {
        if (!config.isEnabled()) {
            log.info("JWT 密钥自动轮换未启用（security.jwt.rotation.enabled=false）");
            return;
        }
        if (dualKeyManager.getActive() == null) {
            log.info("启用轮换但无 active 密钥，生成初始密钥对");
            rotateKey();
        } else {
            log.info("JWT 密钥自动轮换已启用，当前 active 密钥: {}", dualKeyManager.getActiveKid());
        }
    }

    /**
     * 定时轮换调度入口。
     *
     * <p>调度间隔由 {@code security.jwt.rotation.interval-millis} 配置（毫秒），
     * 默认 90 天（7,776,000,000 ms）。首次执行延迟一个完整周期，避免启动即轮换。</p>
     */
    @Scheduled(fixedDelayString = "${security.jwt.rotation.interval-millis:7776000000}",
               initialDelayString = "${security.jwt.rotation.interval-millis:7776000000}")
    public void scheduledRotate() {
        if (!config.isEnabled()) {
            return;
        }
        rotateKey();
    }

    /**
     * 定时清理过期旧密钥调度入口（每 24h 一次）。
     */
    @Scheduled(fixedRate = PURGE_INTERVAL_MILLIS, initialDelay = PURGE_INTERVAL_MILLIS)
    public void scheduledPurge() {
        if (!config.isEnabled()) {
            return;
        }
        int purged = dualKeyManager.purgeExpired(Instant.now());
        if (purged > 0) {
            log.info("过期旧密钥清理完成，共清理 {} 个", purged);
        }
    }

    /**
     * 执行一次密钥轮换（可手动调用，亦由调度器触发）。
     *
     * <p>步骤：</p>
     * <ol>
     *   <li>生成新 RSA 2048 密钥对，kid 格式 {@code k-{yyyyMMddHHmmss}}；</li>
     *   <li>计算旧密钥过渡期结束时间 = now + overlap-days；</li>
     *   <li>发布到 {@link DualKeyManager}，新密钥 active，旧密钥保留至过渡期结束；</li>
     *   <li>持久化新密钥到配置目录（便于跨实例共享与审计）；</li>
     *   <li>记录审计事件。</li>
     * </ol>
     *
     * @return 轮换事件；失败时 success=false
     */
    public synchronized KeyRotationEvent rotateKey() {
        Instant now = Instant.now();
        String previousKid = dualKeyManager.getActiveKid();
        String newKid = "k-" + LocalDateTime.ofInstant(now, ZoneId.systemDefault()).format(KID_FORMAT);

        try {
            // 1. 生成新 RSA 密钥对
            KeyPair keyPair = keyPairGenerator.generateKeyPair();

            // 2. 计算过渡期结束时间
            Instant overlapEnd = now.plus(java.time.Duration.ofDays(config.getOverlapDays()));

            // 3. 构造密钥条目并发布
            DualKeyManager.KeyEntry entry = new DualKeyManager.KeyEntry(
                    newKid, keyPair.getPrivate(), keyPair.getPublic(), now, null);
            dualKeyManager.publishActive(entry, previousKid == null ? null : overlapEnd);

            // 4. 持久化
            try {
                dualKeyManager.persist(entry, config.getKeyStorePath());
            } catch (Exception e) {
                log.warn("密钥持久化失败（不影响轮换）: {}", e.getMessage());
            }

            // 5. 记录审计事件
            KeyRotationEvent event = new KeyRotationEvent(
                    newKid, previousKid, now, overlapEnd, true, null);
            recordEvent(event);
            log.info("JWT 密钥轮换成功: {} → {}, 过渡期至 {} ({} 天)",
                    previousKid, newKid, overlapEnd, config.getOverlapDays());
            return event;
        } catch (Exception e) {
            KeyRotationEvent event = new KeyRotationEvent(
                    newKid, previousKid, now, null, false, e.getMessage());
            recordEvent(event);
            log.error("JWT 密钥轮换失败: {} → {}", previousKid, newKid, e);
            return event;
        }
    }

    /**
     * 记算当前 active 密钥的年龄（天）。
     *
     * @return 密钥年龄；无 active 密钥时返回 -1
     */
    public long activeKeyAgeDays() {
        DualKeyManager.KeyEntry active = dualKeyManager.getActive();
        if (active == null) {
            return -1;
        }
        long ageMillis = Instant.now().toEpochMilli() - active.getCreatedAt().toEpochMilli();
        return ageMillis / MILLIS_PER_DAY;
    }

    /**
     * 是否到达轮换阈值（active 密钥年龄 ≥ interval-days）。
     *
     * @return true 表示应轮换
     */
    public boolean isRotationDue() {
        return activeKeyAgeDays() >= config.getIntervalDays();
    }

    /**
     * 获取轮换事件历史（审计用）。
     *
     * @return 不可变事件列表副本
     */
    public List<KeyRotationEvent> getEventHistory() {
        synchronized (eventHistory) {
            return Collections.unmodifiableList(new ArrayList<>(eventHistory));
        }
    }

    /**
     * 记算距轮换阈值剩余天数（用于告警）。
     *
     * @return 剩余天数；负值表示已超期
     */
    public long daysUntilRotation() {
        return config.getIntervalDays() - activeKeyAgeDays();
    }

    /**

     * 记录审计事件（保留最近 100 条）。
     *
     * @param event 轮换事件
     */
    private void recordEvent(KeyRotationEvent event) {
        eventHistory.add(event);
        while (eventHistory.size() > 100) {
            eventHistory.remove(0);
        }
    }
}