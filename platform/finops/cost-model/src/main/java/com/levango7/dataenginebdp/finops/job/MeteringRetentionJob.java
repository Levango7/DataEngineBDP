package com.levango7.dataenginebdp.finops.job;

import com.levango7.dataenginebdp.finops.config.MeteringRetentionConfig;
import com.levango7.dataenginebdp.finops.repository.QueryMeteringRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * 计量记录保留期清理任务。
 *
 * <p>每日清理超过保留期的 query_metering 明细，防止表无限膨胀；
 * 只删明细，不碰聚合账单（账单历史保留，支撑审计与回看）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MeteringRetentionJob {

    private final QueryMeteringRepository meteringRepository;
    private final MeteringRetentionConfig config;

    /**
     * 每日清理一次（固定延迟，可配置）。
     */
    @Scheduled(initialDelayString = "${shuqing.finops.metering.cleanup-initial-delay-ms:3600000}",
            fixedDelayString = "${shuqing.finops.metering.cleanup-interval-ms:86400000}")
    @Transactional
    public void cleanupExpiredRecords() {
        if (!config.isCleanupEnabled()) {
            log.info("计量保留期清理已禁用（cleanupEnabled=false），跳过");
            return;
        }
        Instant cutoff = Instant.now().minus(config.getRetentionDays(), ChronoUnit.DAYS);
        int deleted = meteringRepository.deleteAllBeforeCutoff(cutoff);
        if (deleted > 0) {
            log.info("计量保留期清理完成: cutoff={}, 删除 {} 条", cutoff, deleted);
        }
    }
}