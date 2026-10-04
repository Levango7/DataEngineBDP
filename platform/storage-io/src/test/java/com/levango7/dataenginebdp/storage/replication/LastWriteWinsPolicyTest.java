package com.levango7.dataenginebdp.storage.replication;

import com.levango7.dataenginebdp.storage.api.ObjectMetadata;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LWW 冲突解决策略单元测试（无需 Docker / 存储）。
 *
 * <p>依赖说明：被测类无外部依赖（纯函数），本测试不涉及 Mock 注入；
 * 依赖注入只发生在 {@link ObjectReplicator} 的构造器边界。
 */
class LastWriteWinsPolicyTest {

    private final LastWriteWinsPolicy policy = new LastWriteWinsPolicy();

    private static ObjectMetadata meta(Instant lastModified) {
        return ObjectMetadata.builder().key("k").size(1).lastModified(lastModified).build();
    }

    @Test
    void targetMissing_shouldCopy() {
        assertThat(policy.decide(meta(Instant.parse("2026-10-05T00:00:00Z")), null))
                .isEqualTo(ReplicationAction.COPY);
    }

    @Test
    void sourceNewer_shouldOverwrite() {
        assertThat(policy.decide(
                meta(Instant.parse("2026-10-05T00:00:10Z")),
                meta(Instant.parse("2026-10-05T00:00:00Z"))))
                .isEqualTo(ReplicationAction.OVERWRITE);
    }

    @Test
    void sameTimestamp_sourceWinsForIdempotency() {
        Instant t = Instant.parse("2026-10-05T00:00:00Z");
        assertThat(policy.decide(meta(t), meta(t))).isEqualTo(ReplicationAction.OVERWRITE);
    }

    @Test
    void targetNewer_shouldSkip() {
        assertThat(policy.decide(
                meta(Instant.parse("2026-10-05T00:00:00Z")),
                meta(Instant.parse("2026-10-05T00:00:10Z"))))
                .isEqualTo(ReplicationAction.SKIP);
    }

    @Test
    void missingTimestamp_sourceWins() {
        ObjectMetadata source = ObjectMetadata.builder().key("k").size(1).build();
        ObjectMetadata target = ObjectMetadata.builder().key("k").size(1).build();
        assertThat(policy.decide(source, target)).isEqualTo(ReplicationAction.OVERWRITE);
    }

    @Test
    void targetMissingTimestamp_sourceWins() {
        assertThat(policy.decide(
                meta(Instant.parse("2026-10-05T00:00:10Z")),
                ObjectMetadata.builder().key("k").size(1).build()))
                .isEqualTo(ReplicationAction.OVERWRITE);
    }

    @Test
    void sourceMissingTimestamp_sourceWins() {
        assertThat(policy.decide(
                ObjectMetadata.builder().key("k").size(1).build(),
                meta(Instant.parse("2026-10-05T00:00:00Z"))))
                .isEqualTo(ReplicationAction.OVERWRITE);
    }

    @Test
    void usableThroughConflictPolicyInterface() {
        ConflictPolicy contract = new LastWriteWinsPolicy();
        assertThat(contract.decide(meta(Instant.parse("2026-10-05T00:00:00Z")), null))
                .isEqualTo(ReplicationAction.COPY);
    }
}
