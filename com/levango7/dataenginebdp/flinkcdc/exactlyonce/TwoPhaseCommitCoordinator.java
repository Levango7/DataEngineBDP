package com.shuqing.bigdata.flinkcdc.exactlyonce;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 两阶段提交协调器，编排 {@link TransactionalSink} 与 {@link IdempotentWriter} 完成端到端 exactly-once 提交。
 *
 * <p><b>两阶段提交协议：</b></p>
 * <pre>{@code
 * 阶段一：preCommit（prepare）
 *   1. 对当前事务调用 TransactionalSink.preCommit（持久化事务句柄）
 *   2. 将事务句柄写入外部存储（如 Kafka 事务日志，确保故障后可恢复）
 *   3. 标记事务为 PRE_COMMITTED，此后拒绝新写入
 *
 * 阶段二：commit（commit）
 *   1. 对已 preCommit 的事务调用 TransactionalSink.commit（原子提交）
 *   2. 标记事务为 COMMITTED
 *   3. 通知 IdempotentWriter.markCommitted 更新去重基线
 *
 * 故障恢复：recover
 *   1. 枚举所有 PRE_COMMITTED 但未 COMMITTED 的事务
 *   2. 对每个事务调用 TransactionalSink.recover（完成提交或回滚）
 *   3. 重放未提交的缓冲记录
 * }</pre>
 *
 * <p><b>exactly-once 保障原理：</b></p>
 * <ul>
 *   <li><b>无重复</b>：preCommit 持久化事务句柄后，即使故障重放，commit 幂等性保证不会重复提交；
 *       IdempotentWriter 在重放时基于主键/版本号去重，丢弃旧记录</li>
 *   <li><b>无丢失</b>：preCommit 已持久化的事务在 recover 时必然被 commit；
 *       未 preCommit 的记录从 checkpoint 状态恢复并重新提交</li>
 *   <li><b>原子性</b>：事务内所有记录要么全部可见（commit），要么全部不可见（abort）</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>{@code
 * TwoPhaseCommitCoordinator coordinator = TwoPhaseCommitCoordinator.builder()
 *     .config(exactlyOnceConfig)
 *     .sink(transactionalKafkaSink)
 *     .writer(idempotentWriter)
 *     .build();
 *
 * // 数据流：buffer → preCommit → commit
 * coordinator.buffer(record1);
 * coordinator.buffer(record2);
 * coordinator.preCommit(checkpointId);
 * coordinator.commit(checkpointId);
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public final class TwoPhaseCommitCoordinator implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(TwoPhaseCommitCoordinator.class);

    private final ExactlyOnceConfig config;
    private final TransactionalSink sink;
    private final IdempotentWriter writer;

    /** 当前活跃事务 ID（null 表示无活跃事务）。 */
    private String currentTxnId;
    /** 已 preCommit 但未 commit 的事务：txnId → 事务元数据。 */
    private final Map<String, TransactionMetadata> pendingTransactions = new LinkedHashMap<>();
    /** 当前事务的缓冲记录（preCommit 后清空，等待下次 checkpoint）。 */
    private final List<ChangeRecord> currentBuffer = new ArrayList<>();
    /** 已完成的事务数（统计）。 */
    private long committedTxnCount = 0L;
    /** 已中止的事务数（统计）。 */
    private long abortedTxnCount = 0L;

    private TwoPhaseCommitCoordinator(ExactlyOnceConfig config,
                                      TransactionalSink sink,
                                      IdempotentWriter writer) {
        this.config = config;
        this.sink = sink;
        this.writer = writer;
    }

    /**
     * 缓冲一条记录到当前事务。
     *
     * @param record 变更记录
     * @throws IllegalStateException 当前事务已 preCommit
     */
    public synchronized void buffer(ChangeRecord record) {
        Objects.requireNonNull(record, "record 不能为 null");
        if (currentTxnId != null
                && sink.statusOf(currentTxnId) == TransactionalSink.TransactionStatus.PRE_COMMITTED) {
            throw new IllegalStateException("事务 " + currentTxnId + " 已 preCommit，不可再写入");
        }
        currentBuffer.add(record);
    }

    /**
     * 缓冲多条记录。
     *
     * @param records 记录集合
     */
    public synchronized void bufferAll(Collection<ChangeRecord> records) {
        Objects.requireNonNull(records, "records 不能为 null");
        for (ChangeRecord r : records) {
            buffer(r);
        }
    }

    /**
     * 阶段一：预提交。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>生成事务 ID（prefix + checkpointId）</li>
     *   <li>调用 {@link TransactionalSink#beginTransaction} 开启事务</li>
     *   <li>通过 {@link IdempotentWriter} 去重后写入 Sink</li>
     *   <li>调用 {@link TransactionalSink#preCommit} 持久化事务句柄</li>
     *   <li>记录事务元数据，标记 PRE_COMMITTED</li>
     * </ol>
     *
     * @param checkpointId Flink checkpoint ID
     * @throws TransactionalSink.TransactionException preCommit 失败
     */
    public synchronized void preCommit(long checkpointId) throws TransactionalSink.TransactionException {
        String txnId = generateTxnId(checkpointId);
        log.info("preCommit 开始: checkpointId={}, txnId={}", checkpointId, txnId);

        // 1. 开启事务
        sink.beginTransaction(txnId);
        currentTxnId = txnId;

        // 2. 幂等去重：逐条写入 writer 进行去重，然后取出有效记录
        for (ChangeRecord record : currentBuffer) {
            writer.write(record);
        }
        List<ChangeRecord> effective = writer.drainPending();
        if (!effective.isEmpty()) {
            sink.write(effective, txnId);
        }

        // 3. preCommit
        sink.preCommit(txnId);

        // 4. 记录事务元数据
        pendingTransactions.put(txnId, new TransactionMetadata(
                txnId, checkpointId, System.currentTimeMillis(),
                effective.size(),
                TransactionalSink.TransactionStatus.PRE_COMMITTED));

        log.info("preCommit 完成: txnId={}, effectiveRecords={}", txnId, effective.size());
    }

    /**
     * 阶段二：提交。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>查找 checkpointId 对应的事务</li>
     *   <li>调用 {@link TransactionalSink#commit} 原子提交</li>
     *   <li>通知 {@link IdempotentWriter#markCommitted} 更新去重基线</li>
     *   <li>清空缓冲，标记 COMMITTED</li>
     * </ol>
     *
     * @param checkpointId Flink checkpoint ID
     * @throws TransactionalSink.TransactionException commit 失败
     */
    public synchronized void commit(long checkpointId) throws TransactionalSink.TransactionException {
        String txnId = generateTxnId(checkpointId);
        TransactionMetadata metadata = pendingTransactions.get(txnId);

        if (metadata == null) {
            log.warn("commit: 事务 {} 不存在（可能已 commit 或从未 preCommit），跳过", txnId);
            return;
        }

        if (metadata.status == TransactionalSink.TransactionStatus.COMMITTED) {
            log.debug("commit: 事务 {} 已提交，幂等跳过", txnId);
            return;
        }

        log.info("commit 开始: txnId={}", txnId);
        sink.commit(txnId);
        writer.markCommitted();

        metadata.status = TransactionalSink.TransactionStatus.COMMITTED;
        pendingTransactions.remove(txnId);
        currentBuffer.clear();
        currentTxnId = null;
        committedTxnCount++;

        log.info("commit 完成: txnId={}", txnId);
    }

    /**
     * 中止事务。
     *
     * @param checkpointId Flink checkpoint ID
     * @throws TransactionalSink.TransactionException abort 失败
     */
    public synchronized void abort(long checkpointId) throws TransactionalSink.TransactionException {
        String txnId = generateTxnId(checkpointId);
        TransactionMetadata metadata = pendingTransactions.get(txnId);

        if (metadata == null) {
            log.warn("abort: 事务 {} 不存在，跳过", txnId);
            return;
        }

        if (metadata.status == TransactionalSink.TransactionStatus.ABORTED) {
            log.debug("abort: 事务 {} 已中止，幂等跳过", txnId);
            return;
        }

        log.info("abort 开始: txnId={}", txnId);
        sink.abort(txnId);
        writer.rollback();

        metadata.status = TransactionalSink.TransactionStatus.ABORTED;
        pendingTransactions.remove(txnId);
        currentBuffer.clear();
        currentTxnId = null;
        abortedTxnCount++;

        log.info("abort 完成: txnId={}", txnId);
    }

    /**
     * 故障恢复：完成未决事务并重放缓冲记录。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>对每个未决事务调用 {@link TransactionalSink#recover}</li>
     *   <li>对恢复的缓冲记录重新加入缓冲（等待下次 checkpoint）</li>
     * </ol>
     *
     * @param restoredRecords      从 checkpoint 状态恢复的缓冲记录
     * @param lastCheckpointId     最近完成的 checkpoint ID
     * @throws TransactionalSink.TransactionException recover 失败
     */
    public synchronized void recover(List<ChangeRecord> restoredRecords, long lastCheckpointId)
            throws TransactionalSink.TransactionException {
        log.info("recover 开始: {} 条恢复记录, lastCheckpointId={}",
                restoredRecords == null ? 0 : restoredRecords.size(),
                lastCheckpointId);

        // 1. 恢复未决事务
        if (lastCheckpointId >= 0) {
            String txnId = generateTxnId(lastCheckpointId);
            TransactionalSink.TransactionStatus status = sink.statusOf(txnId);
            log.info("recover: 事务 {} 当前状态={}", txnId, status);
            switch (status) {
                case PRE_COMMITTED -> {
                    log.info("recover: 事务 {} 已 preCommit 但未 commit，完成提交", txnId);
                    sink.recover(txnId);
                    pendingTransactions.remove(txnId);
                    committedTxnCount++;
                }
                case ACTIVE -> {
                    log.warn("recover: 事务 {} 仍为 ACTIVE，中止以避免数据重复", txnId);
                    sink.abort(txnId);
                    pendingTransactions.remove(txnId);
                    abortedTxnCount++;
                }
                case COMMITTED -> {
                    log.info("recover: 事务 {} 已提交，无需处理", txnId);
                    pendingTransactions.remove(txnId);
                }
                case ABORTED, UNKNOWN -> {
                    log.info("recover: 事务 {} 状态={}，无需处理", txnId, status);
                    pendingTransactions.remove(txnId);
                }
            }
        }

        // 2. 恢复的记录已通过 writer 去重处理（在调用方完成）
        log.info("recover 完成: committedTxnCount={}, abortedTxnCount={}",
                committedTxnCount, abortedTxnCount);
    }

    /**
     * 取出当前缓冲记录用于状态快照（不清空缓冲）。
     *
     * @return 缓冲记录的只读副本
     */
    public synchronized List<ChangeRecord> drainBufferedForSnapshot() {
        return new ArrayList<>(currentBuffer);
    }

    /**
     * 生成事务 ID：prefix + checkpointId。
     *
     * @param checkpointId checkpoint ID
     * @return 事务 ID
     */
    String generateTxnId(long checkpointId) {
        return config.getTransactionalIdPrefix() + checkpointId;
    }

    /**
     * 获取当前活跃事务 ID。
     *
     * @return 事务 ID；无活跃事务返回 {@code null}
     */
    public synchronized String getCurrentTxnId() {
        return currentTxnId;
    }

    /**
     * 获取未决事务的只读视图。
     *
     * @return 未决事务 Map
     */
    public synchronized Map<String, TransactionMetadata> getPendingTransactions() {
        return Collections.unmodifiableMap(new HashMap<>(pendingTransactions));
    }

    /**
     * 获取当前缓冲记录数。
     *
     * @return 缓冲记录数
     */
    public synchronized int getBufferSize() {
        return currentBuffer.size();
    }

    public long getCommittedTxnCount() {
        return committedTxnCount;
    }

    public long getAbortedTxnCount() {
        return abortedTxnCount;
    }

    public ExactlyOnceConfig getConfig() {
        return config;
    }

    public TransactionalSink getSink() {
        return sink;
    }

    public IdempotentWriter getWriter() {
        return writer;
    }

    /**
     * 事务元数据，记录事务的生命周期信息。
     */
    public static final class TransactionMetadata implements Serializable {
        private static final long serialVersionUID = 1L;

        private final String txnId;
        private final long checkpointId;
        private final long createTimestamp;
        private final int recordCount;
        private TransactionalSink.TransactionStatus status;

        public TransactionMetadata(String txnId, long checkpointId, long createTimestamp,
                                   int recordCount,
                                   TransactionalSink.TransactionStatus status) {
            this.txnId = txnId;
            this.checkpointId = checkpointId;
            this.createTimestamp = createTimestamp;
            this.recordCount = recordCount;
            this.status = status;
        }

        public String getTxnId() {
            return txnId;
        }

        public long getCheckpointId() {
            return checkpointId;
        }

        public long getCreateTimestamp() {
            return createTimestamp;
        }

        public int getRecordCount() {
            return recordCount;
        }

        public TransactionalSink.TransactionStatus getStatus() {
            return status;
        }

        @Override
        public String toString() {
            return "TransactionMetadata{txnId='" + txnId + "', checkpointId=" + checkpointId
                    + ", status=" + status + ", recordCount=" + recordCount + '}';
        }
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
     * TwoPhaseCommitCoordinator 构造器。
     */
    public static final class Builder {
        private ExactlyOnceConfig config;
        private TransactionalSink sink;
        private IdempotentWriter writer;

        /** Exactly-Once 配置。 */
        public Builder config(ExactlyOnceConfig config) {
            this.config = Objects.requireNonNull(config);
            return this;
        }

        /** 事务性 Sink。 */
        public Builder sink(TransactionalSink sink) {
            this.sink = Objects.requireNonNull(sink);
            return this;
        }

        /** 幂等写入器。 */
        public Builder writer(IdempotentWriter writer) {
            this.writer = Objects.requireNonNull(writer);
            return this;
        }

        /** 构建协调器。 */
        public TwoPhaseCommitCoordinator build() {
            Objects.requireNonNull(config, "config 不能为 null");
            Objects.requireNonNull(sink, "sink 不能为 null");
            Objects.requireNonNull(writer, "writer 不能为 null");
            return new TwoPhaseCommitCoordinator(config, sink, writer);
        }
    }
}