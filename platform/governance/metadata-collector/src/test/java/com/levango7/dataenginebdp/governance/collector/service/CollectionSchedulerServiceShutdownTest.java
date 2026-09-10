package com.levango7.dataenginebdp.governance.collector.service;

import com.levango7.dataenginebdp.governance.collector.repository.CollectionHistoryRepository;
import com.levango7.dataenginebdp.governance.collector.repository.MetadataSourceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CollectionSchedulerService} 优雅停机（@PreDestroy）单元测试。
 *
 * <p>验证 {@code shutdown()} 调用后 {@code collectionExecutor} 与
 * {@code dynamicScheduler} 被正确关闭，且进行中的任务能在超时窗口内正常完成。</p>
 *
 * <p>由于线程池字段为 private，测试通过反射获取引用以断言其生命周期状态。
 * 这属于白盒测试手段，仅验证关闭行为，不依赖 Spring 容器。</p>
 */
@ExtendWith(MockitoExtension.class)
class CollectionSchedulerServiceShutdownTest {

    @Mock
    private MetadataSourceRepository sourceRepository;
    @Mock
    private CollectionHistoryRepository historyRepository;
    @Mock
    private MetadataWriterService writerService;

    private CollectionSchedulerService service;

    @BeforeEach
    void setUp() {
        service = new CollectionSchedulerService(sourceRepository, historyRepository,
                writerService, Collections.emptyList());
    }

    @AfterEach
    void tearDown() {
        // 确保即使测试失败也不泄漏线程池
        service.shutdown();
    }

    /**
     * 通过反射获取 private {@code collectionExecutor} 字段。
     *
     * @return 采集线程池引用
     */
    private ExecutorService getCollectionExecutor() throws Exception {
        Field f = CollectionSchedulerService.class.getDeclaredField("collectionExecutor");
        f.setAccessible(true);
        return (ExecutorService) f.get(service);
    }

    /**
     * 通过反射获取 private {@code dynamicScheduler} 字段。
     *
     * @return 动态调度器引用
     */
    private ScheduledExecutorService getDynamicScheduler() throws Exception {
        Field f = CollectionSchedulerService.class.getDeclaredField("dynamicScheduler");
        f.setAccessible(true);
        return (ScheduledExecutorService) f.get(service);
    }

    @Test
    @DisplayName("shutdown 调用后 collectionExecutor 和 dynamicScheduler 均已关闭")
    void shutdown_shouldCloseBothExecutors() throws Exception {
        ExecutorService collectionExecutor = getCollectionExecutor();
        ScheduledExecutorService dynamicScheduler = getDynamicScheduler();

        // 关闭前应为活跃状态
        assertFalse(collectionExecutor.isShutdown(),
                "关闭前 collectionExecutor 不应处于 shutdown 状态");
        assertFalse(dynamicScheduler.isShutdown(),
                "关闭前 dynamicScheduler 不应处于 shutdown 状态");

        service.shutdown();

        assertTrue(collectionExecutor.isShutdown(),
                "关闭后 collectionExecutor 应处于 shutdown 状态");
        assertTrue(dynamicScheduler.isShutdown(),
                "关闭后 dynamicScheduler 应处于 shutdown 状态");
    }

    @Test
    @DisplayName("shutdown 应等待正在执行的采集任务正常完成")
    void shutdown_shouldAwaitRunningTaskCompletion() throws Exception {
        ExecutorService collectionExecutor = getCollectionExecutor();
        AtomicBoolean taskCompleted = new AtomicBoolean(false);
        CountDownLatch taskStarted = new CountDownLatch(1);

        // 直接向采集线程池提交一个短任务，模拟进行中的采集
        collectionExecutor.submit(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            taskCompleted.set(true);
        });

        // 确保任务已被线程池接收并开始执行
        assertTrue(taskStarted.await(2, TimeUnit.SECONDS),
                "任务应在 2s 内启动");

        // 调用 shutdown，任务应在 5s 超时窗口内完成而非被强制中断
        service.shutdown();

        assertTrue(taskCompleted.get(),
                "正在执行的采集任务应正常完成而非被中断");
        assertTrue(collectionExecutor.awaitTermination(1, TimeUnit.SECONDS),
                "线程池应在任务完成后完全终止");
    }

    @Test
    @DisplayName("shutdown 多次调用应幂等安全不抛异常")
    void shutdown_shouldBeIdempotent() throws Exception {
        ExecutorService collectionExecutor = getCollectionExecutor();

        service.shutdown();
        // 第二次调用不应抛异常（shutdown/awaitTermination 对已关闭池幂等）
        service.shutdown();

        assertTrue(collectionExecutor.isShutdown(),
                "多次 shutdown 后线程池仍应处于关闭状态");
    }
}