package com.levango7.dataenginebdp.storage.replication;

import com.levango7.dataenginebdp.storage.api.ObjectMetadata;
import com.levango7.dataenginebdp.storage.api.ObjectStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ObjectReplicator 的 Mockito 版单元测试，与 {@link ObjectReplicatorTest}（内存 Fake）互补。
 *
 * <p><b>依赖注入说明</b>：
 * <ul>
 *   <li>依赖通过 {@code ObjectReplicator} 的<b>构造器</b>注入，无 Spring 容器；
 *       本类用 {@link MockitoExtension} 生成 {@code @Mock ObjectStore}，
 *       再在 {@link BeforeEach} 中显式 {@code new ObjectReplicator(source, target)} 装配。</li>
 *   <li><b>刻意不用 {@code @InjectMocks}</b>：{@code ObjectReplicator} 有两个构造器，
 *       Mockito 构造器注入会选择参数最多的构造器并对无法解析的参数（{@code ConflictPolicy}）
 *       传入 null，导致 NPE；显式装配可避免该隐式行为。</li>
 *   <li>分工边界：Mock 用于<b>交互验证与强制异常</b>；字节级一致性与 sha256 校验由
 *       {@link ObjectReplicatorTest} 的内存 Fake 承担（Mock 的 thenReturn 会自证）。</li>
 *   <li><b>流消费契约</b>：复制器把源流包成 {@code DigestInputStream} 交给 {@code putObject}，
 *       并假定实现读满整条流（见 {@code ObjectStore.putObject} 契约）。Mock 默认不消费入参，
 *       故本类对被测写入路径用 {@link org.mockito.Mockito#doAnswer} 显式排空输入流，
 *       否则 digest 将停留在空内容哈希、校验必然失败（这是 Mock 的固有陷阱，非实现缺陷）。</li>
 *   <li>{@link MockitoExtension} 默认 STRICT_STUBS：仅 stub 本用例实际使用的调用。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ObjectReplicatorMockTest {

    private static final String PREFIX = "_system/xdomain/demo/";
    private static final String KEY = PREFIX + "a.bin";

    @Mock
    private ObjectStore source;

    @Mock
    private ObjectStore target;

    /** 被测对象（SUT）：依赖经构造器注入，不使用 @InjectMocks。 */
    private ObjectReplicator replicator;

    @BeforeEach
    void setUp() {
        replicator = new ObjectReplicator(source, target);
    }

    @Test
    @DisplayName("Mock 注入：COPY 时以源长度写入目标，且不删除源")
    void copy_writesTargetWithSourceSize() throws Exception {
        byte[] content = "payload".getBytes(StandardCharsets.UTF_8);
        when(source.listObjects(PREFIX)).thenReturn(List.of(KEY));
        when(source.statObject(KEY)).thenReturn(meta(content.length, Instant.parse("2026-10-05T00:00:10Z")));
        when(target.statObject(KEY)).thenReturn(null);
        when(source.getObject(KEY)).thenReturn(new ByteArrayInputStream(content));
        when(target.getObject(KEY)).thenReturn(new ByteArrayInputStream(content));
        drainOnPut(content.length);

        ReplicationReport report = replicator.replicate(PREFIX);

        assertThat(report.getCopied()).isEqualTo(1);
        assertThat(report.getVerified()).isEqualTo(1);
        assertThat(report.isAllVerified()).isTrue();
        verify(target).putObject(eq(KEY), any(), eq((long) content.length), any());
        verify(source, never()).deleteObject(any());
    }

    @Test
    @DisplayName("Mock 注入：LWW 跳过时目标从不写入、源从不读取（never 交互校验）")
    void skip_neverTouchesTarget() {
        when(source.listObjects(PREFIX)).thenReturn(List.of(KEY));
        when(source.statObject(KEY)).thenReturn(meta(1024, Instant.parse("2026-10-05T00:00:00Z")));
        when(target.statObject(KEY)).thenReturn(meta(1024, Instant.parse("2026-10-05T00:00:10Z")));

        ReplicationReport report = replicator.replicate(PREFIX);

        assertThat(report.getSkipped()).isEqualTo(1);
        assertThat(report.getTotalBytes()).isZero();
        verify(target, never()).putObject(any(), any(), anyLong(), any());
        verify(source, never()).getObject(any());
    }

    @Test
    @DisplayName("Mock 注入：目标写入抛异常 → FAILED 并记录原因（Fake 难以自然构造）")
    void putFailure_isCapturedAsFailed() {
        byte[] content = new byte[]{1, 2, 3};
        when(source.listObjects(PREFIX)).thenReturn(List.of(KEY));
        when(source.statObject(KEY)).thenReturn(meta(content.length, Instant.parse("2026-10-05T00:00:10Z")));
        when(target.statObject(KEY)).thenReturn(null);
        when(source.getObject(KEY)).thenReturn(new ByteArrayInputStream(content));
        doThrow(new IllegalStateException("target 不可用"))
                .when(target).putObject(eq(KEY), any(), anyLong(), any());

        ReplicationReport report = replicator.replicate(PREFIX);

        assertThat(report.getFailed()).isEqualTo(1);
        assertThat(report.getItems().get(0).getAction()).isEqualTo(ReplicationAction.FAILED);
        assertThat(report.getItems().get(0).getError()).contains("target 不可用");
        assertThat(report.isAllVerified()).isFalse();
    }

    private static ObjectMetadata meta(long size, Instant lastModified) {
        return ObjectMetadata.builder().key(KEY).size(size).lastModified(lastModified).build();
    }

    /** 令被 stub 的 putObject 真实施加“完整消费输入流”契约，供复制器内 DigestInputStream 计算哈希。 */
    private void drainOnPut(long contentLength) throws Exception {
        doAnswer(invocation -> {
            InputStream in = invocation.getArgument(1);
            in.readAllBytes();
            return null;
        }).when(target).putObject(eq(KEY), any(), eq(contentLength), any());
    }
}