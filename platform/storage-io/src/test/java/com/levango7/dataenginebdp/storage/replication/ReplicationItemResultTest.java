package com.levango7.dataenginebdp.storage.replication;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ReplicationItemResult 单元测试：构建、缺省值、值语义相等。
 *
 * <p>依赖说明：被测类无外部依赖（值对象），本测试不涉及 Mock 注入。
 */
class ReplicationItemResultTest {

    @Test
    void builder_populatesAllFields() {
        ReplicationItemResult item = ReplicationItemResult.builder()
                .key("_system/x/a.bin")
                .action(ReplicationAction.OVERWRITE)
                .sourceSize(2048L)
                .targetSize(2048L)
                .sourceSha256("aa")
                .targetSha256("aa")
                .verified(true)
                .elapsedMs(12L)
                .build();

        assertThat(item.getKey()).isEqualTo("_system/x/a.bin");
        assertThat(item.getAction()).isEqualTo(ReplicationAction.OVERWRITE);
        assertThat(item.getSourceSize()).isEqualTo(2048L);
        assertThat(item.getTargetSize()).isEqualTo(2048L);
        assertThat(item.getSourceSha256()).isEqualTo("aa");
        assertThat(item.getTargetSha256()).isEqualTo("aa");
        assertThat(item.isVerified()).isTrue();
        assertThat(item.getElapsedMs()).isEqualTo(12L);
        assertThat(item.getError()).isNull();
    }

    @Test
    void defaults_areZeroFalseAndNull() {
        ReplicationItemResult item = ReplicationItemResult.builder().key("k").build();

        assertThat(item.getAction()).isNull();
        assertThat(item.getSourceSize()).isZero();
        assertThat(item.getTargetSize()).isZero();
        assertThat(item.isVerified()).isFalse();
        assertThat(item.getElapsedMs()).isZero();
        assertThat(item.getSourceSha256()).isNull();
        assertThat(item.getError()).isNull();
    }

    @Test
    void equalsAndHashCode_byValue() {
        ReplicationItemResult a = ReplicationItemResult.builder()
                .key("k").action(ReplicationAction.SKIP).build();
        ReplicationItemResult b = ReplicationItemResult.builder()
                .key("k").action(ReplicationAction.SKIP).build();
        ReplicationItemResult c = ReplicationItemResult.builder()
                .key("k").action(ReplicationAction.FAILED).build();

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
    }
}
