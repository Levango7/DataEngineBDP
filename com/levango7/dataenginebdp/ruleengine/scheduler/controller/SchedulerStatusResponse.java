package com.shuqing.bigdata.ruleengine.scheduler.controller;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 调度引擎状态响应 DTO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SchedulerStatusResponse {

    /** 是否启用 */
    private boolean enabled;
    /** 当前队列长度 */
    private int queueSize;
    /** 当前 worker 数 */
    private int workerCount;
    /** 活跃任务数 */
    private int activeTaskCount;
    /** 平均负载（queueSize/workerCount） */
    private double avgLoad;
    /** worker 利用率（active/worker） */
    private double utilization;
    /** 累计完成任务数 */
    private long totalCompleted;
    /** 累计拒绝任务数 */
    private long totalRejected;
}