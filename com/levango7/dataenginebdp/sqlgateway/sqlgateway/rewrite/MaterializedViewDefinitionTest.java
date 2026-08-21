package com.shuqing.bigdata.sqlgateway.rewrite;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MaterializedViewDefinition 单元测试。
 *
 * @author shuqing-bigdata
 */
class MaterializedViewDefinitionTest {

    @Test
    @DisplayName("dimensionList — 拆分逗号分隔的维度列")
    void dimensionList_shouldSplitCsv() {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setDimensionColumns("region, product , category");
        assertThat(view.dimensionList()).containsExactly("region", "product", "category");
    }

    @Test
    @DisplayName("dimensionList — 空字符串返回空列表")
    void dimensionList_empty_shouldReturnEmptyList() {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setDimensionColumns("");
        assertThat(view.dimensionList()).isEmpty();
    }

    @Test
    @DisplayName("dimensionList — null 返回空列表")
    void dimensionList_null_shouldReturnEmptyList() {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        assertThat(view.dimensionList()).isEmpty();
    }

    @Test
    @DisplayName("measureList — 拆分逗号分隔的指标列")
    void measureList_shouldSplitCsv() {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setMeasureColumns("sum(amount), count(*), avg(price)");
        assertThat(view.measureList()).containsExactly("sum(amount)", "count(*)", "avg(price)");
    }

    @Test
    @DisplayName("isAvailable — 启用且已刷新返回 true")
    void isAvailable_enabledAndRefreshed_shouldReturnTrue() {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setEnabled(true);
        view.setLastRefreshTime(Instant.now());
        assertThat(view.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("isAvailable — 未启用返回 false")
    void isAvailable_disabled_shouldReturnFalse() {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setEnabled(false);
        view.setLastRefreshTime(Instant.now());
        assertThat(view.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("isAvailable — 未刷新返回 false")
    void isAvailable_notRefreshed_shouldReturnFalse() {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setEnabled(true);
        assertThat(view.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("全参构造器 — 正确赋值所有字段")
    void constructor_shouldSetAllFields() {
        MaterializedViewDefinition view = new MaterializedViewDefinition(
                "mv_sales_daily", "sales",
                "CREATE MATERIALIZED VIEW mv_sales_daily AS SELECT ...",
                "SELECT region, sum(amount) FROM sales GROUP BY region",
                "region", "sum(amount)",
                "FULL", true, 10, "销售日聚合物化视图");
        assertThat(view.getViewName()).isEqualTo("mv_sales_daily");
        assertThat(view.getSourceTable()).isEqualTo("sales");
        assertThat(view.getRefreshStrategy()).isEqualTo("FULL");
        assertThat(view.getEnabled()).isTrue();
        assertThat(view.getPriority()).isEqualTo(10);
        assertThat(view.dimensionList()).containsExactly("region");
        assertThat(view.measureList()).containsExactly("sum(amount)");
    }

    @Test
    @DisplayName("dimensionList — 含空白项时过滤空项")
    void dimensionList_withBlankItems_shouldFilterBlanks() {
        MaterializedViewDefinition view = new MaterializedViewDefinition();
        view.setDimensionColumns("region, , product,");
        List<String> dims = view.dimensionList();
        assertThat(dims).containsExactly("region", "product");
    }
}