package com.levango7.dataenginebdp.ruleengine.scheduler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 调度引擎配置属性。
 *
 * <p>绑定 {@code app.scheduler.*} 配置项，提供调度引擎各组件的运行参数。
 * 所有数值均给出生产可用默认值，可在 {@code application.yml} 或环境变量中覆盖。</p>
 *
 * <p>配置示例见 {@code application.yml} 的 {@code app.scheduler} 段。</p>
 */
@Data
@ConfigurationProperties(prefix = "app.scheduler")
public class SchedulerProperties {

    /** 是否启用调度引擎；关闭时 SchedulerService 提交将直接拒绝 */
    private boolean enabled = true;

    /** Worker 池配置 */
    private Worker worker = new Worker();

    /** 弹性伸缩配置 */
    private Elastic elastic = new Elastic();

    /** 默认资源配额（每个新注册租户的初始配额） */
    private DefaultQuota defaultQuota = new DefaultQuota();

    /**
     * Worker 池参数。
     */
    @Data
    public static class Worker {
        /** 初始 worker 数量 */
        private int initialSize = 2;
        /** 最小 worker 数量（弹性伸缩下限） */
        private int minSize = 1;
        /** 最大 worker 数量（弹性伸缩上限） */
        private int maxSize = 8;
        /** 单个 worker 拉取任务时的等待超时（毫秒） */
        private long pollTimeoutMs = 1000L;
    }

    /**
     * 弹性伸缩参数。
     */
    @Data
    public static class Elastic {
        /** 是否启用弹性伸缩 */
        private boolean enabled = true;
        /** 扩容触发阈值：队列平均负载（任务数/worker数）超过该值则扩容 */
        private double scaleUpThreshold = 2.0;
        /** 缩容触发阈值：平均负载低于该值则缩容 */
        private double scaleDownThreshold = 0.5;
        /** 扩缩评估间隔（毫秒） */
        private long evalIntervalMs = 5000L;
        /** 缩容冷却期（毫秒）：刚扩容后多久内不缩容，避免抖动 */
        private long cooldownMs = 10000L;
    }

    /**
     * 默认租户资源配额。
     */
    @Data
    public static class DefaultQuota {
        /** 每个租户最大 CPU 核数 */
        private double maxCpuCores = 4.0;
        /** 每个租户最大内存（MB） */
        private long maxMemoryMb = 4096L;
        /** 每个租户最大并发任务数 */
        private int maxConcurrentTasks = 4;
    }
}