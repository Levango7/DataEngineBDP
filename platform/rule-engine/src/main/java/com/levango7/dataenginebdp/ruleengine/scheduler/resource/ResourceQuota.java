package com.levango7.dataenginebdp.ruleengine.scheduler.resource;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 租户资源配额与使用计量。
 *
 * <p>每个租户一份，由 {@link ResourceAllocator} 维护。配额为上限（hard limit），
 * 使用量在分配/释放时原子更新，超出配额的分配请求将被拒绝。</p>
 *
 * <p>字段语义：</p>
 * <ul>
 *   <li>{@code maxCpuCores}/{@code maxMemoryMb}：配额上限，由管理 API 或
 *       {@code SchedulerProperties.defaultQuota} 设置</li>
 *   <li>{@code usedCpuCores}/{@code usedMemoryMb}：实时已用量，分配成功递增、
 *       释放递减，使用 {@code synchronized} 保证多 worker 并发下不超卖</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResourceQuota {

    /** 租户 ID */
    private String tenantId;

    /** CPU 核数上限（可小数，如 4.5） */
    private double maxCpuCores;

    /** 内存上限（MB） */
    private long maxMemoryMb;

    /** 已用 CPU 核数 */
    @Builder.Default
    private double usedCpuCores = 0.0;

    /** 已用内存（MB） */
    @Builder.Default
    private long usedMemoryMb = 0L;

    /**
     * 判断给定请求是否在配额内。
     *
     * @param cpuCores    请求 CPU 核数
     * @param memoryMb    请求内存 MB
     * @return 在配额内返回 true
     */
    public boolean canAllocate(double cpuCores, long memoryMb) {
        return usedCpuCores + cpuCores <= maxCpuCores + 1e-9
                && usedMemoryMb + memoryMb <= maxMemoryMb;
    }
}