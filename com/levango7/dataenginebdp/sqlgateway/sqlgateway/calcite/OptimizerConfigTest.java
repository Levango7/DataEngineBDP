package com.shuqing.bigdata.sqlgateway.calcite;

import com.shuqing.bigdata.sqlgateway.calcite.config.DataSourceConfig;
import com.shuqing.bigdata.sqlgateway.calcite.config.OptimizerConfig;
import com.shuqing.bigdata.sqlgateway.parser.SqlDialect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link OptimizerConfig} 与 {@link DataSourceConfig} 单元测试。
 *
 * <p>覆盖配置的默认值、链式 setter、下推规则开关、Cost 权重、
 * 数据源查找与校验等核心逻辑，确保覆盖率 ≥ 80%。</p>
 *
 * @author shuqing-bigdata
 */
class OptimizerConfigTest {

    // ===================== OptimizerConfig 测试 =====================

    @Test
    @DisplayName("默认配置：启用 + 5 项下推规则 + 3 维 Cost 权重")
    void testDefaultConfig() {
        OptimizerConfig config = new OptimizerConfig();
        assertTrue(config.isEnabled());
        assertTrue(config.isPushDownRuleEnabled("FilterPushDown"));
        assertTrue(config.isPushDownRuleEnabled("ProjectPushDown"));
        assertFalse(config.isPushDownRuleEnabled("AggregatePushDown"));
        assertTrue(config.isPushDownRuleEnabled("LimitPushDown"));
        assertFalse(config.isPushDownRuleEnabled("JoinPushDown"));
        assertEquals(OptimizerConfig.DEFAULT_CPU_WEIGHT, config.getCostWeight("cpu"));
        assertEquals(OptimizerConfig.DEFAULT_IO_WEIGHT, config.getCostWeight("io"));
        assertEquals(OptimizerConfig.DEFAULT_NETWORK_WEIGHT, config.getCostWeight("network"));
        assertFalse(config.isVolcanoPlannerEnabled());
        assertEquals(100, config.getMaxIterations());
    }

    @Test
    @DisplayName("setEnabled 链式设置")
    void testSetEnabled() {
        OptimizerConfig config = new OptimizerConfig();
        OptimizerConfig returned = config.setEnabled(false);
        assertSame(config, returned);
        assertFalse(config.isEnabled());
        config.setEnabled(true);
        assertTrue(config.isEnabled());
    }

    @Test
    @DisplayName("setPushDownRule 启用/禁用下推规则")
    void testSetPushDownRule() {
        OptimizerConfig config = new OptimizerConfig();
        config.setPushDownRule("AggregatePushDown", true);
        assertTrue(config.isPushDownRuleEnabled("AggregatePushDown"));
        config.setPushDownRule("AggregatePushDown", false);
        assertFalse(config.isPushDownRuleEnabled("AggregatePushDown"));
    }

    @Test
    @DisplayName("isPushDownRuleEnabled 未配置规则返回 false")
    void testUnknownPushDownRule() {
        OptimizerConfig config = new OptimizerConfig();
        assertFalse(config.isPushDownRuleEnabled("NonExistentRule"));
    }

    @Test
    @DisplayName("setPushDownRules 批量设置")
    void testSetPushDownRules() {
        Map<String, Boolean> rules = new LinkedHashMap<>();
        rules.put("CustomRule1", true);
        rules.put("CustomRule2", false);
        OptimizerConfig config = new OptimizerConfig();
        config.setPushDownRules(rules);
        assertTrue(config.isPushDownRuleEnabled("CustomRule1"));
        assertFalse(config.isPushDownRuleEnabled("CustomRule2"));
        assertFalse(config.isPushDownRuleEnabled("FilterPushDown"));
    }

    @Test
    @DisplayName("setPushDownRules null 安全")
    void testSetPushDownRulesNull() {
        OptimizerConfig config = new OptimizerConfig();
        config.setPushDownRules(null);
        assertNotNull(config.getPushDownRules());
        assertTrue(config.getPushDownRules().isEmpty());
    }

    @Test
    @DisplayName("getCostWeight 未配置维度返回默认 CPU 权重")
    void testUnknownCostWeight() {
        OptimizerConfig config = new OptimizerConfig();
        assertEquals(OptimizerConfig.DEFAULT_CPU_WEIGHT, config.getCostWeight("unknown"));
    }

    @Test
    @DisplayName("setCostWeight 链式设置")
    void testSetCostWeight() {
        OptimizerConfig config = new OptimizerConfig();
        config.setCostWeight("cpu", 5.0);
        assertEquals(5.0, config.getCostWeight("cpu"));
    }

    @Test
    @DisplayName("setCostWeights 批量设置 + null 安全")
    void testSetCostWeights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        weights.put("cpu", 2.0);
        weights.put("io", 20.0);
        OptimizerConfig config = new OptimizerConfig();
        config.setCostWeights(weights);
        assertEquals(2.0, config.getCostWeight("cpu"));
        assertEquals(20.0, config.getCostWeight("io"));

        config.setCostWeights(null);
        assertNotNull(config.getCostWeights());
        assertTrue(config.getCostWeights().isEmpty());
    }

    @Test
    @DisplayName("setDataSources + getDataSources")
    void testDataSources() {
        DataSourceConfig ds1 = new DataSourceConfig("doris1", DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://localhost:9030");
        DataSourceConfig ds2 = new DataSourceConfig("trino1", DataSourceConfig.Type.TRINO)
                .setJdbcUrl("jdbc:trino://localhost:8080");
        OptimizerConfig config = new OptimizerConfig();
        config.setDataSources(Arrays.asList(ds1, ds2));
        assertEquals(2, config.getDataSources().size());

        config.setDataSources(null);
        assertTrue(config.getDataSources().isEmpty());
    }

    @Test
    @DisplayName("findDataSource 按名称查找")
    void testFindDataSource() {
        DataSourceConfig ds1 = new DataSourceConfig("doris1", DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://localhost:9030");
        DataSourceConfig ds2 = new DataSourceConfig("trino1", DataSourceConfig.Type.TRINO)
                .setJdbcUrl("jdbc:trino://localhost:8080");
        OptimizerConfig config = new OptimizerConfig();
        config.setDataSources(Arrays.asList(ds1, ds2));

        assertEquals(ds1, config.findDataSource("doris1"));
        assertEquals(ds2, config.findDataSource("trino1"));
        assertNull(config.findDataSource("unknown"));
        assertNull(config.findDataSource(null));
    }

    @Test
    @DisplayName("getValidDataSources 过滤非法配置")
    void testGetValidDataSources() {
        DataSourceConfig valid1 = new DataSourceConfig("doris1", DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://localhost:9030");
        DataSourceConfig valid2 = new DataSourceConfig("trino1", DataSourceConfig.Type.TRINO)
                .setEndpoint("http://trino:8080");
        DataSourceConfig invalid1 = new DataSourceConfig(null, DataSourceConfig.Type.DORIS);
        DataSourceConfig invalid2 = new DataSourceConfig("empty", DataSourceConfig.Type.ELASTICSEARCH);
        OptimizerConfig config = new OptimizerConfig();
        config.setDataSources(Arrays.asList(valid1, valid2, invalid1, invalid2, null));

        Set<DataSourceConfig> valid = config.getValidDataSources();
        assertEquals(2, valid.size());
        assertTrue(valid.contains(valid1));
        assertTrue(valid.contains(valid2));
    }

    @Test
    @DisplayName("volcanoPlanner + maxIterations 设置")
    void testPlannerSettings() {
        OptimizerConfig config = new OptimizerConfig();
        config.setVolcanoPlannerEnabled(true);
        config.setMaxIterations(50);
        assertTrue(config.isVolcanoPlannerEnabled());
        assertEquals(50, config.getMaxIterations());
    }

    @Test
    @DisplayName("toString 非空")
    void testToString() {
        OptimizerConfig config = new OptimizerConfig();
        String str = config.toString();
        assertNotNull(str);
        assertTrue(str.contains("OptimizerConfig"));
        assertTrue(str.contains("enabled=true"));
    }

    // ===================== DataSourceConfig 测试 =====================

    @Test
    @DisplayName("DataSourceConfig 默认值")
    void testDataSourceDefaults() {
        DataSourceConfig ds = new DataSourceConfig();
        assertNull(ds.getName());
        assertNull(ds.getType());
        assertEquals(SqlDialect.ANSI, ds.getDialect());
        assertTrue(ds.isPushDownEnabled());
        assertTrue(ds.isCostEstimationEnabled());
        assertNotNull(ds.getProperties());
        assertTrue(ds.getProperties().isEmpty());
    }

    @Test
    @DisplayName("DataSourceConfig 链式 setter")
    void testDataSourceChainedSetter() {
        DataSourceConfig ds = new DataSourceConfig()
                .setName("test_ds")
                .setType(DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://localhost:9030")
                .setEndpoint("http://doris:8030")
                .setDialect(SqlDialect.DORIS)
                .setPushDownEnabled(false)
                .setCostEstimationEnabled(false)
                .addProperty("user", "root")
                .addProperty("password", "secret");

        assertEquals("test_ds", ds.getName());
        assertEquals(DataSourceConfig.Type.DORIS, ds.getType());
        assertEquals("jdbc:mysql://localhost:9030", ds.getJdbcUrl());
        assertEquals("http://doris:8030", ds.getEndpoint());
        assertEquals(SqlDialect.DORIS, ds.getDialect());
        assertFalse(ds.isPushDownEnabled());
        assertFalse(ds.isCostEstimationEnabled());
        assertEquals("root", ds.getProperties().get("user"));
        assertEquals("secret", ds.getProperties().get("password"));
    }

    @Test
    @DisplayName("DataSourceConfig.setProperties null 安全")
    void testDataSourceSetPropertiesNull() {
        DataSourceConfig ds = new DataSourceConfig();
        ds.setProperties(null);
        assertNotNull(ds.getProperties());
        assertTrue(ds.getProperties().isEmpty());
    }

    @Test
    @DisplayName("DataSourceConfig.isValid 校验")
    void testDataSourceIsValid() {
        DataSourceConfig valid1 = new DataSourceConfig("ds1", DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://localhost:9030");
        DataSourceConfig valid2 = new DataSourceConfig("ds2", DataSourceConfig.Type.ELASTICSEARCH)
                .setEndpoint("http://es:9200");
        DataSourceConfig invalid1 = new DataSourceConfig(null, DataSourceConfig.Type.DORIS);
        DataSourceConfig invalid2 = new DataSourceConfig("ds", null);
        DataSourceConfig invalid3 = new DataSourceConfig("ds", DataSourceConfig.Type.DORIS);
        DataSourceConfig invalid4 = new DataSourceConfig("  ", DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://localhost");

        assertTrue(valid1.isValid());
        assertTrue(valid2.isValid());
        assertFalse(invalid1.isValid());
        assertFalse(invalid2.isValid());
        assertFalse(invalid3.isValid());
        assertFalse(invalid4.isValid());
    }

    @Test
    @DisplayName("DataSourceConfig.Type.fromString 大小写无关")
    void testTypeFromString() {
        assertEquals(DataSourceConfig.Type.DORIS, DataSourceConfig.Type.fromString("doris"));
        assertEquals(DataSourceConfig.Type.DORIS, DataSourceConfig.Type.fromString("DORIS"));
        assertEquals(DataSourceConfig.Type.ICEBERG, DataSourceConfig.Type.fromString("iceberg"));
        assertEquals(DataSourceConfig.Type.TRINO, DataSourceConfig.Type.fromString("Trino"));
        assertEquals(DataSourceConfig.Type.IOTDB, DataSourceConfig.Type.fromString("iotdb"));
        assertEquals(DataSourceConfig.Type.ELASTICSEARCH,
                DataSourceConfig.Type.fromString("elasticsearch"));
    }

    @Test
    @DisplayName("DataSourceConfig.Type.fromString 非法名称抛异常")
    void testTypeFromStringInvalid() {
        assertThrows(IllegalArgumentException.class,
                () -> DataSourceConfig.Type.fromString("unknown"));
        assertThrows(NullPointerException.class,
                () -> DataSourceConfig.Type.fromString(null));
    }

    @Test
    @DisplayName("DataSourceConfig equals/hashCode 基于 name+type")
    void testDataSourceEquals() {
        DataSourceConfig ds1 = new DataSourceConfig("ds", DataSourceConfig.Type.DORIS);
        DataSourceConfig ds2 = new DataSourceConfig("ds", DataSourceConfig.Type.DORIS);
        DataSourceConfig ds3 = new DataSourceConfig("ds", DataSourceConfig.Type.TRINO);
        DataSourceConfig ds4 = new DataSourceConfig("other", DataSourceConfig.Type.DORIS);

        assertEquals(ds1, ds2);
        assertEquals(ds1.hashCode(), ds2.hashCode());
        assertNotEquals(ds1, ds3);
        assertNotEquals(ds1, ds4);
        assertNotEquals(ds1, null);
        assertNotEquals(ds1, "not a config");
        assertEquals(ds1, ds1);
    }

    @Test
    @DisplayName("DataSourceConfig toString 非空")
    void testDataSourceToString() {
        DataSourceConfig ds = new DataSourceConfig("ds", DataSourceConfig.Type.DORIS)
                .setJdbcUrl("jdbc:mysql://localhost");
        String str = ds.toString();
        assertNotNull(str);
        assertTrue(str.contains("ds"));
        assertTrue(str.contains("DORIS"));
    }
}
