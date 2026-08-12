package com.levango7.dataenginebdp.flinkcdc.exactlyonce;

import com.levango7.dataenginebdp.flinkcdc.model.ChangeRecord;

import java.io.Serializable;
import java.util.Collection;

/**
 * 事务性 Sink 接口，定义两阶段提交协议下 Sink 必须实现的事务生命周期方法。
 *
 * <p>实现该接口的 Sink 与 {@link TwoPhaseCommitCoordinator} 协作，在 Flink checkpoint
 * 触发时执行 preCommit → commit 流程，故障恢复时执行 abort 与 recover，从而保证
 * 端到端 exactly-once 语义。</p>
 *
 * <p><b>事务生命周期：</b></p>
 * <pre>{@code
 * beginTransaction(txnId)      // 开启事务
 *   └─ write(record, txnId)    // 多次写入（事务内）
 *   └─ write(records, txnId)   // 批量写入（事务内）
 * preCommit(txnId)             // 预提交：持久化事务句柄，不可再写入
 * commit(txnId)                // 提交：原子生效，下游可见
 * // 或
 * abort(txnId)                 // 中止：回滚事务内所有写入
 * // 故障恢复
 * recover(txnId)               // 恢复未决事务：commit 已 preCommit 但未 commit 的事务
 * }</pre>
 *
 * <p><b>实现约束（exactly-once 保障）：</b></p>
 * <ul>
 *   <li>{@code beginTransaction} 必须返回全局唯一的事务 ID（建议 prefix + checkpointId）</li>
 *   <li>{@code write} 在事务内缓冲记录，不立即可见</li>
 *   <li>{@code preCommit} 后不得再调用 {@code write}</li>
 *   <li>{@code commit} 必须幂等：重复 commit 同一 txnId 不产生副作用</li>
 *   <li>{@code abort} 必须幂等：重复 abort 同一 txnId 不产生副作用</li>
 *   <li>{@code recover} 必须能识别 preCommit 但未 commit 的事务并完成提交</li>
 * </ul>
 *
 * @author shuqing-bigdata
 */
public interface TransactionalSink extends Serializable {

    /**
     * 开启新事务，返回全局唯一的事务 ID。
     *
     * @param txnId 由协调器分配的事务 ID（通常为 prefix + checkpointId）
     * @throws TransactionException 事务开启失败
     */
    void beginTransaction(String txnId) throws TransactionException;

    /**
     * 在指定事务内写入单条记录。
     *
     * @param record 变更记录
     * @param txnId  事务 ID
     * @throws TransactionException  写入失败
     * @throws IllegalStateException 事务未开启或已 preCommit
     */
    void write(ChangeRecord record, String txnId) throws TransactionException;

    /**
     * 在指定事务内批量写入记录。
     *
     * @param records 变更记录集合
     * @param txnId   事务 ID
     * @throws TransactionException 写入失败
     */
    default void write(Collection<ChangeRecord> records, String txnId) throws TransactionException {
        for (ChangeRecord record : records) {
            write(record, txnId);
        }
    }

    /**
     * 预提交：持久化事务句柄，标记事务即将提交。
     *
     * <p>preCommit 后不得再调用 {@link #write}。preCommit 的持久化使得故障恢复后
     * 协调器能够识别"已预提交但未提交"的事务并完成提交，从而保证 exactly-once。</p>
     *
     * @param txnId 事务 ID
     * @throws TransactionException 预提交失败
     */
    void preCommit(String txnId) throws TransactionException;

    /**
     * 提交事务：原子生效，下游可见。
     *
     * <p>必须幂等：重复 commit 同一 txnId 不产生副作用。</p>
     *
     * @param txnId 事务 ID
     * @throws TransactionException 提交失败
     */
    void commit(String txnId) throws TransactionException;

    /**
     * 中止事务：回滚事务内所有写入。
     *
     * <p>必须幂等：重复 abort 同一 txnId 不产生副作用。</p>
     *
     * @param txnId 事务 ID
     * @throws TransactionException 中止失败
     */
    void abort(String txnId) throws TransactionException;

    /**
     * 故障恢复：识别并完成未决事务（已 preCommit 但未 commit）。
     *
     * <p>恢复流程：</p>
     * <ol>
     *   <li>枚举所有已 preCommit 的事务句柄</li>
     *   <li>对每个未 commit 的事务调用 {@link #commit}</li>
     *   <li>对每个未 preCommit 的事务调用 {@link #abort}</li>
     * </ol>
     *
     * @param txnId 事务 ID
     * @throws TransactionException 恢复失败
     */
    void recover(String txnId) throws TransactionException;

    /**
     * 查询事务当前状态。
     *
     * @param txnId 事务 ID
     * @return 事务状态；事务不存在返回 {@code UNKNOWN}
     */
    TransactionStatus statusOf(String txnId);

    /**
     * 事务状态枚举。
     */
    enum TransactionStatus {
        /** 事务尚未开启。 */
        NEW,
        /** 事务已开启，可写入。 */
        ACTIVE,
        /** 已预提交，不可再写入，等待 commit。 */
        PRE_COMMITTED,
        /** 已提交，下游可见。 */
        COMMITTED,
        /** 已中止，写入已回滚。 */
        ABORTED,
        /** 事务不存在或状态未知。 */
        UNKNOWN
    }

    /**
     * 事务异常，封装事务生命周期中发生的错误。
     */
    class TransactionException extends Exception {
        private static final long serialVersionUID = 1L;

        private final transient TransactionStatus status;

        public TransactionException(String message) {
            super(message);
            this.status = TransactionStatus.UNKNOWN;
        }

        public TransactionException(String message, Throwable cause) {
            super(message, cause);
            this.status = TransactionStatus.UNKNOWN;
        }

        public TransactionException(String message, TransactionStatus status) {
            super(message);
            this.status = status;
        }

        public TransactionStatus getStatus() {
            return status;
        }
    }
}