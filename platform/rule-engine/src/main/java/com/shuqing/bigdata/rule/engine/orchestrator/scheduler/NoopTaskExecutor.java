package com.shuqing.bigdata.rule.engine.orchestrator.scheduler;

import com.shuqing.bigdata.rule.engine.orchestrator.dag.DagNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 默认 NOOP 任务执行器。
 *
 * <p>作为兜底执行器，当节点 taskType 未匹配任何具体执行器时使用。
 * 仅记录日志并返回成功，便于在未接入真实执行器时验证编排流程。</p>
 *
 * <p>注册为 taskType="NOOP" 的执行器，同时被 {@link DependencyScheduler}
 * 在找不到匹配执行器时显式处理为 FAILED。</p>
 */
@Component
public class NoopTaskExecutor implements TaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(NoopTaskExecutor.class);

    @Override
    public TaskResult execute(DagNode node, Map<String, TaskResult> context) {
        long start = System.currentTimeMillis();
        log.info("[NOOP-EXEC] node={} taskType={} command={}", node.getId(), node.getTaskType(), node.getCommand());
        long duration = System.currentTimeMillis() - start;
        return TaskResult.success(node.getId(), Map.of("noop", true), duration);
    }

    @Override
    public String taskType() {
        return "NOOP";
    }
}