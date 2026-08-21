package com.shuqing.bigdata.flinkcdc.exactlyonce;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.apache.flink.streaming.api.checkpoint.CheckpointedFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Checkpoint 屏障处理器，集成 Flink {@link CheckpointedFunction} 实现周期性 barrier 对齐与状态快照。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>在 Flink checkpoint 触发时（barrier 对齐完成），将缓冲记录与事务句柄快照到状态后端</li>
 *   <li>故障恢复时从状态后端恢复缓冲记录与事务句柄，避免数据丢失</li>
 *   <li>与 {@link TwoPhaseCommitCoordinator} 协作：snapshotState 触发 preCommit，
 *       initializeState 触发 recover</li>
 *   <li>支持 unaligned checkpoint（反压场景下跳过 barrier 对齐，加速恢复）</li>
 * </ul>
 *
 * <p><b>exactly-once 保障原理：</b></p>
 * <p>Flink 通过周期性注入 barrier 实现分布式快照。当所有上游算子对齐 barrier 后，
 * checkpoint 协调器触发 {@link #snapshotState}，此时：</p>
 * <ol>
 *   <li>调用 {@link TwoPhaseCommitCoordinator#preCommit} 持久化事务句柄</li>
 *   <li>将未提交的缓冲记录写入 ListState（状态后端）</li>
 *   <li>checkpoint 完成后由协调器触发 {@link TwoPhaseCommitCoordinator#commit}</li>
 * </ol>
 * <p>故障恢复时，{@link #initializeState} 从状态后端恢复缓冲记录与事务句柄，
 * 协调器调用 {@link TransactionalSink#recover} 完成未决事务，保证无重复无丢失。</p>
 *
 * <p>典型用法（与 Flink Sink 算子集成）：</p>
 * <pre>{@code
 * public class ExactlyOnceSinkFunction extends SinkFunction<ChangeRecord>
 *         implements CheckpointedFunction {
 *     private final CheckpointBarrierHandler handler;
 *
 *     public void invoke(ChangeRecord value, Context ctx) {
 *         handler.buffer(value);  // 缓冲记录
 *     }
 *
 *     public void snapshotState(FunctionSnapshotContext context) throws Exception {
 *         handler.snapshotState(context);  // 触发 preCommit + 快照
 *     }
 *
 *     public void initializeState(FunctionInitializationContext context) throws Exception {
 *         handler.initializeState(context);  // 恢复状态 + recover
 *     }
 * }
 * }</pre>
 *
 * @author shuqing-bigdata
 */
public final class CheckpointBarrierHandler implements CheckpointedFunction {

    private static final long serialVersionUID = 1L;
    private static final Logger log = LoggerFactory.getLogger(CheckpointBarrierHandler.class);

    /** 缓冲记录状态描述符。 */
    private static final String BUFFER_STATE_NAME = "exactly-once-buffer";
    /** 已完成 checkpoint ID 状态描述符。 */
    private static final String COMPLETED_CP_STATE_NAME = "exactly-once-completed-cp";

    private final ExactlyOnceConfig config;
    private final TwoPhaseCommitCoordinator coordinator;

    /** Flink 托管的缓冲记录状态（故障恢复后可重放）。 */
    private transient ListState<ChangeRecord> bufferedRecordsState;
    /** Flink 托管的已完成 checkpoint ID 状态。 */
    private transient ListState<Long> completedCheckpointIdsState;

    /** 当前 checkpoint ID（最近一次完成的 checkpoint）。 */
    private long lastCompletedCheckpointId = -1L;
    /** 是否已从状态恢复（首次 initializeState 后置 true）。 */
    private boolean restored = false;

    public CheckpointBarrierHandler(ExactlyOnceConfig config,
                                    TwoPhaseCommitCoordinator coordinator) {
        this.config = Objects.requireNonNull(config, "config 不能为 null");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator 不能为 null");
    }

    /**
     * 缓冲一条记录（在 invoke 中调用）。
     *
     * @param record 变更记录
     */
    public void buffer(ChangeRecord record) {
        Objects.requireNonNull(record, "record 不能为 null");
        coordinator.buffer(record);
    }

    /**
     * 缓冲多条记录。
     *
     * @param records 变更记录集合
     */
    public void bufferAll(List<ChangeRecord> records) {
        Objects.requireNonNull(records, "records 不能为 null");
        for (ChangeRecord record : records) {
            buffer(record);
        }
    }

    @Override
    public void snapshotState(FunctionSnapshotContext context) throws Exception {
        long checkpointId = context.getCheckpointId();
        log.debug("snapshotState 触发: checkpointId={}", checkpointId);

        // 1. 清空旧状态
        bufferedRecordsState.clear();
        completedCheckpointIdsState.clear();

        // 2. 执行 preCommit：持久化事务句柄
        coordinator.preCommit(checkpointId);

        // 3. 将当前缓冲记录快照到状态后端
        List<ChangeRecord> pending = coordinator.drainBufferedForSnapshot();
        for (ChangeRecord record : pending) {
            bufferedRecordsState.add(record);
        }

        // 4. 记录已完成 checkpoint ID
        completedCheckpointIdsState.add(checkpointId);

        log.debug("snapshotState 完成: checkpointId={}, bufferedRecords={}",
                checkpointId, pending.size());
    }

    @Override
    public void initializeState(FunctionInitializationContext context) throws Exception {
        log.info("initializeState: isRestored={}", context.isRestored());

        bufferedRecordsState = context.getOperatorStateStore()
                .getListState(new ListStateDescriptor<>(
                        BUFFER_STATE_NAME,
                        TypeInformation.of(ChangeRecord.class)));

        completedCheckpointIdsState = context.getOperatorStateStore()
                .getListState(new ListStateDescriptor<>(
                        COMPLETED_CP_STATE_NAME,
                        TypeInformation.of(Long.class)));

        if (context.isRestored()) {
            restoreFromState();
            restored = true;
        } else {
            log.info("首次启动，无需恢复状态");
        }
    }

    /**
     * 从状态后端恢复缓冲记录与已完成 checkpoint ID，并触发协调器 recover。
     *
     * @throws Exception 恢复失败
     */
    private void restoreFromState() throws Exception {
        // 1. 恢复缓冲记录
        List<ChangeRecord> restoredRecords = new ArrayList<>();
        for (ChangeRecord record : bufferedRecordsState.get()) {
            restoredRecords.add(record);
        }

        // 2. 恢复已完成 checkpoint ID
        List<Long> restoredCpIds = new ArrayList<>();
        for (Long cpId : completedCheckpointIdsState.get()) {
            restoredCpIds.add(cpId);
        }
        if (!restoredCpIds.isEmpty()) {
            lastCompletedCheckpointId = Collections.max(restoredCpIds);
        }

        log.info("从状态恢复: {} 条缓冲记录, 最近 checkpointId={}",
                restoredRecords.size(), lastCompletedCheckpointId);

        // 3. 触发协调器恢复（完成未决事务）
        coordinator.recover(restoredRecords, lastCompletedCheckpointId);

        // 4. 将恢复的记录重新加入缓冲（等待下次 checkpoint 提交）
        for (ChangeRecord record : restoredRecords) {
            coordinator.buffer(record);
        }
    }

    /**
     * 通知 checkpoint 已完成（由 CheckpointListener 触发），执行 commit。
     *
     * @param checkpointId 已完成的 checkpoint ID
     * @throws Exception commit 失败
     */
    public void notifyCheckpointComplete(long checkpointId) throws Exception {
        log.debug("notifyCheckpointComplete: checkpointId={}", checkpointId);
        coordinator.commit(checkpointId);
        lastCompletedCheckpointId = checkpointId;
    }

    /**
     * 通知 checkpoint 已撤销，执行 abort。
     *
     * @param checkpointId 被撤销的 checkpoint ID
     * @throws Exception abort 失败
     */
    public void notifyCheckpointAborted(long checkpointId) throws Exception {
        log.warn("notifyCheckpointAborted: checkpointId={}", checkpointId);
        coordinator.abort(checkpointId);
    }

    // ===== 访问器（供测试与监控使用） =====

    public long getLastCompletedCheckpointId() {
        return lastCompletedCheckpointId;
    }

    public boolean isRestored() {
        return restored;
    }

    public ExactlyOnceConfig getConfig() {
        return config;
    }

    public TwoPhaseCommitCoordinator getCoordinator() {
        return coordinator;
    }
}