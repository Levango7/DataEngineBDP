package com.levango7.dataenginebdp.storage.replication;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReplicationReport 单元测试：builder 缺省值（mode=real / items 非空）与 isAllVerified 真值表。
 *
 * <p>依赖说明：被测类无外部依赖（值对象 + 派生方法），本测试不涉及 Mock 注入。
 */
class ReplicationReportTest {

    @Test
    void builder_defaults_modeReal_andItemsEmpty() {
        ReplicationReport report = ReplicationReport.builder().build();

        assertThat(report.getMode()).isEqualTo("real");
        assertThat(report.getItems()).isNotNull().isEmpty();
        assertThat(report.getSourceEndpoint()).isNull();
        assertThat(report.getTotalBytes()).isZero();
    }

    @Test
    void allVerified_trueWhenNoFailureAndAllWrittenVerified() {
        ReplicationReport report = ReplicationReport.builder()
                .copied(1).overwritten(1).skipped(1).failed(0).verified(2)
                .build();

        assertThat(report.isAllVerified()).isTrue();
    }

    @Test
    void allVerified_falseWhenAnyFailure() {
        ReplicationReport report = ReplicationReport.builder()
                .copied(1).overwritten(0).skipped(0).failed(1).verified(1)
                .build();

        assertThat(report.isAllVerified()).isFalse();
    }

    @Test
    void allVerified_falseWhenWrittenButNotAllVerified() {
        ReplicationReport report = ReplicationReport.builder()
                .copied(2).overwritten(0).skipped(0).failed(0).verified(1)
                .build();

        assertThat(report.isAllVerified()).isFalse();
    }

    @Test
    void allVerified_trueWhenOnlySkipped() {
        // 幂等复跑：无写入、无失败 → 视为通过
        ReplicationReport report = ReplicationReport.builder()
                .copied(0).overwritten(0).skipped(3).failed(0).verified(0)
                .build();

        assertThat(report.isAllVerified()).isTrue();
    }

    @Test
    void settersAndItems_areMutable() {
        ReplicationReport report = ReplicationReport.builder()
                .startedAt(Instant.parse("2026-10-05T00:00:00Z"))
                .finishedAt(Instant.parse("2026-10-05T00:00:01Z"))
                .build();
        report.setItems(List.of(ReplicationItemResult.builder().key("k").build()));
        report.setTotalBytes(128L);

        assertThat(report.getItems()).hasSize(1);
        assertThat(report.getTotalBytes()).isEqualTo(128L);
        assertThat(report.getFinishedAt()).isAfter(report.getStartedAt());
    }
}
