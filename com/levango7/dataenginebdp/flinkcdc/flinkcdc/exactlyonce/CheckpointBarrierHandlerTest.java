package com.shuqing.bigdata.flinkcdc.exactlyonce;

import com.shuqing.bigdata.flinkcdc.model.ChangeRecord;
import org.apache.flink.api.common.state.ListState;
import org.apache.flink.api.common.state.ListStateDescriptor;
import org.apache.flink.api.common.state.OperatorStateStore;
import org.apache.flink.runtime.state.FunctionInitializationContext;
import org.apache.flink.runtime.state.FunctionSnapshotContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link CheckpointBarrierHandler} 单元测试。
 *
 * <p>使用 Mockito 模拟 Flink 状态后端，验证 checkpoint 屏障处理流程。</p>
 *
 * @author shuqing-bigdata
 */
class CheckpointBarrierHandlerTest {

    private ExactlyOnceConfig config;
    private InMemoryTransactionalSink sink;
    private IdempotentWriter writer;
    private TwoPhaseCommitCoordinator coordinator;
    private CheckpointBarrierHandler handler;

    @BeforeEach
    void setUp() {
        config = ExactlyOnceConfig.builder()
                .transactionalIdPrefix("cdc-tx-")
                .primaryKeyColumns("id")
                .build();
        sink = new InMemoryTransactionalSink();
        writer = IdempotentWriter.builder().fromConfig(config).build();
        coordinator = TwoPhaseCommitCoordinator.builder()
                .config(config)
                .sink(sink)
                .writer(writer)
                .build();
        handler = new CheckpointBarrierHandler(config, coordinator);
    }

    private ChangeRecord record(int id, String name) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", id);
        after.put("name", name);
        return new ChangeRecord(null, after, "c", null, 100L);
    }

    /**
     * 创建模拟的 FunctionInitializationContext，支持 isRestored 控制与状态存取。
     */
    @SuppressWarnings("unchecked")
    private FunctionInitializationContext mockInitContext(boolean isRestored,
                                                          List<ChangeRecord> restoredRecords,
                                                          List<Long> restoredCpIds) throws Exception {
        FunctionInitializationContext context = Mockito.mock(FunctionInitializationContext.class);
        OperatorStateStore store = Mockito.mock(OperatorStateStore.class);

        when(context.isRestored()).thenReturn(isRestored);
        when(context.getOperatorStateStore()).thenReturn(store);

        ListState<ChangeRecord> bufferState = mockListState(restoredRecords);
        ListState<Long> cpIdState = mockListState(restoredCpIds);

        when(store.getListState(any(ListStateDescriptor.class)))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("rawtypes")
                    ListStateDescriptor desc = (ListStateDescriptor) invocation.getArgument(0);
                    String name = desc.getName();
                    if ("exactly-once-buffer".equals(name)) {
                        return bufferState;
                    } else if ("exactly-once-completed-cp".equals(name)) {
                        return cpIdState;
                    }
                    return mockListState(Collections.emptyList());
                });

        return context;
    }

    /**
     * 创建模拟 ListState，初始数据来自 initial 列表。
     * get() 返回初始数据的副本；add/clear/update/addAll 操作内存列表。
     */
    @SuppressWarnings("unchecked")
    private <T> ListState<T> mockListState(List<T> initial) throws Exception {
        ListState<T> state = Mockito.mock(ListState.class);
        List<T> data = new ArrayList<>(initial);

        Mockito.doAnswer(inv -> new ArrayList<>(data)).when(state).get();
        Mockito.doAnswer(inv -> {
            data.add(inv.getArgument(0));
            return null;
        }).when(state).add(any());
        Mockito.doAnswer(inv -> {
            data.addAll(inv.getArgument(0));
            return null;
        }).when(state).addAll(any(List.class));
        Mockito.doAnswer(inv -> {
            data.clear();
            data.addAll(inv.getArgument(0));
            return null;
        }).when(state).update(any(List.class));
        Mockito.doNothing().when(state).clear();

        return state;
    }

    private FunctionSnapshotContext mockSnapshotContext(long checkpointId) {
        FunctionSnapshotContext ctx = Mockito.mock(FunctionSnapshotContext.class);
        when(ctx.getCheckpointId()).thenReturn(checkpointId);
        return ctx;
    }

    @Nested
    @DisplayName("buffer 操作")
    class BufferTest {

        @Test
        @DisplayName("buffer — 记录加入协调器缓冲")
        void buffer() {
            ChangeRecord r = record(1, "alice");
            handler.buffer(r);
            assertThat(coordinator.getBufferSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("bufferAll — 批量加入缓冲")
        void bufferAll() {
            handler.bufferAll(List.of(record(1, "alice"), record(2, "bob")));
            assertThat(coordinator.getBufferSize()).isEqualTo(2);
        }

        @Test
        @DisplayName("buffer null — 抛出 NPE")
        void bufferNull() {
            assertThatThrownBy(() -> handler.buffer(null))
                    .isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("bufferAll null — 抛出 NPE")
        void bufferAllNull() {
            assertThatThrownBy(() -> handler.bufferAll(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("initializeState")
    class InitializeStateTest {

        @Test
        @DisplayName("首次启动 — 无需恢复")
        void firstStart() throws Exception {
            FunctionInitializationContext ctx = mockInitContext(false, List.of(), List.of());
            handler.initializeState(ctx);

            assertThat(handler.isRestored()).isFalse();
            assertThat(handler.getLastCompletedCheckpointId()).isEqualTo(-1L);
        }

        @Test
        @DisplayName("故障恢复 — 恢复缓冲记录与 checkpointId")
        void restore() throws Exception {
            List<ChangeRecord> restored = List.of(record(1, "alice"), record(2, "bob"));
            List<Long> cpIds = List.of(5L, 10L);

            FunctionInitializationContext ctx = mockInitContext(true, restored, cpIds);
            handler.initializeState(ctx);

            assertThat(handler.isRestored()).isTrue();
            assertThat(handler.getLastCompletedCheckpointId()).isEqualTo(10L);
            assertThat(coordinator.getBufferSize()).isEqualTo(2);
        }

        @Test
        @DisplayName("故障恢复 — 空 checkpoint 列表")
        void restoreEmptyCpIds() throws Exception {
            FunctionInitializationContext ctx = mockInitContext(true, List.of(), List.of());
            handler.initializeState(ctx);

            assertThat(handler.isRestored()).isTrue();
            assertThat(handler.getLastCompletedCheckpointId()).isEqualTo(-1L);
        }

        @Test
        @DisplayName("故障恢复 — 仅恢复记录无 checkpoint")
        void restoreRecordsOnly() throws Exception {
            List<ChangeRecord> restored = List.of(record(1, "alice"));

            FunctionInitializationContext ctx = mockInitContext(true, restored, List.of());
            handler.initializeState(ctx);

            assertThat(handler.isRestored()).isTrue();
            assertThat(coordinator.getBufferSize()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("snapshotState")
    class SnapshotStateTest {

        @Test
        @DisplayName("snapshotState — 触发 preCommit 并快照缓冲")
        void snapshotState() throws Exception {
            handler.buffer(record(1, "alice"));
            handler.buffer(record(2, "bob"));

            FunctionInitializationContext initCtx = mockInitContext(false, List.of(), List.of());
            handler.initializeState(initCtx);

            FunctionSnapshotContext snapCtx = mockSnapshotContext(1L);
            handler.snapshotState(snapCtx);

            // preCommit 后事务应为 PRE_COMMITTED
            assertThat(sink.statusOf("cdc-tx-1"))
                    .isEqualTo(TransactionalSink.TransactionStatus.PRE_COMMITTED);
        }

        @Test
        @DisplayName("snapshotState — 空缓冲也能正常快照")
        void snapshotStateEmptyBuffer() throws Exception {
            FunctionInitializationContext initCtx = mockInitContext(false, List.of(), List.of());
            handler.initializeState(initCtx);

            FunctionSnapshotContext snapCtx = mockSnapshotContext(1L);
            handler.snapshotState(snapCtx);

            assertThat(sink.statusOf("cdc-tx-1"))
                    .isEqualTo(TransactionalSink.TransactionStatus.PRE_COMMITTED);
        }
    }

    @Nested
    @DisplayName("notifyCheckpointComplete / Aborted")
    class NotifyCheckpointTest {

        @Test
        @DisplayName("notifyCheckpointComplete — 触发 commit")
        void notifyComplete() throws Exception {
            handler.buffer(record(1, "alice"));

            FunctionInitializationContext initCtx = mockInitContext(false, List.of(), List.of());
            handler.initializeState(initCtx);

            handler.snapshotState(mockSnapshotContext(1L));
            handler.notifyCheckpointComplete(1L);

            assertThat(sink.statusOf("cdc-tx-1"))
                    .isEqualTo(TransactionalSink.TransactionStatus.COMMITTED);
            assertThat(sink.getCommittedCount()).isEqualTo(1);
            assertThat(handler.getLastCompletedCheckpointId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("notifyCheckpointAborted — 触发 abort")
        void notifyAborted() throws Exception {
            handler.buffer(record(1, "alice"));

            FunctionInitializationContext initCtx = mockInitContext(false, List.of(), List.of());
            handler.initializeState(initCtx);

            handler.snapshotState(mockSnapshotContext(1L));
            handler.notifyCheckpointAborted(1L);

            assertThat(sink.statusOf("cdc-tx-1"))
                    .isEqualTo(TransactionalSink.TransactionStatus.ABORTED);
        }
    }

    @Nested
    @DisplayName("访问器")
    class AccessorTest {

        @Test
        @DisplayName("初始状态")
        void initialState() {
            assertThat(handler.getLastCompletedCheckpointId()).isEqualTo(-1L);
            assertThat(handler.isRestored()).isFalse();
            assertThat(handler.getConfig()).isSameAs(config);
            assertThat(handler.getCoordinator()).isSameAs(coordinator);
        }
    }

    @Nested
    @DisplayName("构造器校验")
    class ConstructorTest {

        @Test
        @DisplayName("null 参数 — 抛出 NPE")
        void nullParams() {
            assertThatThrownBy(() -> new CheckpointBarrierHandler(null, coordinator))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> new CheckpointBarrierHandler(config, null))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}