package com.levango7.dataenginebdp.common.security.ratelimit;

/**
 * 速率限制策略（按路径前缀匹配的配额定义）。
 *
 * <p>以 record 形式表达"前缀 → 每分钟请求数 → 突发倍率"三元组：
 * 桶容量 = ratePerMinute × burstFactor，稳态补充速率 = ratePerMinute。</p>
 *
 * @param pathPrefix   路径前缀（如 {@code /api/v1/auth}）
 * @param ratePerMinute 每分钟稳态补充速率（即持续允许的 RPS×60）
 * @param burstFactor   突发倍率：桶容量相对稳态速率的倍数（1.0=无突发）
 */
public record RateLimitRule(String pathPrefix, int ratePerMinute, double burstFactor) {

    /** 桶容量（突发上限）。 */
    public int burstCapacity() {
        return (int) Math.ceil(ratePerMinute * burstFactor);
    }
}
