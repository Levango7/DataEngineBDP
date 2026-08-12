package com.levango7.dataenginebdp.sqlgateway.calcite.explain;

import com.levango7.dataenginebdp.sqlgateway.calcite.config.DataSourceConfig;
import com.levango7.dataenginebdp.sqlgateway.calcite.rel.CustomRelNode;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PredicateType;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.ProjectionStatistics;
import com.levango7.dataenginebdp.sqlgateway.calcite.rule.PushDownStatistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link TuningAdvisor} 单元测试。
 *
 * @author shuqing-bigdata
 */
@DisplayName("TuningAdvisor 性能调优建议测试")
class TuningAdvisorTest {

    @Test
    @DisplayName("空输入返回空建议")
    void testEmptyInput() {
        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, null, null, null);
        assertTrue(suggestions.isEmpty());
    }

    @Test
    @DisplayName("下推率过低触发 CRITICAL")
    void testLowPushDownRateCritical() {
        PushDownStatistics predStats = new PushDownStatistics();
        // 1/6 ≈ 16.7% < criticalThreshold(20%) → CRITICAL
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.EQUALITY, true);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "UDF", null);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "OR", null);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "子查询", null);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "UDF2", null);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "UDF3", null);

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, predStats, null, null);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("[CRITICAL]")));
    }

    @Test
    @DisplayName("下推率中等触发 WARN")
    void testMediumPushDownRateWarn() {
        PushDownStatistics predStats = new PushDownStatistics();
        // 3/5 = 60%，介于 critical(20%) 和 warn(50%) 之间... 60% > 50%，应该是 INFO
        // 改为 2/5 = 40%，介于 20% 和 50% 之间 → WARN
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.EQUALITY, true);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.RANGE, true);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "UDF", null);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "OR", null);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "子查询", null);

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, predStats, null, null);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("[WARN]") && s.contains("下推率")));
    }

    @Test
    @DisplayName("下推率高生成 INFO")
    void testHighPushDownRateInfo() {
        PushDownStatistics predStats = new PushDownStatistics();
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.EQUALITY, true);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.RANGE, true);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.IN, true);

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, predStats, null, null);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("[INFO]") && s.contains("下推效果良好")));
    }

    @Test
    @DisplayName("不支持谓词触发改写建议")
    void testUnsupportedPredicate() {
        PushDownStatistics predStats = new PushDownStatistics();
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "UDF", null);

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, predStats, null, null);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("不支持谓词")));
    }

    @Test
    @DisplayName("列裁剪率过低触发 WARN")
    void testLowProjectionRate() {
        ProjectionStatistics projStats = new ProjectionStatistics();
        // 10 列保留 8 列，裁剪率 20% < 30% → WARN
        projStats.recordProjection(DataSourceConfig.Type.DORIS, 10, 8);

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, null, projStats, null);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("列裁剪率") && s.contains("[WARN]")));
    }

    @Test
    @DisplayName("高列裁剪率生成 INFO")
    void testHighProjectionRate() {
        ProjectionStatistics projStats = new ProjectionStatistics();
        projStats.recordProjection(DataSourceConfig.Type.DORIS, 10, 3);

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, null, projStats, null);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("数据传输量显著减少")));
    }

    @Test
    @DisplayName("嵌套投影合并生成 INFO")
    void testMergeCount() {
        ProjectionStatistics projStats = new ProjectionStatistics();
        projStats.recordProjection(DataSourceConfig.Type.DORIS, 5, 2);
        projStats.recordMerge();
        projStats.recordMerge();

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, null, projStats, null);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("嵌套投影合并")));
    }

    @Test
    @DisplayName("Cost 瓶颈 NETWORK 触发建议")
    void testCostBottleneckNetwork() {
        Map<String, Object> costStats = new LinkedHashMap<>();
        costStats.put("cost.bottleneck", "NETWORK");
        costStats.put("cost.total", 1000.0);
        costStats.put("cost.share.networkPct", "60.00%");

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, null, null, costStats);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("NETWORK") && s.contains("Colocate")));
    }

    @Test
    @DisplayName("Cost 瓶颈 IO 触发建议")
    void testCostBottleneckIO() {
        Map<String, Object> costStats = new LinkedHashMap<>();
        costStats.put("cost.bottleneck", "IO");
        costStats.put("cost.total", 1000.0);
        costStats.put("cost.share.ioPct", "50.00%");

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, null, null, costStats);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("IO") && s.contains("列存")));
    }

    @Test
    @DisplayName("Cost 瓶颈 CPU 触发建议")
    void testCostBottleneckCPU() {
        Map<String, Object> costStats = new LinkedHashMap<>();
        costStats.put("cost.bottleneck", "CPU");
        costStats.put("cost.total", 1000.0);
        costStats.put("cost.share.cpuPct", "70.00%");

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, null, null, costStats);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("CPU") && s.contains("向量化")));
    }

    @Test
    @DisplayName("总 Cost 过高触发 WARN")
    void testHighTotalCost() {
        Map<String, Object> costStats = new LinkedHashMap<>();
        costStats.put("cost.bottleneck", "CPU");
        costStats.put("cost.total", 20_000_000.0);

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, null, null, costStats);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("总 Cost 较高")));
    }

    @Test
    @DisplayName("跨源 Join 触发建议")
    void testFederatedJoin() {
        CustomRelNode join = CustomRelNode.of(CustomRelNode.Op.JOIN)
                .setCondition("a.id = b.uid")
                .setPushDownStatus(CustomRelNode.PushDownStatus.NOT_APPLICABLE);
        join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("a").setSourceName("doris")
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED));
        join.addChild(CustomRelNode.of(CustomRelNode.Op.TABLE_SCAN)
                .setTableName("b").setSourceName("trino")
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED));

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(join);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("跨源 Join")));
    }

    @Test
    @DisplayName("未下推节点过多触发 WARN")
    void testManyNotPushed() {
        CustomRelNode root = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setPushDownStatus(CustomRelNode.PushDownStatus.NOT_PUSHED);
        for (int i = 0; i < 4; i++) {
            root.addChild(CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setPushDownStatus(CustomRelNode.PushDownStatus.NOT_PUSHED)
                    .setPushDownReason("UDF"));
        }

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(root);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("节点未下推")));
    }

    @Test
    @DisplayName("执行计划过深触发 INFO")
    void testDeepPlan() {
        CustomRelNode root = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
        CustomRelNode current = root;
        for (int i = 0; i < 7; i++) {
            CustomRelNode child = CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
            current.addChild(child);
            current = child;
        }

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(root);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("执行计划深度")));
    }

    @Test
    @DisplayName("未下推节点原因收集")
    void testNotPushedReasons() {
        CustomRelNode root = CustomRelNode.of(CustomRelNode.Op.FILTER)
                .setCondition("UDF(x)=1")
                .setPushDownStatus(CustomRelNode.PushDownStatus.NOT_PUSHED)
                .setPushDownReason("UDF 不支持");

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(root);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("UDF 不支持")));
    }

    @Test
    @DisplayName("建议按严重级别排序 CRITICAL → WARN → INFO")
    void testSuggestionOrdering() {
        PushDownStatistics predStats = new PushDownStatistics();
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.EQUALITY, true);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "UDF", null);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "OR", null);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "子查询", null);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED, false, "UDF2", null);

        Map<String, Object> costStats = new LinkedHashMap<>();
        costStats.put("cost.bottleneck", "NETWORK");
        costStats.put("cost.total", 1000.0);
        costStats.put("cost.share.networkPct", "60%");

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, predStats, null, costStats);
        // CRITICAL 应在 WARN 之前
        int criticalIdx = -1, warnIdx = -1;
        for (int i = 0; i < suggestions.size(); i++) {
            if (suggestions.get(i).contains("[CRITICAL]") && criticalIdx < 0) criticalIdx = i;
            if (suggestions.get(i).contains("[WARN]") && warnIdx < 0) warnIdx = i;
        }
        if (criticalIdx >= 0 && warnIdx >= 0) {
            assertTrue(criticalIdx < warnIdx);
        }
    }

    @Test
    @DisplayName("自定义阈值构造")
    void testCustomThresholds() {
        TuningAdvisor advisor = new TuningAdvisor(0.9, 0.8, 0.5, 100);
        PushDownStatistics predStats = new PushDownStatistics();
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.EQUALITY, true);
        predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.RANGE, true);
        // 100% > 90% → INFO
        List<String> suggestions = advisor.advise(null, predStats, null, null);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("[INFO]")));
    }

    @Test
    @DisplayName("Suggestion.toString 含级别前缀")
    void testSuggestionToString() {
        TuningAdvisor.Suggestion s = new TuningAdvisor.Suggestion(
                TuningAdvisor.Severity.WARN, "测试建议");
        assertEquals("[WARN] 测试建议", s.toString());
        assertEquals(TuningAdvisor.Severity.WARN, s.getSeverity());
        assertEquals("测试建议", s.getMessage());
    }

    @Test
    @DisplayName("Severity 枚举优先级")
    void testSeverityPriority() {
        assertTrue(TuningAdvisor.Severity.CRITICAL.priority > TuningAdvisor.Severity.WARN.priority);
        assertTrue(TuningAdvisor.Severity.WARN.priority > TuningAdvisor.Severity.INFO.priority);
    }

    @Test
    @DisplayName("投影下推跳过次数过多触发 INFO")
    void testSkipCountSuggestion() {
        ProjectionStatistics projStats = new ProjectionStatistics();
        projStats.recordProjection(DataSourceConfig.Type.DORIS, 10, 5);
        projStats.recordSkip("SELECT *");
        projStats.recordSkip("全列引用");
        projStats.recordSkip("count(*)");
        projStats.recordSkip("无 TableScan");

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, null, projStats, null);
        assertTrue(suggestions.stream().anyMatch(s -> s.contains("投影下推跳过")));
    }

    @Test
    @DisplayName("advise 便捷方法 null 节点")
    void testAdviseNullNode() {
        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null);
        assertTrue(suggestions.isEmpty());
    }

    @Test
    @DisplayName("Cost 瓶颈 NONE 不触发建议")
    void testCostBottleneckNone() {
        Map<String, Object> costStats = new LinkedHashMap<>();
        costStats.put("cost.bottleneck", "NONE");
        costStats.put("cost.total", 100.0);

        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, null, null, costStats);
        // NONE 不触发瓶颈建议，但可能触发总 Cost 建议（100 < 10M，不触发）
        assertTrue(suggestions.isEmpty() || suggestions.stream().noneMatch(s -> s.contains("瓶颈")));
    }

    @Test
    @DisplayName("空 Cost 指标不触发建议")
    void testEmptyCostStats() {
        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, null, null, Collections.emptyMap());
        assertTrue(suggestions.isEmpty());
    }

    @Test
    @DisplayName("保留原因超过 5 个不记录")
    void testTooManyReasons() {
        PushDownStatistics predStats = new PushDownStatistics();
        for (int i = 0; i < 6; i++) {
            predStats.recordPredicate(DataSourceConfig.Type.DORIS, PredicateType.UNSUPPORTED,
                    false, "原因" + i, null);
        }
        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(null, predStats, null, null);
        // 原因数 > 5，不记录保留原因
        assertTrue(suggestions.stream().noneMatch(s -> s.contains("未下推原因")));
    }

    @Test
    @DisplayName("未下推节点原因超过 3 个只收集 3 个")
    void testTooManyNotPushedReasons() {
        CustomRelNode root = CustomRelNode.of(CustomRelNode.Op.PROJECT)
                .setPushDownStatus(CustomRelNode.PushDownStatus.PUSHED);
        for (int i = 0; i < 5; i++) {
            root.addChild(CustomRelNode.of(CustomRelNode.Op.FILTER)
                    .setPushDownStatus(CustomRelNode.PushDownStatus.NOT_PUSHED)
                    .setPushDownReason("原因" + i));
        }
        TuningAdvisor advisor = new TuningAdvisor();
        List<String> suggestions = advisor.advise(root);
        // 只收集最多 3 个未下推原因
        long count = suggestions.stream().filter(s -> s.contains("未下推节点")).count();
        assertTrue(count <= 3);
    }
}