package com.shuqing.bigdata.rule.engine.orchestrator.dag;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DAG 边模型。
 *
 * <p>表示从 source 节点到 target 节点的有向依赖关系：
 * source 任务执行成功后才会触发 target 任务执行。</p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>仅保存节点 ID 引用，避免与 DagNode 实例强耦合，便于序列化；</li>
 *   <li>condition 字段预留条件边能力（如仅在上游输出满足某表达式时才触发），
 *       当前版本默认为 null 表示无条件依赖。</li>
 * </ul>
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DagEdge {

    /** 上游节点 ID（依赖被满足的节点） */
    private String source;

    /** 下游节点 ID（依赖 source 的节点） */
    private String target;

    /** 条件表达式（预留），null 表示无条件依赖 */
    private String condition;

    /**
     * 工厂方法：构建无条件边。
     *
     * @param source 上游节点 ID
     * @param target 下游节点 ID
     * @return 无条件 DagEdge
     */
    public static DagEdge of(String source, String target) {
        return DagEdge.builder().source(source).target(target).build();
    }
}