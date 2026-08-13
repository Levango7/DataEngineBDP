package com.levango7.dataenginebdp.finops.job;

import com.levango7.dataenginebdp.finops.config.MeteringRetentionConfig;
import com.levango7.dataenginebdp.finops.model.QueryMeteringRecord;
import com.levango7.dataenginebdp.finops.repository.QueryMeteringRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MeteringRetentionJob 单元测试（H2 内存库）。
 */
@DataJpaTest
@Import({MeteringRetentionConfig.class, MeteringRetentionJob.class})
class MeteringRetentionJobTest {

    @Autowired
    private QueryMeteringRepository meteringRepository;

    @Autowired
    private MeteringRetentionJob retentionJob;

    @Autowired
    private MeteringRetentionConfig config;

    private QueryMeteringRecord record(String tenantId, long ageDays) {
        return QueryMeteringRecord.builder()
                .tenantId(tenantId)
                .engine("trino")
                .bytesScanned(100L)
                .estimated(true)
                .clientRequestId("req-" + System.nanoTime())
                .createdAt(Instant.now().minus(ageDays, ChronoUnit.DAYS))
                .build();
    }

    @BeforeEach
    void setUp() {
        config.setRetentionDays(90);
        config.setCleanupEnabled(true);
    }

    @Test
    void cleanup_removesOnlyExpiredRecords() {
        meteringRepository.save(record("tenant_a", 1));    // 新鲜,保留
        meteringRepository.save(record("tenant_a", 120));  // 过期,删
        meteringRepository.save(record("tenant_b", 500));  // 过期,删

        retentionJob.cleanupExpiredRecords();

        assertThat(meteringRepository.count()).isEqualTo(1);
        assertThat(meteringRepository.findAll().get(0).getTenantId()).isEqualTo("tenant_a");
    }

    @Test
    void cleanup_keepsExactlyAtRetentionBoundary() {
        meteringRepository.save(record("tenant_a", 89));   // 早于 90 天 1 天,保留
        retentionJob.cleanupExpiredRecords();
        assertThat(meteringRepository.count()).isEqualTo(1);
    }

    @Test
    void cleanup_disabledSkipsDeletion() {
        config.setCleanupEnabled(false);
        meteringRepository.save(record("tenant_a", 500));
        retentionJob.cleanupExpiredRecords();
        assertThat(meteringRepository.count()).isEqualTo(1);
    }

    @Test
    void cleanup_emptyTableIsNoOp() {
        retentionJob.cleanupExpiredRecords();
        assertThat(meteringRepository.count()).isZero();
    }
}