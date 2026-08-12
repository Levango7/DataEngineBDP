package com.levango7.dataenginebdp.ruleengine.scheduler.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 资源分配请求。
 *
 * <p>封装一次任务执行所需的 CPU 与内存，由 {@link ResourceAllocator} 在准入校验时使用。
 * 抽成独立 DTO 便于在 {@code SchedulerTask} 与 {@code ResourceAllocator} 间解耦传递。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResourceRequest {

    /** 请求 CPU 核数（&gt; 0） */
    private double cpuCores;

    /** 请求内存 MB（&gt; 0） */
    private long memoryMb;

    /**
     * 构造零资源请求（用于不占资源的轻量任务）。
     *
     * @return 零请求
     */
    public static ResourceRequest zero() {
        return new ResourceRequest(0.0, 0L);
    }
}