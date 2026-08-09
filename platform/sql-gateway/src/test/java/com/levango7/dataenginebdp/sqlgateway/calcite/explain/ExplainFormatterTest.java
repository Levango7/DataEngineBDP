package com.levango7.dataenginebdp.sqlgateway.calcite.explain;

import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ExplainFormatter} 单元测试——覆盖树形/JSON/表格式三种输出。
 *
 * @author shuqing-bigdata
 */
@DisplayName("ExplainFormatter 格式化测试")
class ExplainFormatterTest {

    // ===================== 测试夹具 =====================

    /**
     * 构造典型 RelNode 树：Project → Filter → TableScan。
     *
     * @return RelNode 树
     */
    private CustomRelNode sampleTree() {
        CustomRelNode scan = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("users")
                .setSourceName("doris")
                .setProjects(Arrays.asList("id", "name", "age"))
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED)
                .setRemark("pushed filter: age>18");
        scan.addPushedOperation("filter: age>18");

        CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("id = 100 AND age > 18")
                .setPushDownStatus(CustomRelNode.PushDownStatus.PARTIALLY_PUSHED)
                .setPushDownReason("UDF(name) 不可下推");
        filter.addChild(scan);

        CustomRelNode project = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setProjects(Arrays.asList("name", "age"))
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
        project.addChild(filter);
        return project;
    }

    /**
     * 构造完整 ExplainResult。
     *
     * @return ExplainResult
     */
    private ExplainResult sampleResult() {
        Map<String, Object> pushDown = new LinkedHashMap<>();
        pushDown.put("pushDown.predicate.rate", 0.75);
        pushDown.put("pushDown.predicate.ratePct", "75.00%");
        pushDown.put("pushDown.projection.reductionRate", 0.33);

        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("cost.cpu", 100.0);
        cost.put("cost.io", 50.0);
        cost.put("cost.network", 200.0);
        cost.put("cost.total", 350.0);
        cost.put("cost.bottleneck", "NETWORK");

        List<String> suggestions = Arrays.asList(
                "[CRITICAL] 下推率过低",
                "[WARN] Cost 瓶颈为 NETWORK");

        return ExplainResult.success(
                "SELECT name, age FROM users WHERE id = 100 AND age > 18",
                sampleTree(), 100.0, 3,
                Arrays.asList("FilterPushDown", "ProjectPushDown"),
                pushDown, cost, suggestions);
    }

    // ===================== format 分发测试 =====================

    @Test
    @DisplayName("format 按 TREE 格式输出")
    void testFormatTree() {
        ExplainResult result = sampleResult();
        String out = ExplainFormatter.format(result, ExplainFormat.TREE);
        assertTrue(out.contains("EXPLAIN"));
        assertTrue(out.contains("SQL:"));
        assertTrue(out.contains("Status: SUCCESS"));
        assertTrue(out.contains("Plan:"));
        assertTrue(out.contains("PROJECT"));
        assertTrue(out.contains("FILTER"));
        assertTrue(out.contains("TABLE_SCAN"));
        assertTrue(out.contains("PushDown:"));
        assertTrue(out.contains("Cost:"));
        assertTrue(out.contains("Suggestions:"));
    }

    @Test
    @DisplayName("format 按 JSON 格式输出")
    void testFormatJson() {
        ExplainResult result = sampleResult();
        String out = ExplainFormatter.format(result, ExplainFormat.JSON);
        assertTrue(out.startsWith("{"));
        assertTrue(out.endsWith("}"));
        assertTrue(out.contains("\"sql\""));
        assertTrue(out.contains("\"success\":true"));
        assertTrue(out.contains("\"plan\""));
        assertTrue(out.contains("\"op\":\"PROJECT\""));
        assertTrue(out.contains("\"children\""));
        assertTrue(out.contains("\"pushDown\""));
        assertTrue(out.contains("\"cost\""));
        assertTrue(out.contains("\"suggestions\""));
    }

    @Test
    @DisplayName("format 按 TABLE 格式输出")
    void testFormatTable() {
        ExplainResult result = sampleResult();
        String out = ExplainFormatter.format(result, ExplainFormat.TABLE);
        assertTrue(out.contains("EXPLAIN"));
        assertTrue(out.contains("Plan Nodes:"));
        assertTrue(out.contains("| ID |"));
        assertTrue(out.contains("| 0  |"));
        assertTrue(out.contains("PushDown Statistics:"));
        assertTrue(out.contains("Cost Statistics:"));
        assertTrue(out.contains("Tuning Suggestions:"));
    }

    @Test
    @DisplayName("format null 视为 TREE")
    void testFormatNull() {
        ExplainResult result = sampleResult();
        String out = ExplainFormatter.format(result, null);
        assertTrue(out.contains("EXPLAIN"));
        assertTrue(out.contains("Plan:"));
    }

    @Test
    @DisplayName("format result 为 null 抛 NPE")
    void testFormatNullResult() {
        assertThrows(NullPointerException.class,
                () -> ExplainFormatter.format(null, ExplainFormat.TREE));
    }

    // ===================== 失败结果测试 =====================

    @Test
    @DisplayName("TREE 格式渲染失败结果")
    void testTreeFailure() {
        ExplainResult fail = ExplainResult.failure("BAD SQL", "语法错误");
        String out = ExplainFormatter.format(fail, ExplainFormat.TREE);
        assertTrue(out.contains("Status: FAILURE"));
        assertTrue(out.contains("Error: 语法错误"));
    }

    @Test
    @DisplayName("JSON 格式渲染失败结果")
    void testJsonFailure() {
        ExplainResult fail = ExplainResult.failure("BAD SQL", "语法错误");
        String out = ExplainFormatter.format(fail, ExplainFormat.JSON);
        assertTrue(out.contains("\"success\":false"));
        assertTrue(out.contains("\"error\":\"语法错误\""));
    }

    @Test
    @DisplayName("TABLE 格式渲染失败结果")
    void testTableFailure() {
        ExplainResult fail = ExplainResult.failure("BAD SQL", "语法错误");
        String out = ExplainFormatter.format(fail, ExplainFormat.TABLE);
        assertTrue(out.contains("Status: FAILURE"));
        assertTrue(out.contains("Error: 语法错误"));
    }

    // ===================== 边界情况 =====================

    @Test
    @DisplayName("TREE 格式 relNode 为 null")
    void testTreeNullRelNode() {
        ExplainResult result = ExplainResult.success("sql", null, 0, 0,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        String out = ExplainFormatter.format(result, ExplainFormat.TREE);
        assertTrue(out.contains("(empty plan)"));
    }

    @Test
    @DisplayName("JSON 格式 relNode 为 null")
    void testJsonNullRelNode() {
        ExplainResult result = ExplainResult.success("sql", null, 0, 0,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        String out = ExplainFormatter.format(result, ExplainFormat.JSON);
        assertTrue(out.contains("\"plan\":null"));
    }

    @Test
    @DisplayName("TABLE 格式无节点")
    void testTableNoNodes() {
        ExplainResult result = ExplainResult.success("sql", null, 0, 0,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        String out = ExplainFormatter.format(result, ExplainFormat.TABLE);
        assertTrue(out.contains("(no rows)"));
    }

    @Test
    @DisplayName("空统计指标渲染 (none)")
    void testEmptyStats() {
        ExplainResult result = ExplainResult.success("sql", null, 0, 0,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        String tree = ExplainFormatter.format(result, ExplainFormat.TREE);
        assertTrue(tree.contains("(none)"));
    }

    @Test
    @DisplayName("长条件/备注被截断")
    void testTruncation() {
        String longCond = "a > 1 AND b > 2 AND c > 3 AND d > 4 AND e > 5 AND f > 6";
        CustomRelNode filter = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition(longCond)
                .setRemark(longCond);
        CustomRelNode root = CustomRelNode.of(CustomRelNode.Op.PROJECT);
        root.addChild(filter);

        ExplainResult result = ExplainResult.success("sql", root, 0, 2,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        String tree = ExplainFormatter.format(result, ExplainFormat.TREE);
        assertTrue(tree.contains("..."));
    }

    // ===================== nest 方法测试 =====================

    @Test
    @DisplayName("nest 将扁平键转为分层结构")
    void testNest() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("pushDown.rate", 0.75);
        flat.put("pushDown.byType.EQUALITY.rate", 1.0);
        flat.put("cost.total", 100.0);

        Map<String, Object> nested = ExplainFormatter.nest(flat);
        assertNotNull(nested.get("pushDown"));
        assertNotNull(nested.get("cost"));
        @SuppressWarnings("unchecked")
        Map<String, Object> pushDown = (Map<String, Object>) nested.get("pushDown");
        assertEquals(0.75, pushDown.get("rate"));
        @SuppressWarnings("unchecked")
        Map<String, Object> byType = (Map<String, Object>) pushDown.get("byType");
        @SuppressWarnings("unchecked")
        Map<String, Object> equality = (Map<String, Object>) byType.get("EQUALITY");
        assertEquals(1.0, equality.get("rate"));
    }

    @Test
    @DisplayName("nest 空输入返回空 Map")
    void testNestEmpty() {
        Map<String, Object> result = ExplainFormatter.nest(null);
        assertTrue(result.isEmpty());
        result = ExplainFormatter.nest(Collections.emptyMap());
        assertTrue(result.isEmpty());
    }

    // ===================== JSON 转义测试 =====================

    @Test
    @DisplayName("JSON 格式正确转义特殊字符")
    void testJsonEscape() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("name = \"Alice\"\nAND age > 18");
        ExplainResult result = ExplainResult.success("SELECT \"x\"", node, 0, 1,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        String json = ExplainFormatter.format(result, ExplainFormat.JSON);
        assertTrue(json.contains("\\\""));
        assertTrue(json.contains("\\n"));
    }

    // ===================== 表格对齐测试 =====================

    @Test
    @DisplayName("TABLE 格式列宽对齐")
    void testTableAlignment() {
        ExplainResult result = sampleResult();
        String out = ExplainFormatter.format(result, ExplainFormat.TABLE);
        // 验证包含分隔线
        assertTrue(out.contains("|--") || out.contains("|-"));
        // 验证每行以 | 开头
        for (String line : out.split("\n")) {
            if (line.startsWith("|") && line.endsWith("|")) {
                // 表格行格式正确
                assertTrue(line.contains("|"));
            }
        }
    }

    @Test
    @DisplayName("TREE 格式树形分支字符正确")
    void testTreeBranches() {
        ExplainResult result = sampleResult();
        String out = ExplainFormatter.format(result, ExplainFormat.TREE);
        // 应包含 └─ 或 ├─ 分支字符
        assertTrue(out.contains("└─ ") || out.contains("├─ "));
    }

    @Test
    @DisplayName("多子节点树形渲染")
    void testMultipleChildren() {
        CustomRelNode root = CustomRelNode.of(CustomRelNode.Op.JOIN)
                .setCondition("a.id = b.uid");
        root.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("a").setSourceName("doris"));
        root.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("b").setSourceName("trino"));

        ExplainResult result = ExplainResult.success("sql", root, 0, 2,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        String tree = ExplainFormatter.format(result, ExplainFormat.TREE);
        assertTrue(tree.contains("JOIN"));
        assertTrue(tree.contains("table=a"));
        assertTrue(tree.contains("table=b"));
    }

    @Test
    @DisplayName("JSON 含 estimatedRows/Cost")
    void testJsonWithEstimates() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("t").setSourceName("doris")
                .setEstimatedRows(100)
                .setEstimatedCost(50);
        ExplainResult result = ExplainResult.success("sql", node, 0, 1,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        String json = ExplainFormatter.format(result, ExplainFormat.JSON);
        assertTrue(json.contains("\"estimatedRows\":100.0"));
        assertTrue(json.contains("\"estimatedCost\":50.0"));
    }

    @Test
    @DisplayName("JSON 嵌套 Map/List 值渲染")
    void testJsonNestedValues() {
        Map<String, Object> pushDown = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("subKey", "subVal");
        pushDown.put("nested", nested);
        pushDown.put("list", Arrays.asList("a", "b"));

        ExplainResult result = ExplainResult.success("sql", null, 0, 0,
                Collections.emptyList(), pushDown, Collections.emptyMap(),
                Collections.emptyList());
        String json = ExplainFormatter.format(result, ExplainFormat.JSON);
        assertTrue(json.contains("\"nested\""));
        assertTrue(json.contains("\"subKey\""));
        assertTrue(json.contains("\"list\""));
    }

    @Test
    @DisplayName("formatValue 处理 Float 类型")
    void testFormatValueFloat() {
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("floatVal", 1.5f);
        cost.put("nullVal", null);

        ExplainResult result = ExplainResult.success("sql", null, 0, 0,
                Collections.emptyList(), Collections.emptyMap(), cost,
                Collections.emptyList());
        String table = ExplainFormatter.format(result, ExplainFormat.TABLE);
        assertTrue(table.contains("1.50"));
        assertTrue(table.contains("null"));
    }

    @Test
    @DisplayName("TABLE 格式空建议列表")
    void testTableEmptySuggestions() {
        ExplainResult result = ExplainResult.success("sql", null, 0, 0,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        String table = ExplainFormatter.format(result, ExplainFormat.TABLE);
        assertTrue(table.contains("(none)"));
    }

    @Test
    @DisplayName("TREE 格式空建议列表")
    void testTreeEmptySuggestions() {
        ExplainResult result = ExplainResult.success("sql", null, 0, 0,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        String tree = ExplainFormatter.format(result, ExplainFormat.TREE);
        assertTrue(tree.contains("Suggestions: 0"));
    }
}