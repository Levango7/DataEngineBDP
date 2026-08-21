package com.shuqing.bigdata.ruleengine.scheduler.elastic;

import com.shuqing.bigdata.ruleengine.scheduler.service.SchedulerTask;

/**
 * 任务执行处理器。
 *
 * <p>由 {@code SchedulerService} 实现，解耦 {@link Worker} 与业务层：
 * Worker 仅负责从队列拉取任务并调用本处理器，资源分配/租户上下文绑定/
 * 规则执行/状态更新等业务逻辑由实现类完成。</p>
 *
 * <p>实现要求：处理完成后将任务状态置为终态（SUCCEEDED/FAILED/REJECTED），
 * 并释放所有已占用资源。本方法抛出的异常由 Worker 捕获并标记任务 FAILED，
 * 不应导致 worker 线程退出。</p>
 */
@FunctionalInterface
public interface TaskHandler {

    /**
     * 处理一个已出队任务。
     *
     * @param task 任务（status 已为 QUEUED，由实现决定后续流转）
     */
    void handle(SchedulerTask task);
}