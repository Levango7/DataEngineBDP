package com.shuqing.bigdata.flinkcdc.exactlyonce;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ExactlyOnceConfig} 单元测试。
 *
 * @author shuqing-bigdata
 */
class ExactlyOnceConfigTest {

    @Nested
    @DisplayName("IdempotentStrategy 枚举")
    class StrategyTest {

        @Test
        @DisplayName("fromCode — 正确解析各策略")
        void fromCode_shouldParseAllStrategies() {
            assertThat(ExactlyOnceConfig.IdempotentStrategy.fromCode("primary-key"))
                    .isEqualTo(ExactlyOnceConfig.IdempotentStrategy.PRIMARY_KEY);
            assertThat(ExactlyOnceConfig.IdempotentStrategy.fromCode("version"))
                    .isEqualTo(ExactlyOnceConfig.IdempotentStrategy.VERSION);
            assertThat(ExactlyOnceConfig.IdempotentStrategy.fromCode("txn-lsn"))
                    .isEqualTo(ExactlyOnceConfig.IdempotentStrategy.TXN_LSN);
        }

        @Test
        @DisplayName("fromCode — 大小写不敏感")
        void fromCode_shouldBeCaseInsensitive() {
            assertThat(ExactlyOnceConfig.IdempotentStrategy.fromCode("PRIMARY-KEY"))
                    .isEqualTo(ExactlyOnceConfig.IdempotentStrategy.PRIMARY_KEY);
            assertThat(ExactlyOnceConfig.IdempotentStrategy.fromCode("Version"))
                    .isEqualTo(ExactlyOnceConfig.IdempotentStrategy.VERSION);
        }

        @Test
        @DisplayName("fromCode — 下划线转横线")
        void fromCode_shouldNormalizeUnderscore() {
            assertThat(ExactlyOnceConfig.IdempotentStrategy.fromCode("primary_key"))
                    .isEqualTo(ExactlyOnceConfig.IdempotentStrategy.PRIMARY_KEY);
            assertThat(ExactlyOnceConfig.IdempotentStrategy.fromCode("txn_lsn"))
                    .isEqualTo(ExactlyOnceConfig.IdempotentStrategy.TXN_LSN);
        }

        @Test
        @DisplayName("fromCode — 未知策略抛出异常")
        void fromCode_unknown_shouldThrow() {
            assertThatThrownBy(() -> ExactlyOnceConfig.IdempotentStrategy.fromCode("unknown"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("fromCode — null 抛出 NPE")
        void fromCode_null_shouldThrowNpe() {
            assertThatThrownBy(() -> ExactlyOnceConfig.IdempotentStrategy.fromCode(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("code — 返回正确编码")
        void code_shouldReturnCorrectCodes() {
            assertThat(ExactlyOnceConfig.IdempotentStrategy.PRIMARY_KEY.code()).isEqualTo("primary-key");
            assertThat(ExactlyOnceConfig.IdempotentStrategy.VERSION.code()).isEqualTo("version");
            assertThat(ExactlyOnceConfig.IdempotentStrategy.TXN_LSN.code()).isEqualTo("txn-lsn");
        }
    }

    @Nested
    @DisplayName("Builder 配置")
    class BuilderTest {

        @Test
        @DisplayName("默认配置 — 正确")
        void defaults() {
            ExactlyOnceConfig config = ExactlyOnceConfig.builder()
                    .transactionalIdPrefix("cdc-tx-")
                    .primaryKeyColumns("id")
                    .build();

            assertThat(config.getCheckpointInterval()).isEqualTo(Duration.ofSeconds(60));
            assertThat(config.getCheckpointTimeout()).isEqualTo(Duration.ofMinutes(10));
            assertThat(config.getMinPauseBetweenCheckpoints()).isEqualTo(Duration.ofMillis(500));
            assertThat(config.getTransactionTimeout()).isEqualTo(Duration.ofMinutes(15));
            assertThat(config.getTransactionalIdPrefix()).isEqualTo("cdc-tx-");
            assertThat(config.getIdempotentStrategy()).isEqualTo(ExactlyOnceConfig.IdempotentStrategy.PRIMARY_KEY);
            assertThat(config.isUnalignedCheckpointsEnabled()).isFalse();
            assertThat(config.getMaxRetainedCheckpoints()).isEqualTo(3);
        }

        @Test
        @DisplayName("自定义配置 — 正确生效")
        void customConfig() {
            ExactlyOnceConfig config = ExactlyOnceConfig.builder()
                    .checkpointInterval(Duration.ofSeconds(30))
                    .checkpointTimeout(Duration.ofMinutes(5))
                    .minPauseBetweenCheckpoints(Duration.ofSeconds(1))
                    .transactionTimeout(Duration.ofMinutes(10))
                    .transactionalIdPrefix("my-tx-")
                    .idempotentStrategy(ExactlyOnceConfig.IdempotentStrategy.VERSION)
                    .versionColumn("version")
                    .enableUnalignedCheckpoints()
                    .maxRetainedCheckpoints(5)
                    .kafkaProperty("acks", "all")
                    .build();

            assertThat(config.getCheckpointInterval()).isEqualTo(Duration.ofSeconds(30));
            assertThat(config.getCheckpointTimeout()).isEqualTo(Duration.ofMinutes(5));
            assertThat(config.getMinPauseBetweenCheckpoints()).isEqualTo(Duration.ofSeconds(1));
            assertThat(config.getTransactionTimeout()).isEqualTo(Duration.ofMinutes(10));
            assertThat(config.getTransactionalIdPrefix()).isEqualTo("my-tx-");
            assertThat(config.getIdempotentStrategy()).isEqualTo(ExactlyOnceConfig.IdempotentStrategy.VERSION);
            assertThat(config.getVersionColumn()).isEqualTo("version");
            assertThat(config.isUnalignedCheckpointsEnabled()).isTrue();
            assertThat(config.getMaxRetainedCheckpoints()).isEqualTo(5);
            assertThat(config.getKafkaProducerProperties().getProperty("acks")).isEqualTo("all");
        }

        @Test
        @DisplayName("null 参数 — 抛出 NPE")
        void nullParams() {
            assertThatThrownBy(() -> ExactlyOnceConfig.builder().checkpointInterval(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ExactlyOnceConfig.builder().transactionalIdPrefix(null))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> ExactlyOnceConfig.builder().idempotentStrategy(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("配置校验")
    class ValidationTest {

        @Test
        @DisplayName("transactionalIdPrefix 为空 — 抛出异常")
        void emptyTransactionalIdPrefix() {
            ExactlyOnceConfig config = ExactlyOnceConfig.builder()
                    .primaryKeyColumns("id")
                    .build();
            assertThatThrownBy(config::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transactionalIdPrefix");
        }

        @Test
        @DisplayName("transactionTimeout <= checkpointInterval — 抛出异常")
        void transactionTimeoutNotGreaterThanInterval() {
            ExactlyOnceConfig config = ExactlyOnceConfig.builder()
                    .checkpointInterval(Duration.ofMinutes(10))
                    .transactionTimeout(Duration.ofMinutes(10))
                    .transactionalIdPrefix("tx-")
                    .primaryKeyColumns("id")
                    .build();
            assertThatThrownBy(config::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("transactionTimeout");
        }

        @Test
        @DisplayName("PRIMARY_KEY 策略未指定 primaryKeyColumns — 抛出异常")
        void primaryKeyStrategyWithoutColumns() {
            ExactlyOnceConfig config = ExactlyOnceConfig.builder()
                    .transactionalIdPrefix("tx-")
                    .idempotentStrategy(ExactlyOnceConfig.IdempotentStrategy.PRIMARY_KEY)
                    .build();
            assertThatThrownBy(config::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("primaryKeyColumns");
        }

        @Test
        @DisplayName("VERSION 策略未指定 versionColumn — 抛出异常")
        void versionStrategyWithoutColumn() {
            ExactlyOnceConfig config = ExactlyOnceConfig.builder()
                    .transactionalIdPrefix("tx-")
                    .idempotentStrategy(ExactlyOnceConfig.IdempotentStrategy.VERSION)
                    .build();
            assertThatThrownBy(config::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("versionColumn");
        }

        @Test
        @DisplayName("maxRetainedCheckpoints < 1 — 抛出异常")
        void invalidMaxRetainedCheckpoints() {
            ExactlyOnceConfig config = ExactlyOnceConfig.builder()
                    .transactionalIdPrefix("tx-")
                    .primaryKeyColumns("id")
                    .maxRetainedCheckpoints(0)
                    .build();
            assertThatThrownBy(config::validate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("maxRetainedCheckpoints");
        }

        @Test
        @DisplayName("合法配置 — 校验通过")
        void validConfig() {
            ExactlyOnceConfig config = ExactlyOnceConfig.builder()
                    .transactionalIdPrefix("tx-")
                    .primaryKeyColumns("id")
                    .build();
            config.validate();
        }

        @Test
        @DisplayName("TXN_LSN 策略 — 校验通过")
        void txnLsnStrategy() {
            ExactlyOnceConfig config = ExactlyOnceConfig.builder()
                    .transactionalIdPrefix("tx-")
                    .idempotentStrategy(ExactlyOnceConfig.IdempotentStrategy.TXN_LSN)
                    .build();
            config.validate();
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTest {

        @Test
        @DisplayName("包含关键配置信息")
        void containsKeyConfig() {
            ExactlyOnceConfig config = ExactlyOnceConfig.builder()
                    .transactionalIdPrefix("cdc-tx-")
                    .primaryKeyColumns("id")
                    .build();

            String str = config.toString();
            assertThat(str).contains("cdc-tx-")
                    .contains("PRIMARY_KEY")
                    .contains("maxRetainedCheckpoints=3");
        }
    }
}