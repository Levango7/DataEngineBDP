package com.shuqing.bigdata.flinkcdc.exactlyonce;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存版 {@link TransactionalSink} 实现，用于单元测试。
 *
 * <p>模拟 Kafka 事务 Producer 的行为：begin → write → preCommit → commit/abort，
 * 所有状态保存在内存 Map 中，便于断言事务生命周期。</p>
 *
 * @author shuqing-bigdata
 */
public final class InMemoryTransactionalSink implements TransactionalSink {

    private static final long serialVersionUID = 1L;

    /** 事务 ID → 事务状态。 */
    private final Map<String, TransactionStatus> txnStatus = new HashMap<>();
    /** 事务 ID → 事务内已写入的记录。 */
    private final Map<String, List<ChangeRecord>> txnRecords = new LinkedHashMap<>();
    /** 已提交事务的记录（按 commit 顺序）。 */
    private final List<ChangeRecord> committedRecords = new ArrayList<>();
    /** 已中止事务数。 */
    private int abortedCount = 0;
    /** 已恢复事务数。 */
    private int recoveredCount = 0;

    @Override
    public synchronized void beginTransaction(String txnId) throws TransactionException {
        if (txnStatus.containsKey(txnId) && txnStatus.get(txnId) != TransactionStatus.ABORTED) {
            throw new TransactionException("事务已存在: " + txnId, txnStatus.get(txnId));
        }
        txnStatus.put(txnId, TransactionStatus.ACTIVE);
        txnRecords.put(txnId, new ArrayList<>());
    }

    @Override
    public synchronized void write(ChangeRecord record, String txnId) throws TransactionException {
        TransactionStatus status = txnStatus.get(txnId);
        if (status == null) {
            throw new TransactionException("事务不存在: " + txnId);
        }
        if (status != TransactionStatus.ACTIVE) {
            throw new IllegalStateException("事务 " + txnId + " 状态为 " + status + "，不可写入");
        }
        txnRecords.get(txnId).add(record);
    }

    @Override
    public synchronized void write(Collection<ChangeRecord> records, String txnId) throws TransactionException {
        TransactionStatus status = txnStatus.get(txnId);
        if (status == null) {
            throw new TransactionException("事务不存在: " + txnId);
        }
        if (status != TransactionStatus.ACTIVE) {
            throw new IllegalStateException("事务 " + txnId + " 状态为 " + status + "，不可写入");
        }
        txnRecords.get(txnId).addAll(records);
    }

    @Override
    public synchronized void preCommit(String txnId) throws TransactionException {
        TransactionStatus status = txnStatus.get(txnId);
        if (status == null) {
            throw new TransactionException("事务不存在: " + txnId);
        }
        if (status != TransactionStatus.ACTIVE) {
            throw new TransactionException("事务 " + txnId + " 状态为 " + status + "，不可 preCommit", status);
        }
        txnStatus.put(txnId, TransactionStatus.PRE_COMMITTED);
    }

    @Override
    public synchronized void commit(String txnId) throws TransactionException {
        TransactionStatus status = txnStatus.get(txnId);
        if (status == null) {
            throw new TransactionException("事务不存在: " + txnId);
        }
        // 幂等：已提交直接返回
        if (status == TransactionStatus.COMMITTED) {
            return;
        }
        if (status != TransactionStatus.PRE_COMMITTED) {
            throw new TransactionException("事务 " + txnId + " 状态为 " + status + "，不可 commit", status);
        }
        committedRecords.addAll(txnRecords.get(txnId));
        txnStatus.put(txnId, TransactionStatus.COMMITTED);
    }

    @Override
    public synchronized void abort(String txnId) throws TransactionException {
        TransactionStatus status = txnStatus.get(txnId);
        if (status == null) {
            throw new TransactionException("事务不存在: " + txnId);
        }
        // 幂等：已中止直接返回
        if (status == TransactionStatus.ABORTED) {
            return;
        }
        txnRecords.get(txnId).clear();
        txnStatus.put(txnId, TransactionStatus.ABORTED);
        abortedCount++;
    }

    @Override
    public synchronized void recover(String txnId) throws TransactionException {
        TransactionStatus status = txnStatus.get(txnId);
        if (status == null) {
            return;
        }
        if (status == TransactionStatus.PRE_COMMITTED) {
            committedRecords.addAll(txnRecords.get(txnId));
            txnStatus.put(txnId, TransactionStatus.COMMITTED);
            recoveredCount++;
        }
    }

    @Override
    public synchronized TransactionStatus statusOf(String txnId) {
        return txnStatus.getOrDefault(txnId, TransactionStatus.UNKNOWN);
    }

    // ===== 测试访问器 =====

    public synchronized List<ChangeRecord> getCommittedRecords() {
        return new ArrayList<>(committedRecords);
    }

    public synchronized int getCommittedCount() {
        return committedRecords.size();
    }

    public synchronized int getAbortedCount() {
        return abortedCount;
    }

    public synchronized int getRecoveredCount() {
        return recoveredCount;
    }

    public synchronized List<ChangeRecord> getTxnRecords(String txnId) {
        List<ChangeRecord> records = txnRecords.get(txnId);
        return records == null ? List.of() : new ArrayList<>(records);
    }

    public synchronized int getActiveTxnCount() {
        return (int) txnStatus.values().stream()
                .filter(s -> s == TransactionStatus.ACTIVE || s == TransactionStatus.PRE_COMMITTED)
                .count();
    }
}