package com.levango7.dataenginebdp.finops.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 计量保留期配置。
 *
 * <p>控制 query_metering 明细记录的清理策略：
 * 保留期内不可删（支撑月度滚账与审计），超过保留期由
 * {@link com.levango7.dataenginebdp.finops.job.MeteringRetentionJob} 每日清理。
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "shuqing.finops.metering")
public class MeteringRetentionConfig {

    /** 是否启用保留期清理（默认启用）。 */
    private boolean cleanupEnabled = true;

    /** 计量明细保留天数（默认 90 天；配合月度账单周期足够审计）。 */
    private int retentionDays = 90;

    /** 清理初始延迟（毫秒，默认 1 小时，避免启动瞬间抢资源）。 */
    private long cleanupInitialDelayMs = 3600_000L;

    /** 清理固定间隔（毫秒，默认每 24 小时）。 */
    private long cleanupIntervalMs = 24 * 3600_000L;

    /** 每批次删除条数上限（分批防长事务）。 */
    private int cleanupBatchSize = 1000;
}