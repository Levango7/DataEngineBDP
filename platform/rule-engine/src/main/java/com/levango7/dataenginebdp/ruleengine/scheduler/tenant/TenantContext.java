package com.levango7.dataenginebdp.ruleengine.scheduler.tenant;

/**
 * 调度域租户上下文持有器。
 *
 * <p>与 {@code com.levango7.dataenginebdp.ruleengine.security.TenantContext} 区分：</p>
 * <ul>
 *   <li>{@code security.TenantContext}：HTTP 请求级，在 {@code JwtAuthFilter} 中
 *       从 JWT claim 解析 tenantId/userId，用于鉴权与数据隔离</li>
 *   <li>本类（{@code scheduler.tenant.TenantContext}）：调度执行级，在 worker 线程
 *       拉取任务后绑定当前任务的租户身份，用于执行期内日志关联、指标打点、
 *       资源回收归属。worker 线程池复用，必须在任务执行 finally 中 {@link #clear()}</li>
 * </ul>
 *
 * <p>两者通过 tenantId 关联：调度服务提交任务时从 security.TenantContext 取 tenantId
 * 写入 {@code SchedulerTask}；worker 执行前将 task.tenantId 写入本类，供执行链路下游使用。</p>
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_TASK_ID = new ThreadLocal<>();

    private TenantContext() {
    }

    /**
     * 绑定当前 worker 线程执行的租户与任务。
     *
     * @param tenantId 租户 ID
     * @param taskId   任务 ID
     */
    public static void bind(String tenantId, String taskId) {
        CURRENT_TENANT_ID.set(tenantId);
        CURRENT_TASK_ID.set(taskId);
    }

    /**
     * 获取当前线程绑定的租户 ID。
     *
     * @return 租户 ID；未绑定返回 null
     */
    public static String getTenantId() {
        return CURRENT_TENANT_ID.get();
    }

    /**
     * 获取当前线程绑定的任务 ID。
     *
     * @return 任务 ID；未绑定返回 null
     */
    public static String getTaskId() {
        return CURRENT_TASK_ID.get();
    }

    /**
     * 清理当前线程的调度上下文。
     *
     * <p>必须在 worker 执行任务的 finally 块中调用，避免线程池复用导致的
     * 租户串号与内存泄漏。</p>
     */
    public static void clear() {
        CURRENT_TENANT_ID.remove();
        CURRENT_TASK_ID.remove();
    }
}