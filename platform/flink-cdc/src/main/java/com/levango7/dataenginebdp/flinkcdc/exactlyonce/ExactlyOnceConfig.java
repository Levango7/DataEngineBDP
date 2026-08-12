package com.levango7.dataenginebdp.flinkcdc.exactlyonce;

import java.io.Serializable;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;

/**
 * Exactly-Once 语义保障配置，集中描述 Flink checkpoint、Kafka 事务与幂等写入相关参数。
 *
 * <p>本配置是 {@link TwoPhaseCommitCoordinator}、{@link CheckpointBarrierHandler}、
 * {@link IdempotentWriter} 等组件的统一参数来源，确保端到端 exactly-once 语义可调可控。</p>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * ExactlyOnceConfig config = ExactlyOnceConfig.builder()
 *     .checkpointInterval(Duration.ofSeconds(60))
 *     .checkpointTimeout(Duration.ofMinutes(5))
 *     .transactionTimeout(Duration.ofMinutes(15))
 *     .transactionalIdPrefix("cdc-eo-tx-")
 *     .idempotentStrategy(IdempotentStrategy.PRIMARY_KEY)
 *     .primaryKeyColumns("id")
 *     .build();
 * }</pre>
 *
 * <p><b>exactly-once 保障要点：</b></p>
 * <ul>
 *   <li>Flink checkpoint 周期性 barrier 对齐，将状态快照与下游事务提交原子绑定</li>
 *   <li>Kafka 事务 Producer（transactional.id + initTransactions + commitTransaction）</li>
 *   <li>幂等写入器基于主键/版本号去重，故障恢复后重放无副作用</li>
 *   <li>两阶段提交：preCommit 阶段持久化事务句柄，commit 阶段原子提交</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public final class ExactlyOnceConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 幂等去重策略枚举。
     */
    public enum IdempotentStrategy {
        /** 基于主键去重：相同主键的记录只保留最新版本。 */
        PRIMARY_KEY("primary-key"),
        /** 基于版本号去重：版本号单调递增，仅接受更高版本。 */
        VERSION("version"),
        /** 基于事务 ID + LSN 去重：适用于 CDC 场景，按 Binlog 位点去重。 */
        TXN_LSN("txn-lsn");

        private final String code;

        IdempotentStrategy(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }

        /**
         * 根据编码解析为枚举值（大小写不敏感，支持 kebab-case / snake_case）。
         *
         * @param code 编码
         * @return 枚举值
         * @throws IllegalArgumentException 编码不被识别
         */
        public static IdempotentStrategy fromCode(String code) {
            Objects.requireNonNull(code, "idempotent strategy code 不能为 null");
            String normalized = code.toLowerCase().replace("_", "-");
            for (IdempotentStrategy s : values()) {
                if (s.code.equals(normalized)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知的幂等去重策略: " + code);
        }
    }

    /** Flink checkpoint 间隔（默认 60s）。 */
    private final Duration checkpointInterval;
    /** Flink checkpoint 超时（默认 10min）。 */
    private final Duration checkpointTimeout;
    /** 两次 checkpoint 之间最小间隔（默认 500ms，避免 barrier 排队）。 */
    private final Duration minPauseBetweenCheckpoints;
    /** Kafka 事务超时（默认 15min，需大于 checkpoint 间隔）。 */
    private final Duration transactionTimeout;
    /** Kafka 事务 ID 前缀（必填，需全局唯一以避免 zombie 事务冲突）。 */
    private final String transactionalIdPrefix;
    /** 幂等去重策略。 */
    private final IdempotentStrategy idempotentStrategy;
    /** 主键列名（逗号分隔，PRIMARY_KEY 策略必需）。 */
    private final String primaryKeyColumns;
    /** 版本号列名（VERSION 策略必需）。 */
    private final String versionColumn;
    /** 是否容忍未对齐的 barrier（unaligned checkpoint，加速反压场景）。 */
    private final boolean unalignedCheckpointsEnabled;
    /** 同时保留的最大 checkpoint 数（用于故障恢复回滚）。 */
    private final int maxRetainedCheckpoints;
    /** Kafka Producer 额外属性。 */
    private final Properties kafkaProducerProperties;

    private ExactlyOnceConfig(Duration checkpointInterval, Duration checkpointTimeout,
                              Duration minPauseBetweenCheckpoints, Duration transactionTimeout,
                              String transactionalIdPrefix, IdempotentStrategy idempotentStrategy,
                              String primaryKeyColumns, String versionColumn,
                              boolean unalignedCheckpointsEnabled, int maxRetainedCheckpoints,
                              Properties kafkaProducerProperties) {
        this.checkpointInterval = checkpointInterval;
        this.checkpointTimeout = checkpointTimeout;
        this.minPauseBetweenCheckpoints = minPauseBetweenCheckpoints;
        this.transactionTimeout = transactionTimeout;
        this.transactionalIdPrefix = transactionalIdPrefix;
        this.idempotentStrategy = idempotentStrategy;
        this.primaryKeyColumns = primaryKeyColumns;
        this.versionColumn = versionColumn;
        this.unalignedCheckpointsEnabled = unalignedCheckpointsEnabled;
        this.maxRetainedCheckpoints = maxRetainedCheckpoints;
        this.kafkaProducerProperties = kafkaProducerProperties;
    }

    /**
     * 校验配置完整性。
     *
     * @throws IllegalStateException 配置不合法
     */
    public void validate() {
        if (transactionalIdPrefix == null || transactionalIdPrefix.isEmpty()) {
            throw new IllegalStateException("transactionalIdPrefix 不能为空（exactly-once 必需）");
        }
        if (transactionTimeout != null && checkpointInterval != null
                && transactionTimeout.toMillis() <= checkpointInterval.toMillis()) {
            throw new IllegalStateException(
                    "transactionTimeout 必须大于 checkpointInterval，否则事务可能在 checkpoint 前超时");
        }
        if (idempotentStrategy == IdempotentStrategy.PRIMARY_KEY
                && (primaryKeyColumns == null || primaryKeyColumns.isEmpty())) {
            throw new IllegalStateException("PRIMARY_KEY 策略需要指定 primaryKeyColumns");
        }
        if (idempotentStrategy == IdempotentStrategy.VERSION
                && (versionColumn == null || versionColumn.isEmpty())) {
            throw new IllegalStateException("VERSION 策略需要指定 versionColumn");
        }
        if (maxRetainedCheckpoints < 1) {
            throw new IllegalStateException("maxRetainedCheckpoints 必须 ≥ 1");
        }
    }

    // ===== Getter =====

    public Duration getCheckpointInterval() {
        return checkpointInterval;
    }

    public Duration getCheckpointTimeout() {
        return checkpointTimeout;
    }

    public Duration getMinPauseBetweenCheckpoints() {
        return minPauseBetweenCheckpoints;
    }

    public Duration getTransactionTimeout() {
        return transactionTimeout;
    }

    public String getTransactionalIdPrefix() {
        return transactionalIdPrefix;
    }

    public IdempotentStrategy getIdempotentStrategy() {
        return idempotentStrategy;
    }

    public String getPrimaryKeyColumns() {
        return primaryKeyColumns;
    }

    public String getVersionColumn() {
        return versionColumn;
    }

    public boolean isUnalignedCheckpointsEnabled() {
        return unalignedCheckpointsEnabled;
    }

    public int getMaxRetainedCheckpoints() {
        return maxRetainedCheckpoints;
    }

    public Properties getKafkaProducerProperties() {
        return kafkaProducerProperties;
    }

    @Override
    public String toString() {
        return "ExactlyOnceConfig{checkpointInterval=" + checkpointInterval
                + ", checkpointTimeout=" + checkpointTimeout
                + ", transactionTimeout=" + transactionTimeout
                + ", transactionalIdPrefix='" + transactionalIdPrefix + '\''
                + ", idempotentStrategy=" + idempotentStrategy
                + ", primaryKeyColumns='" + primaryKeyColumns + '\''
                + ", versionColumn='" + versionColumn + '\''
                + ", unalignedCheckpoints=" + unalignedCheckpointsEnabled
                + ", maxRetainedCheckpoints=" + maxRetainedCheckpoints + '}';
    }

    /**
     * 创建 Builder。
     *
     * @return Builder 实例
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * ExactlyOnceConfig 构造器，链式配置。
     */
    public static final class Builder {
        private Duration checkpointInterval = Duration.ofSeconds(60);
        private Duration checkpointTimeout = Duration.ofMinutes(10);
        private Duration minPauseBetweenCheckpoints = Duration.ofMillis(500);
        private Duration transactionTimeout = Duration.ofMinutes(15);
        private String transactionalIdPrefix;
        private IdempotentStrategy idempotentStrategy = IdempotentStrategy.PRIMARY_KEY;
        private String primaryKeyColumns;
        private String versionColumn;
        private boolean unalignedCheckpointsEnabled = false;
        private int maxRetainedCheckpoints = 3;
        private final Properties kafkaProducerProperties = new Properties();

        /** Flink checkpoint 间隔。 */
        public Builder checkpointInterval(Duration interval) {
            this.checkpointInterval = Objects.requireNonNull(interval);
            return this;
        }

        /** Flink checkpoint 超时。 */
        public Builder checkpointTimeout(Duration timeout) {
            this.checkpointTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        /** 两次 checkpoint 之间最小间隔。 */
        public Builder minPauseBetweenCheckpoints(Duration pause) {
            this.minPauseBetweenCheckpoints = Objects.requireNonNull(pause);
            return this;
        }

        /** Kafka 事务超时。 */
        public Builder transactionTimeout(Duration timeout) {
            this.transactionTimeout = Objects.requireNonNull(timeout);
            return this;
        }

        /** Kafka 事务 ID 前缀（exactly-once 必需）。 */
        public Builder transactionalIdPrefix(String prefix) {
            this.transactionalIdPrefix = Objects.requireNonNull(prefix);
            return this;
        }

        /** 幂等去重策略。 */
        public Builder idempotentStrategy(IdempotentStrategy strategy) {
            this.idempotentStrategy = Objects.requireNonNull(strategy);
            return this;
        }

        /** 主键列名（逗号分隔）。 */
        public Builder primaryKeyColumns(String columns) {
            this.primaryKeyColumns = columns;
            return this;
        }

        /** 版本号列名。 */
        public Builder versionColumn(String column) {
            this.versionColumn = column;
            return this;
        }

        /** 启用 unaligned checkpoint。 */
        public Builder enableUnalignedCheckpoints() {
            this.unalignedCheckpointsEnabled = true;
            return this;
        }

        /** 最大保留 checkpoint 数。 */
        public Builder maxRetainedCheckpoints(int n) {
            this.maxRetainedCheckpoints = n;
            return this;
        }

        /** 添加 Kafka Producer 属性。 */
        public Builder kafkaProperty(String key, String value) {
            this.kafkaProducerProperties.setProperty(key, value);
            return this;
        }

        /** 构建 ExactlyOnceConfig。 */
        public ExactlyOnceConfig build() {
            return new ExactlyOnceConfig(
                    checkpointInterval, checkpointTimeout, minPauseBetweenCheckpoints,
                    transactionTimeout, transactionalIdPrefix, idempotentStrategy,
                    primaryKeyColumns, versionColumn, unalignedCheckpointsEnabled,
                    maxRetainedCheckpoints, new Properties(kafkaProducerProperties));
        }
    }
}