package com.levango7.dataenginebdp.finops.dashboard.model;

/**
 * 闲置模式枚举。
 *
 * <p>覆盖 FinOps 优化建议引擎识别的 5 类闲置模式：</p>
 * <ul>
 *   <li>{@link #LOW_CPU_UTILIZATION} 低利用率 CPU（CPU 平均利用率低于阈值）</li>
 *   <li>{@link #LOW_MEMORY_UTILIZATION} 低利用率内存（内存平均利用率低于阈值）</li>
 *   <li>{@link #UNMOUNTED_STORAGE} 未挂载存储（PersistentVolumeClaim 未被 Pod 引用）</li>
 *   <li>{@link #IDLE_GPU} 空闲 GPU（GPU 平均利用率低于阈值）</li>
 *   <li>{@link #LOW_NETWORK_TRAFFIC} 低流量负载（网络流量低于阈值）</li>
 * </ul>
 */
public enum IdlePattern {

    /** 低利用率 CPU：CPU 平均利用率低于阈值（默认 10%） */
    LOW_CPU_UTILIZATION("低利用率 CPU", "CPU 平均利用率低于阈值，可缩容或释放"),

    /** 低利用率内存：内存平均利用率低于阈值（默认 20%） */
    LOW_MEMORY_UTILIZATION("低利用率内存", "内存平均利用率低于阈值，可缩容或释放"),

    /** 未挂载存储：PersistentVolumeClaim 未被 Pod 引用 */
    UNMOUNTED_STORAGE("未挂载存储", "PVC 未被任何 Pod 挂载，可释放存储资源"),

    /** 空闲 GPU：GPU 平均利用率低于阈值（默认 5%） */
    IDLE_GPU("空闲 GPU", "GPU 平均利用率低于阈值，可释放或共享给其他任务"),

    /** 低流量负载：网络流量低于阈值（默认 1 MB/s） */
    LOW_NETWORK_TRAFFIC("低流量负载", "网络流量低于阈值，可缩容或合并部署");

    private final String displayName;
    private final String description;

    IdlePattern(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}