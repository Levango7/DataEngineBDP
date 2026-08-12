package com.levango7.dataenginebdp.streambatch.plugin;

import com.levango7.dataenginebdp.streambatch.model.DagNode;
import com.levango7.dataenginebdp.streambatch.model.TaskExecutionResult;

/**
 * 任务通道接口（模拟 DolphinScheduler TaskChannel SPI）。
 *
 * <p>DolphinScheduler 3.x 通过 {@code TaskChannel} SPI 扩展任务类型。
 * 本接口定义流批统一调度插件的任务通道契约，由
 * {@link SparkBatchTaskChannel} 与 {@link FlinkStreamTaskChannel} 实现。
 *
 * <p>实际部署时，可通过适配层将本接口桥接到 DolphinScheduler 的
 * {@code org.apache.dolphinscheduler.plugin.task.api.TaskChannel}。
 */
public interface TaskChannel {

    /**
     * 获取通道类型标识（与 {@link com.levango7.dataenginebdp.streambatch.model.TaskType} 对应）。
     *
     * @return 通道类型字符串
     */
    String getChannelType();

    /**
     * 执行任务节点。
     *
     * @param node DAG 节点（含任务参数、Iceberg 表、snapshot 隔离配置）
     * @return 执行结果（含实际使用的 snapshot-id、作业 ID）
     * @throws TaskExecutionException 任务执行失败
     */
    TaskExecutionResult execute(DagNode node) throws TaskExecutionException;

    /**
     * 取消任务节点（用于 DAG 中断时清理）。
     *
     * @param jobId 作业 ID
     * @return {@code true} 表示取消成功
     */
    boolean cancel(String jobId);
}