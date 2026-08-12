package com.levango7.dataenginebdp.rule.engine.orchestrator.visual;

import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagEdge;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagGraph;
import com.levango7.dataenginebdp.rule.engine.orchestrator.dag.DagNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mermaid 图形生成器。
 *
 * <p>将 {@link DagGraph} 转换为 Mermaid flowchart 文本，供前端直接渲染
 * 或在 Markdown 中展示。生成的语法形如：
 * <pre>
 * flowchart LR
 *   n1["Extract"]
 *   n2["Transform"]
 *   n1 --> n2
 * </pre>
 * </p>
 *
 * <p>设计说明：
 * <ul>
 *   <li>使用 LR（从左到右）方向，适合多数 ETL 流程展示；</li>
 *   <li>节点 ID 中的特殊字符做 sanitize，避免破坏 Mermaid 语法；</li>
 *   <li>状态样式通过 classDef 标注，前端可结合 status 着色。</li>
 * </ul>
 * </p>
 */
public final class MermaidGenerator {

    /** 默认方向：从左到右 */
    public static final String DEFAULT_DIRECTION = "LR";

    private MermaidGenerator() {
    }

    /**
     * 生成 Mermaid flowchart 文本。
     *
     * @param graph DAG 图
     * @return Mermaid 文本
     */
    public static String generate(DagGraph graph) {
        return generate(graph, DEFAULT_DIRECTION);
    }

    /**
     * 生成 Mermaid flowchart 文本，指定方向。
     *
     * @param graph     DAG 图
     * @param direction 方向：LR/RL/TD/BT
     * @return Mermaid 文本
     */
    public static String generate(DagGraph graph, String direction) {
        StringBuilder sb = new StringBuilder();
        sb.append("flowchart ").append(direction).append('\n');

        // 节点声明，使用 sanitize 后的 id 作为 Mermaid 节点 ID
        Map<String, String> idToMermaid = new LinkedHashMap<>();
        for (DagNode node : graph.allNodes()) {
            String mermaidId = sanitize(node.getId());
            idToMermaid.put(node.getId(), mermaidId);
            String label = node.getName() == null ? node.getId() : node.getName();
            String status = node.getStatus() == null ? DagNode.STATUS_PENDING : node.getStatus();
            sb.append("  ").append(mermaidId)
              .append("[\"").append(escape(label)).append("\"]")
              .append(" ::: ").append(statusClass(status))
              .append('\n');
        }

        // 边
        for (DagEdge edge : graph.getEdges()) {
            String src = idToMermaid.get(edge.getSource());
            String tgt = idToMermaid.get(edge.getTarget());
            if (src == null || tgt == null) {
                continue;
            }
            sb.append("  ").append(src).append(" --> ").append(tgt);
            if (edge.getCondition() != null && !edge.getCondition().isBlank()) {
                sb.append(" | ").append(escape(edge.getCondition())).append(" |");
            }
            sb.append('\n');
        }

        // 状态样式定义
        sb.append("  classDef PENDING fill:#f9f9f9,stroke:#999\n");
        sb.append("  classDef RUNNING fill:#fff7e6,stroke:#fa8c16\n");
        sb.append("  classDef SUCCESS fill:#f6ffed,stroke:#52c41a\n");
        sb.append("  classDef FAILED fill:#fff1f0,stroke:#f5222d\n");
        sb.append("  classDef SKIPPED fill:#f5f5f5,stroke:#d9d9d9\n");
        return sb.toString();
    }

    /**
     * 将节点 ID 中的非字母数字字符替换为下划线，保证 Mermaid 标识符合法。
     */
    private static String sanitize(String id) {
        return id.replaceAll("[^A-Za-z0-9]", "_");
    }

    /**
     * 转义 Mermaid 文本中的双引号与反斜杠。
     */
    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 将运行时状态映射到 Mermaid class 名。
     */
    private static String statusClass(String status) {
        if (status == null) {
            return "PENDING";
        }
        switch (status) {
            case "RUNNING":
            case "SUCCESS":
            case "FAILED":
            case "SKIPPED":
                return status;
            default:
                return "PENDING";
        }
    }
}