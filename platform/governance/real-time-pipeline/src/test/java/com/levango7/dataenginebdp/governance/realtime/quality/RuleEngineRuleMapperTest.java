package com.levango7.dataenginebdp.governance.realtime.quality;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RuleEngineRuleMapper} 单测（台账 #6 阶段 3）。
 *
 * <p>锁住三件事：字段映射、threshold→params 解析、以及"映射不了就跳过"的容错。
 */
class RuleEngineRuleMapperTest {

    private static Map<String, Object> view() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", "42");
        v.put("name", "订单金额范围");
        v.put("targetTable", "ods.orders");
        v.put("targetField", "amount");
        v.put("checkType", "range");
        v.put("threshold", "min=0,max=100000");
        v.put("actionOnFail", "ERROR");
        v.put("status", "enabled");
        return v;
    }

    @Test
    void mapsRuleEngineViewToStreamingRule() {
        QualityRule r = RuleEngineRuleMapper.mapView(view(), "tenant-a");

        assertThat(r).isNotNull();
        assertThat(r.getRuleId()).isEqualTo("42");
        assertThat(r.getTenantId()).isEqualTo("tenant-a");
        assertThat(r.getRuleType()).isEqualTo(QualityRule.RuleType.RANGE);
        assertThat(r.getRuleName()).isEqualTo("订单金额范围");
        assertThat(r.getTableIdentifier()).isEqualTo("ods.orders");
        assertThat(r.getFieldName()).isEqualTo("amount");
        assertThat(r.getSeverity()).isEqualTo("ERROR");
        assertThat(r.isEnabled()).isTrue();
        assertThat(r.getParams()).containsEntry("min", "0").containsEntry("max", "100000");
    }

    @Test
    void checkTypeMappingCoversFiveKinds() {
        assertThat(RuleEngineRuleMapper.mapType("not_null")).isEqualTo(QualityRule.RuleType.NOT_NULL);
        assertThat(RuleEngineRuleMapper.mapType("unique")).isEqualTo(QualityRule.RuleType.UNIQUE);
        assertThat(RuleEngineRuleMapper.mapType("range")).isEqualTo(QualityRule.RuleType.RANGE);
        assertThat(RuleEngineRuleMapper.mapType("format")).isEqualTo(QualityRule.RuleType.FORMAT);
        assertThat(RuleEngineRuleMapper.mapType("custom_expr")).isEqualTo(QualityRule.RuleType.CUSTOM);
        assertThat(RuleEngineRuleMapper.mapType("  ")).isNull();
    }

    @Test
    void unstructuredThresholdFallsBackToThresholdKey() {
        // 含 '=' 的按 k=v 解析：threshold=5 → {threshold: 5}
        assertThat(RuleEngineRuleMapper.parseParams("threshold=5")).containsEntry("threshold", "5");
        // 不含 '=' 的退化为 {"threshold": 原文}（信息不丢）
        assertThat(RuleEngineRuleMapper.parseParams("50")).containsEntry("threshold", "50");
        assertThat(RuleEngineRuleMapper.parseParams("")).isEmpty();
    }

    @Test
    void skipsUnmappableViewsInsteadOfFabricating() {
        Map<String, Object> noTable = view();
        noTable.put("targetTable", "");
        assertThat(RuleEngineRuleMapper.mapView(noTable, "t")).isNull();

        Map<String, Object> noId = view();
        noId.put("id", null);
        assertThat(RuleEngineRuleMapper.mapView(noId, "t")).isNull();

        assertThat(RuleEngineRuleMapper.mapView(null, "t")).isNull();
    }

    @Test
    void extractListHandlesWrappedAndUnwrappedShapes() {
        Map<String, Object> raw = view();
        assertThat(RuleEngineRuleMapper.extractList(Map.of("list", List.of(raw)))).hasSize(1);
        assertThat(RuleEngineRuleMapper.extractList(
                Map.of("code", 0, "data", Map.of("list", List.of(raw))))).hasSize(1);
        assertThat(RuleEngineRuleMapper.extractList(Map.of("data", List.of(raw)))).hasSize(1);
        assertThat(RuleEngineRuleMapper.extractList(Map.of())).isEmpty();
        assertThat(RuleEngineRuleMapper.extractList(null)).isEmpty();
    }
}
