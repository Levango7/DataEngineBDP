package com.shuqing.bigdata.ruleengine.scheduler.service;

/**
 * 调度任务状态枚举。
 *
 * <p>状态机流转：</p>
 * <pre>
 * QUEUED ──→ ALLOCATING ──→ RUNNING ──→ SUCCEEDED
 *    │           │             │
 *    │           └─────────────┴──→ FAILED
 *    └──→ REJECTED (资源不足/租户禁用)
 * </pre>
 *
 * <p>取消语义：QUEUED/RUNNING 可被外部 {@code cancel} 转为 CANCELLED；
 * 终态（SUCCEEDED/FAILED/CANCELLED/REJECTED）不可再变更。</p>
 */
public enum TaskStatus {

    /** 已入队，等待调度 */
    QUEUED,

    /** 资源分配中（过渡态） */
    ALLOCATING,

    /** 已分配资源并交付 worker 执行 */
    RUNNING,

    /** 执行成功（终态） */
    SUCCEEDED,

    /** 执行失败（终态） */
    FAILED,

    /** 被取消（终态） */
    CANCELLED,

    /** 提交即被拒绝：资源不足 / 租户禁用 / 配额超限（终态） */
    REJECTED;

    /**
     * 判断是否为终态。
     *
     * @return 终态返回 true；可流转的中间态返回 false
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED || this == REJECTED;
    }
}