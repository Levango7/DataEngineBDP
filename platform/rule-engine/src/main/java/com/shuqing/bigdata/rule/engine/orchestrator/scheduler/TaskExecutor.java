package com.shuqing.bigdata.rule.engine.orchestrator.scheduler;

import com.shuqing.bigdata.rule.engine.orchestrator.dag.DagNode;

import java.util.Map;

/**
 * 任务执行器接口。
 *
 * <p>抽象单个节点的实际执行逻辑。不同 taskType（SHELL/HTTP/SPARK/RULE 等）
 * 对应不同实现，由调度器按节点 taskType 分派。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>返回 {@link TaskResult} 而非抛异常，便于调度器统一处理失败与重试；</li>
 *   <li>实现方应保证自身线程安全，调度器可能在多线程环境下并发调用。</li>
 * </ul>
 * </p>
 */
public interface TaskExecutor {

    /**
     * 执行单个节点任务。
     *
     * @param node    节点定义
     * @param context 上游节点输出聚合上下文（key=节点 id，value=该节点输出）
     * @return 执行结果
     */
    TaskResult execute(DagNode node, Map<String, TaskResult> context);

    /**
     * 该执行器支持的 taskType。
     *
     * @return taskType 字符串
     */
    String taskType();
}