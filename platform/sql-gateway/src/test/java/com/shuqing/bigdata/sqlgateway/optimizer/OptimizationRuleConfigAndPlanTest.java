package com.shuqing.bigdata.sqlgateway.optimizer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OptimizationRuleConfig} 与 {@link ExecutionPlanGenerator} 单元测试。
 *
 * @author shuqing-bigdata
 */
class OptimizationRuleConfigAndPlanTest {

    @Test
    @DisplayName("默认配置启用 5+ 规则")
    void testDefaultConfig() {
        OptimizationRuleConfig config = new OptimizationRuleConfig();
        assertTrue(config.isEnabled(OptimizationRuleConfig.Rule.FILTER_MERGE));
        assertTrue(config.isEnabled(OptimizationRuleConfig.Rule.FILTER_PUSH_DOWN));
        assertTrue(config.isEnabled(OptimizationRuleConfig.Rule.PROJECT_MERGE));
        assertTrue(config.getEnabledRules().size() >= 5);
    }

    @Test
    @DisplayName("enable/disable 单个规则")
    void testEnableDisable() {
        OptimizationRuleConfig config = new OptimizationRuleConfig();
        config.disable(OptimizationRuleConfig.Rule.FILTER_MERGE);
        assertFalse(config.isEnabled(OptimizationRuleConfig.Rule.FILTER_MERGE));
        config.enable(OptimizationRuleConfig.Rule.FILTER_MERGE);
        assertTrue(config.isEnabled(OptimizationRuleConfig.Rule.FILTER_MERGE));
    }

    @Test
    @DisplayName("enableAll 启用所有规则")
    void testEnableAll() {
        OptimizationRuleConfig config = new OptimizationRuleConfig().enableAll();
        for (OptimizationRuleConfig.Rule r : OptimizationRuleConfig.Rule.values()) {
            assertTrue(config.isEnabled(r));
        }
    }

    @Test
    @DisplayName("disableAll 禁用所有规则")
    void testDisableAll() {
        OptimizationRuleConfig config = new OptimizationRuleConfig().disableAll();
        for (OptimizationRuleConfig.Rule r : OptimizationRuleConfig.Rule.values()) {
            assertFalse(config.isEnabled(r));
        }
    }

    @Test
    @DisplayName("getAllRules 返回不可变映射")
    void testGetAllRules() {
        OptimizationRuleConfig config = new OptimizationRuleConfig();
        assertEquals(OptimizationRuleConfig.Rule.values().length, config.getAllRules().size());
    }

    @Test
    @DisplayName("Rule 枚举含 Calcite 类名")
    void testRuleCalciteClassName() {
        assertNotNull(OptimizationRuleConfig.Rule.FILTER_MERGE.getCalciteClassName());
        assertTrue(OptimizationRuleConfig.Rule.FILTER_MERGE.getCalciteClassName()
                .contains("calcite"));
    }

    @Test
    @DisplayName("ExecutionPlanGenerator 生成计划树")
    void testGeneratePlanTree() {
        RelNode scan = RelNode.of(RelNode.Op.TABLE_SCAN).setTableName("users");
        RelNode filter = RelNode.of(RelNode.Op.FILTER).setCondition("age > 18");
        filter.addChild(scan);
        ExecutionPlanGenerator gen = new ExecutionPlanGenerator();
        String plan = gen.generate(filter);
        assertTrue(plan.contains("Filter"));
        assertTrue(plan.contains("TableScan"));
        assertTrue(plan.contains("users"));
        assertTrue(plan.contains("Table Access Order"));
        assertTrue(plan.contains("Estimated Cost"));
    }

    @Test
    @DisplayName("ExecutionPlanGenerator 处理 null")
    void testGenerateNull() {
        ExecutionPlanGenerator gen = new ExecutionPlanGenerator();
        String plan = gen.generate(null);
        assertNotNull(plan);
        assertTrue(plan.contains("Empty"));
    }

    @Test
    @DisplayName("extractTableAccessOrder 返回表顺序")
    void testExtractTableAccessOrder() {
        RelNode left = RelNode.of(RelNode.Op.TABLE_SCAN).setTableName("a");
        RelNode right = RelNode.of(RelNode.Op.TABLE_SCAN).setTableName("b");
        RelNode join = RelNode.of(RelNode.Op.JOIN).setJoinType("INNER");
        join.addChild(left);
        join.addChild(right);
        ExecutionPlanGenerator gen = new ExecutionPlanGenerator();
        java.util.List<String> tables = gen.extractTableAccessOrder(join);
        assertEquals(2, tables.size());
        assertEquals("a", tables.get(0));
        assertEquals("b", tables.get(1));
    }

    @Test
    @DisplayName("generatePlanTree 不含统计信息")
    void testGeneratePlanTreeOnly() {
        RelNode scan = RelNode.of(RelNode.Op.TABLE_SCAN).setTableName("users");
        ExecutionPlanGenerator gen = new ExecutionPlanGenerator();
        String tree = gen.generatePlanTree(scan);
        assertTrue(tree.contains("TableScan"));
        assertFalse(tree.contains("Table Access Order"));
    }
}