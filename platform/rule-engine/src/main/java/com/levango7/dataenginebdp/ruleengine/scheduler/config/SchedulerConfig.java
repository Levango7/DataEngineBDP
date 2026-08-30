package com.levango7.dataenginebdp.ruleengine.scheduler.config;

import com.levango7.dataenginebdp.ruleengine.scheduler.elastic.ElasticScaler;
import com.levango7.dataenginebdp.ruleengine.scheduler.elastic.LoadMonitor;
import com.levango7.dataenginebdp.ruleengine.scheduler.elastic.TaskHandler;
import com.levango7.dataenginebdp.ruleengine.scheduler.elastic.WorkerPool;
import com.levango7.dataenginebdp.ruleengine.scheduler.priority.PriorityTaskQueue;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * 调度引擎配置与 Bean 装配。
 *
 * <p>装配顺序（无循环依赖）：</p>
 * <ol>
 *   <li>{@link PriorityTaskQueue} — 任务队列</li>
 *   <li>{@link LoadMonitor} — 负载监控（@Component 自动装配 queue）</li>
 *   <li>{@code SchedulerService} — 业务服务（@Service，实现 {@link TaskHandler}）</li>
 *   <li>{@link WorkerPool} — worker 池（注入 queue + @Lazy handler）</li>
 *   <li>{@link ElasticScaler} — 弹性伸缩器</li>
 * </ol>
 *
 * <p>启动（{@link #afterSingletonsInstantiated()}）：在所有单例 Bean 初始化完成后，
 * worker 池初始化到 {@code initialSize}，绑定 LoadMonitor，启动弹性伸缩。
 * 关闭（{@link #shutdown()}）：停止弹性伸缩与 worker 池。</p>
 *
 * <p>{@code app.scheduler.enabled=false} 时仅装配 Bean 但不启动 worker 循环，
 * 提交任务将由 SchedulerService 直接拒绝。</p>
 *
 * <p><b>循环依赖修复</b>：{@code WorkerPool}、{@code LoadMonitor}、{@code ElasticScaler}
 * 均为本类的 {@code @Bean} 方法产物，若通过构造函数注入会造成 Spring 循环创建
 * （SchedulerConfig 需先创建才能调 @Bean 方法，但构造函数又需这些 Bean）。
 * 改用 {@code @Lazy @Autowired} 字段注入 + {@link SmartInitializingSingleton}
 * 延迟启动，确保所有单例 Bean 就绪后再执行绑定与启动逻辑。</p>
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(SchedulerProperties.class)
public class SchedulerConfig implements SmartInitializingSingleton {

    private final SchedulerProperties properties;

    /** @Lazy 字段注入，避免与 @Bean 方法循环创建 */
    @Lazy
    @Autowired
    private WorkerPool workerPool;

    @Lazy
    @Autowired
    private LoadMonitor loadMonitor;

    @Lazy
    @Autowired
    private ElasticScaler elasticScaler;

    public SchedulerConfig(SchedulerProperties properties) {
        this.properties = properties;
    }

    /**
     * 任务队列 Bean（单例）。
     */
    @Bean
    public PriorityTaskQueue priorityTaskQueue() {
        return new PriorityTaskQueue();
    }

    /**
     * Worker 池 Bean。{@code handler} 使用 @Lazy 避免与 SchedulerService 早期初始化冲突。
     *
     * @param queue   任务队列
     * @param handler 任务处理器（SchedulerService 实现）
     * @return worker 池
     */
    @Bean
    public WorkerPool workerPool(PriorityTaskQueue queue, @Lazy TaskHandler handler) {
        SchedulerProperties.Worker w = properties.getWorker();
        return new WorkerPool(queue, handler, w.getPollTimeoutMs(), w.getMinSize(), w.getMaxSize());
    }

    /**
     * 弹性伸缩器 Bean。
     *
     * @param workerPool  worker 池
     * @param loadMonitor 负载监控
     * @return 弹性伸缩器
     */
    @Bean
    public ElasticScaler elasticScaler(WorkerPool workerPool, LoadMonitor loadMonitor) {
        return new ElasticScaler(workerPool, loadMonitor, properties.getElastic());
    }

    /**
     * 启动调度引擎（所有单例 Bean 就绪后调用）。
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (!properties.isEnabled()) {
            log.warn("调度引擎未启用 (app.scheduler.enabled=false)，worker 池不启动");
            return;
        }
        loadMonitor.bind(workerPool);
        workerPool.start(properties.getWorker().getInitialSize());
        elasticScaler.start();
        log.info("调度引擎已启动: initialWorkers={}, elastic={}",
                properties.getWorker().getInitialSize(), properties.getElastic().isEnabled());
    }

    /**
     * 关闭调度引擎。
     */
    @PreDestroy
    public void shutdown() {
        elasticScaler.stop();
        workerPool.stop();
        log.info("调度引擎已关闭");
    }
}
