package com.levango7.dataenginebdp.sqlgateway.optimizer;

import com.levango7.dataenginebdp.sqlgateway.parser.SqlDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link SqlOptimizerService} 单元测试。
 *
 * <p>验证完整优化流程：SQL → AST → RelNode → 优化 → 执行计划，
 * 覆盖谓词下推、列裁剪、Join 重排、代价估算、执行计划生成、REST 端点等。</p>
 *
 * @author shuqing-bigdata
 */
class SqlOptimizerServiceTest {

    private SqlOptimizerService optimizer;

    @BeforeEach
    void setUp() {
        optimizer = new SqlOptimizerService();
    }

    @Test
    @DisplayName("简单 SELECT 优化成功")
    void testOptimizeSimpleSelect() {
        OptimizationResult result = optimizer.optimize("SELECT * FROM users", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        assertNotNull(result.getExecutionPlan());
        assertFalse(result.getExecutionPlan().isEmpty());
        assertTrue(result.getTableAccesses().contains("users"));
    }

    @Test
    @DisplayName("SELECT + WHERE 优化")
    void testOptimizeWithWhere() {
        OptimizationResult result = optimizer.optimize(
                "SELECT id, name FROM users WHERE age > 18", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        assertTrue(result.getExecutionPlan().contains("Filter"));
        assertTrue(result.getExecutionPlan().contains("users"));
    }

    @Test
    @DisplayName("JOIN 优化")
    void testOptimizeJoin() {
        OptimizationResult result = optimizer.optimize(
                "SELECT * FROM orders o JOIN users u ON o.uid = u.id", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        assertTrue(result.getExecutionPlan().contains("Join"));
        assertEquals(2, result.getTableAccesses().size());
    }

    @Test
    @DisplayName("GROUP BY 优化")
    void testOptimizeGroupBy() {
        OptimizationResult result = optimizer.optimize(
                "SELECT dept, COUNT(*) FROM emp GROUP BY dept", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        assertTrue(result.getExecutionPlan().contains("Aggregate"));
    }

    @Test
    @DisplayName("ORDER BY + LIMIT 优化")
    void testOptimizeOrderByLimit() {
        OptimizationResult result = optimizer.optimize(
                "SELECT * FROM users ORDER BY id DESC LIMIT 10", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        assertTrue(result.getExecutionPlan().contains("Sort"));
        assertTrue(result.getExecutionPlan().contains("Limit"));
    }

    @Test
    @DisplayName("完整复杂查询优化")
    void testOptimizeComplexQuery() {
        OptimizationResult result = optimizer.optimize(
                "SELECT u.name, COUNT(*) AS cnt FROM orders o " +
                        "JOIN users u ON o.uid = u.id " +
                        "WHERE o.amount > 100 " +
                        "GROUP BY u.name " +
                        "ORDER BY cnt DESC LIMIT 20", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        assertNotNull(result.getExecutionPlan());
        assertTrue(result.getEstimatedCost() > 0);
    }

    @Test
    @DisplayName("执行计划包含表访问顺序")
    void testExecutionPlanContainsTableOrder() {
        OptimizationResult result = optimizer.optimize(
                "SELECT * FROM a JOIN b ON a.id = b.id", SqlDialect.ANSI);
        assertTrue(result.getExecutionPlan().contains("Table Access Order"));
        assertTrue(result.getTableAccesses().contains("a"));
        assertTrue(result.getTableAccesses().contains("b"));
    }

    @Test
    @DisplayName("执行计划包含代价估算")
    void testExecutionPlanContainsCost() {
        OptimizationResult result = optimizer.optimize(
                "SELECT * FROM users", SqlDialect.ANSI);
        assertTrue(result.getExecutionPlan().contains("Estimated Cost"));
        assertTrue(result.getEstimatedRows() > 0);
    }

    @Test
    @DisplayName("空 SQL 返回失败")
    void testOptimizeEmptySql() {
        OptimizationResult result = optimizer.optimize("", SqlDialect.ANSI);
        assertFalse(result.isSuccess());
        assertNotNull(result.getError());
    }

    @Test
    @DisplayName("null SQL 返回失败")
    void testOptimizeNullSql() {
        OptimizationResult result = optimizer.optimize(null, SqlDialect.ANSI);
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("非法 SQL 返回失败")
    void testOptimizeInvalidSql() {
        OptimizationResult result = optimizer.optimize("NOT A VALID SQL", SqlDialect.ANSI);
        assertFalse(result.isSuccess());
    }

    @Test
    @DisplayName("getExecutionPlan 直接返回计划文本")
    void testGetExecutionPlan() {
        String plan = optimizer.getExecutionPlan("SELECT * FROM users");
        assertNotNull(plan);
        assertTrue(plan.contains("TableScan"));
    }

    @Test
    @DisplayName("谓词下推规则应用")
    void testFilterPushDown() {
        OptimizationResult result = optimizer.optimize(
                "SELECT id FROM (SELECT id, age FROM users) t WHERE age > 18", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        // 应用了谓词下推
        assertTrue(result.getRulesApplied().contains("FilterPushDownPastProjectRule")
                || result.getExecutionPlan().contains("Filter"));
    }

    @Test
    @DisplayName("优化建议生成")
    void testSuggestionsGenerated() {
        OptimizationResult result = optimizer.optimize(
                "SELECT * FROM users WHERE id = 1", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        // 等值条件应触发索引建议
        boolean hasIndexSuggestion = result.getSuggestions().stream()
                .anyMatch(s -> s.contains("索引"));
        assertTrue(hasIndexSuggestion);
    }

    @Test
    @DisplayName("全表扫描建议")
    void testFullScanSuggestion() {
        OptimizationResult result = optimizer.optimize(
                "SELECT * FROM users", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        boolean hasScanSuggestion = result.getSuggestions().stream()
                .anyMatch(s -> s.contains("全表扫描"));
        assertTrue(hasScanSuggestion);
    }

    @Test
    @DisplayName("LIMIT 无 ORDER BY 建议不确定")
    void testLimitWithoutOrderSuggestion() {
        OptimizationResult result = optimizer.optimize(
                "SELECT * FROM users LIMIT 10", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        boolean hasLimitSuggestion = result.getSuggestions().stream()
                .anyMatch(s -> s.contains("不确定"));
        assertTrue(hasLimitSuggestion);
    }

    @Test
    @DisplayName("规则配置 - 禁用所有规则")
    void testDisableAllRules() {
        OptimizationRuleConfig config = new OptimizationRuleConfig().disableAll();
        optimizer.setRuleConfig(config);
        OptimizationResult result = optimizer.optimize(
                "SELECT id, name FROM users WHERE age > 18", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        assertTrue(result.getRulesApplied().isEmpty());
    }

    @Test
    @DisplayName("规则配置 - 启用所有规则")
    void testEnableAllRules() {
        OptimizationRuleConfig config = new OptimizationRuleConfig().enableAll();
        optimizer.setRuleConfig(config);
        OptimizationResult result = optimizer.optimize(
                "SELECT * FROM a JOIN b ON a.id = b.id", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        // Join 重排序规则启用后可能应用
        assertNotNull(result.getRulesApplied());
    }

    @Test
    @DisplayName("listAvailableRules 返回所有规则")
    void testListAvailableRules() {
        List<String> rules = optimizer.listAvailableRules();
        assertFalse(rules.isEmpty());
        assertTrue(rules.size() >= 5);
    }

    @Test
    @DisplayName("listEnabledRules 返回已启用规则")
    void testListEnabledRules() {
        List<String> enabled = optimizer.listEnabledRules();
        assertFalse(enabled.isEmpty());
    }

    @Test
    @DisplayName("Hive 方言优化")
    void testOptimizeHiveDialect() {
        OptimizationResult result = optimizer.optimize(
                "SELECT * FROM users", SqlDialect.HIVE);
        assertTrue(result.isSuccess());
        assertEquals("HIVE", result.getDialect());
    }

    @Test
    @DisplayName("代价估算：Join 代价高于单表")
    void testJoinCostHigherThanScan() {
        OptimizationResult single = optimizer.optimize(
                "SELECT * FROM users", SqlDialect.ANSI);
        OptimizationResult join = optimizer.optimize(
                "SELECT * FROM a JOIN b ON a.id = b.id", SqlDialect.ANSI);
        assertTrue(single.isSuccess());
        assertTrue(join.isSuccess());
        assertTrue(join.getEstimatedCost() > single.getEstimatedCost());
    }

    @Test
    @DisplayName("UNION 优化")
    void testOptimizeUnion() {
        OptimizationResult result = optimizer.optimize(
                "SELECT id FROM a UNION SELECT id FROM b", SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        assertTrue(result.getExecutionPlan().contains("Union"));
    }

    @Test
    @DisplayName("多表 JOIN 优化")
    void testOptimizeMultiJoin() {
        OptimizationResult result = optimizer.optimize(
                "SELECT * FROM a JOIN b ON a.id = b.aid JOIN c ON b.id = c.bid",
                SqlDialect.ANSI);
        assertTrue(result.isSuccess());
        assertEquals(3, result.getTableAccesses().size());
    }
}