package com.levango7.dataenginebdp.encaps.security.rotation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * JWT 密钥轮换配置。
 *
 * <p>对应配置前缀 {@code security.jwt.rotation}，在 {@code application.yml} 中配置：</p>
 * <pre>
 * security:
 *   jwt:
 *     rotation:
 *       enabled: false          # 是否启用自动轮换（默认关闭，生产环境开启）
 *       interval-days: 90       # 轮换间隔（天），默认 90
 *       overlap-days: 7         # 新旧密钥并行验签过渡期（天），默认 7
 *       key-store-path: ~/.dataenginebdp/keys   # 密钥持久化目录
 * </pre>
 *
 * <p>设计约束（见 {@code docs/JWT-KEY-ROTATION-GUIDE.md}）：</p>
 * <ul>
 *   <li>默认 {@code enabled=false}，不影响现有 HMAC/OIDC 验签链路；</li>
 *   <li>过渡期 ≥ 最长 token TTL（本平台 refresh_token TTL=24h，故 overlap-days 至少 1）；</li>
 *   <li>轮换周期 ≤ 90 天，满足等保三级与 JR/T 0071-2012 要求。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "security.jwt.rotation")
public class KeyRotationConfig {

    /** 是否启用自动轮换（默认关闭，避免影响现有功能）。 */
    private boolean enabled = false;

    /** 轮换间隔（天），默认 90 天（合规上限）。 */
    private int intervalDays = 90;

    /** 新旧密钥并行验签过渡期（天），默认 7 天。过渡期内旧密钥仍可验签，保证零停机。 */
    private int overlapDays = 7;

    /** 密钥持久化目录路径；轮换后的密钥对以 PEM 文件落盘到此目录。 */
    private String keyStorePath;

    /**
     * 是否启用自动轮换。
     *
     * @return true 表示启用
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 设置是否启用自动轮换。
     *
     * @param enabled 是否启用
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * 轮换间隔（天）。
     *
     * @return 间隔天数
     */
    public int getIntervalDays() {
        return intervalDays;
    }

    /**
     * 设置轮换间隔（天）。
     *
     * @param intervalDays 间隔天数
     */
    public void setIntervalDays(int intervalDays) {
        this.intervalDays = intervalDays;
    }

    /**
     * 过渡期（天）。
     *
     * @return 过渡期天数
     */
    public int getOverlapDays() {
        return overlapDays;
    }

    /**
     * 设置过渡期（天）。
     *
     * @param overlapDays 过渡期天数
     */
    public void setOverlapDays(int overlapDays) {
        this.overlapDays = overlapDays;
    }

    /**
     * 密钥持久化目录路径。
     *
     * @return 目录路径；未配置时返回 null
     */
    public String getKeyStorePath() {
        return keyStorePath;
    }

    /**
     * 设置密钥持久化目录路径。
     *
     * @param keyStorePath 目录路径
     */
    public void setKeyStorePath(String keyStorePath) {
        this.keyStorePath = keyStorePath;
    }
}