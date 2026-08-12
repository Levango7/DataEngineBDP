package com.levango7.dataenginebdp.sqlgateway.calcite.explain;

import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * EXPLAIN 输出格式化器——将 {@link ExplainResult} 渲染为树形/JSON/表格式文本。
 *
 * <p>本类是 EXPLAIN 可视化的渲染层，不修改结果数据，仅负责按指定格式输出字符串。
 * 三种格式各有适用场景：</p>
 * <ul>
 *   <li><b>树形（TREE）</b>：控制台/日志展示，直观呈现 RelNode 层级与下推标注</li>
 *   <li><b>JSON</b>：程序解析/前端渲染，结构化字段便于二次处理</li>
 *   <li><b>表格（TABLE）</b>：逐节点属性对照，便于横向比较多个节点</li>
 * </ul>
 *
 * <p>典型用法：</p>
 * <pre>
 *   ExplainResult result = explainVisualizer.explain(sql);
 *   String tree = ExplainFormatter.format(result, ExplainFormat.TREE);
 *   String json = ExplainFormatter.format(result, ExplainFormat.JSON);
 *   String table = ExplainFormatter.format(result, ExplainFormat.TABLE);
 * </pre>
 *
 * @author shuqing-bigdata
 */
public final class ExplainFormatter {

    /** 表格列分隔符 */
    private static final String COL_SEP = " | ";
    /** 表格水平线字符 */
    private static final String H_LINE = "-";
    /** 树形分支字符 */
    private static final String BRANCH_MID = "├─ ";
    private static final String BRANCH_END = "└─ ";
    private static final String PIPE = "│  ";
    private static final String SPACE = "   ";

    private ExplainFormatter() {
    }

    /**
     * 按指定格式渲染 EXPLAIN 结果。
     *
     * @param result EXPLAIN 结果
     * @param format 输出格式（null 视为 TREE）
     * @return 格式化文本
     */
    public static String format(ExplainResult result, ExplainFormat format) {
        Objects.requireNonNull(result, "result");
        ExplainFormat fmt = format == null ? ExplainFormat.TREE : format;
        return switch (fmt) {
            case TREE -> formatTree(result);
            case JSON -> formatJson(result);
            case TABLE -> formatTable(result);
        };
    }

    // ===================== 树形格式 =====================

    /**
     * 渲染树形格式。
     *
     * <p>结构：</p>
     * <pre>
     *   EXPLAIN
     *   SQL: SELECT ...
     *   ├─ LogicalProject(...)
     *   │  ├─ LogicalFilter(...)
     *   │  └─ LogicalTableScan(...)
     *   PushDown: 75.00% (3/4)
     *   Cost: cpu=100.0, io=50.0, net=200.0
     *   Suggestions: 2
     * </pre>
     *
     * @param result EXPLAIN 结果
     * @return 树形文本
     */
    static String formatTree(ExplainResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("EXPLAIN\n");
        sb.append("SQL: ").append(nullSafe(result.getSql())).append('\n');
        sb.append("Status: ").append(result.isSuccess() ? "SUCCESS" : "FAILURE").append('\n');

        if (!result.isSuccess()) {
            sb.append("Error: ").append(nullSafe(result.getError())).append('\n');
            return sb.toString();
        }

        sb.append("RowCount: ").append(formatDouble(result.getRowCount())).append('\n');
        sb.append("Depth: ").append(result.getDepth()).append('\n');
        sb.append("RulesApplied: ").append(result.getRulesApplied()).append('\n');
        sb.append("Plan:\n");

        if (result.getRelNode() == null) {
            sb.append("  (empty plan)\n");
        } else {
            renderTree(result.getRelNode(), "", true, sb);
        }

        // 下推统计
        sb.append("PushDown:\n");
        appendStatsTree(result.getPushDownStats(), sb, "  ");

        // Cost 统计
        sb.append("Cost:\n");
        appendStatsTree(result.getCostStats(), sb, "  ");

        // 调优建议
        sb.append("Suggestions: ").append(result.getTuningSuggestions().size()).append('\n');
        for (int i = 0; i < result.getTuningSuggestions().size(); i++) {
            sb.append("  [").append(i + 1).append("] ")
                    .append(result.getTuningSuggestions().get(i)).append('\n');
        }
        return sb.toString();
    }

    /**
     * 递归渲染 RelNode 树。
     *
     * @param node    当前节点
     * @param prefix  前缀（缩进 + 管道符）
     * @param isLast  是否为父节点的最后一个子节点
     * @param sb      输出缓冲
     */
    private static void renderTree(CustomRelNode node, String prefix, boolean isLast,
                                   StringBuilder sb) {
        if (node == null) {
            return;
        }
        String branch = isLast ? BRANCH_END : BRANCH_MID;
        sb.append(prefix).append(branch).append(nodeLabel(node)).append('\n');

        List<CustomRelNode> children = node.getChildren();
        String childPrefix = prefix + (isLast ? SPACE : PIPE);
        for (int i = 0; i < children.size(); i++) {
            renderTree(children.get(i), childPrefix, i == children.size() - 1, sb);
        }
    }

    /**
     * 生成节点标签（含操作类型、表名、下推状态等）。
     *
     * @param node RelNode 节点
     * @return 标签字符串
     */
    private static String nodeLabel(CustomRelNode node) {
        StringBuilder sb = new StringBuilder();
        sb.append(node.getOp());
        if (node.getTableName() != null) {
            sb.append(" table=").append(node.getTableName());
        }
        if (node.getSourceName() != null) {
            sb.append(" source=").append(node.getSourceName());
        }
        if (node.getCondition() != null) {
            sb.append(" cond=[").append(truncate(node.getCondition(), 40)).append("]");
        }
        if (node.getProjects() != null && !node.getProjects().isEmpty()) {
            sb.append(" proj=").append(node.getProjects());
        }
        sb.append(" pushDown=").append(node.getPushDownStatus());
        if (node.getPushDownReason() != null) {
            sb.append(" reason=").append(truncate(node.getPushDownReason(), 30));
        }
        if (node.getRemark() != null) {
            sb.append(" // ").append(truncate(node.getRemark(), 40));
        }
        return sb.toString();
    }

    /**
     * 将统计指标 Map 渲染为树形子节点。
     *
     * @param stats 统计指标
     * @param sb    输出缓冲
     * @param indent 缩进
     */
    private static void appendStatsTree(Map<String, Object> stats, StringBuilder sb,
                                        String indent) {
        if (stats == null || stats.isEmpty()) {
            sb.append(indent).append("(none)\n");
            return;
        }
        for (Map.Entry<String, Object> entry : stats.entrySet()) {
            sb.append(indent).append(entry.getKey()).append(": ")
                    .append(formatValue(entry.getValue())).append('\n');
        }
    }

    // ===================== JSON 格式 =====================

    /**
     * 渲染 JSON 格式。
     *
     * <p>结构：</p>
     * <pre>
     * {
     *   "sql": "SELECT ...",
     *   "success": true,
     *   "rowCount": 100.0,
     *   "depth": 3,
     *   "rulesApplied": ["FilterPushDown", "ProjectPushDown"],
     *   "plan": { "op": "PROJECT", "children": [...] },
     *   "pushDown": { "totalRate": 0.75, ... },
     *   "cost": { "cpu": 100.0, ... },
     *   "suggestions": ["建议1", "建议2"]
     * }
     * </pre>
     *
     * @param result EXPLAIN 结果
     * @return JSON 文本
     */
    static String formatJson(ExplainResult result) {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"sql\":").append(jsonStr(escape(result.getSql()))).append(",");
        sb.append("\"success\":").append(result.isSuccess()).append(",");

        if (!result.isSuccess()) {
            sb.append("\"error\":").append(jsonStr(escape(result.getError())));
            sb.append('}');
            return sb.toString();
        }

        sb.append("\"rowCount\":").append(result.getRowCount()).append(",");
        sb.append("\"depth\":").append(result.getDepth()).append(",");
        sb.append("\"rulesApplied\":").append(jsonArray(result.getRulesApplied())).append(",");
        sb.append("\"plan\":").append(relNodeToJson(result.getRelNode())).append(",");
        sb.append("\"pushDown\":").append(statsToJson(result.getPushDownStats())).append(",");
        sb.append("\"cost\":").append(statsToJson(result.getCostStats())).append(",");
        sb.append("\"suggestions\":").append(jsonArray(result.getTuningSuggestions()));
        sb.append('}');
        return sb.toString();
    }

    /**
     * 将 CustomRelNode 转为 JSON 对象。
     *
     * @param node RelNode 节点
     * @return JSON 字符串
     */
    private static String relNodeToJson(CustomRelNode node) {
        if (node == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"op\":").append(jsonStr(node.getOp().name()));
        if (node.getTableName() != null) {
            sb.append(",\"table\":").append(jsonStr(escape(node.getTableName())));
        }
        if (node.getSourceName() != null) {
            sb.append(",\"source\":").append(jsonStr(escape(node.getSourceName())));
        }
        if (node.getCondition() != null) {
            sb.append(",\"condition\":").append(jsonStr(escape(node.getCondition())));
        }
        if (node.getProjects() != null && !node.getProjects().isEmpty()) {
            sb.append(",\"projects\":").append(jsonArray(node.getProjects()));
        }
        sb.append(",\"pushDown\":").append(jsonStr(node.getPushDownStatus().name()));
        if (node.getPushDownReason() != null) {
            sb.append(",\"reason\":").append(jsonStr(escape(node.getPushDownReason())));
        }
        if (node.getRemark() != null) {
            sb.append(",\"remark\":").append(jsonStr(escape(node.getRemark())));
        }
        if (node.getEstimatedRows() > 0) {
            sb.append(",\"estimatedRows\":").append(node.getEstimatedRows());
        }
        if (node.getEstimatedCost() > 0) {
            sb.append(",\"estimatedCost\":").append(node.getEstimatedCost());
        }
        List<CustomRelNode> children = node.getChildren();
        if (!children.isEmpty()) {
            sb.append(",\"children\":[");
            for (int i = 0; i < children.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(relNodeToJson(children.get(i)));
            }
            sb.append(']');
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 将统计指标 Map 转为 JSON 对象。
     *
     * @param stats 统计指标
     * @return JSON 字符串
     */
    private static String statsToJson(Map<String, Object> stats) {
        if (stats == null || stats.isEmpty()) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Object> entry : stats.entrySet()) {
            if (i++ > 0) {
                sb.append(',');
            }
            sb.append(jsonStr(escape(entry.getKey()))).append(":")
                    .append(valueToJson(entry.getValue()));
        }
        sb.append('}');
        return sb.toString();
    }

    /**
     * 将 Java 值转为 JSON 值。
     *
     * @param value 值
     * @return JSON 字符串
     */
    @SuppressWarnings("unchecked")
    private static String valueToJson(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Map) {
            return statsToJson((Map<String, Object>) value);
        }
        if (value instanceof List) {
            return jsonArray((List<String>) value);
        }
        return jsonStr(escape(value.toString()));
    }

    // ===================== 表格格式 =====================

    /**
     * 渲染表格格式。
     *
     * <p>结构：</p>
     * <pre>
     * EXPLAIN
     * SQL: SELECT ...
     *
     * | ID | Op          | Table    | Source   | PushDown | Condition          | Cost   |
     * |----|-------------|----------|----------|----------|--------------------|--------|
     * | 0  | PROJECT     | -        | -        | PUSHED   | -                  | 100.0  |
     * | 1  | FILTER      | -        | -        | NOT_PUSH | id=100 AND age>18  | 80.0   |
     * | 2  | TABLE_SCAN  | users    | doris    | PUSHED   | -                  | 50.0   |
     *
     * PushDown Statistics:
     * | Metric        | Value   |
     * |---------------|---------|
     * | totalRate     | 75.00%  |
     * ...
     * </pre>
     *
     * @param result EXPLAIN 结果
     * @return 表格文本
     */
    static String formatTable(ExplainResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("EXPLAIN\n");
        sb.append("SQL: ").append(nullSafe(result.getSql())).append('\n');
        sb.append("Status: ").append(result.isSuccess() ? "SUCCESS" : "FAILURE").append("\n\n");

        if (!result.isSuccess()) {
            sb.append("Error: ").append(nullSafe(result.getError())).append('\n');
            return sb.toString();
        }

        sb.append("RowCount: ").append(formatDouble(result.getRowCount())).append('\n');
        sb.append("Depth: ").append(result.getDepth()).append('\n');
        sb.append("RulesApplied: ").append(String.join(", ", result.getRulesApplied()))
                .append("\n\n");

        // 节点表
        List<String> headers = List.of("ID", "Op", "Table", "Source",
                "PushDown", "Condition", "Projects", "Remark");
        List<List<String>> rows = new ArrayList<>();
        if (result.getRelNode() != null) {
            collectRows(result.getRelNode(), new int[]{0}, rows);
        }
        sb.append("Plan Nodes:\n");
        renderTable(headers, rows, sb);
        sb.append('\n');

        // 下推统计表
        sb.append("PushDown Statistics:\n");
        renderStatsTable(result.getPushDownStats(), sb);
        sb.append('\n');

        // Cost 统计表
        sb.append("Cost Statistics:\n");
        renderStatsTable(result.getCostStats(), sb);
        sb.append('\n');

        // 调优建议
        sb.append("Tuning Suggestions:\n");
        if (result.getTuningSuggestions().isEmpty()) {
            sb.append("  (none)\n");
        } else {
            for (int i = 0; i < result.getTuningSuggestions().size(); i++) {
                sb.append("  [").append(i + 1).append("] ")
                        .append(result.getTuningSuggestions().get(i)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 递归收集节点行数据。
     *
     * @param node  当前节点
     * @param idSeq ID 序列生成器
     * @param rows  行集合
     */
    private static void collectRows(CustomRelNode node, int[] idSeq,
                                    List<List<String>> rows) {
        if (node == null) {
            return;
        }
        int id = idSeq[0]++;
        rows.add(List.of(
                String.valueOf(id),
                node.getOp().name(),
                nullSafe(node.getTableName(), "-"),
                nullSafe(node.getSourceName(), "-"),
                node.getPushDownStatus().name(),
                truncate(nullSafe(node.getCondition(), "-"), 30),
                node.getProjects() == null || node.getProjects().isEmpty()
                        ? "-" : truncate(node.getProjects().toString(), 30),
                truncate(nullSafe(node.getRemark(), "-"), 30)
        ));
        for (CustomRelNode child : node.getChildren()) {
            collectRows(child, idSeq, rows);
        }
    }

    /**
     * 渲染 ASCII 表格。
     *
     * @param headers 表头
     * @param rows    数据行
     * @param sb      输出缓冲
     */
    private static void renderTable(List<String> headers, List<List<String>> rows,
                                    StringBuilder sb) {
        if (rows.isEmpty()) {
            sb.append("  (no rows)\n");
            return;
        }
        // 计算列宽
        int[] widths = new int[headers.size()];
        for (int i = 0; i < headers.size(); i++) {
            widths[i] = headers.get(i).length();
        }
        for (List<String> row : rows) {
            for (int i = 0; i < row.size() && i < widths.length; i++) {
                widths[i] = Math.max(widths[i], row.get(i).length());
            }
        }

        // 表头
        appendRow(headers, widths, sb);
        // 分隔线
        sb.append('|');
        for (int w : widths) {
            sb.append(H_LINE.repeat(w + 2)).append('|');
        }
        sb.append('\n');
        // 数据行
        for (List<String> row : rows) {
            appendRow(row, widths, sb);
        }
    }

    /**
     * 追加一行（按列宽对齐）。
     *
     * @param cells  单元格
     * @param widths 列宽
     * @param sb     输出缓冲
     */
    private static void appendRow(List<String> cells, int[] widths, StringBuilder sb) {
        sb.append('|');
        for (int i = 0; i < widths.length; i++) {
            String cell = i < cells.size() ? cells.get(i) : "";
            sb.append(' ').append(padRight(cell, widths[i])).append(' ').append('|');
        }
        sb.append('\n');
    }

    /**
     * 渲染统计指标表（键值对）。
     *
     * @param stats 统计指标
     * @param sb    输出缓冲
     */
    private static void renderStatsTable(Map<String, Object> stats, StringBuilder sb) {
        if (stats == null || stats.isEmpty()) {
            sb.append("  (none)\n");
            return;
        }
        List<String> headers = List.of("Metric", "Value");
        List<List<String>> rows = new ArrayList<>();
        for (Map.Entry<String, Object> entry : stats.entrySet()) {
            rows.add(List.of(entry.getKey(), formatValue(entry.getValue())));
        }
        renderTable(headers, rows, sb);
    }

    // ===================== 辅助方法 =====================

    /**
     * 将扁平化指标 Map 转为分层结构（按点分路径分组）。
     *
     * <p>例如 {@code {"pushDown.totalRate": 0.75, "pushDown.byType.equality": 1.0}}
     * 转为 {@code {"pushDown": {"totalRate": 0.75, "byType": {"equality": 1.0}}}}。</p>
     *
     * @param flat 扁平指标
     * @return 分层指标
     */
    public static Map<String, Object> nest(Map<String, Object> flat) {
        Map<String, Object> root = new LinkedHashMap<>();
        if (flat == null) {
            return root;
        }
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            String[] parts = entry.getKey().split("\\.");
            @SuppressWarnings("unchecked")
            Map<String, Object> current = root;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = current.get(parts[i]);
                if (!(next instanceof Map)) {
                    next = new LinkedHashMap<String, Object>();
                    current.put(parts[i], next);
                }
                current = (Map<String, Object>) next;
            }
            current.put(parts[parts.length - 1], entry.getValue());
        }
        return root;
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String nullSafe(String s, String def) {
        return s == null || s.isEmpty() ? def : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) {
            return s;
        }
        return s + " ".repeat(width - s.length());
    }

    private static String formatDouble(double d) {
        if (d == (long) d) {
            return String.valueOf((long) d);
        }
        return String.format("%.2f", d);
    }

    private static String formatValue(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof Double d) {
            return formatDouble(d);
        }
        if (v instanceof Float f) {
            return formatDouble(f);
        }
        return v.toString();
    }

    private static String jsonStr(String s) {
        return "\"" + s + "\"";
    }

    private static String jsonArray(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(jsonStr(escape(items.get(i))));
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}