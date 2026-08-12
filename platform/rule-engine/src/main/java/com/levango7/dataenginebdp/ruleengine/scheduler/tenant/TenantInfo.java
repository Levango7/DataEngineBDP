package com.levango7.dataenginebdp.ruleengine.scheduler.tenant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 租户元数据与运行时状态。
 *
 * <p>由 {@link TenantManager} 维护，描述一个租户在调度引擎内的配置与实时计数。
 * {@code enabled=false} 的租户提交任务将被拒绝，实现软隔离（停租不停服）。</p>
 *
 * <p>字段分两类：</p>
 * <ul>
 *   <li>配置态：{@code tenantId}/{@code name}/{@code maxConcurrentTasks}/{@code enabled}，
 *       由管理 API 写入</li>
 *   <li>运行态：{@code activeTaskCount}/{@code queuedTaskCount}，由调度引擎在
 *       入队/出队/完成时通过 {@link TenantManager} 原子更新</li>
 * </ul>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantInfo {

    /** 租户 ID（与 JWT claim {@code tenantId} 对应） */
    private String tenantId;

    /** 租户名称（展示用） */
    private String name;

    /** 最大并发执行任务数；&gt; 0，超过则新任务排队 */
    @Builder.Default
    private int maxConcurrentTasks = 4;

    /** 是否启用；禁用后新任务提交被拒绝 */
    @Builder.Default
    private boolean enabled = true;

    /** 注册时间 */
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 运行态：当前正在执行的任务数 */
    @Builder.Default
    private int activeTaskCount = 0;

    /** 运行态：当前排队任务数 */
    @Builder.Default
    private int queuedTaskCount = 0;
}