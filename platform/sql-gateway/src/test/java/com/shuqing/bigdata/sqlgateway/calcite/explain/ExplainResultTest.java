package com.shuqing.bigdata.sqlgateway.calcite.explain;

import com.shuqing.bigdata.sqlgateway.calcite.rel.CustomRelNode;
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
 * {@link ExplainResult} 与 {@link ExplainFormat} 单元测试。
 *
 * @author shuqing-bigdata
 */
@DisplayName("ExplainResult 数据模型测试")
class ExplainResultTest {

    @Test
    @DisplayName("ExplainFormat.fromString 大小写无关解析")
    void testFormatFromString() {
        assertEquals(ExplainFormat.TREE, ExplainFormat.fromString("tree"));
        assertEquals(ExplainFormat.JSON, ExplainFormat.fromString("JSON"));
        assertEquals(ExplainFormat.TABLE, ExplainFormat.fromString("table"));
        // null/blank 默认 TREE
        assertEquals(ExplainFormat.TREE, ExplainFormat.fromString(null));
        assertEquals(ExplainFormat.TREE, ExplainFormat.fromString(""));
        assertEquals(ExplainFormat.TREE, ExplainFormat.fromString("  "));
        // 非法名称抛异常
        assertThrows(IllegalArgumentException.class, () -> ExplainFormat.fromString("xml"));
    }

    @Test
    @DisplayName("success 工厂方法构造成功结果")
    void testSuccessFactory() {
        CustomRelNode node = CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("users").setSourceName("doris");
        Map<String, Object> pushDown = new LinkedHashMap<>();
        pushDown.put("rate", 0.75);
        Map<String, Object> cost = new LinkedHashMap<>();
        cost.put("total", 100.0);
        List<String> suggestions = Arrays.asList("[WARN] 建议1", "[INFO] 建议2");

        ExplainResult result = ExplainResult.success(
                "SELECT * FROM users", node, 100.0, 3,
                Arrays.asList("FilterPushDown", "ProjectPushDown"),
                pushDown, cost, suggestions);

        assertTrue(result.isSuccess());
        assertEquals("SELECT * FROM users", result.getSql());
        assertSame(node, result.getRelNode());
        assertEquals(100.0, result.getRowCount());
        assertEquals(3, result.getDepth());
        assertEquals(2, result.getRulesApplied().size());
        assertEquals(0.75, result.getPushDownStats().get("rate"));
        assertEquals(100.0, result.getCostStats().get("total"));
        assertEquals(2, result.getTuningSuggestions().size());
        assertNull(result.getError());
    }

    @Test
    @DisplayName("failure 工厂方法构造失败结果")
    void testFailureFactory() {
        ExplainResult result = ExplainResult.failure("BAD SQL", "解析失败");
        assertFalse(result.isSuccess());
        assertEquals("BAD SQL", result.getSql());
        assertNull(result.getRelNode());
        assertEquals(0.0, result.getRowCount());
        assertEquals(0, result.getDepth());
        assertTrue(result.getRulesApplied().isEmpty());
        assertTrue(result.getPushDownStats().isEmpty());
        assertTrue(result.getCostStats().isEmpty());
        assertTrue(result.getTuningSuggestions().isEmpty());
        assertEquals("解析失败", result.getError());
    }

    @Test
    @DisplayName("null 参数安全处理")
    void testNullSafety() {
        ExplainResult result = ExplainResult.success(
                "sql", null, 0, 0, null, null, null, null);
        assertTrue(result.isSuccess());
        assertNull(result.getRelNode());
        assertTrue(result.getRulesApplied().isEmpty());
        assertTrue(result.getPushDownStats().isEmpty());
        assertTrue(result.getCostStats().isEmpty());
        assertTrue(result.getTuningSuggestions().isEmpty());
    }

    @Test
    @DisplayName("不可变性——返回的 List/Map 不可修改")
    void testImmutability() {
        ExplainResult result = ExplainResult.success(
                "sql", null, 0, 0,
                Collections.singletonList("rule"),
                Collections.singletonMap("k", "v"),
                Collections.singletonMap("c", 1),
                Collections.singletonList("s"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.getRulesApplied().add("x"));
        assertThrows(UnsupportedOperationException.class,
                () -> result.getTuningSuggestions().add("x"));
    }

    @Test
    @DisplayName("summary 与 toString 返回非空字符串")
    void testSummary() {
        ExplainResult ok = ExplainResult.success("sql", null, 10, 2,
                Collections.emptyList(), Collections.emptyMap(),
                Collections.emptyMap(), Collections.emptyList());
        String summary = ok.summary();
        assertTrue(summary.contains("success=true"));
        assertTrue(summary.contains("depth=2"));
        assertTrue(summary.contains("rowCount=10"));

        ExplainResult fail = ExplainResult.failure("sql", "err");
        assertTrue(fail.toString().contains("error=err"));
    }

    @Test
    @DisplayName("failure 构造 error 为 null 抛 NPE")
    void testFailureNullError() {
        assertThrows(NullPointerException.class, () -> ExplainResult.failure("sql", null));
    }
}